package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.modules.FpSellGrid
import kotlin.test.*

class FpSellGridTest {
    @Test fun `empty pixels and incomplete borders never count as a card`() {
        val w = 132; val h = 92
        val blank = ByteArray(w * h)
        assertFalse(FpSellGrid.present(blank, w, h, 8, 116))
        val border = blank.copyOf()
        for (y in 0 until h) { border[y * w + 8] = 200.toByte(); border[y * w + 123] = 200.toByte() }
        assertTrue(FpSellGrid.present(border, w, h, 8, 116))
        for (y in h / 2 until h) border[y * w + 123] = 0
        assertFalse(FpSellGrid.present(border, w, h, 8, 116))
    }

    @Test fun `one row phase covers every fully visible row at every scroll offset`() {
        val w = 925; val h = 551; val origin = 169
        val cols = (0..6).map { 3 + (it * 133.25).toInt() }
        for (phase in 0 until 142 step 7) {
            val data = ByteArray(w * h)
            for (y in 0 until h) if ((y + origin - phase).mod(142) in 0..125)
                for (x in cols) data[y * w + x] = 200.toByte()
            val result = assertNotNull(FpSellGrid.read(data, w, h, cols, origin, 142, 126))
            assertTrue(result.seven)
            val expected = generateSequence(phase) { it + 142 }.takeWhile { it + 126 <= 720 }.filter { it >= 169 }
            for (top in expected) assertTrue(result.tops.any { kotlin.math.abs(it - top) <= 8 }, "$phase $top ${result.tops}")
        }
        assertNull(FpSellGrid.read(byteArrayOf(), 0, h, cols, origin, 142, 126))
        assertFalse(assertNotNull(FpSellGrid.read(ByteArray(w * h), w, h, cols, origin, 142, 126)).seven)
    }
}
