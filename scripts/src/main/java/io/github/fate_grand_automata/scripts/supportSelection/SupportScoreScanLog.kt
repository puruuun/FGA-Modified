package io.github.fate_grand_automata.scripts.supportSelection

import io.github.fate_grand_automata.scripts.diagnostics.DiagnosticJson
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.Locale

/**
 * Where the support score scan (issue #568, the rust build type only) keeps its folders. One folder
 * per scan; the format is in docs/agents/rust-native.md, "Rust test APK".
 */
interface ScoreScanStorage {
    /** Bytes free where the scans are written. */
    fun freeBytes(): Long

    /** The names of the scan folders that exist now. */
    fun scanNames(): List<String>

    /** Deletes one scan folder and everything in it. */
    fun delete(name: String)

    /** Creates the folder [name] with its `crops/` folder. */
    fun create(name: String): ScoreScanFolder
}

interface ScoreScanFolder : AutoCloseable {
    val name: String

    fun writeCrop(fileName: String, png: ByteArray)

    /** Appends one line to `scan.jsonl` and flushes it, so a killed scan keeps what it read. */
    fun appendLine(json: String)

    /** Writes `summary.json`, replacing an earlier one. */
    fun writeSummary(json: String)

    override fun close() = Unit
}

/** What the summary says about the device; the app fills it in. Nothing in it identifies an account. */
data class ScoreScanDevice(val appVersion: String, val deviceModel: String, val androidVersion: String)

/** The scan folders below [root]. Only names the scan itself gives are listed or deleted. */
class DirectoryScoreScanStorage(
    private val root: File,
    private val free: () -> Long = { root.usableSpaceOfExistingParent() },
) : ScoreScanStorage {
    override fun freeBytes() = free()

    override fun scanNames(): List<String> =
        root.listFiles()?.filter { it.isDirectory && ScoreScanLog.isScanName(it.name) }?.map { it.name } ?: emptyList()

    override fun delete(name: String) {
        require(ScoreScanLog.isScanName(name)) { "not a scan folder: $name" }
        File(root, name).deleteRecursively()
    }

    override fun create(name: String): ScoreScanFolder {
        require(ScoreScanLog.isScanName(name)) { "not a scan folder name: $name" }
        val dir = File(root, name)
        val crops = File(dir, ScoreScanLog.CROPS)
        check(crops.mkdirs() || crops.isDirectory) { "cannot create $crops" }
        return DirectoryScoreScanFolder(dir)
    }

    private class DirectoryScoreScanFolder(private val dir: File) : ScoreScanFolder {
        override val name: String = dir.name
        private val lines: BufferedWriter =
            BufferedWriter(OutputStreamWriter(FileOutputStream(File(dir, ScoreScanLog.LINES), true), Charsets.UTF_8))

        override fun writeCrop(fileName: String, png: ByteArray) =
            File(File(dir, ScoreScanLog.CROPS), fileName).writeBytes(png)

        override fun appendLine(json: String) {
            lines.write(json)
            lines.write("\n")
            lines.flush()
        }

        override fun writeSummary(json: String) {
            // Written beside and renamed, so a scan killed while writing never leaves half a summary.
            val part = File(dir, ScoreScanLog.SUMMARY + ".part")
            part.writeText(json + "\n", Charsets.UTF_8)
            val target = File(dir, ScoreScanLog.SUMMARY)
            if (!part.renameTo(target)) {
                target.delete()
                check(part.renameTo(target)) { "cannot write $target" }
            }
        }

        override fun close() = lines.close()
    }
}

/** usableSpace is 0 for a path that does not exist yet, so ask the nearest folder that does. */
private fun File.usableSpaceOfExistingParent(): Long {
    var dir: File? = absoluteFile
    while (dir != null && !dir.exists()) dir = dir.parentFile
    return dir?.usableSpace ?: 0L
}

/** One row as both readers saw it. */
data class ScoreScanRecord(
    val time: Long,
    val server: String,
    val screen: Int,
    val row: Int,
    val bounds: SupportBounds,
    val crop: String,
    val cropSha256: String,
    val kotlin: SupportScores,
    val kotlinCheckGrand: Boolean,
    val kotlinMicros: Long,
    val rust: EngineScore,
) {
    val agree: Boolean get() = ScoreScanLog.agree(kotlin, rust)
}

/** Why a scan did not start or how it ended. A scan that started has a folder; one that did not, none. */
enum class ScoreScanEndReason(val wireName: String) {
    // Before any folder exists.
    NotSupportList("not_support_list"),
    NoRows("no_rows"),

