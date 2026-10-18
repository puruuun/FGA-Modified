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
    // Normal rows: owner 7/8/10/11.webp (0006-r3), game area normalized to 1280x720:
    // icon left x = 336 in all eight rows. Row origin x = 38 at 720p, so (336 - 38) * 2 = 596.
    const val BAND_X = 500
    const val BAND_WIDTH = NAME_X + NAME_WIDTH - BAND_X
    const val GRAND_ICON_X = 821
    const val NORMAL_ICON_X = 596
    const val ICON_CROP_MARGIN = 6
    // D-17: the labels/spacing differ, but name glyph widths agree within 1 px. Start at the
    // text, 29 px (720p) from the icon left: grand x=478, normal x=365 after rounding.
    // Olga cross-layout similarity improves from 0.932 (icon) to 0.970 (text). Keep the wide
    // horizontal band so beta.1/2 icon-inclusive templates remain usable.
    const val TEXT_START_OFFSET = 58
    const val LEVEL_CROP_GAP = 8
    // The card icon rises about 4 px (720p) above "Lv." on the measured row, so the vertical margin
    // must cover that: 10 px at 1440p (5 px at 720p). 6 px would cut the top of the icon.
    const val CROP_VERTICAL_MARGIN = 10

    // The servant-name line sits above the NP line inside the same blue panel (0006 D-9/D-10).
    // Measured on owner JP grand rows (2026-10-18): the top of the servant label text is 31 px at
    // 720p (62 px at 1440p) above the top of "Lv." in every row checked: 3 frames of rows with a
    // friend comment and one row without a comment (152245.png, 1920 wide: 47 px both ways, which
    // is 62.7 px at 1440p). The offset does not depend on the friend comment or on the row's CE column.
    const val NAME_LINE_OFFSET = 62
    // Band extension above the servant-name line, so the whole two-line block is searched.
    const val BAND_TOP_MARGIN = 10
    // Owner 10.webp has a crop bottom 1 px below nameRegion at 720p. Include the maker's
    // bottom padding in the search band so its whole saved template fits without clipping.
    const val BAND_BOTTOM_MARGIN = CROP_VERTICAL_MARGIN

    fun nameRegion(bounds: Region) = Region(
        bounds.x + NAME_X, bounds.y + NAME_Y, NAME_WIDTH, NAME_HEIGHT
    )

    /**
     * Search band for the NP block (0006 D-1, D-10): the servant-name line and the NP line, on both
     * row layouts. The top edge is above the servant-name line, so the NP image is a two-line block.
     */
    fun noblePhantasmBand(bounds: Region): Region {
        val top = NAME_Y - NAME_LINE_OFFSET - BAND_TOP_MARGIN
        return Region(
            bounds.x + BAND_X, bounds.y + top, BAND_WIDTH, NAME_HEIGHT + NAME_Y - top + BAND_BOTTOM_MARGIN
        )
    }

    /**
     * NP block crop (0006 D-17): from the name text to just before "Lv.", vertically from the top
     * of the servant-name line to the bottom of the NP line. The "Lv.N" text and the upgrade mark are
     * left out, so the same servant and NP name match at any NP level.
     */
    fun noblePhantasmCrop(bounds: Region, grand: Boolean, levelPrefix: Region): Region {
        val iconX = if (grand) GRAND_ICON_X else NORMAL_ICON_X
        val left = bounds.x + iconX + TEXT_START_OFFSET
        val right = levelPrefix.x - LEVEL_CROP_GAP
        val top = levelPrefix.y - NAME_LINE_OFFSET - CROP_VERTICAL_MARGIN
        val bottom = levelPrefix.y + levelPrefix.height + CROP_VERTICAL_MARGIN
        return Region(left, top, right - left, bottom - top)
    }

    fun suffixRegion(prefix: Region) = Region(
        prefix.x - SUFFIX_MARGIN, prefix.y - SUFFIX_MARGIN,
        prefix.width + DIGIT_WIDTH + SUFFIX_MARGIN, prefix.height + SUFFIX_MARGIN * 2
    )
}
