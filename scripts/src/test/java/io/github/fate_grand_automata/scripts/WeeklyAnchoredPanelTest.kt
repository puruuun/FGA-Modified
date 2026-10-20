package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.locations.QuestPanelAnchor
import io.github.fate_grand_automata.scripts.weekly.*
import io.github.lib_automata.Location
import io.github.lib_automata.Region
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class WeeklyAnchoredPanelTest {
    @Test fun `original and shifted emblems move AP name and tap together`() {
        val a = QuestPanelAnchor(Location(1304, 310))
        assertEquals(Region(1638, 410, 100, 76), a.ap)
        assertEquals(Region(1556, 272, 694, 60), a.name)
        assertEquals(Location(1760, 384), a.tap)
        val b = QuestPanelAnchor(Location(1304, 286))
        assertEquals(a.ap + Location(0, -24), b.ap)
        assertEquals(a.name + Location(0, -24), b.name)
        assertEquals(a.tap + Location(0, -24), b.tap)
        val moved = QuestPanelAnchor(Location(1000, 500))
        assertEquals(a.ap + Location(-304, 190), moved.ap)
        assertFalse(QuestPanelAnchor(Location(2400, 1300)).fits(Region(0, 0, 2560, 1440)))
    }

    @Test fun `reset gesture stays inside panel away from reward and info and travels down 100 pixels`() {
        for (mark in listOf(Location(1304, 310), Location(1304, 286), Location(1000, 500))) {
            val a = QuestPanelAnchor(mark)
            assertEquals(a.resetStart.x, a.resetEnd.x)
            assertEquals(200, a.resetEnd.y - a.resetStart.y)
            for (i in 0..20) {
                val x = a.resetStart.x
                val y = a.resetStart.y + (a.resetEnd.y - a.resetStart.y) * i / 20
                assertTrue(x >= a.resetLane.x && x < a.resetLane.right)
                assertTrue(y >= a.resetLane.y && y < a.resetLane.bottom)
                assertTrue(x in (mark.x - 54)..(mark.x + 1158) && y in (mark.y - 66)..(mark.y + 214))
                assertTrue(x < mark.x + 896, "reward/info column is never crossed")
            }
        }
    }

    @Test fun `first anchored Match performs no drag or recenter`() {
        val events = mutableListOf<String>()
        assertTrue(WeeklyPanelFlow.run({ events += "read"; true }, { events += "drag"; true }, { events += "centre"; true }))
        assertEquals(listOf("read"), events)
    }
    @Test fun `drag happens only after a failed anchored read and successful retry ends recovery`() {
        val events = mutableListOf<String>(); var reads = 0
        assertTrue(WeeklyPanelFlow.run({ events += "read"; ++reads == 2 }, { events += "drag"; true }, { events += "centre"; true }))
        assertEquals(listOf("read", "drag", "read"), events)
    }
    @Test fun `second failure moves to centre once and final failure stops`() {
        val events = mutableListOf<String>()
        assertFalse(WeeklyPanelFlow.run({ events += "read"; false }, { events += "drag"; true }, { events += "centre"; true }))
        assertEquals(listOf("read", "drag", "read", "centre", "read"), events)
    }
    @Test fun `no safe reset path never invents a drag and failed recenter stops before reading`() {
        val events = mutableListOf<String>()
        assertFalse(WeeklyPanelFlow.run({ events += "read"; false }, { events += "reset-unavailable"; false }, { events += "centre-failed"; false }))
        assertEquals(listOf("read", "reset-unavailable", "centre-failed"), events)
    }
    @Test fun `map transitions wait original two seconds plus one and recovery really requests centering`() {
        assertEquals(3.seconds, WeeklyPanelFlow.enterMapWait)
        assertEquals(3.seconds, WeeklyPanelFlow.leaveMapWait)
        assertNull(MapView.dragToward(1079.0, 481.0, 1280, 720))
        assertNotNull(MapView.dragToward(1079.0, 481.0, 1280, 720, centre = true))
        assertNull(MapView.dragToward(640.0, 360.0, 1280, 720, centre = true))
    }
}
