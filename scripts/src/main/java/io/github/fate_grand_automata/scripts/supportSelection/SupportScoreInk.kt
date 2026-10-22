package io.github.fate_grand_automata.scripts.supportSelection

/** Geometry of the same filled binary ink used by OCR, before its white padding is added.
 * This only vetoes readings; it never recognizes a number (D-2 remains separate).
 */
internal class SupportScoreInk(pixels: ByteArray, val width: Int, val height: Int,
                               private val binary: ByteArray? = null) {
    private data class Glyph(val left: Int, val top: Int, val right: Int, val bottom: Int,
                             val topX: Double, val bottomX: Double) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1
    }

    private val glyphs: List<Glyph>
    init {
        val seen = BooleanArray(pixels.size)
        val found = mutableListOf<Glyph>()
        for (start in pixels.indices) {
            if (seen[start] || (pixels[start].toInt() and 255) >= 128) continue
            val queue = ArrayDeque<Int>()
            val points = mutableListOf<Int>()
            queue.add(start); seen[start] = true
            while (queue.isNotEmpty()) {
                val p = queue.removeFirst()
                points.add(p)
                val x = p % width
                val y = p / width
                for ((dx, dy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val n = ny * width + nx
                    if (!seen[n] && (pixels[n].toInt() and 255) < 128) {
                        seen[n] = true; queue.add(n)
                    }
                }
            }
            val top = points.minOf { it / width }; val bottom = points.maxOf { it / width }
            // Ignore speckles, the fillText border and the label on the line above the score.
            if (bottom - top + 1 < height / 5 || top <= 1 || bottom >= height - 2) continue
            found.add(Glyph(points.minOf { it % width }, top, points.maxOf { it % width }, bottom,
                points.filter { it / width <= top + (bottom - top) / 3 }.map { (it % width).toDouble() }.average(),
                points.filter { it / width >= bottom - (bottom - top) / 3 }.map { (it % width).toDouble() }.average()))
        }
        glyphs = found.sortedBy { it.left }
    }

    // fillText adds a one-pixel border; reserve one original-image pixel inside it too.
    fun clipped(scale: Int): Boolean {
        if (glyphs.any { it.right >= width - 1 - scale }) return true
        // An outline opened by the crop may disappear from fillText altogether. Inspect the
        // unfilled right margin at the surviving score baseline as well.
        val band = glyphs.maxByOrNull { it.bottom } ?: return true
        val raw = binary ?: return false
        val rawWidth = width - 2
        for (x in rawWidth - scale until rawWidth) {
            val column = (band.top..band.bottom).map { y ->
                (raw[(y - 1) * rawWidth + x].toInt() and 255) > 128
            }
            if (column.count { it } >= scale * 2 && column.count { !it } >= scale * 2 &&
                column.zipWithNext().count { it.first != it.second } >= 2) return true
        }
        return false
    }

    fun grandDigitsAgree(digits: Int): Boolean {
        val slash = glyphs.lastOrNull {
            it.height > it.width && it.topX - it.bottomX > it.width * 0.35
        } ?: return false
        val tail = glyphs.filter { it.left > slash.right && it.bottom >= slash.top + slash.height / 2 }
        // A plus is shorter than the slash. It is optional in OCR, not a digit.
        val numbers = tail.dropWhile { it.height < slash.height * 0.8 && it.width >= it.height * 0.7 }
        if (numbers.isEmpty() || numbers.size > digits) return false
        // Filling outlined text can itself lose an open/clipped glyph. Include bright strokes
        // in the unfilled binary image, so the lost final 6 cannot make /+16 look one digit wide.
        var last = numbers.last().right
        binary?.let { raw ->
            val rawWidth = width - 2
            val top = numbers.minOf { it.top }
            val bottom = numbers.maxOf { it.bottom }
            for (x in numbers.first().left until width - 2) {
                var ink = 0
                for (y in top..bottom) if ((raw[(y - 1) * rawWidth + x - 1].toInt() and 255) > 128) ++ink
                // Require a vertical stroke, and a dark outline beside it; flat panel background
                // is not digit ink. Border-connected background is deliberately inconclusive.
                if (ink >= (bottom - top + 1) / 3 && ink < (bottom - top + 1) * 0.9) last = maxOf(last, x)
            }
        }
        val inkWidth = last - numbers.first().left + 1
        val digitHeight = numbers.maxOf { it.height }
        return when (digits) {
            1 -> inkWidth <= digitHeight * 0.85
            2 -> inkWidth in (digitHeight * 0.65).toInt()..(digitHeight * 1.65).toInt()
            else -> false
        }
    }
}
