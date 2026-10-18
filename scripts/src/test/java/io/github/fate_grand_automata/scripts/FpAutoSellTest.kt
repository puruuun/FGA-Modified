package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.AutoFriendGacha
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.locations.FpSellLocations
import io.github.fate_grand_automata.scripts.modules.*
import io.github.fate_grand_automata.scripts.modules.FpSellPolicy.Category
import io.github.fate_grand_automata.scripts.modules.FpSellPolicy.Cell
import io.github.fate_grand_automata.scripts.modules.FpSellPolicy.Kind
import io.github.fate_grand_automata.scripts.modules.FpSellPolicy.Lock
import io.github.fate_grand_automata.scripts.modules.FpSellPolicy.Options
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.lib_automata.*
import io.mockk.*
import kotlin.test.*

class FpAutoSellTest {
    private val allCategories = Category.entries.toSet()

    /** D-2, D-10, D-12 as one table: every combination is decided by the same rule written out here. */
    @Test fun `tile policy never selects excluded or locked tiles and only dry run takes unknown locks`() {
        for (kind in Kind.entries) for (stars in 1..5) for (new in listOf(true, false, null))
            for (lock in Lock.entries) for (selected in listOf(true, false, null)) for (excluded in listOf(true, false))
                for (dry in listOf(true, false)) for (newOnly in listOf(true, false)) {
                    val cell = Cell(kind, stars, new, lock, selected, excluded)
                    val category = Cell(kind, stars, new, lock, selected).category
                    val expected = !excluded && lock != Lock.Locked && category != null && selected == false && new != null &&
                        (!newOnly || new) && (lock == Lock.Unlocked || dry)
                    assertEquals(expected, FpSellPolicy.select(cell, Options(allCategories, newOnly, dry)), cell.toString())
                }
    }

    @Test fun `disabled categories and unknown kind or stars are never selected`() {
        assertFalse(FpSellPolicy.select(Cell(Kind.Servant, 1, true, Lock.Unlocked, false), Options(emptySet(), true, true)))
        assertFalse(FpSellPolicy.select(Cell(null, 1, true, Lock.Unlocked, false), Options(allCategories, true, true)))
        assertFalse(FpSellPolicy.select(Cell(Kind.Servant, null, true, Lock.Unlocked, false), Options(allCategories, true, true)))
        // 5-star servants are never a category, even when every switch is on.
        assertFalse(FpSellPolicy.select(Cell(Kind.Servant, 5, true, Lock.Unlocked, false), Options(allCategories, true, false)))
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
        every { prefs.fpSellServant1 } returns true
        every { prefs.fpSellServant2 } returns false
        every { prefs.fpSellServant3 } returns false
        every { prefs.fpSellServant4 } returns false
        every { prefs.fpSellExp4 } returns false
        every { prefs.fpSellExp5 } returns false
        every { prefs.fpSellNewOnly } returns true
        every { prefs.gameServer } returns GameServer.Kr
        every { prefs.fpSellDryRun } returns FpSellPolicy.DEFAULT_DRY_RUN
        assertTrue(FpSellPolicy.options(prefs, hasLockTemplate = true).dryRun, "default keeps dry run")
        every { prefs.fpSellDryRun } returns false
        assertFalse(FpSellPolicy.options(prefs, hasLockTemplate = true).dryRun, "explicit opt-out allows real sale")
    }

