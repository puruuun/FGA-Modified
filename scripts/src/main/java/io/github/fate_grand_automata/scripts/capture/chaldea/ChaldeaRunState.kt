package io.github.fate_grand_automata.scripts.capture.chaldea

/**
 * What the sync knows about the Chaldea app. [Unknown] is treated like [Visible]: never close, never write.
 */
enum class ChaldeaRunState { NotRunning, Visible, Background, Unknown }

/** Result of scanning `dumpsys activity activities` for one package. */
class ChaldeaActivityScan(
    /** A `topResumedActivity` / `ResumedActivity` line names the package. */
    val topResumed: Boolean,
    /** One entry per task line of the package: its `visible=` value, or null when the line has none. */
    val taskVisible: List<Boolean?>,
    /** The dump had a recognisable shape (at least one task or activity record line anywhere). */
    val recognised: Boolean,
)

/** Result of scanning `dumpsys window windows` for one package. */
class ChaldeaWindowScan(
    /** One entry per window of the package: its `isVisible=` value, or null when it has none. */
    val windowVisible: List<Boolean?>,
    val recognised: Boolean,
)

/**
 * Pure parsing of the shell output that decides [ChaldeaRunState]. The dumps are consumed line by line so the
 * service never holds a whole dump (they run to hundreds of KB).
 */
object ChaldeaStateParser {
    private fun pkgRegex(pkg: String) = Regex("""(?<![\w.])""" + Regex.escape(pkg) + """(?![\w.])""")
    private val visibleRegex = Regex("""(?<![\w])visible=(true|false)""")
    private val isVisibleRegex = Regex("""(?<![\w])isVisible=(true|false)""")
    private val windowHeader = Regex("""^\s*Window #\d+ Window\{""")

    fun scanActivities(lines: Sequence<String>, pkg: String): ChaldeaActivityScan {
        val re = pkgRegex(pkg)
        var top = false
        var recognised = false
        val tasks = ArrayList<Boolean?>()
        for (line in lines) {
            if (line.contains("Task{") || line.contains("ActivityRecord{")) recognised = true
            if (!line.contains(pkg)) continue
            if (!re.containsMatchIn(line)) continue
            if (line.contains("ResumedActivity") && line.contains("ActivityRecord{")) top = true
            if (line.contains("Task{")) {
                tasks += visibleRegex.find(line)?.groupValues?.get(1)?.toBooleanStrict()
            }
        }
        return ChaldeaActivityScan(top, tasks, recognised)
    }

    fun scanWindows(lines: Sequence<String>, pkg: String): ChaldeaWindowScan {
        val re = pkgRegex(pkg)
        var recognised = false
        val windows = ArrayList<Boolean?>()
        var inChaldea = false
        var current: Boolean? = null
        fun flush() {
            if (inChaldea) windows += current
            inChaldea = false
            current = null
        }
        for (line in lines) {
            if (windowHeader.containsMatchIn(line)) {
                recognised = true
                flush()
                inChaldea = re.containsMatchIn(line)
                continue
            }
            if (inChaldea && current == null) {
                current = isVisibleRegex.find(line)?.groupValues?.get(1)?.toBooleanStrict()
            }
        }
        flush()
        return ChaldeaWindowScan(windows, recognised)
    }

    /**
     * @param pidofExit exit code of `pidof <pkg>`, or null when the command could not be run
     * @param activity null when `dumpsys activity activities` failed
     * @param window null when `dumpsys window windows` failed
     */
    fun decide(
        pidofExit: Int?,
        pidofOutput: String,
        activity: ChaldeaActivityScan?,
        window: ChaldeaWindowScan?,
    ): ChaldeaRunState {
        if (pidofExit == null) return ChaldeaRunState.Unknown
        val hasPid = pidofOutput.trim().isNotEmpty()
        if (!hasPid) return if (pidofExit == 0 || pidofExit == 1) ChaldeaRunState.NotRunning else ChaldeaRunState.Unknown
        if (pidofExit != 0) return ChaldeaRunState.Unknown

        // Any sign of being on screen wins, even when the other dump is missing.
        if (activity != null && (activity.topResumed || activity.taskVisible.any { it == true })) return ChaldeaRunState.Visible
        if (window != null && window.windowVisible.any { it == true }) return ChaldeaRunState.Visible

        // Background needs explicit proof from both dumps.
        if (activity == null || window == null || !activity.recognised || !window.recognised) return ChaldeaRunState.Unknown
        if (activity.taskVisible.isEmpty() || activity.taskVisible.any { it == null }) return ChaldeaRunState.Unknown
        if (window.windowVisible.isEmpty() || window.windowVisible.any { it == null }) return ChaldeaRunState.Unknown
        return ChaldeaRunState.Background
    }

    /** Text form, used by the tests and anything that already holds the dumps as strings. */
    fun parse(
        pkg: String,
        pidofExit: Int?,
        pidofOutput: String,
        activityDump: String?,
        windowDump: String?,
    ): ChaldeaRunState = decide(
        pidofExit, pidofOutput,
        activityDump?.let { scanActivities(it.lineSequence(), pkg) },
        windowDump?.let { scanWindows(it.lineSequence(), pkg) },
    )

    /**
     * Polls [read] until it returns [ChaldeaRunState.NotRunning] or [timeoutMs] passes. Returns the last state read.
     */
    fun awaitNotRunning(
        read: () -> ChaldeaRunState,
        nowMs: () -> Long,
        sleepMs: (Long) -> Unit,
        timeoutMs: Long = 3000,
        intervalMs: Long = 250,
    ): ChaldeaRunState {
        val start = nowMs()
        var state = read()
        while (state != ChaldeaRunState.NotRunning && nowMs() - start < timeoutMs) {
            sleepMs(intervalMs)
            state = read()
        }
        return state
    }
}
