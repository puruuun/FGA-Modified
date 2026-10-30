package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.locations.GameAreaCalibrationLocations
import io.github.lib_automata.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

sealed interface GameAreaCalibration {
    enum class Reason { UnsupportedServer, CaptureUnavailable, AnchorsNotFound, ScalesDisagree, InvalidRectangle, DoubleCheckFailed }
    data class Failure(val reason: Reason) : GameAreaCalibration
    data class Success(val offsets: GameAreaOffsets, val region: Region, val displaySize: Size, val scale: Double,
        val noticeScore: Double, val menuScore: Double, val checkScore: Double) : GameAreaCalibration
}

/** Only borrows its inputs. No preference writes, Android calls, screen gestures or target-dependent matching. */
class GameAreaCalibrator(private val notice: Pattern, private val menu: Pattern) {
    private data class Anchor(val x: Double, val y: Double, val scale: Double, val score: Double)

    companion object {
        const val ANCHOR_SIMILARITY = 0.85
        const val CHECK_SIMILARITY = 0.80
        private const val COARSE_SIMILARITY = 0.55
        private const val COARSE_MAX_SIDE = 768.0
    }

    fun calibrate(frame: Pattern, server: GameServer): GameAreaCalibration {
        if (server !is GameServer.Jp && server !is GameServer.Kr)
            return GameAreaCalibration.Failure(GameAreaCalibration.Reason.UnsupportedServer)
        val reduction = minOf(1.0, COARSE_MAX_SIDE / max(frame.width, frame.height))
        val anchors = frame.resize(frame.size * reduction).use { small ->
            search(frame, small, notice, reduction) to search(frame, small, menu, reduction)
        }
        val n = anchors.first; val m = anchors.second
        if (n == null || m == null) return GameAreaCalibration.Failure(GameAreaCalibration.Reason.AnchorsNotFound)
        if (abs(n.scale - m.scale) / minOf(n.scale, m.scale) > 0.01)
            return GameAreaCalibration.Failure(GameAreaCalibration.Reason.ScalesDisagree)
        val scale = (n.scale + m.scale) / 2
        val bounds = Region(0, 0, frame.width, frame.height)
        val candidates = listOf(false, true).mapNotNull { wide ->
            val (nx, ny) = GameAreaCalibrationLocations.notice720(wide)
            val (mx, my) = GameAreaCalibrationLocations.menu720(wide)
            val left = (n.x - nx * scale).roundToInt()
            val right = (m.x + mx * scale).roundToInt()
            val width = right - left
            if (width <= 0) return@mapNotNull null
            val ratio = width / (720 * scale)
            if (wide && ratio !in 2.0..(21.0 / 9 + 0.005) || !wide && abs(ratio - 16.0 / 9) > 0.02)
                return@mapNotNull null
            // Integer 16:9 dimensions must survive the ordinary manager unchanged. Wide games retain their size.
            val height = if (wide) (720 * scale).roundToInt() else (width * 9.0 / 16).roundToInt()
            val gameScale = height / 720.0
            val topN = n.y - ny * gameScale
            val topM = m.y - my * gameScale
            if (abs(topN - topM) > 6 * gameScale) return@mapNotNull null
            val proposed = Region(left, ((topN + topM) / 2).roundToInt(), width, height)
            // Template resampling can put an otherwise full-screen edge a pixel outside the display.
            // Permit at most 3px of measurement rounding, then save only the ordinary manager's in-bounds result.
            if (proposed.x < -3 || proposed.y < -3 || proposed.right > frame.width + 3 || proposed.bottom > frame.height + 3)
                return@mapNotNull null
            val clipped = bounds.clip(proposed)
            val game = FgoGameAreaManager(clipped.size) { clipped.location }.gameArea
            game.takeIf { it in bounds && FgoGameAreaManager(it.size) { it.location }.gameArea == it && it.size.isWide() == wide }
        }.distinct()
        // Two anchors can be geometrically ambiguous near the 18:9 transition. Never guess a saved rectangle.
        if (candidates.size != 1) return GameAreaCalibration.Failure(GameAreaCalibration.Reason.InvalidRectangle)
        val region = candidates.single()
        val check = checkCrop(frame, region)
        if (check < CHECK_SIMILARITY) return GameAreaCalibration.Failure(GameAreaCalibration.Reason.DoubleCheckFailed)
        return GameAreaCalibration.Success(GameAreaOffsets(region.x, region.y, frame.width - region.right, frame.height - region.bottom),
            region, frame.size, region.height / 720.0, n.score, m.score, check)
    }

    private fun search(frame: Pattern, small: Pattern, template: Pattern, reduction: Double): Anchor? {
        var best: Anchor? = null
        val seen = hashSetOf<Size>()
        for (i in 0..100) {
            val scale = 1.0 + i * 0.02
            val size = template.size * (scale * reduction)
            if (!seen.add(size) || size.width > small.width || size.height > small.height) continue
            template.resize(size).use { resized ->
                val match = small.findMatches(resized, COARSE_SIMILARITY).maxByOrNull { it.score } ?: return@use
                if (match.score > (best?.score ?: 0.0))
                    best = Anchor(match.region.x / reduction, match.region.y / reduction, scale, match.score)
            }
        }
        val coarse = best ?: return null
        val margin = (12 / reduction).roundToInt()
        val left = maxOf(0, coarse.x.roundToInt() - margin)
        val top = maxOf(0, coarse.y.roundToInt() - margin)
        val right = minOf(frame.width, (coarse.x + template.width * (coarse.scale + 0.04)).roundToInt() + margin)
        val bottom = minOf(frame.height, (coarse.y + template.height * (coarse.scale + 0.04)).roundToInt() + margin)
        best = null
        seen.clear()
        frame.crop(Region(left, top, right - left, bottom - top)).use { window ->
            val start = maxOf(1000, (coarse.scale * 1000).roundToInt() - 40)
            val end = minOf(3000, (coarse.scale * 1000).roundToInt() + 40)
            for (i in start..end step 2) {
                val scale = i / 1000.0
                val size = template.size * scale
                if (!seen.add(size) || size.width > window.width || size.height > window.height) continue
                template.resize(size).use { resized ->
                    val match = window.findMatches(resized, ANCHOR_SIMILARITY).maxByOrNull { it.score } ?: return@use
                    if (match.score > (best?.score ?: 0.0)) best = Anchor(left + match.region.x.toDouble(), top + match.region.y.toDouble(), scale, match.score)
                }
            }
        }
        return best
    }

    private fun checkCrop(frame: Pattern, region: Region): Double {
        val manager = FgoGameAreaManager(region.size) { region.location }
        val scale = RealScale(manager).screenToImage ?: 1.0
        return frame.crop(manager.gameArea).use { crop ->
            crop.resize(crop.size * scale).use { normalized ->
                val search = GameAreaCalibrationLocations.menuSearch(normalized.width * 2, normalized.size.isWide()) * 0.5
                normalized.crop(search).use { it.findMatches(menu, CHECK_SIMILARITY).maxByOrNull { it.score }?.score ?: 0.0 }
            }
        }
    }
}
