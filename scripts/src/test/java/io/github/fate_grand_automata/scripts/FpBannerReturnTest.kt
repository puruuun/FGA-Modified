package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha
import io.github.fate_grand_automata.scripts.locations.FpSellLocations
import io.github.fate_grand_automata.scripts.modules.FpSellTiming
import io.github.fate_grand_automata.scripts.modules.FpBannerReturn
import io.github.lib_automata.*
import io.mockk.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class FpBannerReturnTest {
    private class Run(val position: Int = 1, val fpVisible: Boolean = true) {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val loc = mockk<FpSellLocations>(relaxed = true)
        val left = Location(20, 700)
        var presses = 0
        val banner: FpBannerReturn
        init {
            every { loc.close } returns Location(20,20)
            every { loc.menu } returns Region(100,700,40,20)
            every { loc.summonMenu } returns Region(400,700,40,20)
            every { loc.leftArrow } returns left
            every { loc.leftArrowRegion } returns Region(10, 690, 20, 20)
            every { api.prefs.fpBannerPosition } returns position
            every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
            every { with(api) { any<Region>().exists(any(), any(), any()) } } returns true
            every { with(api) { any<Region>().find(any<Pattern>(), any()) } } answers { Match(firstArg<Region>(), 1.0) }
            every { with(api) { Region(10, 690, 20, 20).click(any()) } } answers { presses++ }
            every { api.findImage(any(), Images.FriendSummon) } returns fpVisible
            banner = FpBannerReturn(api, loc)
        }
    }

    @Test fun `restore presses left exactly once for the last banner and twice for the second from the end`() {
        for (n in 1..2) {
            val run = Run(n)
            run.banner.restore()
            assertEquals(n, run.presses)
        }
    }

    @Test fun `each left press waits the arrow delay`() {
        val run = Run(2)
        run.banner.restore()
        verify(exactly = 2) { with(run.api) { FpSellTiming.AFTER_ARROW.wait() } }
    }

    @Test fun `FP label missing after the presses stops with the position reason without summoning`() {
        val run = Run(1, fpVisible = false)
        assertSame(AutoFriendGacha.ExitReason.FpBannerNotAtPosition,
            assertFailsWith<AutoFriendGacha.ExitException> { run.banner.restore() }.reason)
        assertEquals(1, run.presses)
        verify(exactly = 0) { with(run.api) { run.loc.summonButton.click(any()); run.loc.dailySummonButton.click(any()) } }
    }

    @Test fun `summon button missing after the presses stops with the position reason`() {
        val run = Run(2)
        every { with(run.api) { any<Region>().find(any<Pattern>(), any()) } } answers {
            val r = firstArg<Region>()
            if (r == run.loc.summonButton || r == run.loc.dailySummonButton) null else Match(r, 1.0)
        }
        every { run.loc.summonButton } returns Region(900, 1000, 100, 50)
        every { run.loc.dailySummonButton } returns Region(500, 1000, 100, 50)
        assertSame(AutoFriendGacha.ExitReason.FpBannerNotAtPosition,
            assertFailsWith<AutoFriendGacha.ExitException> { run.banner.restore() }.reason)
        assertEquals(2, run.presses)
    }

    @Test fun `missing close or arrow template never taps a guessed button`() {
        val run = Run(1)
        every { with(run.api) { run.loc.leftArrowRegion.find(any<Pattern>(),any()) } } returns null
        assertSame(AutoFriendGacha.ExitReason.SummonMenuFailed,
            assertFailsWith<AutoFriendGacha.ExitException> { run.banner.restore() }.reason)
        assertEquals(0,run.presses)
    }

    @Test fun `resummon requires the FP label and an actual button match and needs no saved title`() {
        val noLabel=Run(1,false)
        assertSame(AutoFriendGacha.ExitReason.SummonMenuFailed,
            assertFailsWith<AutoFriendGacha.ExitException> { noLabel.banner.verifySummon() }.reason)
        val run=Run(1)
        every { with(run.api) { any<Region>().find(any<Pattern>(),any()) } } returns null
        assertSame(AutoFriendGacha.ExitReason.SummonMenuFailed,
            assertFailsWith<AutoFriendGacha.ExitException> { run.banner.verifySummon() }.reason)
    }

    @Test fun `resummon verification does not tap the matched FP button`() {
        val run=Run(1)
        val button=Region(900,1000,120,50)
        every { with(run.api) { any<Region>().find(any<Pattern>(),any()) } } returns Match(button,1.0)
        run.banner.verifySummon()
        verify(exactly=0) { with(run.api) { any<Region>().click(any()); any<Location>().click(any()) } }
        assertEquals(0,run.presses)
    }
    @Test fun `fading workshop MENU waits for a fresh match and taps once`() {
        val run=Run(1)
        var reads=0
        val menu=run.loc.menu
        every { with(run.api) { menu.find(any<Pattern>(),any()) } } answers {
            reads++
            if (reads < 8) null else Match(menu,1.0)
        }
        run.banner.restore()
        assertEquals(8,reads)
        verify(exactly=1) { with(run.api) { menu.click(1) } }
    }
    @Test fun `left arrow retains its delay while resummon verification never taps`() {
        val run=Run(1)
        val summon=Region(900,1000,100,50)
        every { run.loc.summonButton } returns summon
        run.banner.restore()
        verify(exactly=1) { with(run.api) { FpSellTiming.AFTER_ARROW.wait() } }
        clearMocks(run.api, answers = false, childMocks = false)
        run.banner.verifySummon()
        verify(exactly=0) { with(run.api) { summon.click(any()) } }
        verify(exactly=1) { with(run.api) { FpSellTiming.BUTTON_SETTLE.wait() } }
        verify(exactly=1) { with(run.api) { any<kotlin.time.Duration>().wait(any()) } }
    }

    @Test fun `summon screen load waits five seconds then polls up to fifteen seconds and never re-taps`() {
        val run=Run(1)
        val icon=run.loc.summonMenu
        val arrow=run.loc.leftArrowRegion
        var polls=0
        every { with(run.api) { arrow.exists(any(),any(),any()) } } answers { ++polls > 40 }
        run.banner.restore()
        verify(exactly=1) { with(run.api) { icon.click(1) } }
        verify(exactly=1) { with(run.api) { FpSellTiming.SUMMON_LOAD_WAIT.wait() } }
        assertEquals(5.seconds,FpSellTiming.SUMMON_LOAD_WAIT)
        assertEquals(15.seconds,FpSellTiming.FIND_POLL*FpSellTiming.SUMMON_LOAD_POLLS)
    }

    @Test fun `summon screen missing after the load window stops with the summon reason without a second tap`() {
        val run=Run(1)
        val icon=run.loc.summonMenu
        every { with(run.api) { run.loc.leftArrowRegion.exists(any(),any(),any()) } } returns false
        assertSame(AutoFriendGacha.ExitReason.SummonMenuFailed,
            assertFailsWith<AutoFriendGacha.ExitException> { run.banner.restore() }.reason)
        verify(exactly=1) { with(run.api) { icon.click(1) } }
        verify(exactly=1) { with(run.api) { FpSellTiming.SUMMON_LOAD_WAIT.wait() } }
    }
    @Test fun `sell screen close is fixed once then waits four seconds before MENU with no shop check`() {
        val run = Run(1)
        val close = run.loc.close
        val menu = run.loc.menu
        run.banner.restore()
        verify(exactly = 1) { with(run.api) { close.click(1) } }
        verifyOrder { with(run.api) { close.click(1); 4.seconds.wait(); menu.click(1) } }
    }
}
