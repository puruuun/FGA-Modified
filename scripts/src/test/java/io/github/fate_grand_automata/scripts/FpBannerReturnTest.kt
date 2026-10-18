package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha
import io.github.fate_grand_automata.scripts.locations.FpSellLocations
import io.github.fate_grand_automata.scripts.modules.FpBannerReturn
import io.github.lib_automata.*
import io.mockk.*
import kotlin.test.*

class FpBannerReturnTest {
    private class Run(val matchAfter: Int, val fpVisible: Boolean = true) {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val loc = mockk<FpSellLocations>(relaxed = true)
        val saved = mockk<Pattern>(relaxed = true)
        val titleRegion = Region(600, 600, 100, 100)
        val left = Location(20, 700)
        var presses = 0
        var titleReads = 0
        val banner: FpBannerReturn
        init {
            every { loc.bannerTitle } returns titleRegion
            every { loc.leftArrow } returns left
            every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
            every { with(api) { titleRegion.getPattern(any()) } } returns saved
            every { with(api) { any<Region>().exists(any(), any(), any()) } } returns true
            every { with(api) { titleRegion.exists(saved, any(), any()) } } answers { titleReads++; presses >= matchAfter }
            every { with(api) { any<Region>().find(any<Pattern>(), any()) } } answers { Match(firstArg<Region>(), 1.0) }
            every { with(api) { left.click(any()) } } answers { presses++ }
            every { api.findImage(any(), Images.FriendSummon) } returns fpVisible
            banner = FpBannerReturn(api, loc)
            banner.remember()
        }
    }

    @Test fun `restore compares before moving and succeeds on every left step including the tenth`() {
        for (step in 0..10) {
            val run = Run(step)
            run.banner.restore()
            assertEquals(step, run.presses)
            assertEquals(step + 1, run.titleReads)
        }
    }

    @Test fun `title mismatch stops after ten left presses without summoning`() {
        val run = Run(11)
        assertSame(AutoFriendGacha.ExitReason.BannerNotFound,
            assertFailsWith<AutoFriendGacha.ExitException> { run.banner.restore() }.reason)
        assertEquals(10, run.presses)
        assertEquals(11, run.titleReads)
    }

    @Test fun `matching title without FP button never permits a return`() {
        val run = Run(0, fpVisible = false)
        assertSame(AutoFriendGacha.ExitReason.BannerNotFound,
            assertFailsWith<AutoFriendGacha.ExitException> { run.banner.restore() }.reason)
        assertEquals(10, run.presses)
    }

    @Test fun `only the first title is remembered and its pattern is released`() {
        val run = Run(0)
        run.banner.remember()
        verify(exactly = 1) { with(run.api) { run.titleRegion.getPattern(any()) } }
        run.banner.release()
        verify(exactly = 1) { run.saved.close() }
    }
}
