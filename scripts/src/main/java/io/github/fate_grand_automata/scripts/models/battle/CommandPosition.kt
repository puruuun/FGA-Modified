package io.github.fate_grand_automata.scripts.models.battle

/**
 * Zero-based position in the user's command, independent of the actual battle counters.
 * [totalTurn] counts turns across waves, starting at 0 on the first turn of the command.
 */
data class CommandPosition(val stage: Int, val turn: Int, val totalTurn: Int = 0)
