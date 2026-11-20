package io.github.fate_grand_automata.scripts.supportSelection

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.locations.SupportScoreLayout
import io.github.fate_grand_automata.scripts.modules.SupportScreen
import io.github.lib_automata.ExitManager
import io.github.lib_automata.Pattern
import io.github.lib_automata.ScriptAbortException
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** One snapshot of the support list, its rows and each row's score field. */
interface ScoreScanFrames {
    fun <T> sameSnap(block: () -> T): T

    fun rows(): List<SupportBounds>

    /** The score field of [bounds] from the current snapshot, as the engine reads it (60x30 at 720p). */
    fun field(bounds: SupportBounds): Pattern
}

class ApiScoreScanFrames(
    private val api: IFgoAutomataApi,
    private val boundsFinder: SupportBoundsFinder,
) : ScoreScanFrames {
    override fun <T> sameSnap(block: () -> T): T = api.useSameSnapIn(block)

    override fun rows() = boundsFinder.all()

    // The same crop as the rust-engine device case and the Kotlin reader's own number region.
    override fun field(bounds: SupportBounds): Pattern = with(api) {
        SupportScoreLayout.numberRegion(bounds.region).getPattern(FIELD_TAG)
    }

    private companion object {
        const val FIELD_TAG = "support-score-scan"
    }
}

/**
 * The owner's support score scan (issue #568, the rust build type only): reads every score field of
 * the support list with the Kotlin reader and the Rust engine and logs both. Neither answer decides
 * anything. Its only gesture is [SupportScreen.scrollDown]; it never taps, refreshes or opens
 * anything, so it cannot enter a quest or spend anything.
 *
 * End of the list: the rows of a screen, by their y and the SHA-256 of their saved crop, equal the
 * previous screen's rows. A scroll that moved the list changes the y or the pixels of the fields;
 * one that could not move it (the bottom, or a list shorter than a screen) leaves both as they were.
 * That screen is not read again.
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
        data class Read(val keys: List<RowKey>) : ScreenOutcome
        data class End(val reason: ScoreScanEndReason) : ScreenOutcome
    }

    private val serverName = server.serialize()
    private val checkGrand = SupportScoreSelection.showsGrandScore(server)
    private val tally = ScoreScanTally()
    private var screens = 0

    fun run(): ScoreScanResult {
        val startTime = clock()
        val notStarted = try {
            startProblem()
        } catch (e: ScriptAbortException) {
            ScoreScanEndReason.UserStopped
        }
        if (notStarted != null) {
            val detail = if (notStarted == ScoreScanEndReason.EngineUnavailable) engine.unavailableReason else null
            return ScoreScanResult(notStarted, null, 0, 0, 0, 0, detail)
        }

        storage.scanNames().let { existing -> ScoreScanLog.toDelete(existing).forEach(storage::delete) }
        val name = ScoreScanLog.newName(stamp(startTime), storage.scanNames())
        val folder = storage.create(name)
        try {
            val reason = try {
                scan(folder)
            } catch (e: ScriptAbortException) {
                ScoreScanEndReason.UserStopped
            }
            val detail = if (reason == ScoreScanEndReason.EngineUnavailable) engine.unavailableReason else null
            folder.writeSummary(summary(name, startTime, reason, detail))
            return ScoreScanResult(reason, name, screens, tally.rows, tally.agree, tally.differ, detail)
        } catch (e: Exception) {
            runCatching { folder.writeSummary(summary(name, startTime, ScoreScanEndReason.Error, e.toString())) }
            throw e
        } finally {
            runCatching { folder.close() }
        }
    }

    private fun startProblem(): ScoreScanEndReason? = when {
        screen.isAnyDialogOpen() -> ScoreScanEndReason.DialogOpen
        !screen.isListLoaded() -> ScoreScanEndReason.NotSupportList
        !screen.someSupportsPresent() -> ScoreScanEndReason.NoRows
        !engine.available -> ScoreScanEndReason.EngineUnavailable
        storage.freeBytes() < ScoreScanLog.MIN_FREE_BYTES -> ScoreScanEndReason.LowSpace
        else -> null
    }

    private fun scan(folder: ScoreScanFolder): ScoreScanEndReason {
        var previous: List<RowKey>? = null
        while (true) {
            if (storage.freeBytes() < ScoreScanLog.MIN_FREE_BYTES) return ScoreScanEndReason.LowSpace
            when (val outcome = frames.sameSnap { readScreen(folder, screens, previous) }) {
                is ScreenOutcome.End -> return outcome.reason
                is ScreenOutcome.Read -> previous = outcome.keys
            }
            if (screens >= maxScreens) return ScoreScanEndReason.ScreenLimit
            screen.scrollDown()
            // The settle time SupportSelectionLoop waits after each of its scrolls.
            screen.delay(0.3.seconds)
        }
    }

    private fun readScreen(folder: ScoreScanFolder, index: Int, previous: List<RowKey>?): ScreenOutcome {
        if (screen.isAnyDialogOpen()) return ScreenOutcome.End(ScoreScanEndReason.DialogOpen)
        if (!screen.someSupportsPresent()) return ScreenOutcome.End(ScoreScanEndReason.ScreenLost)
        val rows = frames.rows()
        if (rows.isEmpty()) return ScreenOutcome.End(ScoreScanEndReason.ScreenLost)

        val fields = mutableListOf<Pattern>()
        try {
            rows.mapTo(fields) { frames.field(it) }
            val pngs = fields.map { field -> ByteArrayOutputStream().also { field.save(it) }.toByteArray() }
            val keys = rows.indices.map { RowKey(rows[it].region.y, ScoreScanLog.sha256(pngs[it])) }
            if (keys == previous) return ScreenOutcome.End(ScoreScanEndReason.Bottom)

            for (row in rows.indices) {
                exitManager.checkExitRequested()
                if (!engine.available) return ScreenOutcome.End(ScoreScanEndReason.EngineUnavailable)

                val crop = ScoreScanLog.cropName(index, row)
                folder.writeCrop(crop, pngs[row])
                val kotlinStart = timeSource.markNow()
                val kotlin = reader.read(rows[row], true, checkGrand, index, row)
                val kotlinMicros = kotlinStart.elapsedNow().inWholeMicroseconds
                val rust = engine.read(fields[row])
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
                if (ScoreScanLog.status(rust) == "unavailable") return ScreenOutcome.End(ScoreScanEndReason.EngineUnavailable)
            }
            return ScreenOutcome.Read(keys)
        } finally {
            fields.forEach { runCatching { it.close() } }
        }
    }

    private fun summary(name: String, startTime: Long, reason: ScoreScanEndReason, detail: String?) =
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
        ))
}
