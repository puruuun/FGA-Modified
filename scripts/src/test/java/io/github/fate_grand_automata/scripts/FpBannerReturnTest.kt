package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha
import io.github.fate_grand_automata.scripts.locations.FpSellLocations
import io.github.fate_grand_automata.scripts.modules.FpSellTiming
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
            every { loc.screenClose } returns Region(0,0,40,20)
            every { loc.shop } returns Region(100,0,40,20)
            every { loc.menu } returns Region(100,700,40,20)
            every { loc.summonMenu } returns Region(400,700,40,20)
            every { loc.leftArrow } returns left
            every { loc.leftArrowRegion } returns Region(10, 690, 20, 20)
            every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
            every { with(api) { titleRegion.getPattern(any()) } } returns saved
            every { with(api) { any<Region>().exists(any(), any(), any()) } } returns true
            every { with(api) { titleRegion.exists(saved, any(), any()) } } answers { titleReads++; presses >= matchAfter }
            every { with(api) { any<Region>().find(any<Pattern>(), any()) } } answers { Match(firstArg<Region>(), 1.0) }
            every { with(api) { Region(10, 690, 20, 20).click(any()) } } answers { presses++ }
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
        assertSame(AutoFriendGacha.ExitReason.BannerTitleNotFound,
            assertFailsWith<AutoFriendGacha.ExitException> { run.banner.restore() }.reason)
        assertEquals(10, run.presses)
        assertEquals(11, run.titleReads)
    }

    @Test fun `matching title without FP button never permits a return`() {
        val run = Run(0, fpVisible = false)
        assertSame(AutoFriendGacha.ExitReason.BannerTitleNotFound,
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
    @Test fun `missing close or arrow template never taps a guessed button`() {
        val run = Run(1)
        every { with(run.api) { run.loc.leftArrowRegion.find(any<Pattern>(),any()) } } returns null
        assertSame(AutoFriendGacha.ExitReason.BannerTitleNotFound,
            assertFailsWith<AutoFriendGacha.ExitException> { run.banner.restore() }.reason)
        assertEquals(0,run.presses)
    }

    @Test fun `resummon requires the saved title FP label and an actual button match`() {
        for ((step,fp) in listOf(1 to true,0 to false)) {
            val run=Run(step,fp)
            assertSame(AutoFriendGacha.ExitReason.BannerTitleNotFound,
                assertFailsWith<AutoFriendGacha.ExitException> { run.banner.summonAgain() }.reason)
        }
        val run=Run(0)
        every { with(run.api) { any<Region>().find(any<Pattern>(),any()) } } returns null
        assertSame(AutoFriendGacha.ExitReason.SummonMenuFailed,
            assertFailsWith<AutoFriendGacha.ExitException> { run.banner.summonAgain() }.reason)
    }

    @Test fun `resummon taps only the matched FP button`() {
        val run=Run(0)
        val button=Region(900,1000,120,50)
        every { with(run.api) { any<Region>().find(any<Pattern>(),any()) } } returns Match(button,1.0)
        run.banner.summonAgain()
        verify(exactly=1) { with(run.api) { button.click(1) } }
        assertEquals(0,run.presses)
    }
    @Test fun `workshop MENU summon and title failures each log their own stop step`() {
        val reasons = listOf(AutoFriendGacha.ExitReason.WorkshopNotReached,
            AutoFriendGacha.ExitReason.WorkshopMenuFailed, AutoFriendGacha.ExitReason.SummonMenuFailed)
        for (reason in reasons) {
            val run = Run(0)
            val region = when (reason) {
                AutoFriendGacha.ExitReason.WorkshopNotReached -> run.loc.shop
                AutoFriendGacha.ExitReason.WorkshopMenuFailed -> run.loc.summonMenu
                else -> run.loc.leftArrowRegion
            }
            every { with(run.api) { region.exists(any(),any(),any()) } } returns false
            assertSame(reason, assertFailsWith<AutoFriendGacha.ExitException> { run.banner.restore() }.reason)
            verify(exactly=1) { run.api.messages.log(match { it is ScriptLog.FpSell && it.message == "stop step=bannerReturn reason=${reason.label}" }) }
        }
        val run=Run(11)
        assertFailsWith<AutoFriendGacha.ExitException> { run.banner.restore() }
        verify(exactly=1) { run.api.messages.log(match { it is ScriptLog.FpSell && it.message == "stop step=bannerReturn reason=title" }) }
    }

    @Test fun `fading workshop MENU waits for a fresh match and taps once`() {
        val run=Run(0)
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
    @Test fun `ignored first sell close is retried once and workshop arrival permits MENU`() {
        val run=Run(0)
        val close=run.loc.screenClose
        val shop=run.loc.shop
        var closes=0
        every { with(run.api) { close.click(any()) } } answers { closes++ }
        every { with(run.api) { shop.exists(any(),any(),any()) } } answers { closes==2 }
        run.banner.restore()
        assertEquals(2,closes)
        verify(exactly=30) { with(run.api) { FpSellTiming.FIND_POLL.wait() } }
        verify(exactly=4) { with(run.api) { FpSellTiming.BUTTON_SETTLE.wait() } }
    }

    @Test fun `sell close stops with workshop reason after its single retry`() {
        val run=Run(0)
        val close=run.loc.screenClose
        val shop=run.loc.shop
        every { with(run.api) { shop.exists(any(),any(),any()) } } returns false
        assertSame(AutoFriendGacha.ExitReason.WorkshopNotReached,
            assertFailsWith<AutoFriendGacha.ExitException> { run.banner.restore() }.reason)
        verify(exactly=2) { with(run.api) { close.click(1) } }
        verify(exactly=60) { with(run.api) { FpSellTiming.FIND_POLL.wait() } }
    }

    @Test fun `left arrow and FP resummon use the increased post tap delays`() {
        val run=Run(1)
        val summon=Region(900,1000,100,50)
        every { run.loc.summonButton } returns summon
        run.banner.restore()
        run.banner.summonAgain()
        verify(exactly=1) { with(run.api) { FpSellTiming.AFTER_ARROW.wait() } }
        verifyOrder { with(run.api) { summon.click(1);FpSellTiming.AFTER_ACTION.wait() } }
    }
}
