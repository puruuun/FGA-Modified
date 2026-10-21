package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.GameServers
import io.github.fate_grand_automata.scripts.locations.*
import io.github.fate_grand_automata.scripts.modules.SkillLevelMode
import io.github.fate_grand_automata.scripts.weekly.MapView
import io.github.lib_automata.Location
import io.github.lib_automata.Region
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Design 0012. 16:9 values are the centred coordinates the fork used before widescreen support, so the refactor to
 * edge-anchored values must not move anything. The 21:9 values are measured on the owner's 21:9 JP captures
 * (docs/design/0012-widescreen-audit.md, 1440p, script area 3360x1440).
 */
class WideScreenLocationsTest {
    private fun transforms(width: Int): IScriptAreaTransforms = object : IScriptAreaTransforms {
        override val scriptArea = Region(0, 0, width, 1440)
        override val isWide = scriptArea.size.isWide()
        override val gameServer: GameServer = GameServers.default
        override val canLongSwipe = false
        override fun Location.xFromCenter() = this + Location(scriptArea.center.x, 0)
        override fun Region.xFromCenter() = this + Location(scriptArea.center.x, 0)
        override fun Location.xFromRight() = this + Location(scriptArea.right, 0)
        override fun Region.xFromRight() = this + Location(scriptArea.right, 0)
        override fun Location.yFromBottom() = this + Location(0, scriptArea.bottom)
        override fun Region.yFromBottom() = this + Location(0, scriptArea.bottom)
    }

    private val sixteenNine = transforms(2560)
    private val twentyOneNine = transforms(3360)

    @Test fun `the safe area inset exists only on wide screens`() {
        assertEquals(0, sixteenNine.edgeInset)
        assertEquals(WIDE_EDGE_INSET, twentyOneNine.edgeInset)
        with(twentyOneNine) {
            assertEquals(Location(WIDE_EDGE_INSET + 10, 20), Location(10, 20).xFromLeftEdge())
            assertEquals(Location(3360 - WIDE_EDGE_INSET - 10, 20), Location(-10, 20).xFromRightEdge())
        }
        with(sixteenNine) {
            assertEquals(Location(10, 20), Location(10, 20).xFromLeftEdge())
            assertEquals(Location(2550, 20), Location(-10, 20).xFromRightEdge())
        }
    }

    @Test fun `FP sell keeps its 16 9 coordinates`() {
        val loc = FpSellLocations(sixteenNine)
        assertEquals(Region(0, 0, 380, 180), loc.screenClose)
        assertEquals(Location(160, 80), loc.close)
        assertEquals(Region(30, 185, 340, 140), loc.servantTab)
        assertEquals(Location(160, 254), loc.servantTabClick)
        assertEquals(Location(100, 1320), loc.sizeSwitch)
        assertEquals(Region(2040, 0, 500, 160), loc.heading)
        assertEquals(Region(1830, 0, 710, 160), loc.shop)
        assertEquals(Region(2170, 1210, 380, 200), loc.menu)
        assertEquals(Region(2100, 1240, 440, 180), loc.confirm)
        assertEquals(Region(150, 338, 1850, 1102), loc.grid)
        assertEquals(156, loc.columnX(0))
        assertEquals(156 + 1599, loc.columnX(6))
    }

    @Test fun `FP sell follows the measured 21 9 layout`() {
        val loc = FpSellLocations(twentyOneNine)
        // Edge UI: owner capture 14, 閉じる button at x 105-258, tab at 127, title at 1354, 決定 at 1439, MENU at 1456 (720p).
        assertEquals(Region(171, 0, 380, 180), loc.screenClose)
        assertEquals(Location(331, 80), loc.close)
        assertEquals(Region(201, 185, 340, 140), loc.servantTab)
        assertEquals(Location(271, 1268), loc.sizeSwitch)
        assertEquals(Region(2669, 0, 500, 160), loc.heading)
        assertEquals(Region(2459, 0, 710, 160), loc.shop)
        assertEquals(Region(2799, 1210, 380, 200), loc.menu)
        assertEquals(Region(2729, 1240, 440, 180), loc.confirm)
        // The grid stays centred between the two edge columns, 5 px (720p) to the right of the screen centre.
        assertEquals(Region(560, 338, 1850, 1102), loc.grid)
        assertEquals(566, loc.columnX(0))
        assertEquals(566 + 1599, loc.columnX(6))
    }

