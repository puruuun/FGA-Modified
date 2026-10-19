package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.modules.*
import io.github.lib_automata.*
import io.mockk.*
import kotlin.test.*

class FpResummonPathTest {
    private class Done : RuntimeException()

    private fun taps(afterSale: Boolean, daily: Boolean, dry: Boolean): List<String> {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        every { api.prefs.gameServer } returns GameServer.Kr
        every { api.prefs.fpAutoSell } returns true
        every { api.prefs.shouldLimitFP } returns false
        every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
        val full = Region(10,10,10,10)
        val normal = Region(20,20,10,10)
        val free = Region(30,30,10,10)
        val first = Location(100,100)
        val ok = Location(200,200)
        val dailyFirst = Location(300,300)
        val dailyContinue = Region(400,400,10,10)
        every { api.locations.inventoryFullRegion } returns full
        every { api.locations.fp.summonCheck } returns normal
        every { api.locations.fp.initialSummonCheck } returns free
        every { api.locations.fp.first10SummonClick } returns first
        every { api.locations.fp.okClick } returns ok
        every { api.locations.fp.initialSummonClick } returns dailyFirst
        every { api.locations.fp.initialSummonContinueClick } returns dailyContinue
        var stage = if (afterSale) 0 else 1
        every { with(api) { any<Region>().contains(any<Pattern>()) } } answers {
            val region = firstArg<Region>()
            if (region == full) stage == 0 else stage == 1 && region == if (daily) free else normal
        }
        val reader = mockk<FpSellReader>(relaxed = true)
        every { reader.servantFullWarning() } answers { stage == 0 }
        val sale = mockk<FpAutoSell>(relaxed = true)
        every { sale.run() } returns FpAutoSell.Result(FpSellPolicy.Summary(92,0),dry)
        val banner = mockk<FpBannerReturn>(relaxed = true)
        every { banner.restore() } answers { stage = 1 }
        val events = mutableListOf<String>()
        every { with(api) { first.click(any()) } } answers { events.add("first10") }
        every { with(api) { dailyFirst.click(any()) } } answers { events.add("daily") }
        every { with(api) { FpSellTiming.SUMMON_CONFIRM.wait(any()) } } answers { events.add("wait:0.3") }
        every { with(api) { ok.click(any()) } } answers { events.add("ok");throw Done() }
        every { with(api) { dailyContinue.click(any()) } } answers { events.add("dailyContinue");throw Done() }
        assertFailsWith<Done> { AutoFriendGacha(mockk(),api,sale,banner,reader).script() }
        verify(exactly = if (afterSale) 1 else 0) { banner.verifySummon();sale.run();banner.restore() }
        return events
    }

    @Test fun `normal resummon uses the same first10 wait and ok as the first summon in both modes`() {
        val expected = listOf("first10","wait:0.3","ok")
        assertEquals(expected,taps(afterSale=false,daily=false,dry=false))
        for (dry in listOf(true,false)) assertEquals(expected,taps(afterSale=true,daily=false,dry=dry))
    }
    @Test fun `daily free resummon uses the same initial wait and continue as the first daily summon`() {
        val expected = listOf("daily","wait:0.3","dailyContinue")
        assertEquals(expected,taps(afterSale=false,daily=true,dry=false))
        for (dry in listOf(true,false)) assertEquals(expected,taps(afterSale=true,daily=true,dry=dry))
    }
    @Test fun `banner verification failure prevents all original summon taps`() {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        every { api.prefs.fpAutoSell } returns true
        every { api.prefs.gameServer } returns GameServer.Kr
        every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
        val reader = mockk<FpSellReader>(relaxed = true)
        var returned = false
        every { reader.servantFullWarning() } answers { !returned }
        val full = Region(10,10,10,10)
        val normal = Region(20,20,10,10)
        every { api.locations.inventoryFullRegion } returns full
        every { api.locations.fp.summonCheck } returns normal
        every { with(api) { any<Region>().contains(any<Pattern>()) } } answers {
            val region = firstArg<Region>()
            if (region == full) !returned else region == normal && returned
        }
        val sale = mockk<FpAutoSell>(relaxed = true)
        every { sale.run() } returns FpAutoSell.Result(FpSellPolicy.Summary(92,0),false)
        val banner = mockk<FpBannerReturn>(relaxed = true)
        every { banner.restore() } answers { returned=true }
        every { banner.verifySummon() } throws AutoFriendGacha.ExitException(AutoFriendGacha.ExitReason.BannerTitleNotFound)
        assertSame(AutoFriendGacha.ExitReason.BannerTitleNotFound,assertFailsWith<AutoFriendGacha.ExitException> {
            AutoFriendGacha(mockk(),api,sale,banner,reader).script()
        }.reason)
        verify(exactly=0) { with(api) { any<Location>().click(any()) } }
    }
}
