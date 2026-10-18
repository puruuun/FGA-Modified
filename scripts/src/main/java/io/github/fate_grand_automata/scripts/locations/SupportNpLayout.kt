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

    // Noble Phantasm name band (0006 D-1). Grand rows push the NP panel right by the CE column,
    // so the band starts left of the normal-row card icon and ends where the name band ends.
    // Measured on a grand row (owner frame, 2026-10-18, row 1): panel left edge 772, card icon
    // left edge 897, "Lv." left edge 1507 (1440p, screen x) with the row at x = 76.
    // The normal-row icon is NOT measured yet: it is derived from the same panel shift the design
    // gives (360 vs 580 at 1920 wide, 220 px = 293 px at 1440p). Confirm with a normal-row fixture.
    const val BAND_X = 500
    const val BAND_WIDTH = NAME_X + NAME_WIDTH - BAND_X
    const val GRAND_ICON_X = 821
    const val NORMAL_ICON_X = 528
    const val ICON_CROP_MARGIN = 6
    const val LEVEL_CROP_GAP = 8
    // The card icon rises about 4 px (720p) above "Lv." on the measured row, so the vertical margin
    // must cover that: 10 px at 1440p (5 px at 720p). 6 px would cut the top of the icon.
    const val CROP_VERTICAL_MARGIN = 10

    fun nameRegion(bounds: Region) = Region(
        bounds.x + NAME_X, bounds.y + NAME_Y, NAME_WIDTH, NAME_HEIGHT
    )

    /** Search band for the NP name image on both row layouts (0006 D-1). */
    fun noblePhantasmBand(bounds: Region) = Region(
        bounds.x + BAND_X, bounds.y + NAME_Y, BAND_WIDTH, NAME_HEIGHT
    )

    /**
     * NP image crop (0006 D-2): from the card icon up to just before "Lv.", vertically around the
     * "Lv." prefix. The "Lv.N" text and the upgrade mark are left out so the same NP name matches at
     * any NP level.
     */
    fun noblePhantasmCrop(bounds: Region, grand: Boolean, levelPrefix: Region): Region {
        val iconX = if (grand) GRAND_ICON_X else NORMAL_ICON_X
        val left = bounds.x + iconX - ICON_CROP_MARGIN
        val right = levelPrefix.x - LEVEL_CROP_GAP
        val top = levelPrefix.y - CROP_VERTICAL_MARGIN
        return Region(left, top, right - left, levelPrefix.height + CROP_VERTICAL_MARGIN * 2)
    }

    fun suffixRegion(prefix: Region) = Region(
        prefix.x - SUFFIX_MARGIN, prefix.y - SUFFIX_MARGIN,
        prefix.width + DIGIT_WIDTH + SUFFIX_MARGIN, prefix.height + SUFFIX_MARGIN * 2
    )
}
