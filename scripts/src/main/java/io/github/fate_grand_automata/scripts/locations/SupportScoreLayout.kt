package io.github.fate_grand_automata.scripts.locations

import io.github.lib_automata.Region

/** 1440p coordinates for the numeric score, relative to a support row. */
object SupportScoreLayout {
    const val SCORE_X = 1960
    const val SCORE_Y = 310
    const val SCORE_WIDTH = 110
    const val SCORE_HEIGHT = 60

    fun numberRegion(bounds: Region) = Region(
        bounds.x + SCORE_X,
        bounds.y + SCORE_Y,
        SCORE_WIDTH,
        SCORE_HEIGHT
    )
}
