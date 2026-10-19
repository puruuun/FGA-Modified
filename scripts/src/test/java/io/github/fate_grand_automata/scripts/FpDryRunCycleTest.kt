package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.modules.*
import io.github.lib_automata.*
import io.mockk.*
import kotlin.test.*

class FpDryRunCycleTest {
    private fun cycle(selected: Int, secondWarning: Boolean = true): AutoFriendGacha.ExitReason {
        val api=mockk<IFgoAutomataApi>(relaxed=true)
        every { api.prefs.gameServer } returns GameServer.Kr
        every { api.prefs.fpAutoSell } returns true
        every { api.prefs.shouldLimitFP } returns false
        every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
        var stage=0
        // Both inventory and FP detectors use the same mock snapshot. Only the active screen is visible.
        val full=Region(10,10,10,10)
        val summon=Region(20,20,10,10)
        every { api.locations.inventoryFullRegion } returns full
        every { api.locations.fp.summonCheck } returns summon
        every { with(api) { any<Region>().exists(any(),any(),any()) } } answers {
            val region=firstArg<Region>()
            if(region==full) stage==0 || (stage==2 && secondWarning) else region==summon && stage==1
        }
        every { with(api) { any<Region>().contains(any<Pattern>()) } } answers {
            val region=firstArg<Region>()
            if(region==full) stage==0 || (stage==2 && secondWarning) else region==summon && stage==1
        }
        val reader=mockk<FpSellReader>(relaxed=true)
        every { reader.servantFullWarning() } answers { stage==0 || (stage==2 && secondWarning) }
        val sale=mockk<FpAutoSell>(relaxed=true)
        val summary=FpSellPolicy.Summary(selected,0)
        every { sale.run() } returns FpAutoSell.Result(summary,true)
        val banner=mockk<FpBannerReturn>(relaxed=true)
        every { banner.restore() } answers { stage=1 }
        every { banner.summonAgain() } answers { stage=2 }
        val okClick = api.locations.fp.okClick
        val error=assertFailsWith<AutoFriendGacha.ExitException> { AutoFriendGacha(mockk(),api,sale,banner,reader).script() }
        verify(exactly=1) { sale.run();banner.restore();banner.summonAgain() }
        verify(exactly=0) { with(api) { okClick.click(any()) } }
        verify(exactly=1) { sale.logFinalSummary() }
        return error.reason
    }

    @Test fun `dry completion waits for banner return resummon and the second full warning`() {
        assertEquals(407,assertIs<AutoFriendGacha.ExitReason.SellDryRunDone>(cycle(407)).summary.total)
    }
    @Test fun `zero selections skip sale flow and stop with nothing to sell after returning`() {
        assertSame(AutoFriendGacha.ExitReason.InventoryFullNothingToSell,cycle(0))
    }
    @Test fun `no second full warning is a flow failure not a successful dry verification`() {
        assertIs<AutoFriendGacha.ExitReason.SellFlowFailed>(cycle(407,secondWarning=false))
    }
    @Test fun `real return never reenters the initial FP summon action if its label remains visible`() {
        val api=mockk<IFgoAutomataApi>(relaxed=true)
        every { api.prefs.gameServer } returns GameServer.Kr
        every { api.prefs.fpAutoSell } returns true
        every { api.prefs.shouldLimitFP } returns false
        every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
        val full=Region(10,10,10,10)
        val summon=Region(20,20,10,10)
        val ok=Region(30,30,10,10)
        every { api.locations.inventoryFullRegion } returns full
        every { api.locations.fp.summonCheck } returns summon
        var stage=0
        every { with(api) { any<Region>().contains(any<Pattern>()) } } answers {
            val region=firstArg<Region>()
            if(region==full) stage==0 else region==summon && (stage==1 || stage==3)
        }
        val reader=mockk<FpSellReader>(relaxed=true)
        every { reader.servantFullWarning() } answers { stage==0 }
        every { reader.loc.summonOk } returns ok
        every { with(api) { ok.find(any<Pattern>(),any()) } } answers {
            if(stage==2) Match(ok,1.0) else null
        }
        every { with(api) { ok.click(any()) } } answers { stage=3 }
        val sale=mockk<FpAutoSell>(relaxed=true)
        every { sale.run() } returns FpAutoSell.Result(FpSellPolicy.Summary(1,0),false)
        val banner=mockk<FpBannerReturn>(relaxed=true)
        every { banner.restore() } answers { stage=1 }
        every { banner.summonAgain() } answers { stage=2 }
        val initial=api.locations.fp.first10SummonClick
        val error=assertFailsWith<AutoFriendGacha.ExitException> {
            AutoFriendGacha(mockk(),api,sale,banner,reader).script()
        }
        assertSame(AutoFriendGacha.ExitReason.SellScreenUnknown,error.reason)
        verify(exactly=1) { banner.summonAgain();with(api) { ok.click(1) } }
        verify(exactly=0) { with(api) { initial.click(any()) } }
        verify(exactly=1) { sale.logFinalSummary() }
    }
}
