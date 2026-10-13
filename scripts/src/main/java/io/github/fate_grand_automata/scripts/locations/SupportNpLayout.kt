package io.github.fate_grand_automata.scripts.locations

import io.github.lib_automata.Region

/** 1440p coordinates relative to a support row, including rows with a friend comment. */
object SupportNpLayout {
    const val NAME_X = 648
    const val NAME_Y = 260
    const val NAME_WIDTH = 896
    const val NAME_HEIGHT = 104
    const val SUFFIX_MARGIN = 4
    const val DIGIT_WIDTH = 38

    fun nameRegion(bounds: Region) = Region(
        bounds.x + NAME_X, bounds.y + NAME_Y, NAME_WIDTH, NAME_HEIGHT
    )

    fun suffixRegion(prefix: Region) = Region(
        prefix.x - SUFFIX_MARGIN, prefix.y - SUFFIX_MARGIN,
        prefix.width + DIGIT_WIDTH + SUFFIX_MARGIN, prefix.height + SUFFIX_MARGIN * 2
    )
}
