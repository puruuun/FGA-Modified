package io.github.fate_grand_automata.scripts.supportSelection

import java.util.zip.GZIPInputStream
import io.github.fate_grand_automata.scripts.prefs.FeatureTuning
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** JP-only maximum-score evidence. Templates come exclusively from the D-2 native training frames. */
internal object SupportScoreBlocks {
    const val SIMILARITY = 0.90
    const val MARGIN = 0.03
    enum class Decision { Positive, Negative, Unknown }
    data class Result(val class62: Decision, val grand16: Decision, val compound: Boolean?)
    private data class Box(val x: Int, val y: Int, val w: Int, val h: Int)
    private data class Template(val field: String, val value: Int, val pixels: IntArray) {
        val sum = pixels.sum().toDouble()
        val variance = pixels.sumOf { it.toDouble() * it } - sum * sum / pixels.size
    }
    private val templates by lazy {
        GZIPInputStream(checkNotNull(javaClass.getResourceAsStream("/support-score-d2.tsv.gz"))).bufferedReader().useLines { lines ->
            lines.map { line ->
                val parts = line.split('\t') // resolution, field, value, normalized glyph pair
                Template(parts[1], parts[2].toInt(), parts[3].chunked(2).map { it.toInt(16) }.toIntArray())
            }.toList()
        }
    }

