package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.modules.*
import io.github.lib_automata.*
import io.mockk.*
import kotlin.test.*

class FpResummonConfirmTest {
    private class Run(val confirm: Boolean, val fullAfter: Boolean = false) {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val sale = mockk<FpAutoSell>(relaxed = true)
        val banner = mockk<FpBannerReturn>(relaxed = true)
        val reader = mockk<FpSellReader>(relaxed = true)
        val ok = Location(300, 800)
        var stage = 0
        var confirmReads = 0
        init {
            every { api.prefs.gameServer } returns GameServer.Kr
            every { api.prefs.fpAutoSell } returns true
            every { api.prefs.shouldLimitFP } returns false
            every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
            val full = Region(10, 10, 10, 10)
            val summon = Region(20, 20, 10, 10)
            every { api.locations.inventoryFullRegion } returns full
            every { api.locations.fp.summonCheck } returns summon
            every { api.locations.fp.okClick } returns ok
            every { with(api) { any<Region>().contains(any<Pattern>()) } } answers {
                val region = firstArg<Region>()
                if (region == full) stage == 0 || (stage == 2 && fullAfter) else region == summon && stage == 1
            }
            every { reader.servantFullWarning() } answers { stage == 0 || (stage == 2 && fullAfter) }
            every { reader.resummonConfirmation() } answers { confirmReads++; stage == 2 && confirm }
            every { sale.run() } returnsMany listOf(
                FpAutoSell.Result(FpSellPolicy.Summary(92, 0), false),
                FpAutoSell.Result(FpSellPolicy.Summary(0, 0), false))
            every { banner.restore() } answers { stage = 1 }
            every { banner.summonAgain() } answers { stage = 2 }
            every { with(api) { ok.click(any()) } } answers { stage = 3 }
        }
        fun stop(): AutoFriendGacha.ExitReason = assertFailsWith<AutoFriendGacha.ExitException> {
            AutoFriendGacha(mockk(), api, sale, banner, reader).script()
        }.reason
    }
    @Test fun `watermark permits original ok coordinate exactly once`() {
        val run = Run(confirm = true)
        assertSame(AutoFriendGacha.ExitReason.SellScreenUnknown, run.stop())
        verify(exactly = 1) { with(run.api) { run.ok.click(1) } }
        verify(exactly = 1) { run.banner.summonAgain(); run.sale.logFinalSummary() }
    }
    @Test fun `missing watermark never blindly taps ok and reports resummon step`() {
        val run = Run(confirm = false)
        assertEquals(92, assertIs<AutoFriendGacha.ExitReason.ResummonConfirmFailed>(run.stop()).summary.total)
        assertTrue(run.confirmReads >= 2, "fresh-frame retries")
        verify(exactly = 0) { with(run.api) { run.ok.click(any()) } }
    }
    @Test fun `full warning wins over watermark and real mode sells again until zero selectable`() {
        val run = Run(confirm = true, fullAfter = true)
        assertSame(AutoFriendGacha.ExitReason.InventoryFullNothingToSell, run.stop())
        verify(exactly = 2) { run.sale.run(); run.banner.restore(); run.banner.summonAgain() }
        verify(exactly = 0) { with(run.api) { run.ok.click(any()) } }
        assertEquals(0, run.confirmReads)
    }
}
