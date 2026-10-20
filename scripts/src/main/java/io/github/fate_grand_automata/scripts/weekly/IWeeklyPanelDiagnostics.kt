package io.github.fate_grand_automata.scripts.weekly

import io.github.lib_automata.Pattern

/** Android supplies a beta-only, run-scoped sink. Caller retains and closes the frame. */
interface IWeeklyPanelDiagnostics {
    val enabled: Boolean
    fun save(frame: Pattern, questId: Int, attempt: Int): String?
}
