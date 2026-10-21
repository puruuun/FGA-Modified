package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.modules.QuestPanel
import io.github.fate_grand_automata.scripts.modules.QuestPanelMatch
import io.github.fate_grand_automata.scripts.weekly.MapView
import io.github.lib_automata.OcrText
import kotlin.test.*

/** Design 0009 D-29 and D-33: the spot ribbon as second evidence, and the tap fallback at a fixed button. */
class WeeklySpotNameTest {
    private fun reads(vararg r: Pair<String, Int>) = r.map { OcrText(it.first, it.second) }
    private val chapter100 = listOf("未確認座標Ｘ－Ａ", "未確認座標Ｘ－Ｂ", "未確認座標Ｘ－Ｃ", "未確認座標Ｘ－Ｅ", "変動座標Ｘ－Ｄ")

    @Test fun `dash look-alikes and ribbon outline symbols do not matter`() {
        assertEquals("未確認座標X-A", QuestPanelMatch.normalizedSpot("未確認座標Ｘ－Ａ"))
        assertEquals("未確認座標X-A", QuestPanelMatch.normalizedSpot("_ 未確認 座標 XーA ,"))
        assertEquals(QuestPanelMatch.normalizedSpot("ヤガ・ディーヴァール"), QuestPanelMatch.normalizedSpot("ヤガ・ディ-ヴァ-ル"))
    }

    @Test fun `owner beta8 ribbon reads settle on the exact neighbour spot`() {
        // Frame 2: X-E read with a leading underscore at confidence 58..73; X-D and X-B are one edit away and must not win.
        val spot = QuestPanelMatch.canonicalSpot(
            reads("_ 未確認 座標 XーE" to 58, "_ 未確認 座標 XーE" to 73, "未確認 座標 XーE ," to 61, "_ 未確認 座標 XーE" to 68), chapter100)
        assertEquals("未確認座標X-E", spot)
        assertEquals("未確認座標X-A", QuestPanelMatch.canonicalSpot(
            reads("未確認 座標 XーA" to 65, "未確認 座標 XーA" to 63, "未確認 座標 XーA" to 90), chapter100))
    }

    @Test fun `a contradicting or single ribbon read is not a spot`() {
        assertNull(QuestPanelMatch.canonicalSpot(reads("未確認座標XーA" to 80), chapter100))
        assertNull(QuestPanelMatch.canonicalSpot(reads("未確認座標XーA" to 80, "未確認座標XーB" to 80), chapter100))
        assertNull(QuestPanelMatch.canonicalSpot(reads("未確認座標XーA" to 30, "未確認座標XーA" to 20), chapter100))
        assertNull(QuestPanelMatch.canonicalSpot(reads("とても違う名前" to 90, "とても違う名前" to 90), chapter100))
    }

    private fun panel(name: String?, spot: String?, cells: Double? = 3.0, free: Boolean = true) =
        QuestPanel(free, null, cells, null, name, spotText = spot)

    @Test fun `quest name stays primary and a different readable name is never overridden`() {
        assertEquals("quest-name", QuestPanelMatch.matchBy(panel("屋敷跡", null), "屋敷跡", "未確認座標Ｘ－Ａ", 1))
        // Right spot, right cells, but the panel clearly reads another quest: no match.
        assertNull(QuestPanelMatch.matchBy(panel("爆心地", "未確認座標X-A", 3.0), "屋敷跡", "未確認座標Ｘ－Ａ", 1))
    }

    @Test fun `an unreadable name matches only with spot, single free quest, emblem and cells`() {
        assertEquals("spot-name+cells", QuestPanelMatch.matchBy(panel(null, "未確認座標X-A"), "屋敷跡", "未確認座標Ｘ－Ａ", 1))
        // Negative cases: each missing piece alone refuses the match.
        assertNull(QuestPanelMatch.matchBy(panel(null, "未確認座標X-B"), "屋敷跡", "未確認座標Ｘ－Ａ", 1), "different spot")
        assertNull(QuestPanelMatch.matchBy(panel(null, null), "屋敷跡", "未確認座標Ｘ－Ａ", 1), "spot unread")
        assertNull(QuestPanelMatch.matchBy(panel(null, "未確認座標X-A"), "屋敷跡", "未確認座標Ｘ－Ａ", 2), "two free quests at the spot")
        assertNull(QuestPanelMatch.matchBy(panel(null, "未確認座標X-A"), "屋敷跡", "未確認座標Ｘ－Ａ", 0), "unknown free quest count")
        assertNull(QuestPanelMatch.matchBy(panel(null, "未確認座標X-A", free = false), "屋敷跡", "未確認座標Ｘ－Ａ", 1), "no Free Quest emblem")
        assertNull(QuestPanelMatch.matchBy(panel(null, "未確認座標X-A", cells = 9.0), "屋敷跡", "未確認座標Ｘ－Ａ", 1), "name far too wide")
        assertNull(QuestPanelMatch.matchBy(panel(null, "未確認座標X-A", cells = null), "屋敷跡", "未確認座標Ｘ－Ａ", 1), "no ink measured")
        assertNull(QuestPanelMatch.matchBy(panel(null, "未確認座標X-A"), "屋敷跡", "", 1), "no Atlas spot name")
    }

