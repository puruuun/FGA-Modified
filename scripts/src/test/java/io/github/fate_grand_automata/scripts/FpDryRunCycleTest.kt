package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.modules.*
import io.github.lib_automata.*
import io.mockk.*
import kotlin.test.*

class FpDryRunCycleTest {
    @Test fun `dry run and zero selection stop without banner restore or resummon`() {
        for (reason in listOf(AutoFriendGacha.ExitReason.InventoryFullNothingToSell,
            AutoFriendGacha.ExitReason.SellDryRunDone(FpSellPolicy.Summary(407, 0)))) {
            val api = mockk<IFgoAutomataApi>(relaxed = true)
            every { api.prefs.gameServer } returns GameServer.Kr
            every { api.prefs.fpAutoSell } returns true
            every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
            val reader = mockk<FpSellReader>(relaxed = true)
            every { reader.servantFullWarning() } returns true
            val sale = mockk<FpAutoSell>(relaxed = true)
            every { sale.run() } throws AutoFriendGacha.ExitException(reason)
            val banner = mockk<FpBannerReturn>(relaxed = true)
            assertSame(reason, assertFailsWith<AutoFriendGacha.ExitException> {
                AutoFriendGacha(mockk(), api, sale, banner, reader).script()
            }.reason)
            verify(exactly = 0) { banner.restore(); banner.verifySummon() }
            verify(exactly = 1) { sale.logFinalSummary() }
        }
    }

    @Test fun `real return never reenters the initial FP summon action if its label remains visible`() {
        val api=mockk<IFgoAutomataApi>(relaxed=true)
        every { api.prefs.gameServer } returns GameServer.Kr
        every { api.prefs.fpAutoSell } returns true
        every { api.prefs.shouldLimitFP } returns false
        every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
        val full=Region(10,10,10,10)
        val summon=Region(20,20,10,10)
        val ok=Location(30,30)
        every { api.locations.inventoryFullRegion } returns full
        every { api.locations.fp.summonCheck } returns summon
        var stage=0
        every { with(api) { any<Region>().contains(any<Pattern>()) } } answers {
            val region=firstArg<Region>()
            if(region==full) stage==0 else region==summon && (stage==1 || stage==3)
        }
        val reader=mockk<FpSellReader>(relaxed=true)
        every { reader.servantFullWarning() } answers { stage==0 }
        every { api.locations.fp.okClick } returns ok
        every { with(api) { ok.click(any()) } } answers { stage=3 }
        val sale=mockk<FpAutoSell>(relaxed=true)
        every { sale.run() } returns FpAutoSell.Result(FpSellPolicy.Summary(1,0),false)
        val banner=mockk<FpBannerReturn>(relaxed=true)
        every { banner.restore() } answers { stage=1 }
        every { banner.verifySummon() } just Runs
        val initial=api.locations.fp.first10SummonClick
        val error=assertFailsWith<AutoFriendGacha.ExitException> {
            AutoFriendGacha(mockk(),api,sale,banner,reader).script()
        }
        assertSame(AutoFriendGacha.ExitReason.SellScreenUnknown,error.reason)
        verify(exactly=1) { banner.verifySummon();with(api) { ok.click(1) } }
        verify(exactly=1) { with(api) { initial.click(1) } }
        verify(exactly=1) { sale.logFinalSummary() }
    }
}
