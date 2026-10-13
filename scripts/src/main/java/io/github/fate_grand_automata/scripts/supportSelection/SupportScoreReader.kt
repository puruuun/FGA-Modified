package io.github.fate_grand_automata.scripts.supportSelection

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.locations.SupportScoreLayout
import io.github.lib_automata.OcrService
import io.github.lib_automata.OcrText
import io.github.lib_automata.TextLineOcrOptions
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

/** The score before '/' is Class; the score after it is Grand. Missing scores stay unknown. */
data class SupportScores(val classScore: Int? = null, val grandScore: Int? = null)

@ScriptScope
class SupportScoreReader @Inject constructor(
    api: IFgoAutomataApi,
    private val ocr: OcrService
) : IFgoAutomataApi by api {
    companion object {
        const val MIN_OCR_CONFIDENCE = 75
        const val MIN_CONFIRM_CONFIDENCE = 85
        const val MIN_AGREEMENT = 2
        const val OCR_SCALE = 4
        const val OCR_LARGE_SCALE = 6
        const val OCR_THRESHOLD = 100.0 / 255.0
        const val OCR_BRIGHT_THRESHOLD = 110.0 / 255.0
        const val OCR_PADDING = 12
        val OCR_OPTIONS = TextLineOcrOptions(
            scale = OCR_SCALE, threshold = OCR_THRESHOLD, fillOutline = true,
            padding = OCR_PADDING, allowedCharacters = "+/0123456789"
        )
        val OCR_PROFILES = listOf(
            OCR_OPTIONS,
            OCR_OPTIONS.copy(threshold = OCR_BRIGHT_THRESHOLD),
            OCR_OPTIONS.copy(scale = OCR_LARGE_SCALE),
            OCR_OPTIONS.copy(scale = OCR_LARGE_SCALE, threshold = OCR_BRIGHT_THRESHOLD)
        )
        // At 720p OCR can omit the small plus sign. The slash and both complete
        // numeric fields are mandatory; their order, not the plus glyph, identifies the score.
        private val compoundScore = Regex("""\+?(\d{1,3})/\+?(\d{1,3})""")

        fun parse(result: OcrText): SupportScores? {
            if (result.confidence < MIN_OCR_CONFIDENCE) return null
            val text = result.text.trim().replace(Regex("""\s+"""), "")
            val match = compoundScore.matchEntire(text) ?: return null
            return SupportScores(match.groupValues[1].toInt(), match.groupValues[2].toInt())
        }

        fun confirmed(results: List<OcrText>): SupportScores? {
            val readings = results.mapNotNull { result -> parse(result)?.let { it to result.confidence } }
            fun field(value: (SupportScores) -> Int?): Int? {
                val detected = readings.mapNotNull { value(it.first) }.distinct().singleOrNull() ?: return null
                return detected.takeIf {
                    readings.count { it.second >= MIN_CONFIRM_CONFIDENCE && value(it.first) == detected } >= MIN_AGREEMENT
                }
            }
            // A disabled/uncertain Class field must not veto a confidently read Grand field,
            // and vice versa. Only the selected conditions are combined by the selection policy.
            val scores = SupportScores(field { it.classScore }, field { it.grandScore })
            return scores.takeIf { it.classScore != null || it.grandScore != null }
        }
    }

    /** Read-only within the caller's candidate snapshot, before any Append display switch. */
    fun read(bounds: SupportBounds, checkClass: Boolean, checkGrand: Boolean): SupportScores {
        if (!checkClass && !checkGrand) return SupportScores()
        val region = SupportScoreLayout.numberRegion(bounds.region)
        // Preserve the validated ordinary +62 matcher; compact +62/+16 uses a different font.
        val legacyClass62 = checkClass && region.exists(
            images[Images.SupportScore62], similarity = SupportScoreSelection.SCORE_SIMILARITY
        )
        if (legacyClass62 && !checkGrand) return SupportScores(classScore = 62)

        val results = region.getPattern("support_class_grand_scores").use { pattern ->
            if (pattern.width * 2 < region.width - 1 || pattern.height * 2 < region.height - 1) emptyList()
            else OCR_PROFILES.map { ocr.detectTextLine(pattern, it) }
        }
        confirmed(results)?.let { return it }
        // A visible slash with an uncertain pair must not fall back to finding +62 on the
        // Grand side. An ordinary score never supplies a Grand score implicitly.
        return if (legacyClass62 && results.none { '/' in it.text }) SupportScores(classScore = 62)
        else SupportScores()
    }
}
