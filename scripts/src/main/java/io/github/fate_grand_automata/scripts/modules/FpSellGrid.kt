package io.github.fate_grand_automata.scripts.modules

/** Frame geometry at the matching resolution (720p); no card artwork, labels or rarity are interpreted. */
object FpSellGrid {
    data class Result(val seven: Boolean, val tops: List<Int>)
    private fun median(values: List<Double>) = values.sorted()[values.size / 2]
    private fun pixel(data: ByteArray, width: Int, x: Int, y: Int) = data[y * width + x].toInt() and 255
    private fun edge(data: ByteArray, width: Int, y: Int, x: Int, rising: Boolean): Double =
        (-7..8).maxOf { offset ->
            val a = pixel(data, width, (x + offset - 1).coerceIn(0, width - 1), y)
            val b = pixel(data, width, (x + offset).coerceIn(0, width - 1), y)
            maxOf(0, if (rising) b - a else a - b).toDouble()
        }

    fun read(data: ByteArray, width: Int, height: Int, columns: List<Int>, originY: Int, pitch: Int, cardHeight: Int): Result? {
        if (width <= 0 || pitch <= 0 || height < pitch || data.size != width * height || columns.size != 7) return null
        val edges = columns.map { x -> DoubleArray(height) { y -> edge(data, width, y, x, true) } }
        val profile = DoubleArray(height) { y -> median(edges.map { it[y] }) }
        fun score(phase: Int): Double {
            val inside = profile.indices.filter { (it + originY - phase).mod(pitch) in 15..110 }
            val gap = profile.indices.filter { (it + originY - phase).mod(pitch) in 130..139 }
            return inside.map { profile[it] }.average() - gap.map { profile[it] }.average()
        }
        val phase = (0 until pitch).maxByOrNull { score(it) } ?: return null
        // The periodic frame/gap contrast cleanly separates 7 columns from 5/6. A darkened dialog is never a live sell screen.
        val seven = score(phase) >= 30.0
        val tops = generateSequence(phase) { it + pitch }.takeWhile { it < originY + height }
            .filter { it + cardHeight > originY }.toList()
        return Result(seven, tops)
    }

    fun present(data: ByteArray, width: Int, height: Int, left: Int, cardWidth: Int): Boolean {
        if (width < cardWidth || height == 0 || data.size != width * height) return false
        // Require a continuous left AND right border. A patterned background or an isolated debug box is insufficient.
        val bands = listOf(0 until height / 2, height / 2 until height)
        return bands.all { rows ->
            val leftMean = rows.map { edge(data, width, it, left, true) }.average()
            val rightMean = rows.map { edge(data, width, it, left + cardWidth, false) }.average()
            leftMean >= 12.0 && rightMean >= 12.0
        }
    }
}