    @Test fun `dry run is forced whenever real sale is not allowed`() {
        val prefs = mockk<IPreferences>()
        every { prefs.fpSellServant1 } returns true
        every { prefs.fpSellServant2 } returns false
        every { prefs.fpSellServant3 } returns false
        every { prefs.fpSellServant4 } returns false
        every { prefs.fpSellExp4 } returns false
        every { prefs.fpSellExp5 } returns false
        every { prefs.fpSellNewOnly } returns true
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

    @Test fun `banner ordinal difference chooses direction and count and rejects incompatible dots`() {
        for (from in 0..16) for (to in 0..16)
            assertEquals(to - from, FpBannerReturn.steps(FpBannerReturn.Dots(from, 17), FpBannerReturn.Dots(to, 17)))
        assertNull(FpBannerReturn.steps(FpBannerReturn.Dots(0, 16), FpBannerReturn.Dots(14, 17)))
        assertNull(FpBannerReturn.steps(FpBannerReturn.Dots(-1, 17), FpBannerReturn.Dots(14, 17)))
    }

    @Test fun `two dot rows are numbered row by row`() {
        // KR style: one row of 17 dots. JP style: rows of 30 and 12 dots.
        assertEquals(FpBannerReturn.Dots(5, 17), FpBannerReturn.rowMajor(listOf(17), 0, 5))
        assertEquals(FpBannerReturn.Dots(30, 42), FpBannerReturn.rowMajor(listOf(30, 12), 1, 0))
        assertEquals(FpBannerReturn.Dots(41, 42), FpBannerReturn.rowMajor(listOf(30, 12), 1, 11))
        assertEquals(FpBannerReturn.Dots(0, 42), FpBannerReturn.rowMajor(listOf(30, 12), 0, 0))
        assertNull(FpBannerReturn.rowMajor(listOf(30, 12), 1, 12))
        assertNull(FpBannerReturn.rowMajor(listOf(10, 10, 10), 0, 0))
        assertNull(FpBannerReturn.rowMajor(emptyList(), 0, 0))
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
    private class SellRun(val selections: List<Boolean?>, val sellFound: Boolean = true, val sevenColumns: Boolean = true) {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val card = Region(110, 110, 20, 20)
        val sell = Region(10, 10, 20, 20)
        val reader = mockk<FpSellReader>()
        val unselected = Cell(Kind.Servant, 1, true, Lock.Unknown, false)

        init {
            val prefs = mockk<IPreferences>(relaxed = true)
            every { api.prefs } returns prefs
            every { prefs.gameServer } returns GameServer.Kr
            every { prefs.fpSellServant1 } returns true
            every { prefs.fpSellNewOnly } returns true
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
            every { reader.tier(card) } returns FpSellPolicy.Tier.Bronze
            every { reader.onSellHeading() } returns true
            every { reader.onServantTab() } returns true
            every { reader.onSellScreen() } returns true
            every { reader.isSevenColumns() } returns sevenColumns
            every { reader.readPage() } returns listOf(FpSellReader.PageTile(0, card, null, unselected, emptyList(), 0.0))
            // The second page shows the same tile, now selected, so it must not be tapped or counted again.
            every { reader.confirmed(card) } returnsMany listOf(unselected, unselected.copy(selected = true))
            if (selections.isNotEmpty()) every { reader.selection(card) } returnsMany selections
        }

        fun run(): AutoFriendGacha.ExitException =
            assertFailsWith<AutoFriendGacha.ExitException> { FpAutoSell(api, reader, mockk(relaxed = true)).run() }
    }

    @Test fun `dry run selects an unknown lock, counts it, and stops before confirm`() {
        val run = SellRun(listOf(true))
        val error = run.run()
        val summary = assertIs<AutoFriendGacha.ExitReason.SellDryRunDone>(error.reason).summary
        assertEquals(1, summary.total)
        assertEquals(1, summary.unknownLock)
        assertEquals(mapOf(Category.Servant1 to 1), summary.counts)
        verify(exactly = 1) { with(run.api) { run.card.click(1) } }
        val imageLoader = run.api.images
        verify(exactly = 0) { imageLoader[Images.FpSellConfirm, any()] }
    }

    @Test fun `a selection confirmed on a later fresh frame counts once and never taps the tile again`() {
        val run = SellRun(listOf(null, false, null, true))
        val error = run.run()
        assertEquals(1, assertIs<AutoFriendGacha.ExitReason.SellDryRunDone>(error.reason).summary.total)
        verify(exactly = 1) { with(run.api) { run.card.click(1) } }
        verify(exactly = 4) { run.reader.selection(run.card) }
    }

    @Test fun `a selection never confirmed stops after about 1_5 s of fresh frames without a second tap`() {
        val run = SellRun(List(FpAutoSell.SELECTION_POLLS) { if (it % 2 == 0) null else false })
        val reason = assertIs<AutoFriendGacha.ExitReason.SelectionNotConfirmed>(run.run().reason)
        assertTrue(reason.dryRun)
        assertEquals(0, reason.selected)
        assertEquals(1.5, FpAutoSell.SELECTION_POLLS * FpAutoSell.SELECTION_POLL.inWholeMilliseconds / 1000.0, 0.01)
        verify(exactly = 1) { with(run.api) { run.card.click(any()) } }
        verify(exactly = FpAutoSell.SELECTION_POLLS) { run.reader.selection(run.card) }
    }

    @Test fun `a missing sell button stops as an entry failure before any tile is touched`() {
        val run = SellRun(emptyList(), sellFound = false)
        val reason = assertIs<AutoFriendGacha.ExitReason.SellEntryFailed>(run.run().reason)
        assertTrue(reason.dryRun)
        assertEquals(0, reason.selected)
        verify(exactly = 0) { with(run.api) { run.card.click(any()) } }
    }

    @Test fun `failing to reach seven columns stops as a column switch failure`() {
        val run = SellRun(emptyList(), sevenColumns = false)
        assertIs<AutoFriendGacha.ExitReason.ColumnSwitchFailed>(run.run().reason)
        verify(exactly = 0) { with(run.api) { run.card.click(any()) } }
    }

    @Test fun `each sell step maps to its own stop reason carrying dry run and selected count`() {
        val expected = mapOf(
            AutoFriendGacha.SellStep.Entry to AutoFriendGacha.ExitReason.SellEntryFailed::class,
            AutoFriendGacha.SellStep.Columns to AutoFriendGacha.ExitReason.ColumnSwitchFailed::class,
            AutoFriendGacha.SellStep.Selection to AutoFriendGacha.ExitReason.SelectionNotConfirmed::class,
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

    /** Frame 46 (JP, scrolled): the strip templates read 0.0 there, the per-slot star count reads one star. */
    @Test fun `star slots count right-aligned stars and leave borderline rows unknown`() {
        assertEquals(1, FpSellPolicy.starsFromSlots(listOf(0.83, 0.40, 0.0, 0.0, 0.0)))
        assertEquals(1, FpSellPolicy.starsFromSlots(listOf(0.60, 0.26, 0.05, 0.18, 0.09)))
        assertEquals(2, FpSellPolicy.starsFromSlots(listOf(0.71, 0.64, 0.12, 0.34, 0.0)))
        assertEquals(3, FpSellPolicy.starsFromSlots(listOf(0.85, 0.80, 0.68, 0.34, 0.41)))
        assertEquals(4, FpSellPolicy.starsFromSlots(listOf(0.83, 0.75, 0.69, 0.77, 0.41)))
        // The slot after the last star must be clearly empty; a borderline one is not a category.
        assertNull(FpSellPolicy.starsFromSlots(listOf(0.83, 0.46, 0.0, 0.0, 0.0)))
        // No star at all, five stars, a gap followed by a star, or a missing slot: unknown, never a guess.
        assertNull(FpSellPolicy.starsFromSlots(listOf(0.42, 0.33, 0.0, 0.0, 0.36)))
        assertNull(FpSellPolicy.starsFromSlots(listOf(0.9, 0.9, 0.9, 0.9, 0.9)))
        assertNull(FpSellPolicy.starsFromSlots(listOf(0.9, 0.1, 0.9, 0.0, 0.0)))
        assertNull(FpSellPolicy.starsFromSlots(listOf(0.9, 0.1)))
    }

    @Test fun `decision names the first reason and is select exactly when the policy selects`() {
        val owner = Options(setOf(Category.Servant1, Category.Servant2, Category.Servant3, Category.Servant4), true, true)
        val tile = Cell(Kind.Servant, 1, true, Lock.Unlocked, false)
        assertEquals("select", FpSellPolicy.decision(tile, owner))
        assertEquals("skip:not-new", FpSellPolicy.decision(tile.copy(new = false), owner))
        assertEquals("skip:new-unknown", FpSellPolicy.decision(tile.copy(new = null), owner))
        assertEquals("skip:already-selected", FpSellPolicy.decision(tile.copy(selected = true), owner))
        assertEquals("skip:selection-unknown", FpSellPolicy.decision(tile.copy(selected = null), owner))
        assertEquals("skip:stars-unknown", FpSellPolicy.decision(tile.copy(stars = null), owner))
        assertEquals("skip:kind-unknown", FpSellPolicy.decision(tile.copy(kind = null), owner))
        assertEquals("skip:category-off", FpSellPolicy.decision(tile.copy(kind = Kind.Exp, stars = 4), owner))
        assertEquals("skip:excluded", FpSellPolicy.decision(tile.copy(excluded = true), owner))
        assertEquals("skip:locked", FpSellPolicy.decision(tile.copy(lock = Lock.Locked), owner))
        assertEquals("skip:lock-unknown", FpSellPolicy.decision(tile.copy(lock = Lock.Unknown), owner.copy(dryRun = false)))
        for (kind in listOf(Kind.Servant, Kind.Exp, null)) for (stars in listOf(1, 4, null)) for (new in listOf(true, false, null))
            for (lock in Lock.entries) for (selected in listOf(true, false, null)) for (excluded in listOf(true, false))
                for (dry in listOf(true, false)) for (newOnly in listOf(true, false)) {
                    val cell = Cell(kind, stars, new, lock, selected, excluded)
                    val options = Options(allCategories, newOnly, dry)
                    assertEquals(FpSellPolicy.select(cell, options), FpSellPolicy.decision(cell, options) == "select", cell.toString())
                }
    }

    @Test fun `tile log line carries page, position, reads, scores and the decision`() {
        val line = FpSellPolicy.tileLine(2, 0, 3, Cell(Kind.Servant, 1, true, Lock.Unlocked, false), null,
            listOf(1 to 0.0, 2 to 0.0), listOf(0.83, 0.4, 0.0, 0.0, 0.0), 0.932, "select")
        assertEquals("page=2 r1c4 kind=Servant stars=1(strip 1:0.0/2:0.0 slots 0.83,0.4,0.0,0.0,0.0) " +
            "new=true(0.93) lock=Unlocked excluded=false selected=false decision=select", line)
        assertEquals("page=0 r1c1 decision=skip:gridTop",
            FpSellPolicy.tileLine(0, 0, 0, null, "gridTop", emptyList(), emptyList(), 0.0, "skip:gridTop"))
    }

    @Test fun `new tag bands accept the weakest observed real tag and keep a gap above the search floor`() {
        assertTrue(0.858 >= FpSellReader.NEW_PRESENT)
        assertTrue(FpSellReader.NEW_ABSENT > 0.75)
        assertTrue(FpSellReader.NEW_ABSENT < FpSellReader.NEW_PRESENT)
    }
}
