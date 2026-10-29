package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.modules.*
import io.github.fate_grand_automata.scripts.weekly.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.*

class ScrollbarFixtureTest {
    private fun gray(path: String): Array<DoubleArray> {
        val im = ImageIO.read(File(path))!!
        return Array(im.height) { y -> DoubleArray(im.width) { x ->
            val rgb = im.getRGB(x, y)
            (0.299 * (rgb shr 16 and 255) + 0.587 * (rgb shr 8 and 255) + 0.114 * (rgb and 255)).roundToInt().toDouble()
        } }
    }
    /** Independent normalized correlation over the exact upstream-sized production window. */
    private fun score(file: String, screen: String, end: String): Double {
        val im = gray("../docs/reference-images/$file")
        val template = gray("../app/src/main/assets/En/support_scrollbar_$end.png")
        val wide = im[0].size > 1440
        val left = (im[0].size - (if (wide) 85.5 else 0.0) -
            if (screen == "weekly") (if (wide) 60 else 50) else (if (wide) 293 else 283)).roundToInt()
        val top = if (screen == "fp") 658 else if (end == "top") 84 else 570
        val tw = template[0].size; val th = template.size
        val tm = template.flatMap { it.toList() }.average()
        val tv = template.sumOf { row -> row.sumOf { (it - tm) * (it - tm) } }
        var best = -1.0
        for (y in top..top + 28 - th) for (x in left..minOf(left + (if (wide) 70 else 50), im[0].size) - tw) {
            val mean = (0 until th).sumOf { dy -> (0 until tw).sumOf { dx -> im[y + dy][x + dx] } } / (tw * th)
            var dot = 0.0; var variance = 0.0
            for (dy in 0 until th) for (dx in 0 until tw) {
                val v = im[y + dy][x + dx] - mean
                dot += v * (template[dy][dx] - tm); variance += v * v
            }
            if (variance > 0) best = maxOf(best, dot / sqrt(variance * tv))
        }
        return best
    }
    private fun weekly(name: String) = ListScrollbar.read(score("Kr/scroll-end/$name.png", "weekly", "top"),
        score("Kr/scroll-end/$name.png", "weekly", "bottom"))

    @Test fun `animated bottom fixtures skip down before the first swipe`() {
        for (i in 1..3) {
            val bar = weekly("weekly-bottom-$i")
            assertTrue(bar.bottom); assertFalse(bar.top)
            var captures = 0
            WeeklyListScan.run(14, { ListScanFrame(true, if (captures++ > 0) "found above" else null, bar) },
                { direction, _ -> assertEquals(ListSearchDirection.Up, direction) })
        }
    }
    @Test fun `wide top and sixteen nine middle match upstream end templates`() {
        assertTrue(weekly("weekly-top").top); assertFalse(weekly("weekly-top").bottom)
        assertFalse(weekly("weekly-middle").top); assertFalse(weekly("weekly-middle").bottom)
    }
    @Test fun `wide and sixteen nine FP bottoms complete their current page`() {
        for (name in listOf("Kr/scroll-end/fp-bottom.png", "Jp/fp-sell/beta7/20261019-104653-291-watch.png")) {
            assertTrue(score(name, "fp", "bottom") >= FpSellReader.SCROLLBAR_SIMILARITY)
            val progress = FpSellProgress(284)
            progress.beginPage(listOf(400, 684), 0); progress.tapped(0, 400, FpSellPolicy.Lock.Unlocked)
            assertEquals("scrollbar-end", progress.finishPage(bottom = true))
            assertEquals(1, progress.pages); assertEquals(1, progress.selected)
        }
    }
    @Test fun `FP top and middle stay below the separately measured threshold`() {
        for (n in listOf(18,19,22,24,27,28)) assertTrue(score("Kr/fp-sell/$n.png", "fp", "bottom") < FpSellReader.SCROLLBAR_SIMILARITY)
    }
    @Test fun `missing end scores never imply a one page list`() {
        assertEquals(ListScrollbar.State(false, false), ListScrollbar.read(null, null))
        assertTrue(ListScrollbar.read(.9, .9).let { it.top && it.bottom })
    }
}
