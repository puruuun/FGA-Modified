package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.locations.SkillLevelLocations
import io.github.lib_automata.OcrService
import io.github.lib_automata.OcrText
import io.github.lib_automata.TextLineOcrOptions
import javax.inject.Inject

data class PanelLevelEvidence(
    val level: Int?, val locked: Boolean, val candidate: Int?, val score: Double,
    val margin: Double, val reason: String, val readings: List<OcrText> = emptyList()
)

/** Phase 1 only: no clicks, no enhancement-flow wiring and no dialog fallback. */
class SkillLevelPanelReader @Inject constructor(
    api: IFgoAutomataApi, private val loc: SkillLevelLocations, private val ocr: OcrService
) : IFgoAutomataApi by api {
    companion object {
        fun parse(text: String): Int? = Regex("(?:Lv[.]?)?(10|[1-9])/10", RegexOption.IGNORE_CASE).matchEntire(text.filterNot { it.isWhitespace() })
            ?.groupValues?.get(1)?.toIntOrNull()

        fun decide(readings: List<OcrText>): PanelLevelEvidence {
            val confident = readings.filter { it.confidence >= 80 }
            val groups = confident.mapNotNull { r -> parse(r.text)?.let { it to r.confidence } }
                .groupBy({ it.first }, { it.second }).mapValues { it.value.min() / 100.0 }
                .toList().sortedByDescending { it.second }
            val candidate = groups.firstOrNull()?.first
            val score = groups.firstOrNull()?.second ?: 0.0
            val margin = score - (groups.getOrNull(1)?.second ?: 0.0)
            val accepted = confident.size >= 3 && candidate != null && confident.all { parse(it.text) == candidate }
            return PanelLevelEvidence(if (accepted) candidate else null, false, candidate, score, margin,
                if (accepted) "ocr-consensus" else "unread-or-conflict", readings)
        }

        // Whitelisting Lv./slash can force a correct-looking string at confidence zero.
        // Unrestricted OCR on the complete row retains the real confidence; parse validates its grammar.
        val skillProfiles: List<TextLineOcrOptions> = listOf(2, 3).flatMap { scale ->
            listOf(false, true).map { gray ->
                TextLineOcrOptions(scale = scale, threshold = 0.6, rawGrayscale = gray, padding = 8)
            }
        }

    }

    private val appendMatcher by lazy {
        runCatching {
            val sheet = images[Images.SkillLevelPanelAppendSamples]
            sheet.grayPixels()?.let { PanelOutlineMatcher(it, sheet.width, sheet.height) }
        }.getOrNull()
    }

    fun read(mode: SkillLevelMode, slot: Int): PanelLevelEvidence = useSameSnapIn {
        require(slot in 0 until if (mode == SkillLevelMode.Skill) 3 else 5)
        if (prefs.gameServer !is GameServer.Kr && prefs.gameServer !is GameServer.Jp)
            return@useSameSnapIn PanelLevelEvidence(null, false, null, 0.0, 0.0, "unsupported-server")
        if (mode == SkillLevelMode.Append && SkillLevelScreens(this, loc).slotLocked(mode, slot))
            return@useSameSnapIn PanelLevelEvidence(null, true, null, 0.0, 0.0, "locked")
        loc.panelLevel(mode, slot).getPattern("panel-level").use { pattern ->
            if (mode == SkillLevelMode.Append) {
                val pixels = pattern.grayPixels()
                if (pixels == null) PanelLevelEvidence(null, false, null, 0.0, 0.0, "no-gray-pixels")
                else appendMatcher?.read(pixels, pattern.width, pattern.height, slot)
                    ?: PanelLevelEvidence(null, false, null, 0.0, 0.0, "missing-templates")
            } else decide(skillProfiles.map { ocr.detectTextLine(pattern, it) })
        }
    }
}
