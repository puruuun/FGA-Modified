package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.locations.SkillLevelLocations
import io.github.lib_automata.OcrService
import io.github.lib_automata.OcrText
import io.github.lib_automata.Region
import io.github.lib_automata.TextLineOcrOptions
import javax.inject.Inject

/**
 * D-11: reads `Lv.current > Lv.next` from the confirm dialog. The digits are large white (current) and yellow (next)
 * glyphs with a black outline, so several binarization profiles are tried and the reading only counts when the
 * confident ones agree.
 */
class SkillLevelDigits @Inject constructor(
    api: IFgoAutomataApi,
    private val loc: SkillLevelLocations,
    private val ocr: OcrService
) : IFgoAutomataApi by api {
    companion object {
        const val MIN_CONFIDENCE = 70
        /**
         * Native size (scale 1): Tesseract misses or misreads the 40 px tall glyphs once they are enlarged, and a
         * horizontal stretch of 1.2 or more turns "1" into "4". These profiles were picked on the owner dialogs
         * (digits 1-6), where every confident reading of all of them was right.
         */
        val PROFILES = listOf(0.6, 0.65, 0.7, 0.75, 0.8).flatMap { threshold ->
            listOf(0.8, 1.0).flatMap { stretch ->
                listOf(4, 12).map { padding ->
                    TextLineOcrOptions(scale = 1, threshold = threshold, invert = true, padding = padding,
                        allowedCharacters = "0123456789", horizontalScale = stretch)
                }
            }
        }

        /** Never replace a confident nonblank read, including invalid or contradictory primary reads. */
        fun needsTemplateFallback(readings: List<OcrText>): Boolean = readings.none {
            it.confidence >= MIN_CONFIDENCE && it.text.isNotBlank()
        }

        const val NINE_MIN_SCORE = 0.96
        const val NINE_MIN_MARGIN = 0.10
        fun templateNine(scores: List<Pair<Int, Double>>): Int? {
            if (scores.any { !it.second.isFinite() }) return null
            val nine = scores.filter { it.first == 9 }.maxOfOrNull { it.second } ?: return null
            val other = scores.filter { it.first != 9 }.maxOfOrNull { it.second } ?: return null
            return 9.takeIf { nine >= NINE_MIN_SCORE && nine - other >= NINE_MIN_MARGIN }
        }

        /** One value, supported by at least two confident readings and contradicted by none. */
        fun consensus(readings: List<OcrText>, valid: IntRange): Int? {
            val values = readings.filter { it.confidence >= MIN_CONFIDENCE }
                .mapNotNull { r -> r.text.trim().takeIf { it.matches(Regex("[0-9]{1,2}")) }?.toIntOrNull() }
            if (values.any { it !in valid }) return null
            return values.distinct().singleOrNull()?.takeIf { values.size >= 2 }
        }
    }

    private val digitTemplates by lazy {
        listOf(
            Images.SkillLevelDigit0, Images.SkillLevelDigit1, Images.SkillLevelDigit2, Images.SkillLevelDigit3,
            Images.SkillLevelDigit4, Images.SkillLevelDigit5, Images.SkillLevelDigit6, Images.SkillLevelDigit7,
            Images.SkillLevelDigit8, Images.SkillLevelDigit9, Images.SkillLevelDigit9Kr, Images.SkillLevelDigit9Next
        ).mapIndexedNotNull { index, image -> runCatching { images[image] }.getOrNull()?.let { minOf(index, 9) to it } }
            .takeIf { it.size == 12 }
    }

    /** Raw primary OCR readings, kept for the QA report. */
    fun readings(region: Region): List<OcrText> = region.getPattern("skill_level_digit").use { pattern ->
        PROFILES.map { ocr.detectTextLine(pattern, it) }
    }

    private fun readDigit(region: Region, valid: IntRange): Int? {
        val primary = readings(region)
        if (!needsTemplateFallback(primary)) return consensus(primary, valid)
        // The isolated glyph 9 is missed by Tesseract. Require a near-exact match that beats every other digit.
        val templates = digitTemplates ?: return null
        val scores = templates.map { (digit, pattern) -> digit to (region.find(pattern, similarity = 0.7)?.score ?: 0.0) }
        return templateNine(scores)?.takeIf { it in valid }
    }

    /** Current is valid from 1 to 9, next from 2 to 10. Unread or contradictory values remain null. */
    fun read(mode: SkillLevelMode): SkillLevelReading = useSameSnapIn {
        SkillLevelReading(readDigit(loc.currentLevel(mode), 1..9), readDigit(loc.nextLevel(mode), 2..10))
    }
}
