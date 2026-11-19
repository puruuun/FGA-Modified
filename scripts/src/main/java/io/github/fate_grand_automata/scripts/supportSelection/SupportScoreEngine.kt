package io.github.fate_grand_automata.scripts.supportSelection

import io.github.lib_automata.Pattern
import kotlin.time.Duration

/**
 * An optional second reader of one support score field (issue #568): the Rust engine in the
 * owner's Rust test APK, nothing anywhere else. Like [ISupportScoreReader] it says what it read and
 * keeps its thresholds to itself (design 0025, D-3).
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
