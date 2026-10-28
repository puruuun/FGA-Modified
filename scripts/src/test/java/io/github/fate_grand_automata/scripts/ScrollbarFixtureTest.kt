package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.modules.*
import io.github.fate_grand_automata.scripts.weekly.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.*

class ScrollbarFixtureTest {
    private fun image(name: String) = ImageIO.read(File("../docs/reference-images/$name"))!!
    private fun box(image: java.awt.image.BufferedImage, centre: Int, y: Int, height: Int, reader: ScrollbarBoxes): ScrollbarBoxes.Sample? {
        val pixels = ByteArray(28 * height) { i ->
            val rgb = image.getRGB(centre - 14 + i % 28, y + i / 28)
            (0.299 * (rgb shr 16 and 255) + 0.587 * (rgb shr 8 and 255) + 0.114 * (rgb and 255)).roundToInt().toByte()
        }
        return reader.sample(pixels, 28, height)
    }
    private fun weekly(name: String): ListScrollbar.State {
        val image = image("Kr/scroll-end/$name.png")
        val centre = image.width - if (image.width > 1440) 108 else 23
        return ListScrollbar.read(box(image, centre, 102, 6, ListScrollbar.boxes),
            box(image, centre, 578, 6, ListScrollbar.boxes), box(image, centre, 330, 24, ListScrollbar.boxes))
    }

    @Test fun `three animated idle bottom captures skip down immediately`() {
        for (index in 1..3) {
            val bar = weekly("weekly-bottom-$index")
            assertTrue(bar.railPresent)
            assertTrue(bar.bottom)
            assertFalse(bar.top)
            assertTrue(WeeklyListScan.atEnd(bar, ListSearchDirection.Down))
            var captures = 0
            WeeklyListScan.run(14, { ListScanFrame(true, if (captures++ > 0) "found above" else null, bar) },
                { direction, _ -> assertEquals(ListSearchDirection.Up, direction, "Down must never swipe") })
        }
    }
    @Test fun `owner top capture ends up immediately`() {
        val bar = weekly("weekly-top")
        assertTrue(bar.railPresent)
        assertTrue(bar.top)
        assertFalse(bar.bottom)
        assertTrue(WeeklyListScan.atEnd(bar, ListSearchDirection.Up))
    }
    @Test fun `sixteen nine middle has a rail and neither end cap`() {
        val bar = weekly("weekly-middle")
        assertTrue(bar.railPresent)
        assertFalse(bar.top)
        assertFalse(bar.bottom)
    }
    @Test fun `FP bottom ends after completing its first page`() {
        val image = image("Kr/scroll-end/fp-bottom.png")
        val reader = FpSellReader.scrollbarBoxes
        val centre = image.width - 344
        assertTrue(reader.rail(box(image, centre, 330, 24, reader)))
        val bottom = reader.thumb(box(image, centre, 665, 6, reader))
        assertTrue(bottom)
        val progress = FpSellProgress(284)
        progress.beginPage(listOf(400, 684), 0)
        progress.tapped(0, 400, FpSellPolicy.Lock.Unlocked)
        assertEquals("scrollbar-end", progress.finishPage(railPresent = true, bottom = bottom))
        assertEquals(1, progress.pages)
        assertEquals(1, progress.selected)
    }
    @Test fun `FP existing sixteen nine page is not at the bottom`() {
        val image = image("Kr/fp-sell/18.png")
        val reader = FpSellReader.scrollbarBoxes
        assertTrue(reader.rail(box(image, 1022, 330, 24, reader)))
        assertFalse(reader.thumb(box(image, 1022, 665, 6, reader)))
    }
    @Test fun `uniform dark and bright boxes have no rail or thumb and missing samples are rejected`() {
        for (reader in listOf(ListScrollbar.boxes, FpSellReader.scrollbarBoxes)) {
            for (grey in listOf(0, 255)) {
                val sample = reader.sample(ByteArray(28 * 24) { grey.toByte() }, 28, 24)
                assertFalse(reader.rail(sample))
                assertFalse(reader.thumb(sample))
            }
            assertNull(reader.sample(null, 28, 24))
            assertNull(reader.sample(ByteArray(1), 28, 24))
        }
    }
}