    // At the start or on a later screen.
    DialogOpen("dialog_open"),
    EngineUnavailable("engine_unavailable"),
    LowSpace("low_space"),
    UserStopped("user_stopped"),

    // Only after the first screen was read.
    Bottom("bottom"),
    ScreenLimit("screen_limit"),
    ScreenLost("screen_lost"),

    /** An exception the scan did not expect; it is rethrown after the summary is written. */
    Error("error"),
}

/** What the owner is shown at the end. [folder] is null when the scan never started. */
data class ScoreScanResult(
    val reason: ScoreScanEndReason,
    val folder: String?,
    val screens: Int,
    val rows: Int,
    val agree: Int,
    val differ: Int,
    /** The engine's unavailableReason or the exception message, for the owner and the summary. */
    val detail: String?,
)

/** The counts of summary.json. Holds two lists of at most one time per row read, bounded by the screen limit. */
class ScoreScanTally {
    var rows = 0; private set
    var agree = 0; private set
    var differ = 0; private set
    var kotlinOnly = 0; private set
    var rustOnly = 0; private set
    var bothUnknown = 0; private set
    var kotlinClass62 = 0; private set
    var rustClass62 = 0; private set
    var kotlinGrand16 = 0; private set
    var rustGrand16 = 0; private set
    val kotlinMicros = mutableListOf<Long>()
    val rustMicros = mutableListOf<Long>()

    fun add(record: ScoreScanRecord) {
        rows++
        if (record.agree) agree++ else differ++
        val kotlinRead = record.kotlin.classScore != null || record.kotlin.grandScore != null
        val rustRead = record.rust.classScore.valueOrNull != null || record.rust.grandScore.valueOrNull != null
        when {
            kotlinRead && !rustRead -> kotlinOnly++
            rustRead && !kotlinRead -> rustOnly++
            !kotlinRead && !rustRead -> bothUnknown++
        }
        if (record.kotlin.classScore == 62) kotlinClass62++
        if (record.rust.classScore.valueOrNull == 62) rustClass62++
        if (record.kotlin.grandScore == 16) kotlinGrand16++
        if (record.rust.grandScore.valueOrNull == 16) rustGrand16++
        kotlinMicros += record.kotlinMicros
        record.rust.engineTime?.let { rustMicros += it.inWholeMicroseconds }
    }
}

data class ScoreScanSummary(
    val device: ScoreScanDevice,
    val server: String,
    val folder: String,
    val startTime: Long,
    val endTime: Long,
    val reason: ScoreScanEndReason,
    val detail: String?,
    val screens: Int,
    val tally: ScoreScanTally,
    val engineLoadMicros: Long?,
    val engineUnavailableReason: String?,
)

object ScoreScanLog {
    const val SCHEMA = 1
    const val ROOT = "support-score-scan"
    const val LINES = "scan.jsonl"
    const val SUMMARY = "summary.json"
    const val CROPS = "crops"

    /** The 20 newest scans are kept: about 2 MB each at the screen bound, so the folder stays small. */
    const val KEEP_SCANS = 20

    /**
     * A scan writes at most about 2 MB (80 screens of a few 3 KB crops and lines); 50 MB leaves the
     * device room for the game, which also writes to this storage.
     */
    const val MIN_FREE_BYTES = 50L * 1024 * 1024

    private val scanName = Regex("""\d{8}-\d{6}(-\d{1,3})?""")

    fun isScanName(name: String) = scanName.matches(name)

    fun cropName(screen: Int, row: Int) = String.format(Locale.ROOT, "s%03d-r%d.png", screen, row)

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { String.format(Locale.ROOT, "%02x", it) }

    /**
     * The scan folders to delete so that, with the one about to be created, [KEEP_SCANS] remain.
     * The names are timestamps, so the oldest sort first.
     */
    fun toDelete(existing: List<String>): List<String> {
        val scans = existing.filter(::isScanName).sorted()
        return scans.take((scans.size - (KEEP_SCANS - 1)).coerceAtLeast(0))
    }

    /** [stamp] is `yyyyMMdd-HHmmss`; a second scan in the same second gets a suffix. */
    fun newName(stamp: String, existing: List<String>): String {
        if (stamp !in existing) return stamp
        return (2..999).map { "$stamp-$it" }.first { it !in existing }
    }

