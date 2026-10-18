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

class FpAutoSellTest {
    @Test fun `every tile combination follows D29 with only dry run admitting an unknown lock`() {
        for (present in listOf(true, false)) for (lock in Lock.entries)
            for (selected in listOf(true, false, null)) for (excluded in listOf(true, false))
                for (dry in listOf(true, false)) {
                    val cell = Cell(present, lock, selected, excluded)
                    val expected = present && lock != Lock.Locked && !excluded && selected == false &&
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
        val prefs = mockk<IPreferences>()
        every { prefs.gameServer } returns GameServer.Kr
        every { prefs.fpSellDryRun } returns FpSellPolicy.DEFAULT_DRY_RUN
        assertTrue(FpSellPolicy.options(prefs, hasLockTemplate = true).dryRun, "default keeps dry run")
        every { prefs.fpSellDryRun } returns false
        assertFalse(FpSellPolicy.options(prefs, hasLockTemplate = true).dryRun, "explicit opt-out allows real sale")
    }

    @Test fun `dry run is forced whenever real sale is not allowed`() {
        val prefs = mockk<IPreferences>()
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
        val sale = mockk<FpAutoSell>()
        every { sale.run() } returns 0
        val banner = mockk<FpBannerReturn>(relaxed = true)
        val reader = mockk<FpSellReader>(relaxed = true)
        every { reader.servantFullWarning() } returns true
        val error = assertFailsWith<AutoFriendGacha.ExitException> {
            AutoFriendGacha(mockk(), api, sale, banner, reader).script()
        }
        assertSame(AutoFriendGacha.ExitReason.InventoryFullNothingToSell, error.reason)
        verify(exactly = 1) { sale.run(); banner.restore() }
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
        val sale = mockk<FpAutoSell>()
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
        val sale = mockk<FpAutoSell>()
        val banner = mockk<FpBannerReturn>(relaxed = true)
        val reader = mockk<FpSellReader>(relaxed = true)
        val error = assertFailsWith<AutoFriendGacha.ExitException> {
            AutoFriendGacha(mockk(), api, sale, banner, reader).script()
        }
        assertSame(AutoFriendGacha.ExitReason.InventoryFull, error.reason)
        verify { sale wasNot Called }
        verify(exactly = 0) { banner.remember(); banner.restore() }
    }

    /** One sell run on mocks: a single unlocked 1-star candidate and a reader whose selection reads are scripted. */
    private class SellRun(val sellFound: Boolean = true, val sevenColumns: Boolean = true) {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val card = Region(110, 110, 20, 20)
        val sell = Region(10, 10, 20, 20)
        val reader = mockk<FpSellReader>()
        val unselected = Cell(true, Lock.Unknown, false)

        init {
            val prefs = mockk<IPreferences>(relaxed = true)
            every { api.prefs } returns prefs
            every { prefs.gameServer } returns GameServer.Kr
            every { prefs.fpSellDryRun } returns true
            every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
            val loc = mockk<FpSellLocations>(relaxed = true)
            val grid = Region(100, 100, 100, 100)
            every { loc.fullSell } returns sell
            every { loc.grid } returns grid
            val template = mockk<Pattern>()
            val before = mockk<Pattern>(relaxed = true)
            every { api.images[Images.FpSellSell, any()] } returns template
            every { with(api) { sell.find(template, any()) } } returns if (sellFound) Match(sell, 1.0) else null
            every { with(api) { grid.getPattern(any()) } } returns before
            every { with(api) { grid.exists(before, any(), any()) } } returns true
            every { reader.loc } returns loc
            every { reader.hasLockTemplate } returns false
            every { reader.onSellHeading() } returns true
            every { reader.onServantTab() } returns true
            every { reader.onSellScreen() } returns true
            every { reader.isSevenColumns() } returns sevenColumns
            every { reader.readPage() } returns listOf(FpSellReader.PageTile(0, card, null))
            // The second page shows the same tile, now selected, so it must not be tapped or counted again.
            every { reader.readCard(card) } returnsMany listOf(unselected, unselected.copy(selected = true))
        }

        fun run(): AutoFriendGacha.ExitException =
            assertFailsWith<AutoFriendGacha.ExitException> { FpAutoSell(api, reader, mockk(relaxed = true)).run() }
    }

    @Test fun `dry run taps once without confirmation and overlap skips selected tiles`() {
        val run = SellRun()
        val summary = assertIs<AutoFriendGacha.ExitReason.SellDryRunDone>(run.run().reason).summary
        assertEquals(FpSellPolicy.Summary(1, 1), summary)
        verify(exactly = 1) { with(run.api) { run.card.click(1) } }
        verify(exactly = 0) { run.reader.selection(any()) }
        val imageLoader = run.api.images
        verify(exactly = 0) { imageLoader[Images.FpSellConfirm, any()] }
    }

    @Test fun `unknown selection never taps`() {
        val run = SellRun()
        every { run.reader.readCard(run.card) } returns run.unselected.copy(selected = null)
        assertEquals(0, assertIs<AutoFriendGacha.ExitReason.SellDryRunDone>(run.run().reason).summary.total)
        verify(exactly = 0) { with(run.api) { run.card.click(any()) } }
    }

    @Test fun `missing entry and columns keep distinct stop messages`() {
        assertIs<AutoFriendGacha.ExitReason.SellEntryFailed>(SellRun(sellFound = false).run().reason)
        assertIs<AutoFriendGacha.ExitReason.ColumnSwitchFailed>(SellRun(sevenColumns = false).run().reason)
    }

    @Test fun `no rows retries once then stops with the grid reason`() {
        val run = SellRun()
        every { run.reader.readPage() } returns emptyList()
        assertIs<AutoFriendGacha.ExitReason.SelectionScreenLost>(run.run().reason)
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

    @Test fun `banner checks in place and after each of at most ten left presses`() {
        for (foundAfter in 0..10) {
            var presses = 0
            var reads = 0
            assertTrue(FpBannerReturn.search({ reads++; presses == foundAfter }, { presses++ }))
            assertEquals(foundAfter, presses)
            assertEquals(foundAfter + 1, reads)
        }
        var presses = 0
        var reads = 0
        assertFalse(FpBannerReturn.search({ reads++; false }, { presses++ }))
        assertEquals(10, presses)
        assertEquals(11, reads)
    }

    @Test fun `summary and tile log have one selected count and safety signals only`() {
        assertEquals(7, FpSellPolicy.Summary(7, 2).total)
        assertEquals("page=2 r1c4 present=true lock=Unlocked excluded=false selected=false decision=select",
            FpSellPolicy.tileLine(2, 0, 3, Cell(true, Lock.Unlocked, false), "select"))
    }
}
