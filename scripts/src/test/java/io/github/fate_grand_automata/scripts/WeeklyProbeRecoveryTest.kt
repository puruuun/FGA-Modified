package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.modules.*
import io.github.fate_grand_automata.scripts.weekly.*
import kotlin.test.*

class WeeklyProbeRecoveryTest {
    private val server = GameServer.Jp.Original
    private val name = "骸彷徨う教会"

    @Test fun `exact voted name matches without AP or glyph width`() {
        val panel = QuestPanel(true, null, null, null, name)
        assertEquals(PanelVerdict.Match, QuestPanelMatch.strictVerdict(panel, 5, name, server))
        assertEquals(PanelVerdict.Match, QuestPanelMatch.verdict(panel.copy(ap = 99), 5, name, server))
    }

    @Test fun `AP alone or matching glyph count never matches`() {
        for (text in listOf(null, "別のクエスト")) {
            val panel = QuestPanel(true, 5, QuestPanelMatch.nameCells(name), 128, text)
            assertIs<PanelVerdict.NameMismatch>(QuestPanelMatch.verdict(panel, 5, name, server))
            assertIs<PanelVerdict.NameMismatch>(QuestPanelMatch.strictVerdict(panel, 5, name, server))
        }
    }

    @Test fun `non free panel is rejected even with exact name`() {
        assertEquals(PanelVerdict.NotFree, QuestPanelMatch.verdict(QuestPanel(false, 5, null, null, name), 5, name, server))
    }

    @Test fun `panel recovery failures retain last verdict and never become map failures`() {
        val failure = WeeklyPanelFailure()
        assertEquals("spot out of reach", failure.reason("spot out of reach"))
        failure.panelOpened()
        failure.verdict("NameMismatch")
        for (recovery in listOf("spot out of reach", "map not recognized", "no panel at the spot")) {
            val reason = failure.reason(recovery)
            assertTrue(reason.startsWith("panel_mismatch last=NameMismatch"))
            assertEquals(ProbeStatus.Mismatch, ProbeStatus.classify(reason))
        }
    }

    @Test fun `X G is tappable on standard maps and wide portrait overlap stays blocked`() {
        for ((w, h) in listOf(1280 to 720, 1152 to 720, 1600 to 720, 2560 to 1440)) {
            val s = h / 720.0
            val wide = w.toDouble() / h > 2.0
            assertEquals(!wide, MapView.isTappable(243*s, 485*s, w, h))
            for ((x,y) in listOf(100.0 to 600.0, 300.0 to 600.0, 500.0 to 600.0, 100.0 to 50.0)) {
                assertFalse(MapView.isTappable(x*s, y*s, w, h))
            }
            assertFalse(MapView.isTappable(w-100*s, 650*s, w, h))
            assertFalse(MapView.isTappable(w-100*s, 50*s, w, h))
            assertFalse(MapView.isTappable(w-40*s, 300*s, w, h))
            assertTrue(MapView.isTappable(12*s, 360*s, w, h))
            assertFalse(MapView.isTappable(11*s, 360*s, w, h))
            assertEquals(wide, MapView.dragToward(243*s, 485*s, w, h) != null)
        }
    }

    @Test fun `a stalled scroll stops immediately and recenter can tap a safe current spot`() {
        val before = ScreenPoint(0.0, -1.0)
        val after = ScreenPoint(0.5, 1.5)
        assertEquals(MapView.ScrollResult.Stop, MapView.afterScroll(before, after, false, false))
        assertEquals(MapView.ScrollResult.Stop, MapView.afterScroll(before, after, true, false))
        assertEquals(MapView.ScrollResult.Tap, MapView.afterScroll(before, after, true, true))
        assertEquals(MapView.ScrollResult.Refit, MapView.afterScroll(before, ScreenPoint(0.0, 3.0), false, false))
    }
}