    fun read(pixels: ByteArray, width: Int, height: Int, tuning: FeatureTuning = FeatureTuning()): Result {
        if (pixels.size != width * height || width < 1 || height < 1) return unknown()
        val w = width * 4; val h = height * 4
        // Bilinear enlargement preserves the gaps between tiny outlined JP glyphs.
        val enlarged = DoubleArray(w * h) { p ->
            val sx = (p % w + 0.5) / 4 - 0.5; val sy = (p / w + 0.5) / 4 - 0.5
            val x = floor(sx).toInt(); val y = floor(sy).toInt()
            fun at(dx: Int, dy: Int) = pixels[(y + dy).coerceIn(0, height - 1) * width + (x + dx).coerceIn(0, width - 1)].toInt() and 255
            val fx = sx - x; val fy = sy - y
            val value = (at(0, 0) * (1 - fx) + at(1, 0) * fx) * (1 - fy) +
                (at(0, 1) * (1 - fx) + at(1, 1) * fx) * fy
            value
        }
        // A local contrast gate separates digit strokes from a bright/blurred blue panel.
        val integral = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) for (x in 0 until w) {
            val p = (y + 1) * (w + 1) + x + 1
            integral[p] = enlarged[y * w + x] + integral[p - 1] + integral[p - w - 1] - integral[p - w - 2]
        }
        val ink = BooleanArray(w * h) { p ->
            val x = p % w; val y = p / w
            val l = max(0, x - 10); val t = max(0, y - 10)
            val rr = min(w, x + 11); val bb = min(h, y + 11)
            val mean = (integral[bb * (w + 1) + rr] - integral[t * (w + 1) + rr] -
                integral[bb * (w + 1) + l] + integral[t * (w + 1) + l]) / ((rr - l) * (bb - t))
            enlarged[p] >= 110.5 && enlarged[p] > mean + 5
        }
        val seen = BooleanArray(ink.size); val components = mutableListOf<Box>(); val borders = mutableListOf<Int>()
        for (start in ink.indices) {
            if (!ink[start] || seen[start]) continue
            val queue = ArrayDeque<Int>(); queue.add(start); seen[start] = true
            var left = w; var right = 0; var top = h; var bottom = 0
            while (queue.isNotEmpty()) {
                val p = queue.removeFirst(); val x = p % w; val y = p / w
                left = min(left, x); right = max(right, x); top = min(top, y); bottom = max(bottom, y)
                // Eight-neighbour components, as used when cutting the training glyphs.
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val n = ny * w + nx
                    if (ink[n] && !seen[n]) { seen[n] = true; queue.add(n) }
                }
            }
            val bh = bottom - top + 1
            if (bh >= h * .8 && right - left + 1 < w / 4 && left > 0) borders.add(left)
            // Exclude panel borders and title fragments, without fixing any horizontal glyph position.
            if (bh >= h / 6 && top >= h * .2 && bottom + 1 <= h * .92 && bh <= h * .7)
                components.add(Box(left, top, right - left + 1, bh))
        }
        val bottoms = components.filter { it.h >= h / 3 }.map { it.y + it.h }.sorted()
        val baseline = bottoms.getOrNull(bottoms.size / 2) ?: return unknown()
        val clean = BooleanArray(ink.size)
        components.filter { it.y + it.h - baseline in -12..8 }.forEach { b ->
            for (y in b.y until b.y + b.h) for (x in b.x until b.x + b.w) clean[y * w + x] = ink[y * w + x]
        }
        // Occupied ink columns define glyph runs. No fixed slash or digit offsets.
        val runs = mutableListOf<Box>(); var start = -1
        for (x in 0..w) {
            val occupied = x < w && (0 until h).any { clean[it * w + x] }
            if (occupied && start < 0) start = x
            if (!occupied && start >= 0) {
                val ys = (0 until h).filter { y -> (start until x).any { clean[y * w + it] } }
                runs.add(Box(start, ys.first(), x - start, ys.last() - ys.first() + 1)); start = -1
            }
        }
        val merged = mutableListOf<Box>()
        for (b in runs) {
            val a = merged.lastOrNull()
            if (a != null && b.x - a.x - a.w <= max(a.h, b.h) * .25 &&
                min(a.h, b.h) < max(a.h, b.h) * .8 && b.x + b.w - a.x <= max(a.h, b.h) * .85) {
                merged.removeAt(merged.lastIndex)
                val y = min(a.y, b.y)
                merged.add(Box(a.x, y, b.x + b.w - a.x, max(a.y + a.h, b.y + b.h) - y))
            } else merged.add(b)
        }
        fun slash(b: Box): Boolean {
            if (b.h < b.w * 2) return false
            fun mean(top: Boolean): Double? {
                val xs = mutableListOf<Int>()
                for (y in 0 until b.h) if (if (top) y < b.h / 3.0 else y >= b.h * 2 / 3.0)
                    for (x in 0 until b.w) if (clean[(b.y + y) * w + b.x + x]) xs.add(x)
                return xs.takeIf { it.isNotEmpty() }?.average()
            }
            return (mean(true) ?: return false) - (mean(false) ?: return false) > b.w * .4
        }
        val slashes = merged.indices.filter { slash(merged[it]) }
        if (slashes.size > 1) return unknown()
        val s = slashes.singleOrNull()
        val ref = s?.let { merged[it].h } ?: merged.maxOfOrNull { it.h } ?: return unknown()
        fun decide(boxes: List<Box>, field: String, target: Int): Decision {
            val digits = boxes.dropWhile { it.h < ref * .8 }
            if (digits.isEmpty() || digits.size > 2 || digits.any { it.h < ref * .7 }) return Decision.Unknown
            if (digits.size != 2) {
                // A digit joined to the title/panel can disappear from the cleaned components.
                // Surviving raw strokes after the counted digit make its count untrustworthy.
                val last = digits.last()
                val fieldEnd = if (field == "class" && s != null) merged[s].x else w
                val end = min(fieldEnd, borders.filter { it > last.x + last.w }.minOrNull() ?: fieldEnd)
                var run = 0
                for (x in last.x + last.w + 2 until end - 2) {
                    val count = (last.y until last.y + last.h).count { ink[it * w + x] }
                    run = if (count >= max(4.0, last.h * .2)) run + 1 else 0
                    if (run >= 4) return Decision.Unknown
                }
                return Decision.Negative
            }
            val query = digits.flatMap { b ->
                // Area-average the tight glyphs to a shared 32-pixel height and 16-column cell.
                (0 until 512).map { p ->
                    val x0 = p % 16 * b.w / 16.0; val x1 = (p % 16 + 1) * b.w / 16.0
                    val y0 = p / 16 * b.h / 32.0; val y1 = (p / 16 + 1) * b.h / 32.0
                    var sum = 0.0
                    for (y in floor(y0).toInt() until min(b.h, kotlin.math.ceil(y1).toInt()))
                        for (x in floor(x0).toInt() until min(b.w, kotlin.math.ceil(x1).toInt()))
                            if (clean[(b.y + y) * w + b.x + x]) sum +=
                                (min(x1, x + 1.0) - max(x0, x.toDouble())) * (min(y1, y + 1.0) - max(y0, y.toDouble()))
                    sum * 255 / ((x1 - x0) * (y1 - y0))
                }
            }
            val sum = query.sum()
            val variance = query.sumOf { it * it } - sum * sum / query.size
            if (variance <= 0) return Decision.Unknown
            var positive = 0.0; var negative = 0.0
            for (template in templates) if (template.field == field) {
                val covariance = query.indices.sumOf { query[it] * template.pixels[it] } - sum * template.sum / query.size
                val score = covariance / sqrt(variance * template.variance)
                if (template.value == target) positive = max(positive, score) else negative = max(negative, score)
            }
            return when {
                positive >= tuning.similarity(SIMILARITY) && positive - negative >= MARGIN -> Decision.Positive
                negative >= tuning.similarity(SIMILARITY) && negative - positive >= MARGIN -> Decision.Negative
                else -> Decision.Unknown
            }
        }
        return if (s == null) Result(decide(merged, "class", 62), Decision.Unknown, false)
        else Result(decide(merged.take(s), "class", 62), decide(merged.drop(s + 1), "grand", 16), true)
    }

    private fun unknown() = Result(Decision.Unknown, Decision.Unknown, null)
}
