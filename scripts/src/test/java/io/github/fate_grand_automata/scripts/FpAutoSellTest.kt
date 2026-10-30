package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.locations.FpSellLocations
import io.github.fate_grand_automata.scripts.modules.*
import io.github.fate_grand_automata.scripts.modules.FpSellPolicy.Cell
import io.github.fate_grand_automata.scripts.modules.FpSellPolicy.Lock
import io.github.fate_grand_automata.scripts.modules.FpSellPolicy.Options
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.lib_automata.*
import io.mockk.*
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class FpAutoSellTest {
    @Test fun `every tile combination follows D29 with only dry run admitting an unknown lock`() {
        for (present in listOf(true, false)) for (lock in Lock.entries)
            for (selected in listOf(true, false, null))
                for (dry in listOf(true, false)) {
                    val cell = Cell(present, lock, selected)
                    val expected = present && lock != Lock.Locked && selected == false &&
                        (dry || lock == Lock.Unlocked)
                    assertEquals(expected, FpSellPolicy.select(cell, Options(dry)), cell.toString())
                    assertEquals(expected, FpSellPolicy.decision(cell, Options(dry)) == "select")
                }
    }

    @Test fun `real sale needs a validated build, a lock template, and a KR or JP server`() {
        for (server in listOf(GameServer.Kr, GameServer.Jp.Original, GameServer.En.Original, GameServer.Tw))
            for (hasTemplate in listOf(true, false))
                assertEquals(FpSellPolicy.REAL_SELL_VALIDATED && hasTemplate && (server is GameServer.Kr || server is GameServer.Jp),
                    FpSellPolicy.realSellAllowed(server, hasTemplate), "$server $hasTemplate")
        assertFalse(FpSellPolicy.realSellAllowed(GameServer.En.Original, hasLockTemplate = true))
        assertFalse(FpSellPolicy.realSellAllowed(GameServer.Kr, hasLockTemplate = false))
    }

    @Test fun `dry run is on by default and real sale needs it turned off explicitly`() {
        assertTrue(FpSellPolicy.DEFAULT_DRY_RUN)
        val prefs = mockk<IPreferences>(relaxed = true)
        every { prefs.gameServer } returns GameServer.Kr
        every { prefs.fpSellDryRun } returns FpSellPolicy.DEFAULT_DRY_RUN
        assertTrue(FpSellPolicy.options(prefs, hasLockTemplate = true).dryRun, "default keeps dry run")
        every { prefs.fpSellDryRun } returns false
        assertFalse(FpSellPolicy.options(prefs, hasLockTemplate = true).dryRun, "explicit opt-out allows real sale")
    }

    @Test fun `dry run is forced whenever real sale is not allowed`() {
        val prefs = mockk<IPreferences>(relaxed = true)
        every { prefs.fpSellDryRun } returns false
        every { prefs.gameServer } returns GameServer.Kr
        assertTrue(FpSellPolicy.options(prefs, hasLockTemplate = false).dryRun, "no lock template")
        assertFalse(FpSellPolicy.options(prefs, hasLockTemplate = true).dryRun, "KR with lock template")
        every { prefs.gameServer } returns GameServer.En.Original
        assertTrue(FpSellPolicy.options(prefs, hasLockTemplate = true).dryRun, "EN is never validated")
        every { prefs.gameServer } returns GameServer.Kr
        every { prefs.fpSellDryRun } returns true
        assertTrue(FpSellPolicy.options(prefs, hasLockTemplate = true).dryRun, "user dry run")
    }

    @Test fun `size toggle reaches seven columns in at most two presses on the 5-6-7 cycle`() {
        assertEquals(0, FpSellPolicy.sizeTogglesToSeven(7))
        assertEquals(1, FpSellPolicy.sizeTogglesToSeven(6))
        assertEquals(2, FpSellPolicy.sizeTogglesToSeven(5))
        assertNull(FpSellPolicy.sizeTogglesToSeven(4))
    }

    @Test fun `full warning is a servant warning only when the servant word is on the first line`() {
        assertTrue(FpSellPolicy.isServantFullWarning(servantWordFound = true))
        assertFalse(FpSellPolicy.isServantFullWarning(servantWordFound = false))
    }

    @Test fun `repeated full inventory after selling zero stops without a second sale`() {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val prefs = mockk<IPreferences>(relaxed = true)
        every { api.prefs } returns prefs
        every { prefs.fpAutoSell } returns true
        every { prefs.gameServer } returns GameServer.Kr
        every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
        every { with(api) { any<Region>().exists(any(), any(), any()) } } returns true
        val sale = mockk<FpAutoSell>(relaxed = true)
        every { sale.run() } throws AutoFriendGacha.ExitException(AutoFriendGacha.ExitReason.InventoryFullNothingToSell)
        val banner = mockk<FpBannerReturn>(relaxed = true)
        val reader = mockk<FpSellReader>(relaxed = true)
        every { reader.servantFullWarning() } returns true
        val error = assertFailsWith<AutoFriendGacha.ExitException> {
            AutoFriendGacha(mockk(), api, sale, banner, reader).script()
        }
        assertSame(AutoFriendGacha.ExitReason.InventoryFullNothingToSell, error.reason)
        verify(exactly = 1) { sale.run() }
        verify(exactly = 0) { banner.restore() }
    }

    @Test fun `full warning that is not a servant warning stops without selling`() {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val prefs = mockk<IPreferences>(relaxed = true)
        val full = mockk<Pattern>()
        val region = Region(0, 0, 10, 10)
        every { api.prefs } returns prefs
        every { prefs.fpAutoSell } returns true
        every { prefs.gameServer } returns GameServer.Jp.Original
        every { api.images[Images.InventoryFull, any()] } returns full
        every { api.locations.inventoryFullRegion } returns region
        every { with(api) { region.exists(full, any(), any()) } } returns true
        every { with(api) { region.contains(full) } } returns true
        every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
        val sale = mockk<FpAutoSell>(relaxed = true)
        val banner = mockk<FpBannerReturn>(relaxed = true)
        val reader = mockk<FpSellReader>(relaxed = true)
        every { reader.servantFullWarning() } returns false
        val error = assertFailsWith<AutoFriendGacha.ExitException> {
            AutoFriendGacha(mockk(), api, sale, banner, reader).script()
        }
        assertSame(AutoFriendGacha.ExitReason.InventoryFullNotServant, error.reason)
        verify { sale wasNot Called }
        verify(exactly = 0) { banner.restore() }
    }

    @Test fun `auto sell off exits inventory full without touching sale or banner`() {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val prefs = mockk<IPreferences>(relaxed = true)
        val full = mockk<Pattern>()
        val region = Region(0, 0, 10, 10)
        every { api.prefs } returns prefs
        every { prefs.gameServer } returns GameServer.Kr
        every { prefs.fpAutoSell } returns false
        every { api.images[Images.InventoryFull, any()] } returns full
        every { api.locations.inventoryFullRegion } returns region
        every { with(api) { region.exists(full, any(), any()) } } returns true
        every { with(api) { region.contains(full) } } returns true
        every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
        val sale = mockk<FpAutoSell>(relaxed = true)
        val banner = mockk<FpBannerReturn>(relaxed = true)
        val reader = mockk<FpSellReader>(relaxed = true)
        val error = assertFailsWith<AutoFriendGacha.ExitException> {
            AutoFriendGacha(mockk(), api, sale, banner, reader).script()
        }
        assertSame(AutoFriendGacha.ExitReason.InventoryFull, error.reason)
        verify { sale wasNot Called }
        verify(exactly = 0) { banner.restore() }
    }

    /** The same fixture exercises the complete final-confirmation control flow without physical gestures. */
    private class SellRun(val sellFound: Boolean = true, val sevenColumns: Boolean = true,
        val dry: Boolean = true, lock: Lock = Lock.Unknown) {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val card = Region(110, 110, 20, 20)
        val sell = Region(10, 10, 20, 20)
        val confirm = Region(300, 300, 20, 20)
        val cancel = Region(100, 500, 20, 20)
        val convert = Region(600, 500, 20, 20)
        val close = Region(100, 800, 20, 20)
        val reader = mockk<FpSellReader>()
        val swiper = mockk<Swiper>(relaxed = true)
        val unselected = Cell(true, lock, false)
        val logs = mutableListOf<String>()
        val sale: FpAutoSell
        init {
            val prefs = mockk<IPreferences>(relaxed = true)
            every { api.prefs } returns prefs
            every { prefs.gameServer } returns GameServer.Kr
            every { prefs.fpSellDryRun } returns dry
            every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
            val loc = mockk<FpSellLocations>(relaxed = true)
            every { loc.rowPitch } returns 284
            every { loc.fullSell } returns sell
            every { loc.confirm } returns confirm
            every { loc.convert } returns convert
            every { loc.doneClose } returns close
            every { api.images[any(), any()] } returns mockk<Pattern>()
            every { with(api) { any<Region>().find(any<Pattern>(), any()) } } answers { Match(firstArg<Region>(), 1.0) }
            if (!sellFound) every { with(api) { sell.find(any<Pattern>(), any()) } } returns null
            every { with(api) { any<Region>().exists(any(), any(), any()) } } returns true
            every { api.messages.log(any()) } answers { (firstArg<ScriptLog>() as? ScriptLog.FpSell)?.let { logs.add(it.message) }; Unit }
            every { reader.loc } returns loc
            every { reader.hasLockTemplate } returns true
            every { reader.onServantTab() } returns true
            every { reader.gridReadable() } returns true
            every { reader.isSevenColumns() } returns sevenColumns
            every { reader.readPage() } returns listOf(FpSellReader.PageTile(0, card, null))
            every { reader.readCard(card) } returnsMany listOf(unselected, unselected.copy(selected = true))
            every { reader.scrollbar() } returnsMany listOf(
                FpSellReader.ScrollbarState(false),
                FpSellReader.ScrollbarState(false),
                FpSellReader.ScrollbarState(true))
            sale = FpAutoSell(api, reader, swiper)
        }
        fun result() = try { sale.run() } catch (e: AutoFriendGacha.ExitException) {
            val dry = e.reason as? AutoFriendGacha.ExitReason.SellDryRunDone ?: throw e
            FpAutoSell.Result(dry.summary, true)
        } finally { sale.logFinalSummary() }
        fun failure() = assertFailsWith<AutoFriendGacha.ExitException> { try { sale.run() } finally { sale.logFinalSummary() } }
    }

    @Test fun `bottom template finishes after tile taps with no extra swipe`() {
        for (bar in listOf(FpSellReader.ScrollbarState(true))) {
            val run = SellRun()
            every { run.reader.scrollbar() } returns bar
            assertEquals(1, run.result().summary.total)
            verifyOrder { run.reader.readPage(); run.reader.readCard(run.card); with(run.api) { run.card.click(1) }; run.reader.scrollbar() }
            verify(exactly = 0) { run.swiper.scroll(any(), any()) }
            assertTrue(run.logs.any { "reason=scrollbar-end" in it })
        }
    }

    @Test fun `dry run stops immediately after Confirm without reading or tapping final dialog`() {
        val run = SellRun()
        assertEquals(FpSellPolicy.Summary(1, 1), assertIs<AutoFriendGacha.ExitReason.SellDryRunDone>(run.failure().reason).summary)
        verify(exactly = 1) { with(run.api) { run.confirm.click(1) } }
        verify(exactly = 0) { with(run.api) { run.convert.click(any()); run.close.click(any()); run.cancel.click(any()) } }
    }

    @Test fun `real final taps use fixed locations and exact one one four second waits`() {
        val run = SellRun(dry = false, lock = Lock.Unlocked)
        run.result()
        verifyOrder { with(run.api) {
            run.confirm.click(1); 1.seconds.wait(); run.convert.click(1); 1.seconds.wait()
            run.close.click(1); 4.seconds.wait()
        } }
        verify(exactly = 0) { with(run.api) { run.confirm.find(any<Pattern>(), any()); run.convert.find(any<Pattern>(), any()); run.close.find(any<Pattern>(), any()) } }
        verify(exactly = 1) { run.reader.onServantTab() }
    }

    @Test fun `entry waits for any column grid before checking the tab and seven columns`() {
        val run = SellRun()
        every { run.reader.gridReadable() } returnsMany listOf(false, false, true)
        run.result()
        verifyOrder { run.reader.gridReadable(); run.reader.onServantTab(); run.reader.isSevenColumns() }
        verify(exactly = 3) { run.reader.gridReadable() }
    }

    @Test fun `real mode finds and taps Convert and completion Close but never Cancel`() {
        val run = SellRun(dry = false, lock = Lock.Unlocked)
        assertEquals(FpAutoSell.Result(FpSellPolicy.Summary(1, 0), false), run.result())
        verify(exactly = 1) { with(run.api) { run.convert.click(1); run.close.click(1) } }
        verify(exactly = 0) { with(run.api) { run.cancel.click(any()) } }
    }

    @Test fun `unknown selection never taps and zero selections bypass Confirm`() {
        val run = SellRun()
        every { run.reader.readCard(run.card) } returns run.unselected.copy(selected = null)
        assertSame(AutoFriendGacha.ExitReason.InventoryFullNothingToSell, run.failure().reason)
        verify(exactly = 0) { with(run.api) { run.card.click(any()); run.confirm.click(any()); run.convert.click(any()) } }
    }

    @Test fun `rejected overlap is subtracted including unknown lock and never tapped again`() {
        val run = SellRun()
        every { run.reader.readCard(run.card) } returns run.unselected
        assertSame(AutoFriendGacha.ExitReason.InventoryFullNothingToSell, run.failure().reason)
        verify(exactly = 1) { with(run.api) { run.card.click(1) } }
        verify(exactly = 0) { with(run.api) { run.confirm.click(any()); run.convert.click(any()) } }
        assertTrue(run.logs.any { it.contains("decision=tap-rejected") })
        assertTrue(run.logs.any { it.contains("taps=1 rejected=1 selected=0") })
    }

    @Test fun `manual stop writes final pages taps rejected summary`() {
        val run = SellRun()
        every { run.reader.readPage() } throws ScriptAbortException()
        assertFailsWith<ScriptAbortException> { run.result() }
        assertTrue(run.logs.any { it.contains("final-summary pages=0 taps=0 rejected=0") })
    }

    @Test fun `missing entry and columns keep distinct stop messages`() {
        assertIs<AutoFriendGacha.ExitReason.SellEntryFailed>(SellRun(sellFound = false).failure().reason)
        assertIs<AutoFriendGacha.ExitReason.ColumnSwitchFailed>(SellRun(sevenColumns = false).failure().reason)
    }

    @Test fun `no rows retries once then stops with the grid reason`() {
        val run = SellRun()
        every { run.reader.readPage() } returns emptyList()
        assertIs<AutoFriendGacha.ExitReason.SelectionScreenLost>(run.failure().reason)
        verify(exactly = 2) { run.reader.readPage() }
        verify(exactly = 0) { with(run.api) { run.card.click(any()) } }
    }

    @Test fun `each sell step maps to its own stop reason carrying dry run and selected count`() {
        val expected = mapOf(
            AutoFriendGacha.SellStep.Entry to AutoFriendGacha.ExitReason.SellEntryFailed::class,
            AutoFriendGacha.SellStep.Columns to AutoFriendGacha.ExitReason.ColumnSwitchFailed::class,
            AutoFriendGacha.SellStep.Selection to AutoFriendGacha.ExitReason.SelectionScreenLost::class,
            AutoFriendGacha.SellStep.Flow to AutoFriendGacha.ExitReason.SellFlowFailed::class)
        assertEquals(AutoFriendGacha.SellStep.entries.toSet(), expected.keys)
        for ((step, type) in expected) for (dry in listOf(true, false)) {
            val reason = AutoFriendGacha.sellStop(step, dry, 7)
            assertEquals(type, reason::class, "$step")
            assertEquals(dry, reason.dryRun)
            assertEquals(7, reason.selected)
        }
        assertEquals(4, expected.values.toSet().size, "reasons are distinct")
        assertNotEquals<Any>(AutoFriendGacha.ExitReason.SellScreenUnknown::class, AutoFriendGacha.ExitReason.SellEntryFailed::class)
    }

    @Test fun `teal frame reads selected, other frame colours read unselected, and borderline reads unknown`() {
        val teal = FpSellReader.SELECTION_TEAL
        fun strip(dr: Double, dg: Double, db: Double) = MeanColor(teal.red + dr, teal.green + dg, teal.blue + db)
        fun state(vararg strips: MeanColor?) = FpSellReader.selectionState(FpSellReader.selectionDistance(strips.toList()))
        assertEquals(true, state(strip(0.0, 0.0, 0.0)))
        assertEquals(true, state(strip(100.0, -100.0, 0.0), strip(8.0, -6.0, 5.0)), "best strip wins")
        assertEquals(false, state(MeanColor(254.0, 224.0, 28.0)), "gold")
        assertEquals(false, state(MeanColor(200.0, 200.0, 200.0)), "silver")
        assertEquals(false, state(MeanColor(150.0, 120.0, 100.0)), "bronze")
        assertEquals(false, state(MeanColor(60.0, 60.0, 60.0)), "dark gray")
        assertNull(state(strip(30.0, 0.0, 0.0)), "borderline")
        assertNull(state(strip(0.0, 0.0, -35.0)), "borderline")
        assertNull(state(null), "grayscale capture carries no colour")
        assertNull(state(strip(0.0, 0.0, 0.0), null))
        assertNull(FpSellReader.selectionState(null))
    }

    @Test fun `summary and tile log have one selected count and safety signals only`() {
        assertEquals(7, FpSellPolicy.Summary(7, 2).total)
        assertEquals("page=2 r1c4 present=true lock=Unlocked selected=false decision=select",
            FpSellPolicy.tileLine(2, 0, 3, Cell(true, Lock.Unlocked, false), "select"))
    }
    @Test fun `final summary is printed once across normal completion and outer cleanup`() {
        val run=SellRun()
        run.result()
        run.sale.logFinalSummary()
        run.sale.logFinalSummary()
        assertEquals(1,run.logs.count { it.startsWith("final-summary ") })
    }

    @Test fun `sale does not print final summary before script cleanup even across multiple cycles`() {
        val run=SellRun()
        repeat(2) { assertFailsWith<AutoFriendGacha.ExitException> { run.sale.run() } }
        assertEquals(0,run.logs.count { it.startsWith("final-summary ") })
        run.sale.logFinalSummary()
        run.sale.logFinalSummary()
        assertEquals(1,run.logs.count { it.startsWith("final-summary ") })
    }
    @Test fun `selection keeps immediate tile taps and exactly the existing two scroll waits`() {
        val run=SellRun()
        val second=Region(150,110,20,20)
        every { run.reader.readPage() } returns listOf(
            FpSellReader.PageTile(0,run.card,null),FpSellReader.PageTile(1,second,null))
        every { run.reader.readCard(second) } returnsMany listOf(run.unselected,run.unselected.copy(selected=true))
        var selecting=false
        every { run.reader.readPage() } answers {
            selecting=true
            listOf(FpSellReader.PageTile(0,run.card,null),FpSellReader.PageTile(1,second,null))
        }
        every { run.api.messages.log(any()) } answers {
            if (firstArg<ScriptLog>() is ScriptLog.FpSellSelection) selecting=false
        }
        val selectionWaits=mutableListOf<Duration>()
        for(delay in listOf(1.seconds,0.7.seconds)) {
            every { with(run.api) { delay.wait(any()) } } answers { if(selecting) selectionWaits.add(delay) }
        }
        assertEquals(2,run.result().summary.total)
        assertEquals(listOf(0.7.seconds,0.7.seconds),selectionWaits)
        verify(exactly=5) { with(run.api) { any<Duration>().wait(any()) } }
        verify(exactly=1) { with(run.api) { run.card.click(1);second.click(1) } }
        verify(exactly=2) { with(run.api) { 1.seconds.wait(any()) } }
    }

    @Test fun `servant tab and column switch apply one second settle and increased post tap wait`() {
        val run=SellRun()
        val loc=run.reader.loc
        val tab=Location(20,40)
        val size=Location(30,50)
        every { loc.servantTabClick } returns tab
        every { loc.sizeSwitch } returns size
        every { run.reader.onServantTab() } returns false
        every { run.reader.isSevenColumns() } returnsMany listOf(false,true)
        every { run.reader.onServantTab() } returnsMany listOf(false,true)
        run.result()
        verifyOrder { with(run.api) {
            1.seconds.wait();tab.click(1);1.seconds.wait()
            1.seconds.wait();size.click(1);1.seconds.wait()
        } }
    }
    @Test fun `stable blocked by real sale gate stops before any gesture and never falls back to dry run`() {
        val run = SellRun(dry = true)
        every { run.api.prefs.fpSellStableBuild } returns true
        every { run.reader.hasLockTemplate } returns false
        val reason = assertIs<AutoFriendGacha.ExitReason.SellEntryFailed>(run.failure().reason)
        assertFalse(reason.dryRun)
        verify(exactly = 0) { with(run.api) { any<Region>().click(any()); any<Location>().click(any()) } }
        verify(exactly = 0) { run.reader.readPage() }
    }

}
