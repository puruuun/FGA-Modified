package io.github.fate_grand_automata.scripts.supportSelection

/**
 * Reads the score of one support candidate. The caller gets the class score, the Grand score and
 * how the score was displayed, and nothing about how they were found. Thresholds and templates
 * stay inside each implementation so the flow can swap engines (design 0025, D-3).
 */
interface ISupportScoreReader {
    fun read(bounds: SupportBounds, checkClass: Boolean, checkGrand: Boolean, screen: Int = -1, row: Int = -1): SupportScores
}
