package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.supportSelection.EngineDisplay
import io.github.fate_grand_automata.scripts.supportSelection.EngineField
import io.github.fate_grand_automata.scripts.supportSelection.EngineScore
import io.github.fate_grand_automata.scripts.supportSelection.EngineUnknownReason
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreEngine
import io.github.lib_automata.Pattern
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * Answers in the shapes the real engine sends: fga-sensing's `decide` with `RangePolicy::Joint`
 * through ScoreWire. An ordinary read has a class value and a Grand `NotShown`; a compound read has
 * both values; every rule that refuses a read (size, blank, confidence, format, a joint range
 * failure, clipping) makes both fields unknown with the same reason and the display unknown; an
 * ordinary number out of range is class `Range` with Grand `NotShown`; the binding's own failures
 * are `EngineError` or `Unavailable` on both fields with a detail.
 */
object EngineAnswers {
    fun ordinary(classScore: Int) = EngineScore(
        EngineField.Value(classScore), EngineField.Unknown(EngineUnknownReason.NotShown), EngineDisplay.Ordinary,
        "+$classScore", 0.99f, 0.97f, 31.milliseconds, 25_000.microseconds,
    )

    fun compound(classScore: Int, grandScore: Int) = EngineScore(
        EngineField.Value(classScore), EngineField.Value(grandScore), EngineDisplay.Compound,
        "+$classScore/+$grandScore", 0.98f, 0.95f, 33.milliseconds, 27_000.microseconds,
    )

    /** One of the reader's refusals: Blank, LowConfidence, Format, Range (joint), Clipped, CropSize. */
    fun refused(reason: EngineUnknownReason, text: String = "+6") = EngineScore(
        EngineField.Unknown(reason), EngineField.Unknown(reason), EngineDisplay.Unknown,
        if (reason == EngineUnknownReason.Blank || reason == EngineUnknownReason.CropSize) "" else text,
        if (reason == EngineUnknownReason.CropSize) null else 0.5f,
        if (reason == EngineUnknownReason.CropSize) null else 0.4f,
        30.milliseconds, 24_000.microseconds,
    )

    val REFUSALS = listOf(
        EngineUnknownReason.Blank, EngineUnknownReason.LowConfidence, EngineUnknownReason.Format,
        EngineUnknownReason.Range, EngineUnknownReason.Clipped, EngineUnknownReason.CropSize,
    )

    fun ordinaryOutOfRange() = EngineScore(
        EngineField.Unknown(EngineUnknownReason.Range), EngineField.Unknown(EngineUnknownReason.NotShown),
        EngineDisplay.Unknown, "+63", 0.99f, 0.97f, 31.milliseconds, 25_000.microseconds,
    )

    fun engineError(detail: String = "malformed engine reply: magic 0") =
        EngineScore.unknown(EngineUnknownReason.EngineError, detail, 1.milliseconds)

    fun unavailable(detail: String = "the engine was closed at the end of the run") =
        EngineScore.unknown(EngineUnknownReason.Unavailable, detail)
}

/** An engine whose availability and answers a test scripts; it counts what the reader asks. */
class FakeSupportScoreEngine(
    var reason: String? = null,
    override val loadTime: Duration? = 120.milliseconds,
    private val answer: (Pattern) -> EngineScore = { EngineAnswers.engineError() },
) : SupportScoreEngine {
    var reasonAsks = 0
    val reads = mutableListOf<Pattern>()

    override val unavailableReason: String?
        get() {
            reasonAsks++
            return reason
        }

    override fun read(field: Pattern): EngineScore {
        reads += field
        // The real engine answers a read after a failed load or a close the same way.
        reason?.let { return EngineAnswers.unavailable(it) }
        return answer(field)
    }
}