    // D-37: Ebihu mountain (93000713 and 93000715) and Amakusa style two-quest spots.
    private val ebihu = listOf("震える霊峰", "大江山盗賊団")

    @Test fun `spot scoped vote reads the beta9 failure of 93000713 from its own two candidates`() {
        // Real beta.9 reads: only one is at confidence 45 or more, so the chapter-wide vote found nothing.
        val raw = reads("因 える 霊峰" to 43, "岩 える 寺 風" to 40, "岩 える 圭 峰" to 43, "震え る 寺 峰" to 45)
        assertEquals("震える霊峰", QuestPanelMatch.canonicalSpotQuestName(raw, ebihu))
        val panel = QuestPanel(true, 21, 4.97, null, "震える霊峰", nameBySpot = true)
        assertEquals("quest-name-spot", QuestPanelMatch.matchBy(panel, "震える霊峰", "エビフ山", 2))
        assertNull(QuestPanelMatch.matchBy(panel, "大江山盗賊団", "エビフ山", 2), "the sibling target must not match")
    }

    @Test fun `spot scoped vote needs a two edit margin and half length distance`() {
        val pair = listOf("静かな入り江", "名もなき霊峰")
        // The upper panel of a two quest spot shows the sibling: it must resolve to the sibling and never to the target.
        assertEquals("静かな入り江", QuestPanelMatch.canonicalSpotQuestName(reads("静かな入り江" to 80, "静かな入り江" to 70), pair))
        assertEquals("静かな入り江", QuestPanelMatch.canonicalSpotQuestName(reads("静か入り江" to 60, "静かな入り汀" to 55), pair))
        // Equally far from both candidates: no margin, no vote.
        assertNull(QuestPanelMatch.canonicalSpotQuestName(reads("静かな霊峰" to 80, "静かな霊峰" to 80), pair))
        // More than half of the name is wrong.
        assertNull(QuestPanelMatch.canonicalSpotQuestName(reads("静口口口口口" to 80, "静口口口口口" to 80), pair))
        // One read, a contradicting read, or reads below the spot confidence floor are no vote.
        assertNull(QuestPanelMatch.canonicalSpotQuestName(reads("静かな入り江" to 80), pair))
        assertNull(QuestPanelMatch.canonicalSpotQuestName(reads("静かな入り江" to 80, "名もなき霊峰" to 80), pair))
        assertNull(QuestPanelMatch.canonicalSpotQuestName(reads("静かな入り江" to 30, "静かな入り江" to 20), pair))
        assertNull(QuestPanelMatch.canonicalSpotQuestName(reads("静かな入り江" to 80, "静かな入り江" to 80), listOf("静かな入り江")), "one candidate is the chapter-wide rule")
    }

    @Test fun `spot scope applies only to spots with two or more distinct free quests`() {
        assertEquals(emptyList(), QuestPanelMatch.spotScoped(emptyList()))
        assertEquals(emptyList(), QuestPanelMatch.spotScoped(listOf("豊穣祈願")))
        assertEquals(emptyList(), QuestPanelMatch.spotScoped(listOf("豊穣祈願", "豊穣祈願")))
        assertEquals(listOf("A", "B"), QuestPanelMatch.spotScoped(listOf("A", "B ")))
    }

    @Test fun `a spot icon just inside the gift box zone is tapped on its visible part`() {
        // beta.8 Okeanos 93000311: spot (467,566), gift box zone starts at y > 560.
        assertFalse(MapView.isTappable(467.0, 566.0, 1280, 720))
        val point = assertNotNull(MapView.visibleTap(467.0, 566.0, 1280, 720))
        assertTrue(point.y < 560 && 566 - point.y <= MapView.MAX_SHIFT)
        assertTrue(MapView.isTappable(point.x, point.y, 1280, 720))
        assertEquals(467.0, point.x)
    }

    @Test fun `the tap fallback gives up beyond thirty pixels and never moves a tappable spot`() {
        assertNull(MapView.visibleTap(467.0, 640.0, 1280, 720), "icon centre 80 px inside the zone")
        assertNull(MapView.visibleTap(640.0, 360.0, 1280, 720), "already tappable")
        assertNull(MapView.visibleTap(Double.NaN, 100.0, 1280, 720))
    }
}
