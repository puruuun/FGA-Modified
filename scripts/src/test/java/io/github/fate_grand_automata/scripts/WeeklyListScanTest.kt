package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.weekly.*
import kotlin.test.*

class WeeklyListScanTest {
    private val down = ListSearchDirection.Down
    private val up = ListSearchDirection.Up
    private fun frame(top: Boolean = false, bottom: Boolean = false,
        match: String? = null, valid: Boolean = true) = ListScanFrame(valid, match, ListScrollbar.State(top, bottom))
    private fun scan(frames: List<ListScanFrame<String>>, cap: Int = 14): Pair<List<ListSearchDirection>, List<String>> {
        val directions = mutableListOf<ListSearchDirection>()
        val logs = mutableListOf<String>()
        var i = 0
        WeeklyListScan.run(cap, { frames[minOf(i++, frames.lastIndex)] }, { d, _ -> directions += d }, log = logs::add)
        return directions to logs
    }

    @Test fun `bottom start skips down before any swipe`() {
        val (directions, logs) = scan(listOf(frame(bottom = true)))
        assertEquals(List(14) { up }, directions)
        assertTrue(logs.any { "skip direction=Down reason=scrollbar-end" in it })
    }
    @Test fun `top starts down and skips already searched upper content`() {
        assertTrue(WeeklyListScan.atEnd(frame(top = true).scrollbar, up))
        val (directions, _) = scan(listOf(frame(top = true), frame()), cap = 3)
        assertEquals(List(3) { down }, directions)
    }
    @Test fun `end box is trusted immediately and final frame is searched once`() {
        var captures = 0
        val directions = mutableListOf<ListSearchDirection>()
        val frames = listOf(frame(), frame(bottom = true), frame(top = true, match = "target"))
        val found = WeeklyListScan.run(14, { frames[captures++] }, { d, _ -> directions += d })
        assertEquals("target", found)
        assertEquals(listOf(down, up), directions)
        assertEquals(3, captures)
    }
    @Test fun `no end box hits retain fourteen scrolls each way as sole fallback`() {
        val (directions, logs) = scan(listOf(frame()))
        assertEquals(List(14) { down } + List(14) { up }, directions)
        assertEquals(2, logs.count { "reason=safety-cap" in it })
    }
    @Test fun `both end templates make a one page list with no swipes`() {
        var captures = 0
        val result = WeeklyListScan.run(14, { captures++; frame(top = true, bottom = true) }, { _, _ -> fail("Must not swipe") })
        assertNull(result)
        assertEquals(1, captures)
    }
    @Test fun `one page can still contain the target`() {
        assertEquals("target", WeeklyListScan.run(14, { frame(top = true, bottom = true, match = "target") }, { _, _ -> fail("Must not swipe") }))
    }
    @Test fun `missing end templates never claim an end`() {
        val (directions, logs) = scan(listOf(frame()))
        assertEquals(28, directions.size)
        assertTrue(logs.none { "no-scrollbar" in it })
    }
    @Test fun `changed screen aborts before another scroll`() {
        val (directions, _) = scan(listOf(frame(), frame(valid = false)))
        assertEquals(listOf(down), directions)
    }
    @Test fun `current frame match needs no scroll`() {
        val (directions, _) = scan(listOf(frame(match = "target")))
        assertTrue(directions.isEmpty())
    }
}
