package io.github.fate_grand_automata.scripts.supportSelection

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** One snapshot of the support list, its rows and each row's score field. */
interface ScoreScanFrames {
    fun <T> sameSnap(block: () -> T): T

    fun rows(): List<SupportBounds>

    /** The score field of [bounds] from the current snapshot, as the engine reads it (60x30 at 720p). */
    fun field(bounds: SupportBounds): Pattern

    /** Where [field] crops: 720p image coordinates inside the game-area crop of the capture. */
    fun fieldImageRegion(bounds: SupportBounds): Region

    /**
     * The SupportExtra tab's region of the current snapshot (65x65 at 720p in the class filter bar),
     * where the list check looks for the tab; the caller closes it. Not the rows: they hold account text.
     */
    fun extraTab(): Pattern

    /** Mean, minimum and maximum gray value of the whole 720p game area in the current snapshot. */
    fun frameStats(): ScoreScanFrameStats?

    /** What the rows check finds in the current snapshot, by the same templates it uses. */
    fun listMeasures(): ScoreScanListMeasures
}

/** Confirm-setup buttons found in their column, and whether the guest mark was found in its column. */
data class ScoreScanListMeasures(val confirmButtons: Int, val guestMark: Boolean) {
    override fun toString() = "confirm-setup buttons $confirmButtons, guest mark ${if (guestMark) "found" else "not found"}"
}

data class ScoreScanFrameStats(val mean: Double, val min: Int, val max: Int) {
    override fun toString() = String.format(Locale.ROOT, "mean %.1f, min %d, max %d", mean, min, max)

    companion object {
        fun of(pixels: ByteArray): ScoreScanFrameStats? {
            if (pixels.isEmpty()) return null
            var sum = 0L
            var min = 255
            var max = 0
            for (b in pixels) {
                val v = b.toInt() and 0xff
                sum += v
                if (v < min) min = v
                if (v > max) max = v
            }
            return ScoreScanFrameStats(sum.toDouble() / pixels.size, min, max)
        }
    }
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

    // The region RealSupportScreen.isAnyDialogOpen matches the SupportExtra tab in.
    override fun extraTab(): Pattern = with(api) { locations.support.extraRegion.getPattern(FIELD_TAG) }

    override fun frameStats(): ScoreScanFrameStats? = with(api) {
        locations.scriptArea.getPattern(FIELD_TAG).use { it.grayPixels() }?.let { ScoreScanFrameStats.of(it) }
    }

    // The two halves of RealSupportScreen.someSupportsPresent: the confirm-setup buttons (what
    // SupportBoundsFinder finds) and the guest mark.
    override fun listMeasures(): ScoreScanListMeasures = with(api) {
        ScoreScanListMeasures(boundsFinder.all().size, images[Images.Guest] in locations.support.friendRegion)
    }

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

