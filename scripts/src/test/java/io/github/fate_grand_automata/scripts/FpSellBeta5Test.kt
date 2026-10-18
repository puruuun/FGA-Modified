package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.modules.FpSellPolicy
import io.github.fate_grand_automata.scripts.modules.FpSellPolicy.Tier
import io.github.fate_grand_automata.scripts.modules.FpSellReader
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

    @Test fun `bronze compares the second star against the first without the old empty slot false rejection`() {
        assertEquals(1, FpSellPolicy.starsFromTier(Tier.Bronze, listOf(.72, .45, .45, .41, .44)))
        assertEquals(1, FpSellPolicy.starsFromTier(Tier.Bronze, listOf(.77, .42)))
        assertEquals(2, FpSellPolicy.starsFromTier(Tier.Bronze, listOf(.8, .76)))
        assertNull(FpSellPolicy.starsFromTier(Tier.Bronze, listOf(.8, .58)))
        assertNull(FpSellPolicy.starsFromTier(Tier.Bronze, listOf(.5, .1)))
        assertNull(FpSellPolicy.starsFromTier(Tier.Bronze, listOf(.8)))
        assertNull(FpSellPolicy.starsFromTier(Tier.Bronze, listOf(Double.NaN, .1)))
    }

    @Test fun `silver is three and gold needs four stars followed by a clearly empty fifth`() {
        assertEquals(3, FpSellPolicy.starsFromTier(Tier.Silver, emptyList()))
        assertEquals(4, FpSellPolicy.starsFromTier(Tier.Gold, listOf(.8, .8, .8, .8, .4)))
        assertNull(FpSellPolicy.starsFromTier(Tier.Gold, listOf(.8, .8, .8, .8, .51)))
        assertNull(FpSellPolicy.starsFromTier(Tier.Gold, listOf(.8, .8, .8, .8, .8)))
        assertNull(FpSellPolicy.starsFromTier(Tier.Gold, listOf(.8, .2, .8, .8, .1)))
        assertNull(FpSellPolicy.starsFromTier(Tier.Gold, emptyList()))
        assertNull(FpSellPolicy.starsFromTier(null, listOf(.9, .9, .9, .9, .1)))
    }

    @Test fun `frame colors distinguish bronze silver gold and reject teal gray and conflicting bands`() {
        fun tier(vararg c: MeanColor?) = FpSellReader.frameTier(c.toList())
        assertEquals(Tier.Bronze, tier(MeanColor(182.0,160.0,142.0)))
        assertEquals(Tier.Silver, tier(MeanColor(250.0,250.0,250.0)))
        assertEquals(Tier.Gold, tier(MeanColor(230.0,204.0,32.0)))
        assertNull(tier(FpSellReader.SELECTION_TEAL))
        assertNull(tier(MeanColor(60.0,60.0,60.0)))
        assertNull(tier(null))
        assertNull(tier(MeanColor(182.0,160.0,142.0), MeanColor(250.0,250.0,250.0)))
        // Owner stop dialog dims the game to 40 percent; its exposed teal strips still mean selected.
        val dark = MeanColor(36.0, 95.0, 68.0)
        assertEquals(true, FpSellReader.selectionState(FpSellReader.selectionDistance(listOf(dark))))
        val debug = MeanColor(0.0, 0.0, 255.0)
        assertEquals(true, FpSellReader.selectionState(FpSellReader.selectionDistance(listOf(debug, dark))))
        assertEquals(false, FpSellReader.selectionState(FpSellReader.selectionDistance(listOf(debug, MeanColor(182.0,160.0,142.0)))))
        assertNull(FpSellReader.selectionDistance(listOf(debug)))
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
