package io.github.fate_grand_automata.scripts.modules

import kotlin.math.sqrt

/** Same-size 720p samples. A Laplacian suppresses slow icon gradients while retaining the dark digit outline.
 * No Android/OpenCV dependencies. Templates from the target icon slot are excluded even in production:
 * evidence must transfer from another icon background, never match its own calibration crop. */
internal class PanelOutlineMatcher(sheet: ByteArray, width: Int, height: Int) {
    companion object {
        const val WIDTH = 33
        const val HEIGHT = 21
        const val MIN_SCORE = 0.70
        const val MIN_MARGIN = 0.10
        private val sourceSlots = listOf(0, 2, 3, 4)

        fun feature(pixels: ByteArray, width: Int, height: Int): DoubleArray? {
            if (width < 3 || height < 3 || pixels.size != width * height) return null
            fun at(x: Int, y: Int) = (pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)].toInt() and 255).toDouble()
            val f = DoubleArray(pixels.size) { p ->
                val x = p % width; val y = p / width
                at(x - 1, y) + at(x + 1, y) + at(x, y - 1) + at(x, y + 1) - 4 * at(x, y)
            }
            val mean = f.average()
            for (i in f.indices) f[i] -= mean
            val norm = sqrt(f.sumOf { it * it })
            if (norm < 1e-6) return null
            for (i in f.indices) f[i] /= norm
            return f
        }
    }

    private data class Sample(val level: Int, val slot: Int, val feature: DoubleArray)
    private val samples: List<Sample>
    init {
        require(width == WIDTH * 4 && height == HEIGHT * 10 && sheet.size == width * height)
        samples = (1..10).flatMap { level -> sourceSlots.mapIndexed { col, slot ->
            val patch = ByteArray(WIDTH * HEIGHT) { p -> sheet[((level - 1) * HEIGHT + p / WIDTH) * width + col * WIDTH + p % WIDTH] }
            Sample(level, slot, checkNotNull(feature(patch, WIDTH, HEIGHT)))
        } }
    }

    fun read(pixels: ByteArray, width: Int, height: Int, slot: Int): PanelLevelEvidence {
        // A stress fixture can compress the glyph horizontally. Normalize the observation to
        // the template grid with bilinear sampling; source assets remain at 720p scale.
        if (width < 3 || height < 3 || pixels.size != width * height)
            return PanelLevelEvidence(null, false, null, 0.0, 0.0, "invalid-crop")
        val normalized = if (width == WIDTH && height == HEIGHT) pixels else resample(pixels, width, height)
        val f = feature(normalized, WIDTH, HEIGHT)
            ?: return PanelLevelEvidence(null, false, null, 0.0, 0.0, "no-outline")
        val scores = samples.filter { it.slot != slot }.groupBy { it.level }.mapValues { (_, group) ->
            group.maxOf { s -> f.indices.sumOf { f[it] * s.feature[it] } }
        }.toList().sortedByDescending { it.second }
        val top = scores.first(); val margin = top.second - scores[1].second
        val accepted = top.second >= MIN_SCORE && margin >= MIN_MARGIN
        return PanelLevelEvidence(if (accepted) top.first else null, false, top.first, top.second, margin,
            if (accepted) "outline-ncc" else "weak-outline-or-margin")
    }

    private fun resample(p: ByteArray, w: Int, h: Int): ByteArray = ByteArray(WIDTH * HEIGHT) { pos ->
        val sx = (pos % WIDTH + 0.5) * w / WIDTH - 0.5
        val sy = (pos / WIDTH + 0.5) * h / HEIGHT - 0.5
        val x = kotlin.math.floor(sx).toInt(); val y = kotlin.math.floor(sy).toInt()
        fun at(dx: Int, dy: Int) = p[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)].toInt() and 255
        val fx = sx - x; val fy = sy - y
        (((at(0, 0) * (1 - fx) + at(1, 0) * fx) * (1 - fy) +
            (at(0, 1) * (1 - fx) + at(1, 1) * fx) * fy).toInt().coerceIn(0, 255)).toByte()
    }
}
