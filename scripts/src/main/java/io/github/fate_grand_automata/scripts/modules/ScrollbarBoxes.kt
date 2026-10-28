package io.github.fate_grand_automata.scripts.modules

/** A 28px-wide, 720p box: four core columns and two three-column border bands. */
class ScrollbarBoxes(
    private val thumbMinimum: Double,
    private val endContrast: Double,
    private val railContrast: Double,
    private val railMinimumRows: Double
) {
    data class Sample(val core: Double, val contrast: Double, val railRows: Double)

    fun sample(pixels: ByteArray?, width: Int, height: Int): Sample? {
        if (pixels == null || width != 28 || height <= 0 || pixels.size != width * height) return null
        fun mean(y: Int, xs: IntRange) = xs.sumOf { pixels[y * width + it].toInt() and 255 }.toDouble() / xs.count()
        val cores = DoubleArray(height) { mean(it, 12..15) }
        val contrasts = DoubleArray(height) { cores[it] - (mean(it, 2..4) + mean(it, 23..25)) / 2 }
        val railRows = cores.indices.count {
            (cores[it] <= 200 && contrasts[it] <= -railContrast) ||
                (cores[it] >= thumbMinimum && contrasts[it] >= railContrast)
        }.toDouble() / height
        return Sample(cores.average(), contrasts.average(), railRows)
    }

    fun thumb(sample: Sample?) = sample != null && sample.core >= thumbMinimum && sample.contrast >= endContrast
    fun rail(sample: Sample?) = sample != null && sample.railRows >= railMinimumRows
}
