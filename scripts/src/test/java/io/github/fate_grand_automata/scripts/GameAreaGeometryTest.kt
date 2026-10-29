package io.github.fate_grand_automata.scripts

import io.github.lib_automata.Location
import io.github.lib_automata.Region
import io.github.lib_automata.Size
import io.github.lib_automata.customGameArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GameAreaGeometryTest {
    private val screen = Size(3040, 1904)

    @Test
    fun `zero offsets retain the whole screen`() {
        assertEquals(Region(0, 0, 3040, 1904), customGameArea(screen, 0, 0, 0, 0, false))
    }

    @Test
    fun `all four offsets trim native pixels`() {
        assertEquals(Region(1049, 432, 1891, 1116), customGameArea(screen, 1049, 432, 100, 356, false))
    }

    @Test
    fun `rotation 270 swaps the horizontal origin but keeps the size and vertical offsets`() {
        assertEquals(Region(100, 432, 1891, 1116), customGameArea(screen, 1049, 432, 100, 356, true))
    }

    @Test
    fun `invalid offsets retain the existing region validation`() {
        assertFailsWith<IllegalArgumentException> { customGameArea(screen, 3040, 0, 0, 0, false) }
        assertFailsWith<IllegalArgumentException> { customGameArea(screen, 0, 1000, 0, 904, false) }
    }

    @Test
    fun `whole tablet pane fits a centred 16 by 9 area around y 445 to 1565`() {
        val region = customGameArea(screen, 1049, 107, 0, 0, false)
        val game = FgoGameAreaManager(region.size) { region.location }.gameArea
        assertEquals(Region(1049, 107, 1991, 1797), region)
        // Pixel rounding in the existing manager gives 446..1566, within the measured approximation.
        assertEquals(Region(1049, 446, 1991, 1120), game)
        assertEquals(1566, game.bottom)
    }

    @Test
    fun `measured tablet image offsets give 1991 by 1116 and fit with symmetric side borders`() {
        val region = customGameArea(screen, 1049, 432, 0, 356, false)
        val game = FgoGameAreaManager(region.size) { region.location }.gameArea
        assertEquals(Region(1049, 432, 1991, 1116), region)
        assertEquals(Region(1053, 432, 1984, 1116), game)
        assertEquals(1548, game.bottom)
    }

    @Test
    fun `preview fit preserves the managers wide screen behaviour`() {
        assertEquals(Region(40, 20, 3000, 1440),
            FgoGameAreaManager(Size(3000, 1440)) { Location(40, 20) }.gameArea)
        assertEquals(Region(360, 20, 3360, 1440),
            FgoGameAreaManager(Size(4000, 1440)) { Location(40, 20) }.gameArea)
    }
}
