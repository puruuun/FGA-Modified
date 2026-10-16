package io.github.fate_grand_automata.scripts.supportSelection

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.locations.SupportScoreLayout
import io.github.lib_automata.OcrService
import io.github.lib_automata.OcrText
import io.github.lib_automata.TextLineOcrOptions
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

enum class SupportScoreDisplay { Ordinary, Compound, Unknown }

/** Format must be confirmed before an absent Grand field can be ignored. */
data class SupportScores(
    val classScore: Int? = null,
    val grandScore: Int? = null,
    val display: SupportScoreDisplay = if (grandScore != null) SupportScoreDisplay.Compound else SupportScoreDisplay.Unknown
)

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
        // JP screenshot field crops: true matches >= .96; negatives <= .85.
        // Templates include the slash, so an ordinary +16 or +62 cannot supply a compound field.
        const val COMPACT_FIELD_SIMILARITY = 0.94
        val COMPACT_CLASS_TEMPLATES = listOf(
            Images.SupportCompactClass62A, Images.SupportCompactClass62B, Images.SupportCompactClass62C
        )
        val GRAND_TEMPLATES = listOf(
            Images.SupportGrand16A, Images.SupportGrand16B, Images.SupportGrand16C,
            Images.SupportGrand16LargeA, Images.SupportGrand16LargeB, Images.SupportGrand16LargeC
        )
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

        private val ordinaryScore = Regex("""\+?(\d{1,3})""")

        fun parse(result: OcrText): SupportScores? {
            if (result.confidence < MIN_OCR_CONFIDENCE) return null
            val text = result.text.trim().replace(Regex("""\s+"""), "")
            compoundScore.matchEntire(text)?.let {
                return SupportScores(it.groupValues[1].toInt(), it.groupValues[2].toInt())
            }
            return ordinaryScore.matchEntire(text)?.let {
                SupportScores(classScore = it.groupValues[1].toInt(), display = SupportScoreDisplay.Ordinary)
            }
        }

        fun confirmed(results: List<OcrText>): SupportScores? {
            val parsed = results.mapNotNull { result -> parse(result)?.let { it to result.confidence } }
            val compound = results.any { '/' in it.text }
            val readings = parsed.filter { (scores, _) ->
                scores.display == if (compound) SupportScoreDisplay.Compound else SupportScoreDisplay.Ordinary
            }
            fun field(value: (SupportScores) -> Int?): Int? {
                val detected = readings.mapNotNull { value(it.first) }.distinct().singleOrNull() ?: return null
                return detected.takeIf {
                    readings.count { it.second >= MIN_CONFIRM_CONFIDENCE && value(it.first) == detected } >= MIN_AGREEMENT
                }
            }
            // A disabled/uncertain Class field must not veto a confidently read Grand field,
            // and vice versa. Only the selected conditions are combined by the selection policy.
            val classScore = field { it.classScore }
            val grandScore = field { it.grandScore }
            if (classScore == null && grandScore == null) return null
            return SupportScores(classScore, grandScore,
                if (compound) SupportScoreDisplay.Compound else SupportScoreDisplay.Ordinary)
        }
    }

    /** Read-only within the caller's candidate snapshot, before any Append display switch. */
    fun read(bounds: SupportBounds, checkClass: Boolean, checkGrand: Boolean): SupportScores {
        if (!checkClass && !checkGrand) return SupportScores()
        val region = SupportScoreLayout.numberRegion(bounds.region)
        // Preserve the validated ordinary +62 matcher; compact +62/+16 uses a different font.
        val legacyClass62 = region.exists(
            images[Images.SupportScore62], similarity = SupportScoreSelection.SCORE_SIMILARITY
        )
        if (legacyClass62 && !checkGrand) return SupportScores(classScore = 62, display = SupportScoreDisplay.Ordinary)

        val results = region.getPattern("support_class_grand_scores").use { pattern ->
            if (pattern.width * 2 < region.width - 1 || pattern.height * 2 < region.height - 1) emptyList()
            else OCR_PROFILES.map { ocr.detectTextLine(pattern, it) }
        }
        // A clipped number is not evidence that Grand is absent.
        if (results.isEmpty()) return SupportScores()
        val recognized = confirmed(results)
        if (recognized != null && (recognized.display == SupportScoreDisplay.Ordinary ||
                ((!checkClass || recognized.classScore != null) && (!checkGrand || recognized.grandScore != null)))) {
            return recognized
        }

        // JP shrinks the entire pair for two-digit Class scores. Reading both tiny fields
        // as one OCR word can lose the slash/plus or produce low confidence even on +56/+16.
        // Independently recover only a requested maximum, with a delimiter-bearing template.
        // Keep any already confirmed numeric field, including a confirmed nonmaximum.
        fun matches(templates: List<Images>, verifyGrandDigit: Boolean = false) = templates.any { image ->
            val template = images[image]
            if (!verifyGrandDigit) region.exists(template, similarity = COMPACT_FIELD_SIMILARITY)
            else {
                val candidates = region.findAll(template, similarity = COMPACT_FIELD_SIMILARITY)
                if (candidates.isEmpty()) false else {
                    // The common /+1 prefix must not hide a different final digit.
                    val pixels = SupportScoreLayout.grandLastDigit(template.width, template.height)
                    template.crop(pixels).use { digit ->
                        candidates.any { match ->
                            SupportScoreLayout.matchedPixels(match.region, template.width, template.height, pixels)
                                .exists(digit, similarity = COMPACT_FIELD_SIMILARITY)
                        }
                    }
                }
            }
        }
        val class62 = checkClass && matches(COMPACT_CLASS_TEMPLATES)
        val grand16 = checkGrand && matches(GRAND_TEMPLATES, verifyGrandDigit = true)
        if (class62 || grand16) return SupportScores(
            recognized?.classScore ?: if (class62) 62 else null,
            recognized?.grandScore ?: if (grand16) 16 else null,
            SupportScoreDisplay.Compound
        )
        recognized?.let { return it }
        // A visible slash with an uncertain pair must not fall back to finding +62 on the
        // Grand side. An ordinary score never supplies a Grand score implicitly.
        return when {
            results.any { '/' in it.text } -> SupportScores(display = SupportScoreDisplay.Compound)
            legacyClass62 -> SupportScores(classScore = 62, display = SupportScoreDisplay.Ordinary)
            else -> SupportScores()
        }
    }
}