    @Test fun `skill level screen keeps 16 9 and moves the measured 21 9 elements`() {
        val old = SkillLevelLocations(sixteenNine)
        assertEquals(Region(1360, 0, 1200, 150), old.title)
        assertEquals(Region(340, 0, 340, 180), old.reselect)
        assertEquals(Region(780, 196, 520, 104), old.bandWords)
        assertEquals(Region(790, 216, 740, 64), old.bandColor)
        assertEquals(Region(2060, 1260, 500, 180), old.buttonLabel)
        assertEquals(Location(-334, 522) + Location(1280, 0), old.slotClick(SkillLevelMode.Skill, 0))
        assertEquals(Region(2 * (473 - 40 - 640) + 1280, 410, 160, 224), old.slotLock(SkillLevelMode.Skill, 0))

        val wide = SkillLevelLocations(twentyOneNine)
        // Owner capture 12: title 1019, re-select 279, band text 14 px up, slot icons 22 px up, button 342 px right / 30 px up.
        assertEquals(Region(1989, 0, 1200, 150), wide.title)
        assertEquals(Region(511, 0, 340, 180), wide.reselect)
        assertEquals(Region(1180, 168, 520, 104), wide.bandWords)
        assertEquals(Region(1190, 188, 740, 64), wide.bandColor)
        assertEquals(Region(2745, 1200, 500, 180), wide.buttonLabel)
        assertEquals(Location(1680 + (473 - 640) * 2, 478), wide.slotClick(SkillLevelMode.Skill, 0))
        // The append screen has no 21:9 capture: its heights stay as measured on 16:9.
        assertEquals(522, wide.slotClick(SkillLevelMode.Append, 0).y)
        assertEquals(410, wide.slotLock(SkillLevelMode.Append, 0).y)
    }

    @Test fun `quest item party heading and weekly header buttons follow the right and left inset`() {
        assertEquals(Region(1760, 0, 800, 170), QuestItemLocations(sixteenNine).partyHeading)
        assertEquals(Region(2389, 0, 800, 170), QuestItemLocations(twentyOneNine).partyHeading)
        assertEquals(Region(10, 30, 330, 140), WeeklyRouteLocations(sixteenNine).topLeftButton)
        assertEquals(Region(181, 30, 330, 140), WeeklyRouteLocations(twentyOneNine).topLeftButton)
        assertEquals(Region(1230, 250, 330, 1100), WeeklyRouteLocations(sixteenNine).panelColumn)
        assertEquals(Region(1859, 250, 330, 1100), WeeklyRouteLocations(twentyOneNine).panelColumn)
    }

    @Test fun `quest panel moves in by the inset on wide screens`() {
        val normal = QuestPanelLocations(sixteenNine)
        val wide = QuestPanelLocations(twentyOneNine)
        assertEquals(Region(1230, 270, 330, 220), normal.freeLabel)
        assertEquals(Region(1859, 270, 330, 220), wide.freeLabel)
        // Right edge of the panel is the same distance from the right edge plus the inset.
        assertEquals(wide.apDigits.x - normal.apDigits.x, 3360 - 2560 - WIDE_EDGE_INSET)
        assertEquals(wide.name.x - normal.name.x, 3360 - 2560 - WIDE_EDGE_INSET)
    }

    @Test fun `map no-tap zones grow by the inset on the wide side only`() {
        // 16:9: unchanged zones.
        assertTrue(MapView.isTappable(240.0, 40.0, 1280, 720))
        assertFalse(MapView.isTappable(100.0, 40.0, 1280, 720))
        // 21:9 (1680x720): the terminal button spans x 105-253 on capture 03, the portrait and MENU sit further in.
        assertFalse(MapView.isTappable(240.0, 40.0, 1680, 720))
        assertTrue(MapView.isTappable(300.0, 40.0, 1680, 720))
        assertFalse(MapView.isTappable(280.0, 600.0, 1680, 720))
        assertFalse(MapView.isTappable(1365.0, 650.0, 1680, 720))
        assertTrue(MapView.isTappable(1330.0, 560.0, 1680, 720))
        val (from, to) = MapView.dragPath(io.github.fate_grand_automata.scripts.weekly.ScreenPoint(2000.0, 0.0), 1680, 720)
        assertTrue(from.x >= 372 + WIDE_EDGE_INSET / 2.0 - 0.1 && to.x <= 1680 - 126 - WIDE_EDGE_INSET / 2.0 + 0.1)
    }
}
