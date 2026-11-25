package io.github.fate_grand_automata.scripts.supportSelection

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.locations.SupportScoreLayout
import io.github.fate_grand_automata.scripts.locations.SupportScreenLocations
import io.github.lib_automata.Pattern
import kotlin.time.Duration

/**
 * The Rust reader of one support score field (issue #568). The support selection asks it first
 * through [EngineFirstSupportScoreReader] and falls back to [SupportScoreReader] (design 0026, D-1),
 * and the owner's support score scan compares the two. Builds without the engine bind
 * [NoSupportScoreEngine], so they read with [SupportScoreReader] alone. Like [ISupportScoreReader]
 * it says what it read and keeps its thresholds to itself (design 0025, D-3).
 *
 * None of its members throws, short of errors that leave the virtual machine unusable: an engine
 * that is missing, failed to load or answered something unexpected reads every field as unknown
 * with a reason. The first [available], [unavailableReason] or [read] loads the engine, so none of
 * them may run on the main thread. [close] frees it at the end of a run without waiting for a
 * load or read in progress; afterwards every field is unknown ([EngineUnknownReason.Unavailable]).
 */
interface SupportScoreEngine : AutoCloseable {
    /** Why the engine cannot read, or null when it can. Loads the engine the first time. */
    val unavailableReason: String?

    val available: Boolean get() = unavailableReason == null

    /** How long the first load took, null before it ran. For the log. */
    val loadTime: Duration?

    /** [field] is the score region of one support row at 720p, a 60x30 grayscale image. */
    fun read(field: Pattern): EngineScore

    override fun close() = Unit
}

/**
 * The engine's input, cut the same way by the selection and the score scan: the row's score number
 * region from the caller's current snapshot. The engine was measured on exactly this crop, 60x30
 * grayscale at 720p. Whether a field goes to the engine is decided here, in [refusal], for both:
 * the selection gives the engine no field that [refusal] refuses; the scan gives it every crop of
 * another size (a row cut by the screen edge) and logs the engine's `CropSize` refusal, and does
 * not give it a field under the bottom band.
 */
object SupportScoreEngineField {
    const val WIDTH = 60
    const val HEIGHT = 30

    /**
     * The field's rows 0 until this hold every row the engine's recogniser reads: it leaves out the
     * bottom 4 of the 30 (`RowTrim::MEASURED` in native/fga-sensing, issue #639). The value is
     * listed in native/fga-sensing/src/readers/support_score/row-trim.txt, which a Rust and a
     * Kotlin test both read, so neither side can change alone.
     */
    const val RECOGNISED_ROWS_END = 26

    fun cut(api: IFgoAutomataApi, bounds: SupportBounds, tag: String): Pattern = with(api) {
        SupportScoreLayout.numberRegion(bounds.region).getPattern(tag)
    }

    fun fits(field: Pattern) = field.width == WIDTH && field.height == HEIGHT

    /** Script y just below the last row the recogniser reads of this row's field. */
    fun recognisedEndY(bounds: SupportBounds): Int =
        bounds.region.y + SupportScoreLayout.SCORE_Y + RECOGNISED_ROWS_END * (SupportScoreLayout.SCORE_HEIGHT / HEIGHT)

    /**
     * Issue #657: the band at the bottom of the support screen can cover the lower rows of a field
     * that is still a whole 60x30 crop, and the engine's own pixel rule (`clipping.rs`) only finds
     * a flat band, not a textured one. So a field is refused by its position when the band, at its
     * lowest measured top, can reach a row the recogniser reads: bounds.y above 1056. It is not
     * refused for touching the band at all (bounds.y above 1048), because the third row of every
     * first screen of a list lies at 1050 to 1066 and would then always go to the existing reader.
     * A refused row is read by the existing reader like any row the engine does not answer; the
     * selection reads it again with the engine once a swipe has moved it up (not in a list of three
     * supports or fewer, or with no swipes).
     */
    fun coveredByBottomBand(bounds: SupportBounds): Boolean =
        recognisedEndY(bounds) > SupportScreenLocations.BOTTOM_BAND_TOP_Y

    /** Why the engine is not given [field] of [bounds], or null when it is. The size comes first. */
    fun refusal(bounds: SupportBounds, field: Pattern): EngineFieldRefusal? = when {
        !fits(field) -> EngineFieldRefusal.CropSize
        coveredByBottomBand(bounds) -> EngineFieldRefusal.BottomBand
        else -> null
    }
}

/** Why the caller does not give a field to the engine (the engine's own refusals are [EngineUnknownReason]). */
enum class EngineFieldRefusal {
    /** Not 60x30: the row leaves the game area. */
    CropSize,

    /** 60x30, but the bottom band can cover a row the recogniser reads ([SupportScoreEngineField.coveredByBottomBand]). */
    BottomBand,
}

/** The binding of every build that has no engine. */
object NoSupportScoreEngine : SupportScoreEngine {
    private const val REASON = "this build has no support-score engine"

    override val unavailableReason = REASON
    override val loadTime: Duration? = null

    override fun read(field: Pattern) = EngineScore.unknown(EngineUnknownReason.Unavailable, REASON)
}

/** Why a field is unknown. The first seven are the reader's own; the last two are the binding's. */
enum class EngineUnknownReason {
    Blank,
    LowConfidence,
    Format,
    Range,
    Clipped,
    NotShown,
    CropSize,

    /** The engine answered something malformed, or failed while reading. */
    EngineError,

    /** No engine: not in this build, not packaged, its model missing, or it did not load. */
    Unavailable,
}

/** A value or the reason there is none; there is no number that stands for "unknown". */
sealed interface EngineField {
    data class Value(val value: Int) : EngineField
    data class Unknown(val reason: EngineUnknownReason) : EngineField

    val valueOrNull: Int? get() = (this as? Value)?.value
}

enum class EngineDisplay { Ordinary, Compound, Unknown }

/**
 * One read. [text], the confidences, [engineTime] and [detail] are for the log only. [duration]
 * is the whole call as the caller saw it; [engineTime] is the engine's own reading time, null when
 * the engine did not read.
 */
data class EngineScore(
    val classScore: EngineField,
    val grandScore: EngineField,
    val display: EngineDisplay,
    val text: String,
    val confidence: Float?,
    val minCharConfidence: Float?,
    val duration: Duration,
    val engineTime: Duration? = null,
    /** For an [EngineUnknownReason.EngineError] or [EngineUnknownReason.Unavailable] field: what went wrong. */
    val detail: String? = null,
    /**
     * The engine read a misread sign in [text] as `+` (`+62/-16` as `+62/+16`; QA 0025, section
     * 4.19). For the log only: the fields mean the same either way.
     */
    val signCorrected: Boolean = false,
) {
    companion object {
        fun unknown(reason: EngineUnknownReason, detail: String?, duration: Duration = Duration.ZERO) = EngineScore(
            classScore = EngineField.Unknown(reason),
            grandScore = EngineField.Unknown(reason),
            display = EngineDisplay.Unknown,
            text = "",
            confidence = null,
            minCharConfidence = null,
            duration = duration,
            detail = detail,
        )
    }
}
