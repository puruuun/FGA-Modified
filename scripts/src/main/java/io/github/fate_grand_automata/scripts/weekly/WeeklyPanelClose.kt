package io.github.fate_grand_automata.scripts.weekly

/** D-38: bounded retries followed by one screen-driven recovery. */
object WeeklyPanelClose {
    fun run(tap: () -> Boolean, waitMap: () -> Boolean, diagnostic: () -> Unit, recover: () -> Boolean): Boolean {
        repeat(3) {
            if (tap() && waitMap()) return true
        }
        diagnostic()
        return recover()
    }
}
