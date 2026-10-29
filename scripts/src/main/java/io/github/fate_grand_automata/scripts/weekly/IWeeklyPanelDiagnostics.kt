package io.github.fate_grand_automata.scripts.weekly

import io.github.lib_automata.Pattern

/** Android supplies a beta-only, run-scoped sink. Caller retains and closes the frame. */
interface IWeeklyPanelDiagnostics {
    fun saveAbort(frame: Pattern): String? = save(frame, 0, -1)
    val enabled: Boolean
    /** Probe mode (D-25) saves failure frames in any build, up to [WeeklyProbe.DIAGNOSTIC_LIMIT]. */
    fun startProbe()
    fun save(frame: Pattern, questId: Int, attempt: Int): String?
    /** D-30: the evidence picture of a probe success. Not counted against [WeeklyProbe.DIAGNOSTIC_LIMIT]. Returns the path or null. */
    fun saveEvidence(image: Pattern, questId: Int): String? = null
    /** Issue #91: one weekly-route map line (localize, plan, scroll, tap point) for the run's `map.log`. Beta builds only. */
    fun mapLog(line: String) {}
    /** Issue #102: writes any map lines still buffered. Called when the run ends. */
    fun flushMapLog() {}
}
