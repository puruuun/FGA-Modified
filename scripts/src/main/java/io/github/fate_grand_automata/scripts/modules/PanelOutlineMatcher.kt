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
        // The left 14 pixels contain the yellow Lv. prefix and icon artwork, not the current digits.
        // Apply the identical window to every sample and observation, for every slot and channel.
        private const val DIGIT_LEFT = 14
        private val sourceSlots = listOf(0, 2, 3, 4)

        fun feature(pixels: ByteArray, width: Int, height: Int): DoubleArray? {
            val digitWidth = width - DIGIT_LEFT
            if (digitWidth < 3 || height < 3 || pixels.size != width * height) return null
            fun at(x: Int, y: Int) = (pixels[y.coerceIn(0, height - 1) * width + DIGIT_LEFT + x.coerceIn(0, digitWidth - 1)].toInt() and 255).toDouble()
            val f = DoubleArray(digitWidth * height) { p ->
                val x = p % digitWidth; val y = p / digitWidth
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
        // Normalize to the template grid; source assets remain at 720p scale.
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

/** Both banks and observations use the same RGB conversion; no grayscale fallback can silently
 * feed luminance into the blue bank. A disagreement, even from an abstaining channel, stays unread. */
internal class PanelAppendMatcher(rgb: ByteArray, width: Int, height: Int) {
    private val blue: PanelOutlineMatcher
    private val luminance: PanelOutlineMatcher

    init {
        val channels = checkNotNull(channels(rgb, width, height))
        blue = PanelOutlineMatcher(channels.first, width, height)
        luminance = PanelOutlineMatcher(channels.second, width, height)
    }

    fun read(rgb: ByteArray, width: Int, height: Int, slot: Int): PanelLevelEvidence {
        val channels = channels(rgb, width, height)
            ?: return PanelLevelEvidence(null, false, null, 0.0, 0.0, "invalid-color-crop")
        return combine(blue.read(channels.first, width, height, slot),
            luminance.read(channels.second, width, height, slot))
    }

    companion object {
        /** Blue preserves white while darkening yellow/gold. Luminance retains dark outlines on pale art. */
        internal fun channels(rgb: ByteArray, width: Int, height: Int): Pair<ByteArray, ByteArray>? {
            if (width <= 0 || height <= 0 || rgb.size != width * height * 3) return null
            val blue = ByteArray(width * height)
            val luminance = ByteArray(width * height)
            for (p in blue.indices) {
                val r = rgb[p * 3].toInt() and 255
                val g = rgb[p * 3 + 1].toInt() and 255
                val b = rgb[p * 3 + 2].toInt() and 255
                blue[p] = b.toByte()
                luminance[p] = ((299 * r + 587 * g + 114 * b + 500) / 1000).toByte()
            }
            return blue to luminance
        }

        internal fun combine(blue: PanelLevelEvidence, luminance: PanelLevelEvidence): PanelLevelEvidence {
            val strongest = if (blue.score >= luminance.score) blue else luminance
            if (blue.candidate == null || luminance.candidate == null || blue.candidate != luminance.candidate)
                return strongest.copy(level = null, reason = "channel-conflict-or-unread")
            // A higher score with a failing margin never suppresses the agreeing channel that clears both gates.
            val accepted = listOf(blue, luminance).filter { it.level != null }.maxByOrNull { it.score }
                ?: return strongest.copy(level = null, reason = "weak-outline-or-margin")
            return accepted.copy(reason = if (accepted === blue) "blue-outline-ncc" else "luminance-outline-ncc")
        }
    }
}
