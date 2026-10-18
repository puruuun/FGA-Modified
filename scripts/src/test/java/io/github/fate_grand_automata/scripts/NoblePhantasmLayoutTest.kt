package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.scripts.locations.SupportNpLayout
import io.github.lib_automata.Region
import kotlin.test.Test

class NoblePhantasmLayoutTest {
    private val row = Region(76, 408, 2356, 428)
    private val band = SupportNpLayout.noblePhantasmBand(row)
    private val nameRegion = SupportNpLayout.nameRegion(row)

    @Test
    fun `the band covers both row layouts and ends where the name region ends`() {
        val grandIcon = row.x + SupportNpLayout.GRAND_ICON_X
        val normalIcon = row.x + SupportNpLayout.NORMAL_ICON_X
        assertThat(band.x <= normalIcon - SupportNpLayout.ICON_CROP_MARGIN).isEqualTo(true)
        assertThat(band.x + band.width > grandIcon).isEqualTo(true)
        assertThat(band.right).isEqualTo(nameRegion.right)
        assertThat(band.bottom).isEqualTo(nameRegion.bottom + SupportNpLayout.BAND_BOTTOM_MARGIN)
    }

    @Test
    fun `the band reaches above the servant-name line so the whole two-line block is searched`() {
        val servantLineTop = nameRegion.y - SupportNpLayout.NAME_LINE_OFFSET
        assertThat(band.y <= servantLineTop - SupportNpLayout.BAND_TOP_MARGIN).isEqualTo(true)
        assertThat(band.y).isEqualTo(servantLineTop - SupportNpLayout.BAND_TOP_MARGIN)
    }

    @Test
    fun `the grand row icon sits to the right of the normal row icon by the panel shift`() {
        // Owner normal rows measured at x=336, grand x=448.5 (720p), relative to row origin x=38.
        val shift = SupportNpLayout.GRAND_ICON_X - SupportNpLayout.NORMAL_ICON_X
        assertThat(shift).isEqualTo(225)
    }

    @Test
    fun `the block crop starts at the name text, stops before Lv and starts at the servant-name line`() {
        val prefix = Region(row.x + 1431, row.y + 280, 60, 40)
        val grand = SupportNpLayout.noblePhantasmCrop(row, true, prefix)
        assertThat(grand.x).isEqualTo(row.x + SupportNpLayout.GRAND_ICON_X + SupportNpLayout.TEXT_START_OFFSET)
        assertThat(grand.right).isEqualTo(prefix.x - SupportNpLayout.LEVEL_CROP_GAP)
        assertThat(grand.y).isEqualTo(prefix.y - SupportNpLayout.NAME_LINE_OFFSET - SupportNpLayout.CROP_VERTICAL_MARGIN)
        assertThat(grand.bottom).isEqualTo(prefix.bottom + SupportNpLayout.CROP_VERTICAL_MARGIN)

        val normal = SupportNpLayout.noblePhantasmCrop(row, false, prefix)
        assertThat(normal.x).isEqualTo(row.x + SupportNpLayout.NORMAL_ICON_X + SupportNpLayout.TEXT_START_OFFSET)
        assertThat(normal.right).isEqualTo(grand.right)
    }

    @Test
    fun `the Lv level text is never part of the NP crop`() {
        val prefix = Region(row.x + 1431, row.y + 280, 60, 40)
        val crop = SupportNpLayout.noblePhantasmCrop(row, true, prefix)
        assertThat(crop.right <= prefix.x).isEqualTo(true)
    }

    @Test
    fun `owner normal row with a low Lv marker keeps the whole maker crop inside the band`() {
        // 10.webp row 0 at 720p: row y=215, Lv=(641,368,38,25), crop bottom=398.
        val bounds = Region(76, 430, 2356, 428)
        val crop = SupportNpLayout.noblePhantasmCrop(bounds, false, Region(1282, 736, 76, 50))
        assertThat(crop in SupportNpLayout.noblePhantasmBand(bounds)).isEqualTo(true)
    }
}
