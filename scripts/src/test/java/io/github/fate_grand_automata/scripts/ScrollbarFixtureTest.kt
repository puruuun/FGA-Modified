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
    /**
     * Independent normalized correlation over the exact production window (beta.6 rail-sized boxes, 1440p halved to 720p).
     * [dy] moves the picture down by that many 720p rows, as if the thumb stood that far from the end.
     */
    private fun score(file: String, screen: String, end: String, dy: Int = 0): Double {
        val im = gray("../docs/reference-images/$file")
        val template = gray("../app/src/main/assets/En/support_scrollbar_$end.png")
        val width = im[0].size
        val wide = width > 1440
        // 1440p box (x, y, w, h); wide boxes are centre-anchored, 16:9 boxes sit on the right edge.
        val (bx, by, bw, bh) = when {
            screen == "fp" && wide -> listOf(956, 1330, 72, 34)
            screen == "fp" -> listOf(-554, 1330, 72, 34)
            end == "top" && wide -> listOf(1430, 178, 72, 42)
            end == "top" -> listOf(-82, 178, 74, 38)
            wide -> listOf(1430, 1152, 72, 34)
            else -> listOf(-82, 1152, 74, 34)
        }
        val left = ((if (wide) width else 2 * width) + bx) / 2
        val top = by / 2
        val tw = template[0].size; val th = template.size
        val tm = template.flatMap { it.toList() }.average()
        val tv = template.sumOf { row -> row.sumOf { (it - tm) * (it - tm) } }
        var best = -1.0
        for (y in top..top + (bh + 1) / 2 - th) for (x in left..left + bw / 2 - tw) {
            val mean = (0 until th).sumOf { ty -> (0 until tw).sumOf { tx -> im[y + ty - dy][x + tx] } } / (tw * th)
            var dot = 0.0; var variance = 0.0
            for (ty in 0 until th) for (tx in 0 until tw) {
                val v = im[y + ty - dy][x + tx] - mean
                dot += v * (template[ty][tx] - tm); variance += v * v
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
    @Test fun `a thumb a few pixels away from the end does not count as the end`() {
        // Small drift (3 px) still reads as the end; 8 px away (the thumb has left the cap) does not.
        for (dy in listOf(-3, 3)) {
            assertTrue(ListScrollbar.read(score("Kr/scroll-end/weekly-top.png", "weekly", "top", dy), null).top)
            assertTrue(ListScrollbar.read(null, score("Kr/scroll-end/weekly-bottom-1.png", "weekly", "bottom", dy)).bottom)
            assertTrue(score("Kr/scroll-end/fp-bottom.png", "fp", "bottom", dy) >= FpSellReader.SCROLLBAR_SIMILARITY)
        }
        assertFalse(ListScrollbar.read(score("Kr/scroll-end/weekly-top.png", "weekly", "top", 8), null).top)
        assertFalse(ListScrollbar.read(null, score("Kr/scroll-end/weekly-bottom-1.png", "weekly", "bottom", -8)).bottom)
        assertTrue(score("Kr/scroll-end/fp-bottom.png", "fp", "bottom", -8) < FpSellReader.SCROLLBAR_SIMILARITY)
    }
    @Test fun `missing end scores never imply a one page list`() {
        assertEquals(ListScrollbar.State(false, false), ListScrollbar.read(null, null))
        assertTrue(ListScrollbar.read(.9, .9).let { it.top && it.bottom })
    }
}
