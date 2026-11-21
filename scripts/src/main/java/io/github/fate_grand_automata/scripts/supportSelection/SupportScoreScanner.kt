package io.github.fate_grand_automata.scripts.supportSelection

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.locations.SupportScoreLayout
import io.github.fate_grand_automata.scripts.modules.SupportScreen
import io.github.fate_grand_automata.scripts.modules.SupportSelectionLoop
import io.github.lib_automata.ExitManager
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.github.lib_automata.ScriptAbortException
import io.github.lib_automata.Transformer
import io.github.lib_automata.size
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.TimeSource

/** One snapshot of the support list, its rows and each row's score field. */
interface ScoreScanFrames {
    fun <T> sameSnap(block: () -> T): T

    fun rows(): List<SupportBounds>

    /** The score field of [bounds] from the current snapshot, as the engine reads it (60x30 at 720p). */
    fun field(bounds: SupportBounds): Pattern

    /** Where [field] crops: 720p image coordinates inside the game-area crop of the capture. */
    fun fieldImageRegion(bounds: SupportBounds): Region
}

class ApiScoreScanFrames(
    private val api: IFgoAutomataApi,
    private val boundsFinder: SupportBoundsFinder,
    private val transformer: Transformer,
) : ScoreScanFrames {
    override fun <T> sameSnap(block: () -> T): T = api.useSameSnapIn(block)

    override fun rows() = boundsFinder.all()

    // The same crop as the rust-engine device case and the Kotlin reader's own number region.
    override fun field(bounds: SupportBounds): Pattern = with(api) {
        SupportScoreLayout.numberRegion(bounds.region).getPattern(FIELD_TAG)
    }

    // getPattern crops the snapshot at exactly this region.
    override fun fieldImageRegion(bounds: SupportBounds): Region =
        transformer.toImage(SupportScoreLayout.numberRegion(bounds.region))

    private companion object {
        const val FIELD_TAG = "support-score-scan"
    }
}

/**
 * The owner's support score scan (issue #568, the rust build type only): reads every score field of
 * the support list with the Kotlin reader and the Rust engine and logs both. Neither answer decides
 * anything. Its only gesture is [SupportScreen.scrollDown]; it never taps, refreshes or opens
 * anything, so it cannot enter a quest or spend anything. Reading a screen's rows takes a while
 * (the Kotlin reader runs OCR several times per row), so the stop and the screen are looked at
 * again, on a new snapshot, right before every scroll.
 *
 * End of the list: the rows of a screen, by their y and the SHA-256 of their crop, equal the
 * previous screen's. A scroll that moved the list changes the y or the pixels of the fields; one
 * that could not (the bottom, or a list shorter than a screen) leaves both as they were. The game
 * can also drop a swipe, so the bottom needs two unchanged screens in a row; an unchanged screen
 * is never read again.
 *
 * Native crops (issue #600): once a screen's rows are read and their lines are on disk, one
 * full-display frame is taken and each row's field, with a margin, is saved at that resolution. A
 * capture that fails costs only the native lines of that screen; one that could not restore the
 * 720p capture ends the scan before the next snapshot or gesture.
 */
