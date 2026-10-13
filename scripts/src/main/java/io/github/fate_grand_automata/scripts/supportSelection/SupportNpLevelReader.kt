package io.github.fate_grand_automata.scripts.supportSelection

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.GameServers
import io.github.fate_grand_automata.scripts.locations.SupportNpLayout
import io.github.lib_automata.OcrService
import io.github.lib_automata.OcrText
import io.github.lib_automata.TextLineOcrOptions
import io.github.lib_automata.dagger.ScriptScope
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
        private val levelText = Regex("""Lv\.?\s*([1-5])""", RegexOption.IGNORE_CASE)

        fun parse(result: OcrText): Int? =
            if (result.confidence < MIN_OCR_CONFIDENCE) null
            else levelText.matchEntire(result.text.trim())?.groupValues?.get(1)?.toInt()
    }

    /** Pure read of the caller's candidate snapshot. No taps, waits or display changes. */
    fun read(bounds: SupportBounds): Int? {
        val search = SupportNpLayout.nameRegion(bounds.region)
        val servers = if (prefs.gameServer is GameServer.Jp)
            listOf(prefs.gameServer, GameServers.default) else listOf(prefs.gameServer)
        val matches = servers.flatMap { server ->
            listOf(Images.SupportNpLevelPrefix, Images.SupportNpLevelPrefixGold).flatMap { image ->
                search.findAll(images[image, server], similarity = PREFIX_SIMILARITY)
            }
        }
        val best = matches.maxByOrNull { it.score } ?: return null
        // White/gold templates can match the same marker. A competing marker elsewhere is ambiguous.
        if (matches.any {
                it.score >= best.score - AMBIGUITY_SCORE_GAP &&
                    (abs(it.region.x - best.region.x) > SAME_PREFIX_DISTANCE ||
                        abs(it.region.y - best.region.y) > SAME_PREFIX_DISTANCE)
            }) return null
        val suffix = SupportNpLayout.suffixRegion(best.region)
        if (suffix !in search) return null
        return suffix.getPattern("support_np_level").use {
            // Script coordinates are 1440p; matching patterns are 720p. A screen-edge crop
            // must not silently remove part of the number after the prefix has matched.
            if (it.width * 2 < suffix.width - 1 || it.height * 2 < suffix.height - 1) null
            else parse(ocr.detectTextLine(it, OCR_OPTIONS))
        }
    }
}
