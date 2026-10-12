package io.github.fate_grand_automata.scripts.models.battle

/** Zero-based position in the user's command, independent of the actual battle counters. */
data class CommandPosition(val stage: Int, val turn: Int)