class SupportScoreScanner(
    private val screen: SupportScreen,
    private val frames: ScoreScanFrames,
    private val reader: ISupportScoreReader,
    private val engine: SupportScoreEngine,
    private val storage: ScoreScanStorage,
    private val device: ScoreScanDevice,
    private val exitManager: ExitManager,
    private val server: GameServer,
    private val nativeFrames: ScoreScanNativeFrames,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val maxScreens: Int = MAX_SCREENS,
) {
    companion object {
        /** About 400 rows; a support list is far shorter, so this only stops a scan that never ends. */
        const val MAX_SCREENS = 80

        fun stamp(millis: Long): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(millis))
    }

    private data class RowKey(val y: Int, val sha256: String)

    private sealed interface ScreenOutcome {
        class Read(val keys: List<RowKey>, val logged: List<ScoreScanLoggedRow>) : ScreenOutcome
        data object Unchanged : ScreenOutcome
        data class End(val reason: ScoreScanEndReason) : ScreenOutcome
    }

    private val serverName = server.serialize()
    private val checkGrand = SupportScoreSelection.showsGrandScore(server)
    private val tally = ScoreScanTally()
    private val nativeTally = ScoreScanNativeTally()
    private var screens = 0
    private var restoreDetail: String? = null

    fun run(): ScoreScanResult {
        val startTime = clock()
        val notStarted = try {
            // A stop that arrives while the engine's first load succeeds: nothing created, nothing deleted.
            startProblem() ?: run {
                exitManager.checkExitRequested()
                null
            }
        } catch (e: ScriptAbortException) {
            ScoreScanEndReason.UserStopped
        }
        if (notStarted != null) {
            val detail = if (notStarted == ScoreScanEndReason.EngineUnavailable) engine.unavailableReason else null
            return ScoreScanResult(notStarted, null, 0, 0, 0, 0, detail)
        }

        val name = ScoreScanLog.newName(stamp(startTime), storage.scanNames())
        val folder = storage.create(name)
        try {
            // Only after the new folder exists, so a failed create deletes nothing.
            ScoreScanLog.toDelete(storage.scanNames() - name).forEach(storage::delete)
            val reason = try {
                scan(folder)
            } catch (e: ScriptAbortException) {
                ScoreScanEndReason.UserStopped
            }
            val detail = when (reason) {
                ScoreScanEndReason.EngineUnavailable -> engine.unavailableReason
                ScoreScanEndReason.NativeRestoreFailed -> restoreDetail
                else -> null
            }
            folder.writeSummary(summary(name, startTime, reason.wireName, detail))
            return ScoreScanResult(reason, name, screens, tally.rows, tally.agree, tally.differ, detail)
        } catch (e: Exception) {
            runCatching { folder.writeSummary(summary(name, startTime, ScoreScanLog.ERROR, e.toString())) }
            throw e
        } finally {
            runCatching { folder.close() }
        }
    }

    // Free space first: the engine's first use copies its model (about 21 MB).
    private fun startProblem(): ScoreScanEndReason? = when {
        screen.isAnyDialogOpen() -> ScoreScanEndReason.NotSupportListOrDialog
        !screen.isListLoaded() -> ScoreScanEndReason.ListNotLoaded
        !screen.someSupportsPresent() -> ScoreScanEndReason.NoRows
        storage.freeBytes() < ScoreScanLog.MIN_FREE_BYTES -> ScoreScanEndReason.LowSpace
        !engine.available -> engineUnavailable()
        // someSupportsPresent also matches a guest row, which has no confirm-setup button to find.
        frames.sameSnap { frames.rows() }.isEmpty() -> ScoreScanEndReason.NoRows
        else -> null
    }

    /** A stop during the engine's first load makes it unavailable too; that is the user's stop. */
    private fun engineUnavailable(): ScoreScanEndReason {
        exitManager.checkExitRequested()
        return ScoreScanEndReason.EngineUnavailable
    }

    /** Null while the support list is on screen without a dialog, in the current snapshot. */
    private fun listProblem(): ScoreScanEndReason? = when {
        screen.isAnyDialogOpen() -> ScoreScanEndReason.NotSupportListOrDialog
        !screen.someSupportsPresent() -> ScoreScanEndReason.RowsLost
        else -> null
    }

    private fun scan(folder: ScoreScanFolder): ScoreScanEndReason {
        var previous: List<RowKey>? = null
        var unchangedBefore = false
        while (true) {
            if (storage.freeBytes() < ScoreScanLog.MIN_FREE_BYTES) return ScoreScanEndReason.LowSpace
            when (val outcome = frames.sameSnap { readScreen(folder, screens, previous) }) {
                is ScreenOutcome.End -> return outcome.reason
                ScreenOutcome.Unchanged -> {
                    if (unchangedBefore) return ScoreScanEndReason.Bottom
                    unchangedBefore = true
                }
                is ScreenOutcome.Read -> {
                    previous = outcome.keys
                    unchangedBefore = false
                    // Outside the snapshot block: the capture does not use the 720p snapshot.
                    if (!logNative(folder, outcome.logged)) return ScoreScanEndReason.NativeRestoreFailed
                    if (screens >= maxScreens) return ScoreScanEndReason.ScreenLimit
                }
            }
            exitManager.checkExitRequested()
            frames.sameSnap { listProblem() }?.let { return it }
            screen.scrollDown()
            screen.delay(SupportSelectionLoop.SCROLL_SETTLE)
        }
    }

    private fun readScreen(folder: ScoreScanFolder, index: Int, previous: List<RowKey>?): ScreenOutcome {
        listProblem()?.let { return ScreenOutcome.End(it) }
        // A later screen with only rows that have no confirm-setup button ends here, not as the bottom.
        val rows = frames.rows()
        if (rows.isEmpty()) return ScreenOutcome.End(ScoreScanEndReason.NoRows)

        val fields = mutableListOf<Pattern>()
        try {
            rows.mapTo(fields) { frames.field(it) }
            val pngs = fields.map { field -> ByteArrayOutputStream().also { field.save(it) }.toByteArray() }
            val keys = rows.indices.map { RowKey(rows[it].region.y, ScoreScanLog.sha256(pngs[it])) }
            if (keys == previous) return ScreenOutcome.Unchanged

            val logged = mutableListOf<ScoreScanLoggedRow>()
            for (row in rows.indices) {
                exitManager.checkExitRequested()
                if (!engine.available) return ScreenOutcome.End(engineUnavailable())

                val kotlinStart = timeSource.markNow()
                val kotlin = reader.read(rows[row], true, checkGrand, index, row)
                val kotlinMicros = kotlinStart.elapsedNow().inWholeMicroseconds
                val rust = engine.read(fields[row])
                // After both reads, so a read that fails leaves neither; a failing appendLine, or a
                // process killed between the two writes, can still leave a crop without its line.
                val crop = ScoreScanLog.cropName(index, row)
                folder.writeCrop(crop, pngs[row])
                val record = ScoreScanRecord(
                    time = clock(),
                    server = serverName,
                    screen = index,
                    row = row,
                    bounds = rows[row],
                    crop = crop,
                    cropSha256 = keys[row].sha256,
                    kotlin = kotlin,
                    kotlinCheckGrand = checkGrand,
                    kotlinMicros = kotlinMicros,
                    rust = rust,
                )
                folder.appendLine(ScoreScanLog.line(record))
                tally.add(record)
                screens = index + 1
                if (ScoreScanLog.status(rust) == "unavailable") return ScreenOutcome.End(engineUnavailable())
                logged += ScoreScanLoggedRow(
                    screen = index,
                    row = row,
                    cropSha256 = keys[row].sha256,
                    fieldImage = frames.fieldImageRegion(rows[row]),
                    fieldPixels = fields[row].grayPixels() ?: ByteArray(0),
                    fieldSize = fields[row].size,
                )
            }
            return ScreenOutcome.Read(keys, logged)
        } finally {
            fields.forEach { runCatching { it.close() } }
        }
    }

    /**
     * One capture for the screen whose rows were just logged, never retried. False only when the
     * capture could not restore the 720p capture; every other problem is logged and the scan goes on.
     */
    private fun logNative(folder: ScoreScanFolder, logged: List<ScoreScanLoggedRow>): Boolean {
        if (logged.isEmpty()) return true
        val start = timeSource.markNow()
        val capture = try {
            nativeFrames.capture()
        } catch (e: ScriptAbortException) {
            throw e
        } catch (e: Exception) {
            ScoreScanNativeCapture.Failed(ScoreScanNative.ERROR, e.toString())
        }
        val micros = start.elapsedNow().inWholeMicroseconds

        fun failAll(reason: String, detail: String?) {
            nativeTally.failed(reason, micros)
            for (row in logged) {
                folder.appendLine(ScoreScanLog.nativeLine(ScoreScanNativeRecord(
                    clock(), serverName, row.screen, row.row, row.cropSha256, null, reason, detail, micros,
                )))
            }
        }

        when (capture) {
            is ScoreScanNativeCapture.RestoreFailed -> {
                failAll(ScoreScanNative.RESTORE_FAILED, capture.detail)
                restoreDetail = capture.detail
                return false
            }
            is ScoreScanNativeCapture.Failed -> failAll(capture.reason, capture.detail)
            is ScoreScanNativeCapture.Frame -> capture.frame.use { frame ->
                if (micros > ScoreScanNative.CAPTURE_BOUND.inWholeMicroseconds) {
                    failAll(ScoreScanNative.SLOW, null)
                } else {
                    nativeTally.taken(capture.map, micros)
                    for (row in logged) logNativeRow(folder, frame, capture.map, row, micros)
                }
            }
        }
        return true
    }

    private fun logNativeRow(folder: ScoreScanFolder, frame: Pattern, map: ScoreScanNativeMap, row: ScoreScanLoggedRow, micros: Long) {
        var reason: String? = null
        var detail: String? = null
        var saved: Pair<ScoreScanNativeResult, ByteArray>? = null
        try {
            val crop = ScoreScanNative.crop(map, row.fieldImage)
            if (crop == null) {
                reason = ScoreScanNative.FIELD_OUTSIDE_FRAME
            } else {
                val png = frame.crop(crop.region).use { ScoreScanNative.png(it) }
                val match = ScoreScanNative.match(frame, crop.field + crop.region.location, row.fieldPixels, row.fieldSize)
                saved = ScoreScanNativeResult(
                    crop = ScoreScanLog.cropName(row.screen, row.row),
                    cropSha256 = ScoreScanLog.sha256(png),
                    frame = map.frame,
                    scaleX = map.scaleX,
                    scaleY = map.scaleY,
                    region = crop.region,
                    field = crop.field,
                    clamped = crop.clamped,
                    match = match,
                    captureMicros = micros,
                ) to png
            }
        } catch (e: Exception) {
            reason = ScoreScanNative.ERROR
            detail = e.toString()
        }
        // A failed file write is the scan's own error, as for the 720p crops.
        saved?.let { (result, png) ->
            folder.writeNativeCrop(result.crop, png)
            nativeTally.match(result.match)
        }
        folder.appendLine(ScoreScanLog.nativeLine(ScoreScanNativeRecord(
            clock(), serverName, row.screen, row.row, row.cropSha256, saved?.first, reason, detail, micros,
        )))
    }

    private fun summary(name: String, startTime: Long, reason: String, detail: String?) =
        ScoreScanLog.summary(ScoreScanSummary(
            device = device,
            server = serverName,
            folder = "${ScoreScanLog.ROOT}/$name",
            startTime = startTime,
            endTime = clock(),
            reason = reason,
            detail = detail,
            screens = screens,
            tally = tally,
            engineLoadMicros = engine.loadTime?.inWholeMicroseconds,
            engineUnavailableReason = engine.unavailableReason,
            native = nativeTally,
        ))
}
