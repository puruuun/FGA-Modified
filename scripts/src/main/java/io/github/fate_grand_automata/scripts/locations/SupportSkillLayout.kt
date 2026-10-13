package io.github.fate_grand_automata.scripts.locations

import io.github.lib_automata.Region

/** 1440p coordinates relative to the row found by SupportBoundsFinder. */
object SupportSkillLayout {
    const val LEVEL_X = 1610
    const val LEVEL_Y = 325
    const val LEVEL_WIDTH = 60
    const val LEVEL_HEIGHT = 50
    const val NORMAL_SPACING = 90
    const val APPEND_SPACING = 75

    fun levelRegions(bounds: Region, append: Boolean): List<Region> {
        val spacing = if (append) APPEND_SPACING else NORMAL_SPACING
        return List(if (append) 5 else 3) { index ->
            Region(
                bounds.x + LEVEL_X + index * spacing,
                bounds.y + LEVEL_Y,
                LEVEL_WIDTH,
                LEVEL_HEIGHT
            )
        }
    }
}
