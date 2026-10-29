package io.github.fate_grand_automata.scripts.models

/** Usable face-card colours belonging to one target servant. */
data class CardColourCounts(val buster: Int = 0, val arts: Int = 0, val quick: Int = 0) {
    override fun toString() = "B$buster A$arts Q$quick"
}
