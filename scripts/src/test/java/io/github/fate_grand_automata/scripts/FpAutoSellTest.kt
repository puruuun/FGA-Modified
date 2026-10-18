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

    @Test fun `dry run selects an unknown lock, counts it, and stops before confirm`() {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val prefs = mockk<IPreferences>(relaxed = true)
        every { api.prefs } returns prefs
        every { prefs.gameServer } returns GameServer.Kr
        every { prefs.fpSellServant1 } returns true
        every { prefs.fpSellNewOnly } returns true
        every { prefs.fpSellDryRun } returns true
        every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
        val loc = mockk<FpSellLocations>(relaxed = true)
        val sell = Region(10, 10, 20, 20)
        val grid = Region(100, 100, 100, 100)
        val card = Region(110, 110, 20, 20)
        every { loc.fullSell } returns sell
        every { loc.grid } returns grid
        val template = mockk<Pattern>()
        val before = mockk<Pattern>(relaxed = true)
        every { api.images[Images.FpSellSell, any()] } returns template
        every { with(api) { sell.find(template, any()) } } returns Match(sell, 1.0)
        every { with(api) { grid.getPattern(any()) } } returns before
        every { with(api) { grid.exists(before, any(), any()) } } returns true
        val reader = mockk<FpSellReader>()
        every { reader.loc } returns loc
        every { reader.hasLockTemplate } returns false
        every { reader.onSellHeading() } returns true
        every { reader.onServantTab() } returns true
        every { reader.onSellScreen() } returns true
        every { reader.isSevenColumns() } returns true
        val unselected = Cell(Kind.Servant, 1, true, Lock.Unknown, false)
        every { reader.read() } returns listOf(FpSellReader.ReadCell(card, unselected))
        every { reader.confirmed(card) } returnsMany listOf(unselected, unselected.copy(selected = true))
        every { reader.readCard(card) } returns unselected.copy(selected = true)
        val error = assertFailsWith<AutoFriendGacha.ExitException> { FpAutoSell(api, reader, mockk(relaxed = true)).run() }
        val summary = assertIs<AutoFriendGacha.ExitReason.SellDryRunDone>(error.reason).summary
        assertEquals(1, summary.total)
        assertEquals(1, summary.unknownLock)
        assertEquals(mapOf(Category.Servant1 to 1), summary.counts)
        verify(exactly = 1) { with(api) { card.click(1) } }
        val imageLoader = api.images
        verify(exactly = 0) { imageLoader[Images.FpSellConfirm, any()] }
    }
}
