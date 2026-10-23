package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.weekly.*
import kotlin.test.*

class WeeklyListScanTest {
    private val down = ListSearchDirection.Down
    private val up = ListSearchDirection.Up
    private fun frame(position: Double?, pixels: ByteArray? = null, match: String? = null, valid: Boolean = true) =
        ListScanFrame(valid, match, position, pixels)
    private fun scan(frames: List<ListScanFrame<String>>, cap: Int = 14): Pair<List<ListSearchDirection>, List<String>> {
        val directions = mutableListOf<ListSearchDirection>()
        val logs = mutableListOf<String>()
        var i = 0
        WeeklyListScan.run(cap, { frames[minOf(i++, frames.lastIndex)] }, { d, _ -> directions += d }, log = logs::add)
        return directions to logs
    }

    @Test fun `bottom start searches up only`() {
        val (directions, _) = scan(listOf(frame(1.0)))
        assertEquals(List(14) { up }, directions)
    }
    @Test fun `top starts down and skips up once top is reached`() {
        val (directions, _) = scan(listOf(frame(0.0), frame(0.5)), cap = 3)
        assertEquals(List(3) { down }, directions)
    }
    @Test fun `middle reaches bottom then switches up and checks final frame once`() {
        var captures = 0
        val directions = mutableListOf<ListSearchDirection>()
        val frames = listOf(frame(0.5), frame(1.0), frame(0.0, match = "target"))
        val found = WeeklyListScan.run(14, { frames[captures++] }, { d, _ -> directions += d })
        assertEquals("target", found)
        assertEquals(listOf(down, up), directions)
        assertEquals(3, captures)
    }
    @Test fun `unknown retains fourteen scrolls each way`() {
        val (directions, _) = scan(listOf(frame(null)))
        assertEquals(List(14) { down } + List(14) { up }, directions)
    }
    @Test fun `stationary ends both directions after final frame search`() {
        val (directions, logs) = scan(listOf(frame(null, ByteArray(100))))
        assertEquals(listOf(down, up), directions)
        assertEquals(2, logs.count { "reason=stationary" in it })
    }
    @Test fun `moving frame contradicts thumb endpoint`() {
        val (directions, logs) = scan(listOf(frame(0.5, ByteArray(100)), frame(1.0, ByteArray(100) { 100 }),
            frame(1.0, ByteArray(100) { 100 })))
        assertEquals(listOf(down, down) + List(1) { up }, directions)
        assertTrue(logs.any { "distrust" in it })
    }
    @Test fun `changed screen aborts before another scroll`() {
        val (directions, _) = scan(listOf(frame(null), frame(null, valid = false)))
        assertEquals(listOf(down), directions)
    }
    @Test fun `current frame match needs no scroll`() {
        val (directions, _) = scan(listOf(frame(0.5, match = "target")))
        assertTrue(directions.isEmpty())
    }

    @Test fun `thumb positions and invalid profiles`() {
        fun thumb(start: Int, length: Int) = ByteArray(100 * 4) { if (it / 4 in start until start + length) 240.toByte() else 90 }
        assertEquals(0.0, ListScrollbar.position(thumb(3, 30), 4, 100))
        assertEquals(0.5, ListScrollbar.position(thumb(35, 30), 4, 100))
        assertEquals(1.0, ListScrollbar.position(thumb(67, 30), 4, 100))
        assertNull(ListScrollbar.position(ByteArray(400), 4, 100))
        assertNull(ListScrollbar.position(ByteArray(400) { 255.toByte() }, 4, 100))
        assertNull(ListScrollbar.position(null, 4, 100))
        val two = thumb(3, 25)
        for (i in 200 until 300) two[i] = 240.toByte()
        assertNull(ListScrollbar.position(two, 4, 100))
    }
    @Test fun `movement ignores noise and detects changed banners`() {
        assertEquals(false, ListScrollbar.moved(ByteArray(1000), ByteArray(1000) { 5 }))
        assertEquals(true, ListScrollbar.moved(ByteArray(1000), ByteArray(1000) { if (it < 100) 50 else 0 }))
        assertNull(ListScrollbar.moved(null, ByteArray(1)))
    }
}
