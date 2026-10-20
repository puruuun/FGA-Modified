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