    fun EngineUnknownReason.wireName(): String = when (this) {
        EngineUnknownReason.Blank -> "blank"
        EngineUnknownReason.LowConfidence -> "low_confidence"
        EngineUnknownReason.Format -> "format"
        EngineUnknownReason.Range -> "range"
        EngineUnknownReason.Clipped -> "clipped"
        EngineUnknownReason.NotShown -> "not_shown"
        EngineUnknownReason.CropSize -> "crop_size"
        EngineUnknownReason.EngineError -> "engine_error"
        EngineUnknownReason.Unavailable -> "unavailable"
    }

    private fun EngineField.reason(): EngineUnknownReason? = (this as? EngineField.Unknown)?.reason

    /** "read" when the engine read the field, else why it could not: "engine_error" or "unavailable". */
    fun status(score: EngineScore): String {
        val reasons = listOfNotNull(score.classScore.reason(), score.grandScore.reason())
        return when {
            EngineUnknownReason.Unavailable in reasons -> "unavailable"
            EngineUnknownReason.EngineError in reasons -> "engine_error"
            else -> "read"
        }
    }

    fun SupportScoreDisplay.wireName() = name.lowercase()
    fun EngineDisplay.wireName() = name.lowercase()

    /** Class, Grand and display equal; an unknown equals only an unknown, whatever its reason. */
    fun agree(kotlin: SupportScores, rust: EngineScore) =
        kotlin.classScore == rust.classScore.valueOrNull &&
            kotlin.grandScore == rust.grandScore.valueOrNull &&
            kotlin.display.wireName() == rust.display.wireName()

    private fun Float?.json(): Float? = this?.takeIf { it.isFinite() }

    fun line(record: ScoreScanRecord): String = DiagnosticJson.write(linkedMapOf(
        "schema" to SCHEMA,
        "time" to record.time,
        "server" to record.server,
        "screen" to record.screen,
        "row" to record.row,
        "bounds" to record.bounds.region.let { linkedMapOf("x" to it.x, "y" to it.y, "w" to it.width, "h" to it.height) },
        "crop" to "$CROPS/${record.crop}",
        "cropSha256" to record.cropSha256,
        "kotlin" to linkedMapOf(
            "classScore" to record.kotlin.classScore,
            "grandScore" to record.kotlin.grandScore,
            "display" to record.kotlin.display.wireName(),
            "checkGrand" to record.kotlinCheckGrand,
            "micros" to record.kotlinMicros,
        ),
        "rust" to record.rust.let { rust ->
            linkedMapOf(
                "status" to status(rust),
                "classScore" to rust.classScore.valueOrNull,
                "grandScore" to rust.grandScore.valueOrNull,
                "display" to rust.display.wireName(),
                "classReason" to rust.classScore.reason()?.wireName(),
                "grandReason" to rust.grandScore.reason()?.wireName(),
                "confidence" to rust.confidence.json(),
                "minCharConfidence" to rust.minCharConfidence.json(),
                "text" to rust.text,
                "detail" to rust.detail,
                "micros" to rust.engineTime?.inWholeMicroseconds,
                "callMicros" to rust.duration.inWholeMicroseconds,
            )
        },
        "agree" to record.agree,
    ))

    /** The lower median, so the value is always one that was measured. */
    private fun times(values: List<Long>) = values.sorted().let { sorted ->
        linkedMapOf(
            "count" to sorted.size,
            "median" to sorted.getOrNull((sorted.size - 1) / 2),
            "max" to sorted.lastOrNull(),
        )
    }

    fun summary(s: ScoreScanSummary): String = DiagnosticJson.write(linkedMapOf(
        "schema" to SCHEMA,
        "appVersion" to s.device.appVersion,
        "deviceModel" to s.device.deviceModel,
        "androidVersion" to s.device.androidVersion,
        "server" to s.server,
        "folder" to s.folder,
        "startTime" to s.startTime,
        "endTime" to s.endTime,
        "endReason" to s.reason.wireName,
        "detail" to s.detail,
        "screens" to s.screens,
        "rows" to s.tally.rows,
        "counts" to linkedMapOf(
            "agree" to s.tally.agree,
            "differ" to s.tally.differ,
            "kotlinOnly" to s.tally.kotlinOnly,
            "rustOnly" to s.tally.rustOnly,
            "bothUnknown" to s.tally.bothUnknown,
            "kotlinClass62" to s.tally.kotlinClass62,
            "rustClass62" to s.tally.rustClass62,
            "kotlinGrand16" to s.tally.kotlinGrand16,
            "rustGrand16" to s.tally.rustGrand16,
        ),
        "engineLoadMicros" to s.engineLoadMicros,
        "kotlinMicros" to times(s.tally.kotlinMicros),
        "rustMicros" to times(s.tally.rustMicros),
        "engineUnavailableReason" to s.engineUnavailableReason,
    ))
}