        /**
         * After a native capture the list check may look up to this many times, waiting
         * [POST_NATIVE_WAIT] before each further look. The first 720p buffer after the capture's
         * restore is whatever the compositor queued first, and it is not known to show the screen;
         * the game draws continuously, so 150 ms spans several new frames, and three further looks
         * cover 0.45 s. A real dialog costs those 0.45 s before the scan ends.
         */
        const val POST_NATIVE_LOOKS = 4
        val POST_NATIVE_WAIT = 150.milliseconds

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
    private var lookDetail: String? = null

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
                ScoreScanEndReason.NotSupportListOrDialog, ScoreScanEndReason.RowsLost -> lookDetail
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
            var native: NativeScreen? = null
            var logged = emptyList<ScoreScanLoggedRow>()
            when (val outcome = frames.sameSnap { readScreen(folder, screens, previous) }) {
                is ScreenOutcome.End -> return outcome.reason
                ScreenOutcome.Unchanged -> {
                    if (unchangedBefore) return ScoreScanEndReason.Bottom
                    unchangedBefore = true
                }
                is ScreenOutcome.Read -> {
                    previous = outcome.keys
                    unchangedBefore = false
                    // A stop during the last row must not pay for the capture (two display resizes).
                    exitManager.checkExitRequested()
                    native = NativeScreen()
                    logged = outcome.logged
                }
            }
            // The native lines carry the look count, so they are written once the looks are over. Every
            // native crop already on disk gets its line, however the screen ends.
            var failure: Throwable? = null
            try {
                if (native != null) {
                    // Outside the snapshot block: the capture does not use the 720p snapshot.
                    captureNative(folder, logged, native)
                    if (native.restoreFailed) return ScoreScanEndReason.NativeRestoreFailed
                    // A stop at the last allowed screen is the user's, not the screen limit.
                    exitManager.checkExitRequested()
                    if (screens >= maxScreens) return ScoreScanEndReason.ScreenLimit
                }
                exitManager.checkExitRequested()
                val problem = if (native != null) lookAfterNative(folder, native) else frames.sameSnap { listProblem() }
                problem?.let { return it }
            } catch (e: Throwable) {
                failure = e
                throw e
            } finally {
                if (native != null) {
                    // A failed write must not hide a stop or the error that ended the screen.
                    if (failure == null) native.writeLines(folder)
                    else try { native.writeLines(folder) } catch (w: Throwable) { failure.addSuppressed(w) }
                }
            }
            // The lines were just written: a stop that arrived meanwhile must not be followed by a swipe.
            exitManager.checkExitRequested()
            screen.scrollDown()
            screen.delay(SupportSelectionLoop.SCROLL_SETTLE)
        }
    }

    /**
     * The native lines of one screen, gathered as each row's crop is written and held until its
     * post-capture looks are over. [attempt] is what the capture did: `ok`, or its failure reason.
     */
    private inner class NativeScreen {
        val records = mutableListOf<ScoreScanNativeRecord>()
        var looks: Int? = null
        var restoreFailed = false
        var attempt = ScoreScanNative.OK

        /** The check's outcome for [ScoreScanNativeTally.frames], counted once the lines are on disk. */
        var frames: (() -> Unit)? = null

        fun writeLines(folder: ScoreScanFolder) {
            while (records.isNotEmpty()) {
                val record = records.first()
                folder.appendLine(ScoreScanLog.nativeLine(record.copy(postNativeLooks = looks)))
                if (record.reason == ScoreScanNative.FRAME_MISMATCH) nativeTally.refused()
                records.removeAt(0)
            }
            frames?.invoke()
            frames = null
            looks?.let { nativeTally.looks(it) }
        }
    }

    /**
     * The list check after a native capture attempt, which may have resized the capture twice: it
     * looks again on a new snapshot, after [POST_NATIVE_WAIT], at most [POST_NATIVE_LOOKS] times in
     * all, before it ends the scan with what the last look saw. No gesture happens before a look
     * passes. A look counts once its snapshot was taken and checked. On the last failed look it saves
     * the SupportExtra tab's region and writes what the look measured into the summary's detail.
     */
    private fun lookAfterNative(folder: ScoreScanFolder, native: NativeScreen): ScoreScanEndReason? {
        var looks = 0
        while (true) {
            val last = looks + 1 >= POST_NATIVE_LOOKS
            val problem = frames.sameSnap {
                listProblem()?.also { if (last) saveLookDiagnosis(folder, it, looks + 1, native.attempt) }
            }
            looks++
            native.looks = looks
            if (problem == null || last) return problem
            screen.delay(POST_NATIVE_WAIT)
        }
    }

    private fun saveLookDiagnosis(folder: ScoreScanFolder, reason: ScoreScanEndReason, looks: Int, attempt: String) {
        val name = String.format(Locale.ROOT, "s%03d-extra-tab.png", screens - 1)
        frames.extraTab().use { folder.writeDebug(name, ScoreScanNative.png(it)) }
        val measured = listOfNotNull(frames.frameStats()?.let { "last 720p frame $it" }, frames.listMeasures().toString())
        lookDetail = "after the native capture attempt ($attempt), $looks looks, the last saw ${reason.wireName}; " +
            measured.joinToString("; ")
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
     * One capture for the screen whose rows were just logged, never retried after it returned. Its
     * crops are written at once and their lines gathered in [result]. [NativeScreen.restoreFailed]
     * only when the capture could not restore the 720p capture; every other problem is logged and the
     * scan goes on. A row's crop is written only when its native field matches the 720p field that
     * was read ([ScoreScanNative.FLOOR]); while the display is at the full size, a frame of which no
     * row passes is replaced by a newer one ([ScoreScanNativeFrameLoop]).
     */
    private fun captureNative(folder: ScoreScanFolder, logged: List<ScoreScanLoggedRow>, result: NativeScreen) {
        val judge = RowJudge(logged)
        val start = timeSource.markNow()
        val capture = try {
            nativeFrames.capture(judge)
        } catch (e: ScriptAbortException) {
            throw e
        } catch (e: Exception) {
            ScoreScanNativeCapture.Failed(ScoreScanNative.ERROR, e.toString())
        }
        val micros = start.elapsedNow().inWholeMicroseconds

        // Frames judged before the capture failed or was dropped are logged for diagnosis; nothing of them is saved.
        fun failAll(reason: String, detail: String?) {
            result.attempt = reason
            nativeTally.failed(reason, micros)
            val judged = judge.judged.takeIf { it > 0 }
            for ((index, row) in logged.withIndex()) {
                result.records += ScoreScanNativeRecord(
                    clock(), serverName, row.screen, row.row, row.cropSha256, null, reason, detail, micros,
                    frameCheck = judged?.let { (judge.checks.getOrNull(index) as? RowCheck.Checked)?.check },
                    nativeFrames = judged,
                )
            }
        }

        when (capture) {
            is ScoreScanNativeCapture.RestoreFailed -> {
                failAll(ScoreScanNative.RESTORE_FAILED, capture.detail)
                restoreDetail = capture.detail
                result.restoreFailed = true
            }
            is ScoreScanNativeCapture.Failed -> failAll(capture.reason, capture.detail)
            is ScoreScanNativeCapture.Frame -> capture.frame.use { frame ->
                if (micros > ScoreScanNative.CAPTURE_BOUND.inWholeMicroseconds) {
                    failAll(ScoreScanNative.SLOW, null)
                } else {
                    // The save rule must not depend on the port having judged the frame it answered.
                    if (judge.frame !== frame) judge.accept(frame, capture.map)
                    nativeTally.taken(capture.map, micros)
                    nativeTally.screenMatches(judge.firstMatches)
                    val checks = judge.checks
                    val judged = judge.judged
                    result.frames = {
                        nativeTally.frames(judged, checks.any { it is RowCheck.Checked && it.check.passes },
                            checks.any { it is RowCheck.Checked })
                    }
                    for ((row, check) in logged.zip(checks)) {
                        result.records += nativeRow(folder, frame, capture.map, row, check, micros, judge.judged)
                    }
                }
            }
        }
    }

    private sealed interface RowCheck {
        data object Outside : RowCheck
        data class Error(val detail: String) : RowCheck
        data class Checked(val crop: ScoreScanNativeCrop, val check: ScoreScanFrameCheck) : RowCheck
    }

    /** Judges each full-size frame by its rows; keeps what it measured on the last one. */
    private inner class RowJudge(private val logged: List<ScoreScanLoggedRow>) : ScoreScanFrameJudge {
        var frame: Pattern? = null
        var checks: List<RowCheck> = emptyList()
        var judged = 0

        /** The first frame's matches at the mapped place, which [ScoreScanNativeTally.lowMatchScreens] counts. */
        var firstMatches: List<Double?> = emptyList()

        override val stopRequested get() = exitManager.isExitRequested

        override fun accept(frame: Pattern, map: ScoreScanNativeMap): Boolean {
            checks = logged.map { row ->
                try {
                    val crop = ScoreScanNative.crop(map, row.fieldImage)
                    if (crop == null) RowCheck.Outside
                    else RowCheck.Checked(crop, ScoreScanNative.check(frame, map, crop, row.fieldPixels, row.fieldSize))
                } catch (e: Exception) {
                    RowCheck.Error(e.toString())
                }
            }
            if (judged == 0) firstMatches = checks.map { (it as? RowCheck.Checked)?.check?.match }
            judged++
            this.frame = frame
            // With no row that can be checked, a newer frame of the same map could not do better.
            return checks.none { it is RowCheck.Checked } || checks.any { it is RowCheck.Checked && it.check.passes }
        }
    }

    private fun nativeRow(
        folder: ScoreScanFolder,
        frame: Pattern,
        map: ScoreScanNativeMap,
        row: ScoreScanLoggedRow,
        rowCheck: RowCheck,
        micros: Long,
        judged: Int,
    ): ScoreScanNativeRecord {
        var reason: String? = null
        var detail: String? = null
        var saved: Pair<ScoreScanNativeResult, ByteArray>? = null
        val measured = (rowCheck as? RowCheck.Checked)?.check
        when (rowCheck) {
            RowCheck.Outside -> reason = ScoreScanNative.FIELD_OUTSIDE_FRAME
            is RowCheck.Error -> {
                reason = ScoreScanNative.ERROR
                detail = rowCheck.detail
            }
            // Nothing of a refused row is written: its pixels may show any part of the screen.
            is RowCheck.Checked -> if (!rowCheck.check.passes) reason = ScoreScanNative.FRAME_MISMATCH else try {
                val crop = rowCheck.crop
                val png = frame.crop(crop.region).use { ScoreScanNative.png(it) }
                saved = ScoreScanNativeResult(
                    crop = ScoreScanLog.cropName(row.screen, row.row),
                    cropSha256 = ScoreScanLog.sha256(png),
                    frame = map.frame,
                    scaleX = map.scaleX,
                    scaleY = map.scaleY,
                    region = crop.region,
                    field = crop.field,
                    clamped = crop.clamped,
                    match = rowCheck.check.match,
                    captureMicros = micros,
                    fieldOffset = rowCheck.check.offset ?: 0,
                ) to png
            } catch (e: Exception) {
                reason = ScoreScanNative.ERROR
                detail = e.toString()
            }
        }
        // A failed file write is the scan's own error, as for the 720p crops.
        saved?.let { (result, png) ->
            folder.writeNativeCrop(result.crop, png)
            nativeTally.match(result.match)
        }
        return ScoreScanNativeRecord(
            clock(), serverName, row.screen, row.row, row.cropSha256, saved?.first, reason, detail, micros,
            frameCheck = measured, nativeFrames = judged,
        )
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
