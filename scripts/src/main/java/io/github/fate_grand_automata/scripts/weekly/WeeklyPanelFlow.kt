package io.github.fate_grand_automata.scripts.weekly

import kotlin.time.Duration.Companion.seconds

/** Final D-22 order. A successful first read performs no recovery gestures. */
object WeeklyPanelFlow {
    val enterMapWait = 3.seconds // original 2s + D-23 1s
    val leaveMapWait = 3.seconds
    fun run(read: () -> Boolean, resetList: () -> Boolean, recenter: () -> Boolean): Boolean {
        if (read()) return true
        if (resetList() && read()) return true
        return recenter() && read()
    }
}

/** Once a panel was reached, recovery failures cannot overwrite its last reading with a map failure. */
class WeeklyPanelFailure {
    var opened = false
        private set
    var lastVerdict = "NoPanel"
        private set
    /** What the OCR read at the last non-matching panel (probe CSV `read_quest` / `read_spot`, JP issue #103). */
    var read = io.github.fate_grand_automata.scripts.weekly.ProbeEvidence()
        private set
    fun panelRead(questRead: String?, spotRead: String?) {
        read = io.github.fate_grand_automata.scripts.weekly.ProbeEvidence(questRead = questRead.orEmpty(), spotRead = spotRead.orEmpty())
    }
    fun panelOpened() { opened = true }
    fun verdict(value: String) { lastVerdict = value }
    fun reason(recovery: String): String =
        if (opened) "panel_mismatch last=$lastVerdict recovery=$recovery" else recovery
}
