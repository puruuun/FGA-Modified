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

    /** Pixel coordinates inside a 720p delimiter-bearing Grand template. */
    fun grandLastDigit(templateWidth: Int, templateHeight: Int): Region {
        val width = if (templateWidth >= 34) 12 else 10
        return Region(templateWidth - width, 0, width, templateHeight)
    }

    /** Map a template's pixel subregion into its matched 1440p script region. */
    fun matchedPixels(match: Region, templateWidth: Int, templateHeight: Int, pixels: Region) = Region(
        match.x + pixels.x * match.width / templateWidth,
        match.y + pixels.y * match.height / templateHeight,
        pixels.width * match.width / templateWidth,
        pixels.height * match.height / templateHeight
    )
}
