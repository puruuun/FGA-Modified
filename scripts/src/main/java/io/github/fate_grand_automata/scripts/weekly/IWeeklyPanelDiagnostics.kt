package io.github.fate_grand_automata.scripts.weekly

import io.github.lib_automata.Pattern

/** Android supplies a beta-only, run-scoped sink. Caller retains and closes the frame. */
interface IWeeklyPanelDiagnostics {
    val enabled: Boolean
    /** Probe mode (D-25) saves failure frames in any build, up to [WeeklyProbe.DIAGNOSTIC_LIMIT]. */
    fun startProbe()
    fun save(frame: Pattern, questId: Int, attempt: Int): String?
    /** D-30: the evidence picture of a probe success. Not counted against [WeeklyProbe.DIAGNOSTIC_LIMIT]. Returns the path or null. */
    fun saveEvidence(image: Pattern, questId: Int): String? = null
}
