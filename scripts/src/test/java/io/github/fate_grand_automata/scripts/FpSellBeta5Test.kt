package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.modules.FpSellPolicy
import io.github.lib_automata.*
import io.mockk.*
import kotlin.test.*

class FpSellBeta5Test {
    @Test fun `swipe retains an evaluated row and never crosses an unread row`() {
        for (pitch in listOf(270, 280, 284, 288)) for (count in 2..4) for (phase in 0 until pitch step 11) {
            val rows = (0 until count).map { 366 + phase + it * pitch }
            val distance = assertNotNull(FpSellPolicy.swipeDistance(rows))
            assertTrue(distance <= rows.last() - rows.first(), "$rows -> $distance")
            assertTrue(distance <= pitch * (count - 1))
            assertTrue(distance in 1..560)
            // The previous last row must reappear at or below the first evaluated Y, not in gridTop.
            assertTrue(rows.last() - distance >= rows.first())
            // Every row that passes above the viewport on this move was already evaluated.
            assertTrue(rows.filter { it - distance < rows.first() }.all { it in rows.dropLast(1) })
        }
        assertEquals(284, FpSellPolicy.swipeDistance(listOf(400)))
        assertEquals(284, FpSellPolicy.swipeDistance(listOf(400, 684)))
        assertEquals(560, FpSellPolicy.swipeDistance(listOf(400, 684, 968)))
        assertNull(FpSellPolicy.swipeDistance(emptyList()))
    }

    @Test fun `bounded scrolling ignores swipe multiplier and uses the gesture endpoint hold`() {
        val gestures = mockk<GestureService>(relaxed = true)
        val platform = mockk<PlatformImpl>()
        val transform = mockk<Transformer>()
        every { transform.toScreen(any<Location>()) } answers { firstArg() }
        val from = Location(1280,1310); val to = Location(1280,750)
        RealSwiper(gestures, platform, transform).scroll(from,to)
        verify(exactly=1) { gestures.scroll(from,to) }
        verify(exactly=0) { gestures.swipe(any(),any()) }
        verify { platform wasNot Called }
    }
}
