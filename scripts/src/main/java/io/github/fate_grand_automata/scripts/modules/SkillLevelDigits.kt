package io.github.fate_grand_automata.scripts.modules

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

        /** One value, supported by at least two confident readings and contradicted by none. */
        fun consensus(readings: List<OcrText>, valid: IntRange): Int? {
            val values = readings.filter { it.confidence >= MIN_CONFIDENCE }
                .mapNotNull { r -> r.text.trim().takeIf { it.matches(Regex("[0-9]{1,2}")) }?.toIntOrNull() }
            if (values.any { it !in valid }) return null
            return values.distinct().singleOrNull()?.takeIf { values.size >= 2 }
        }
    }

    /** Raw readings, kept for the QA report. */
    fun readings(region: Region): List<OcrText> = region.getPattern("skill_level_digit").use { pattern ->
        PROFILES.map { ocr.detectTextLine(pattern, it) }
    }

    /** Current is valid from 1 to 9 (a dialog never shows 10), next from 2 to 10. Everything else reads as null. */
    fun read(mode: SkillLevelMode): SkillLevelReading = useSameSnapIn {
        SkillLevelReading(
            consensus(readings(loc.currentLevel(mode)), 1..9),
            consensus(readings(loc.nextLevel(mode)), 2..10)
        )
    }
}
