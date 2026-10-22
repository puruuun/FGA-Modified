package io.github.fate_grand_automata.scripts.supportSelection

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.diagnostics.DiagnosticJson
import io.github.fate_grand_automata.scripts.diagnostics.IReaderDiagnostics
import io.github.fate_grand_automata.scripts.diagnostics.NoReaderDiagnostics
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.locations.SupportScoreLayout
import io.github.lib_automata.OcrService
import io.github.lib_automata.OcrText
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.github.lib_automata.Size
import io.github.lib_automata.TextLineOcrOptions
import io.github.lib_automata.dagger.ScriptScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.max

enum class SupportScoreDisplay { Ordinary, Compound, Unknown }

/** Keep the confirmed display format separate from independently read numeric fields. */
data class SupportScores(
    val classScore: Int? = null,
    val grandScore: Int? = null,
    val display: SupportScoreDisplay = if (grandScore != null) SupportScoreDisplay.Compound else SupportScoreDisplay.Unknown
)

@ScriptScope
class SupportScoreReader @Inject constructor(
    api: IFgoAutomataApi,
    private val ocr: OcrService,
    private val diagnostics: IReaderDiagnostics = NoReaderDiagnostics
) : IFgoAutomataApi by api {
    companion object {
        const val MIN_OCR_CONFIDENCE = 75
        const val MIN_CONFIRM_CONFIDENCE = 85
        const val MIN_AGREEMENT = 2
        const val OCR_SCALE = 4
        const val OCR_LARGE_SCALE = 6
        const val OCR_THRESHOLD = 100.0 / 255.0
        const val OCR_BRIGHT_THRESHOLD = 110.0 / 255.0
        const val OCR_DARK_THRESHOLD = 80.0 / 255.0
        const val OCR_PADDING = 12
        // JP screenshot field crops: true matches >= .96; negatives <= .85.
        // Templates include the slash, so an ordinary +16 or +62 cannot supply a compound field.
        const val COMPACT_FIELD_SIMILARITY = 0.94
        // Grand "/+16" templates (and their final-digit check) use their own floor. A BlueStacks
        // 2340x1080 frame scales to 720p slightly differently from the 1920x1080 frames the older
        // templates were cut from: the same +59/+16 field scored 0.9397 whole and 0.9248 on the last
        // digit against the 2340 template. Measured JP/KR negatives never exceed 0.67 whole-field.
        const val GRAND_FIELD_SIMILARITY = 0.90
        val COMPACT_CLASS_TEMPLATES = listOf(
            Images.SupportCompactClass62A, Images.SupportCompactClass62B, Images.SupportCompactClass62C
        )
        val GRAND_TEMPLATES = listOf(
            Images.SupportGrand16A, Images.SupportGrand16B, Images.SupportGrand16C, Images.SupportGrand16D,
            Images.SupportGrand16LargeA, Images.SupportGrand16LargeB, Images.SupportGrand16LargeC,
            Images.SupportGrand16LargeD
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
        // KR blue score panels lose their outlined digits at the primary thresholds.
        // Retry only inconclusive fields, keeping all primary conflict evidence.
        val OCR_DARK_PROFILES = listOf(OCR_SCALE, OCR_LARGE_SCALE).map {
            OCR_OPTIONS.copy(scale = it, threshold = OCR_DARK_THRESHOLD)
        }
        // At 720p OCR can omit the small plus sign. The slash and both complete
        // numeric fields are mandatory; their order, not the plus glyph, identifies the score.
        private val compoundScore = Regex("""\+?(-?\d+)/\+?(-?\d+)""")

        private val ordinaryScore = Regex("""\+?(-?\d+)""")

        // Anything may precede the slash (a garbled Class field); only the Grand digits matter here.
        private val grandTail = Regex(""".*/\+?(-?\d+)""")

        /**
         * OCR fallback for a Grand +16 whose Class half is unreadable. Every readable result must end in
         * exactly "/16" (with or without the plus), and at least [MIN_AGREEMENT] of them must be strong.
         */
        fun grandTail16(results: List<OcrText>): Boolean {
            val tails = results.filter { it.confidence >= MIN_OCR_CONFIDENCE }.mapNotNull { result ->
                grandTail.matchEntire(result.text.trim().replace(Regex("""\s+"""), ""))
                    ?.let { (it.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE) to result.confidence }
            }
            return tails.isNotEmpty() && tails.all { it.first == 16 } &&
                tails.count { it.second >= MIN_CONFIRM_CONFIDENCE } >= MIN_AGREEMENT
        }

        /** Diagnostics stop recording after this many candidates in one run. */
        const val MAX_DIAGNOSTIC_CANDIDATES = 500

        /** Folder under the diagnostics root. */
        const val DIAGNOSTIC_READER = "support-score"

        /** Widening of the score region on each side, in 1440p script pixels. */
        const val DIAGNOSTIC_MARGIN = 20

        private const val DIAGNOSTIC_SCORE_TAG = "support_score_diagnostic"
        private const val DIAGNOSTIC_WIDE_TAG = "support_score_diagnostic_wide"

        private fun rawParse(result: OcrText): SupportScores? {
            if (result.confidence < MIN_OCR_CONFIDENCE) return null
            val text = result.text.trim().replace(Regex("""\s+"""), "")
            compoundScore.matchEntire(text)?.let {
                return SupportScores((it.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE), (it.groupValues[2].toIntOrNull() ?: Int.MAX_VALUE), SupportScoreDisplay.Compound)
            }
            return ordinaryScore.matchEntire(text)?.let {
                SupportScores(classScore = (it.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE), display = SupportScoreDisplay.Ordinary)
            }
        }

        fun parse(result: OcrText): SupportScores? = rawParse(result)?.let {
            it.copy(classScore = it.classScore?.takeIf { value -> value in 0..62 },
                grandScore = it.grandScore?.takeIf { value -> value in 0..16 })
        }

        private fun invalidClass(results: List<OcrText>) = results.any {
            rawParse(it)?.classScore?.let { value -> value !in 0..62 } == true
        }

        private fun invalidGrand(results: List<OcrText>) = results.any {
            it.confidence >= MIN_OCR_CONFIDENCE && grandTail.matchEntire(it.text.filterNot(Char::isWhitespace))
                ?.groupValues?.get(1)?.let { text -> text.toIntOrNull() ?: Int.MAX_VALUE }?.let { value -> value !in 0..16 } == true
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
            val classScore = if (invalidClass(results)) null else field { it.classScore }
            val grandScore = if (invalidGrand(results)) null else field { it.grandScore }
            if (classScore == null && grandScore == null) return null
            return SupportScores(classScore, grandScore,
                if (compound) SupportScoreDisplay.Compound else SupportScoreDisplay.Ordinary)
        }
    }

    private var diagnosticCandidates = 0
    private var diagnosticLimitLogged = false

    /**
     * Evidence for the diagnostic record. Only allocated while diagnostics are on; the decisions in
     * [readScores] never read it.
     */
    private class ReadEvidence {
        val ocr = mutableListOf<Map<String, Any?>>()
        val templates = linkedMapOf<String, Boolean>()
    }

    /** Read-only within the caller's candidate snapshot, before any Append display switch. */
    fun read(bounds: SupportBounds, checkClass: Boolean, checkGrand: Boolean, screen: Int = -1, row: Int = -1): SupportScores {
        if (!checkClass && !checkGrand) return SupportScores()
        if (!diagnostics.enabled) return readScores(bounds, checkClass, checkGrand, null)
        if (diagnosticCandidates >= MAX_DIAGNOSTIC_CANDIDATES) {
            if (!diagnosticLimitLogged) {
                diagnosticLimitLogged = true
                messages.log(ScriptLog.SupportDiagnosticsLimit(MAX_DIAGNOSTIC_CANDIDATES))
            }
            return readScores(bounds, checkClass, checkGrand, null)
        }

        val evidence = ReadEvidence()
        val started = System.nanoTime()
        val scores = readScores(bounds, checkClass, checkGrand, evidence)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        // Both images come from the same snapshot as the reading above, so they show what it saw.
        val region = SupportScoreLayout.numberRegion(bounds.region)
        region.getPattern(DIAGNOSTIC_SCORE_TAG).use { score ->
            region.widened(DIAGNOSTIC_MARGIN).getPattern(DIAGNOSTIC_WIDE_TAG).use { wide ->
                val json = DiagnosticJson.write(linkedMapOf(
                    "schema" to 1,
                    "reader" to "supportScore",
                    "server" to prefs.gameServer.simple,
                    "time" to timestamp(),
                    "run" to diagnostics.runId,
                    "screen" to screen,
                    "row" to row,
                    "rowY1440" to bounds.region.y,
                    // Score crop size at 720p, so the frame scale can be checked from the record.
                    "cropSize" to listOf(score.width, score.height),
                    // The reader never runs inside useColor, so its crops are always grayscale.
                    "colorMode" to "gray",
                    "checkClass" to checkClass,
                    "checkGrand" to checkGrand,
                    "ocr" to evidence.ocr,
                    "templates" to evidence.templates,
                    "result" to linkedMapOf(
                        "class" to scores.classScore,
                        "grand" to scores.grandScore,
                        "display" to scores.display.name
                    ),
                    "matched" to SupportScoreSelection.matches(scores, checkClass, checkGrand),
                    "elapsedMs" to elapsedMs
                ))
                diagnostics.record(
                    DIAGNOSTIC_READER,
                    "$screen-$row",
                    // "" and "-wide" are file suffixes; see IReaderDiagnostics.record.
                    mapOf("" to score, "-wide" to wide),
                    json
                )
            }
        }
        ++diagnosticCandidates
        return scores
    }

    private fun readScores(
        bounds: SupportBounds,
        checkClass: Boolean,
        checkGrand: Boolean,
        evidence: ReadEvidence?
    ): SupportScores {
        val region = SupportScoreLayout.numberRegion(bounds.region)
        // Preserve the validated ordinary +62 matcher; compact +62/+16 uses a different font.
        val legacyClass62 = evidence.template(
            Images.SupportScore62,
            region.exists(images[Images.SupportScore62], similarity = SupportScoreSelection.SCORE_SIMILARITY)
        )
        var blockedClass = false
        var blockedGrand = false
        var inconsistentGrand = false
        var clipped = false
        fun inspect(pattern: Pattern, options: TextLineOcrOptions, result: OcrText) {
            pattern.resize(Size(pattern.width * options.scale, pattern.height * options.scale)).use { scaled ->
                scaled.threshold(options.threshold).use { binary ->
                    binary.fillText().use { filled ->
                        val ink = filled.grayPixels()?.let {
                            SupportScoreInk(it, filled.width, filled.height, binary.grayPixels())
                        }
                        clipped = clipped || ink == null || ink.clipped(options.scale)
                        if ('/' in result.text) {
                            val tail = grandTail.matchEntire(result.text.filterNot(Char::isWhitespace))
                            if (ink == null ||
                                (result.confidence >= MIN_OCR_CONFIDENCE && tail != null &&
                                    !ink.grandDigitsAgree(tail.groupValues[1].length))) inconsistentGrand = true
                        }
                    }
                }
            }
        }

        val results = region.getPattern("support_class_grand_scores").use { pattern ->
            if (pattern.width * 2 < region.width - 1 || pattern.height * 2 < region.height - 1) emptyList()
            else {
                val primary = OCR_PROFILES.mapIndexed { profile, options ->
                    detect(pattern, options, "primary", profile, evidence).also { inspect(pattern, options, it) }
                }
                val known = confirmed(primary)
                if (known != null && (known.display == SupportScoreDisplay.Ordinary ||
                        ((!checkClass || known.classScore != null) && (!checkGrand || known.grandScore != null)))) primary
                else if (prefs.gameServer is GameServer.Kr) primary + OCR_DARK_PROFILES.mapIndexed { profile, options ->
                    detect(pattern, options, "dark", profile, evidence).also { inspect(pattern, options, it) }
                }
                else primary
            }
        }
        // A clipped number is not evidence that Grand is absent.
        if (results.isEmpty()) return SupportScores()
        val recognized = confirmed(results)?.let {
            if (inconsistentGrand) it.copy(grandScore = null) else it
        }
        // JP shrinks the entire pair for two-digit Class scores. Reading both tiny fields
        // as one OCR word can lose the slash/plus or produce low confidence even on +56/+16.
        // Independently recover only a requested maximum, with a delimiter-bearing template.
        // Reconcile both sources before returning; neither may override contradictory evidence.
        var differentGrandDigit = false
        fun matches(templates: List<Images>, verifyGrandDigit: Boolean = false) = templates.any { image ->
            val template = images[image]
            val hit = if (!verifyGrandDigit) region.exists(template, similarity = COMPACT_FIELD_SIMILARITY)
            else {
                val candidates = region.findAll(template, similarity = GRAND_FIELD_SIMILARITY)
                if (candidates.isEmpty()) false else {
                    // The common /+1 prefix must not hide a different final digit.
                    val pixels = SupportScoreLayout.grandLastDigit(template.width, template.height)
                    template.crop(pixels).use { digit ->
                        candidates.any { match ->
                            SupportScoreLayout.matchedPixels(match.region, template.width, template.height, pixels)
                                .exists(digit, similarity = GRAND_FIELD_SIMILARITY).also {
                                    if (!it) differentGrandDigit = true
                                }
                        }
                    }
                }
            }
            evidence.template(image, hit)
        }
        val class62 = checkClass && matches(COMPACT_CLASS_TEMPLATES)
        val grandTemplate16 = checkGrand && matches(GRAND_TEMPLATES, verifyGrandDigit = true)
        // A width mismatch discards OCR (including the tail fallback), while a complete,
        // final-digit-verified template is independent evidence. Explicit OCR contradictions
        // and range/clipping vetoes below still make the field unknown.
        val grand16 = grandTemplate16 || (checkGrand && !inconsistentGrand && grandTail16(results))
        val compound = class62 || grand16 || results.any { '/' in it.text }
        val classTemplate = if (compound) class62 else legacyClass62
        val raw = results.mapNotNull(::rawParse)
        blockedClass = blockedClass || (!compound && clipped) || invalidClass(results) ||
            (classTemplate && raw.any { it.classScore != null && it.classScore != 62 })
        blockedGrand = blockedGrand || (compound && clipped) || invalidGrand(results) ||
            (grandTemplate16 && results.any {
                it.confidence >= MIN_OCR_CONFIDENCE && grandTail.matchEntire(it.text.filterNot(Char::isWhitespace))
                    ?.groupValues?.get(1)?.let { text -> text.toIntOrNull() ?: Int.MAX_VALUE }?.let { value -> value != 16 } == true
            }) || (!grandTemplate16 && differentGrandDigit && recognized?.grandScore == 16)
        return SupportScores(
            if (blockedClass) null else recognized?.classScore ?: if (classTemplate) 62 else null,
            if (blockedGrand) null else recognized?.grandScore ?: if (grand16) 16 else null,
            if (compound) SupportScoreDisplay.Compound else recognized?.display ?:
                if (legacyClass62) SupportScoreDisplay.Ordinary else SupportScoreDisplay.Unknown
        )
    }

    private fun detect(
        pattern: Pattern,
        options: TextLineOcrOptions,
        stage: String,
        profile: Int,
        evidence: ReadEvidence?
    ): OcrText {
        val result = ocr.detectTextLine(pattern, options)
        evidence?.ocr?.add(mapOf(
            "stage" to stage,
            "profile" to profile,
            "text" to result.text,
            "confidence" to result.confidence
        ))
        return result
    }

    private fun ReadEvidence?.template(image: Images, hit: Boolean): Boolean {
        this?.templates?.set(image.name, hit)
        return hit
    }

    private fun Region.widened(margin: Int) = Region(
        max(0, x - margin),
        max(0, y - margin),
        width + 2 * margin,
        height + 2 * margin
    )

    private fun timestamp() =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())
}
