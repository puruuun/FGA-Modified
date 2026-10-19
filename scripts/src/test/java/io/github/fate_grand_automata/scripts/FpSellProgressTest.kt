package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.modules.FpSellPolicy
import io.github.fate_grand_automata.scripts.modules.FpSellProgress
import kotlin.test.*

class FpSellProgressTest {
    private val tile = FpSellPolicy.Cell(true, FpSellPolicy.Lock.Unknown, false)
    @Test fun `rejected overlapping tile keeps identity across bounce and is subtracted exactly once`() {
        val p = FpSellProgress(284)
        p.beginPage(listOf(578,862,1146), 0)
        assertNull(p.seen(4,1146,tile))
        p.tapped(4,1146,tile.lock)
        assertEquals(FpSellPolicy.Summary(1,1),p.summary())
        p.finishPage()
        p.beginPage(listOf(574,858,1142),560)
        assertEquals("tap-rejected",p.seen(4,1142,tile))
        assertEquals(FpSellPolicy.Summary(0,0),p.summary())
        assertTrue(p.samePosition(4,1142))
        p.finishPage()
        p.beginPage(listOf(578,862,1146),560)
        assertEquals("skip:tap-rejected",p.seen(4,1146,tile))
        assertEquals(1,p.rejected)
        assertEquals(1,p.taps)
        assertFailsWith<IllegalStateException> { p.tapped(4,1146,tile.lock) }
    }

    @Test fun `actual overlap is inferred from requested swipe and row phase not fixed screen coordinates`() {
        val p = FpSellProgress(284)
        p.beginPage(listOf(364,648,932),0)
        val id = p.id(0,932)
        p.tapped(0,932,FpSellPolicy.Lock.Unlocked)
        p.finishPage()
        p.beginPage(listOf(462,746,1030),560)
        assertEquals(id,p.id(0,462)) // 470px actual movement, not the 560px requested gesture.
        assertEquals("skip:already-selected",p.seen(0,462,tile.copy(selected=true)))
        assertEquals(1,p.selected)
        assertNull(p.seen(0,746,tile))
        p.tapped(0,746,FpSellPolicy.Lock.Unlocked)
        assertEquals(2,p.selected)
    }

    @Test fun `the same screen position cannot be tapped consecutively even for a new list identity`() {
        val p=FpSellProgress(284)
        p.beginPage(listOf(364,648,932),0)
        p.tapped(4,648,tile.lock)
        p.finishPage()
        p.beginPage(listOf(462,746,1030),560)
        assertFalse(p.samePosition(4,746))
        assertTrue(p.samePosition(4,650))
        assertFailsWith<IllegalStateException> { p.tapped(4,650,tile.lock) }
    }

    @Test fun `two consecutive row matches end despite image bounce and no image equality check`() {
        val p=FpSellProgress(284)
        p.beginPage(listOf(578,862,1146),0);assertNull(p.finishPage())
        p.beginPage(listOf(574,858,1142),560);assertNull(p.finishPage())
        p.beginPage(listOf(578,862,1146),560);assertEquals("stationary-rows",p.finishPage())
        assertTrue(FpSellProgress.sameRows(listOf(400,684),listOf(412,696)))
        assertFalse(FpSellProgress.sameRows(listOf(400,684),listOf(413,697)))
        assertFalse(FpSellProgress.sameRows(emptyList(),emptyList()))
        assertFalse(FpSellProgress.sameRows(listOf(400),listOf(400,684)))
    }

    @Test fun `five pages without any new tap end even when row positions never match`() {
        val p=FpSellProgress(284)
        repeat(5) { page ->
            p.beginPage(listOf(400+page*30,684+page*30),560)
            assertEquals(if(page==4) "no-new-taps" else null,p.finishPage())
        }
        assertEquals(5,p.pages)
    }

    @Test fun `a new tap resets the five page cap`() {
        val p=FpSellProgress(284)
        repeat(8) { page ->
            val y=400+page*30
            p.beginPage(listOf(y,y+284),560)
            if(page==3) p.tapped(0,y,tile.lock)
            assertNull(p.finishPage())
        }
        p.beginPage(listOf(640,924),560)
        assertEquals("no-new-taps",p.finishPage())
    }
    @Test fun `owner beta6 log replay retains all 407 selections and ends at the idle cap`() {
        val lines = checkNotNull(javaClass.getResourceAsStream("/fp-sell-beta6-replay.csv"))
            .bufferedReader().readLines().map { it.split(',') }.groupBy { it[0].toInt() }
        val p = FpSellProgress(284)
        var ended: String? = null
        for ((page, cells) in lines) {
            p.beginPage(cells.first()[1].split(';').map(String::toInt), if (page == 0) 0 else 560)
            for (c in cells) {
                val column = c[2].toInt() - 1; val y = c[3].toInt()
                val cell = FpSellPolicy.Cell(c[4].toBoolean(), FpSellPolicy.Lock.valueOf(c[5]),
                    c[7].toBooleanStrictOrNull(), c[6].toBoolean())
                if (p.seen(column,y,cell) == null && FpSellPolicy.select(cell,FpSellPolicy.Options(true)) && !p.samePosition(column,y))
                    p.tapped(column,y,cell.lock)
            }
            ended = p.finishPage()
            if (ended != null) break
        }
        assertEquals("no-new-taps",ended)
        assertEquals(407,p.selected)
        assertEquals(407,p.taps)
        assertEquals(0,p.rejected)
        assertEquals(40,p.pages)
    }
}
