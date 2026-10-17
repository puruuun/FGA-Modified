package io.github.fate_grand_automata.scripts.supportSelection

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.GameServers
import io.github.fate_grand_automata.scripts.locations.SupportNpLayout
import io.github.lib_automata.Match
import io.github.lib_automata.OcrService
import io.github.lib_automata.OcrText
import io.github.lib_automata.Region
import io.github.lib_automata.TextLineOcrOptions
import io.github.lib_automata.dagger.ScriptScope
import io.github.lib_automata.size
import javax.inject.Inject
import kotlin.math.abs

@ScriptScope
class SupportNpLevelReader @Inject constructor(
    api: IFgoAutomataApi,
    private val ocr: OcrService
) : IFgoAutomataApi by api {
    companion object {
        const val PREFIX_SIMILARITY = 0.80
        const val MIN_OCR_CONFIDENCE = 75
        const val AMBIGUITY_SCORE_GAP = 0.05
        const val SAME_PREFIX_DISTANCE = 8
        const val OCR_SCALE = 4
        const val OCR_THRESHOLD = 100.0 / 255.0
        const val OCR_PADDING = 12
        val OCR_OPTIONS = TextLineOcrOptions(
            scale = OCR_SCALE, threshold = OCR_THRESHOLD, fillOutline = true,
            padding = OCR_PADDING, allowedCharacters = "LlvV.0123456789"
        )
        // Recover smaller NP-name fonts and resampling differences only after the fast read fails.
        // The lower search threshold proposes a crop; it never accepts an NP level on its own.
        const val RECOVERY_PREFIX_SIMILARITY = 0.75
        // KR white Lv markers have different outline sampling on the blue name panel.
        // This only proposes a suffix; two exact, high-confidence OCR reads still decide.
        const val KR_RECOVERY_PREFIX_SIMILARITY = 0.72
        const val MIN_RECOVERY_OCR_CONFIDENCE = 85
        const val MIN_RECOVERY_AGREEMENT = 2
        const val RECOVERY_BRIGHT_THRESHOLD = 110.0 / 255.0
        const val RECOVERY_DARK_THRESHOLD = 80.0 / 255.0
        const val RECOVERY_OCR_SCALE = 6
        val RECOVERY_PREFIX_SCALES = listOf(0.80, 0.85, 0.90, 0.95, 1.00, 1.05, 1.10)
        val RECOVERY_OCR_OPTIONS = listOf(
            OCR_OPTIONS,
            OCR_OPTIONS.copy(threshold = RECOVERY_BRIGHT_THRESHOLD),
            OCR_OPTIONS.copy(threshold = RECOVERY_DARK_THRESHOLD),
            OCR_OPTIONS.copy(scale = RECOVERY_OCR_SCALE)
        )
        private val levelText = Regex("""Lv\.?\s*([1-5])""", RegexOption.IGNORE_CASE)

        fun parse(result: OcrText): Int? =
            if (result.confidence < MIN_OCR_CONFIDENCE) null
            else levelText.matchEntire(result.text.trim())?.groupValues?.get(1)?.toInt()

        fun recoveryLevel(results: List<OcrText>): Int? {
            // Inspect every profile, including the later ones: two agreeing reads must not
            // hide a third confident result that names a different level.
            val level = results.mapNotNull(::parse).distinct().singleOrNull() ?: return null
            return level.takeIf {
                results.count { it.confidence >= MIN_RECOVERY_OCR_CONFIDENCE && parse(it) == level } >=
                    MIN_RECOVERY_AGREEMENT
            }
        }
    }

    /** Pure read of the caller's candidate snapshot. No taps, waits or display changes. */
    fun read(bounds: SupportBounds): Int? {
        val search = SupportNpLayout.nameRegion(bounds.region)
        val servers = if (prefs.gameServer is GameServer.Jp)
            listOf(prefs.gameServer, GameServers.default) else listOf(prefs.gameServer)
        val prefixes = servers.flatMap { server ->
            listOf(Images.SupportNpLevelPrefix, Images.SupportNpLevelPrefixGold).map { images[it, server] }
        }
        val matches = prefixes.flatMap { search.findAll(it, similarity = PREFIX_SIMILARITY) }
        val best = matches.maxByOrNull { it.score }
        if (best != null) {
            if (ambiguous(matches, best)) return null
            val results = readSuffix(search, best, listOf(OCR_OPTIONS)) ?: return null
            parse(results.single())?.let { return it }
        }

        val recoverySimilarity = if (prefs.gameServer is GameServer.Kr)
            KR_RECOVERY_PREFIX_SIMILARITY else RECOVERY_PREFIX_SIMILARITY
        val recoveryMatches = prefixes.flatMap { template ->
            RECOVERY_PREFIX_SCALES.flatMap { scale ->
                if (scale == 1.0) search.findAll(template, similarity = recoverySimilarity)
                else template.resize(template.size * scale).use {
                    // Image-loader templates are borrowed; only resized copies belong to this reader.
                    search.findAll(it, similarity = recoverySimilarity)
                }
            }
        }
        val recoveryBest = recoveryMatches.maxByOrNull { it.score } ?: return null
        if (ambiguous(recoveryMatches, recoveryBest)) return null
        return readSuffix(search, recoveryBest, RECOVERY_OCR_OPTIONS)?.let(::recoveryLevel)
    }

    private fun ambiguous(matches: List<Match>, best: Match): Boolean {
        // White/gold templates can match the same marker. A competing marker elsewhere is ambiguous.
        return matches.any {
            it.score >= best.score - AMBIGUITY_SCORE_GAP &&
                (abs(it.region.x - best.region.x) > SAME_PREFIX_DISTANCE ||
                    abs(it.region.y - best.region.y) > SAME_PREFIX_DISTANCE)
        }
    }

    private fun readSuffix(search: Region, prefix: Match, profiles: List<TextLineOcrOptions>): List<OcrText>? {
        val suffix = SupportNpLayout.suffixRegion(prefix.region)
        if (suffix !in search) return null
        return suffix.getPattern("support_np_level").use {
            // Script coordinates are 1440p; matching patterns are 720p. A screen-edge crop
            // must not silently remove part of the number after the prefix has matched.
            if (it.width * 2 < suffix.width - 1 || it.height * 2 < suffix.height - 1) null
            else profiles.map { options -> ocr.detectTextLine(it, options) }
        }
    }
}
