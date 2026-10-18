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
        assertThat(band.y).isEqualTo(nameRegion.y)
        assertThat(band.height).isEqualTo(nameRegion.height)
    }

    @Test
    fun `the grand row icon sits to the right of the normal row icon by the panel shift`() {
        // The NP panel shifts by about 220 px at 1920 wide, i.e. 293 px at 1440p (0006 D-1 background).
        val shift = SupportNpLayout.GRAND_ICON_X - SupportNpLayout.NORMAL_ICON_X
        assertThat(shift).isEqualTo(293)
    }

    @Test
    fun `the crop starts at the icon, stops before Lv and follows the Lv vertical extent`() {
        val prefix = Region(row.x + 1431, row.y + 280, 60, 40)
        val grand = SupportNpLayout.noblePhantasmCrop(row, true, prefix)
        assertThat(grand.x).isEqualTo(row.x + SupportNpLayout.GRAND_ICON_X - SupportNpLayout.ICON_CROP_MARGIN)
        assertThat(grand.right).isEqualTo(prefix.x - SupportNpLayout.LEVEL_CROP_GAP)
        assertThat(grand.y).isEqualTo(prefix.y - SupportNpLayout.CROP_VERTICAL_MARGIN)
        assertThat(grand.height).isEqualTo(prefix.height + SupportNpLayout.CROP_VERTICAL_MARGIN * 2)

        val normal = SupportNpLayout.noblePhantasmCrop(row, false, prefix)
        assertThat(normal.x).isEqualTo(row.x + SupportNpLayout.NORMAL_ICON_X - SupportNpLayout.ICON_CROP_MARGIN)
        assertThat(normal.right).isEqualTo(grand.right)
    }

    @Test
    fun `the Lv level text is never part of the NP crop`() {
        val prefix = Region(row.x + 1431, row.y + 280, 60, 40)
        val crop = SupportNpLayout.noblePhantasmCrop(row, true, prefix)
        assertThat(crop.right <= prefix.x).isEqualTo(true)
    }
}
