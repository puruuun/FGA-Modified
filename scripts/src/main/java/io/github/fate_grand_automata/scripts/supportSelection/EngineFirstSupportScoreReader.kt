package io.github.fate_grand_automata.scripts.supportSelection

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.diagnostics.DiagnosticJson
import io.github.fate_grand_automata.scripts.diagnostics.IReaderDiagnostics
import io.github.fate_grand_automata.scripts.diagnostics.NoReaderDiagnostics
import io.github.lib_automata.Pattern
import io.github.lib_automata.dagger.ScriptScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Design 0026, D-1: when the engine's answer decides every condition the caller asked for, it is
 * the answer; otherwise null, and the whole row goes to the existing reader. The two readers'
 * fields are never combined, because no sample ever validated such a mix.
 *
 * An ordinary display with a class value is the definite "no Grand score", as the existing
 * reader's [SupportScoreDisplay.Ordinary] is: [SupportScoreSelection.matches] rejects it under a
 * Grand condition either way.
 */
object EngineScoreCompleteness {
    fun answer(score: EngineScore, checkClass: Boolean, checkGrand: Boolean): SupportScores? {
        if (!checkClass && !checkGrand) return null
        val classValue = score.classScore.valueOrNull
        val grandValue = score.grandScore.valueOrNull
        if (checkClass && classValue == null) return null
        if (checkGrand) {
            when (score.display) {
                EngineDisplay.Compound -> if (grandValue == null) return null
                EngineDisplay.Ordinary ->
                    if (classValue == null || score.grandScore != EngineField.Unknown(EngineUnknownReason.NotShown)) return null
                EngineDisplay.Unknown -> return null
            }
        }
        return when (score.display) {
            EngineDisplay.Ordinary -> SupportScores(classValue, null, SupportScoreDisplay.Ordinary)
            EngineDisplay.Compound -> SupportScores(classValue, grandValue, SupportScoreDisplay.Compound)
            EngineDisplay.Unknown -> SupportScores(classValue, grandValue, SupportScoreDisplay.Unknown)
        }
    }
}

/**
 * The support selection's score reader (design 0026, D-3): the Rust engine first, the existing
 * [SupportScoreReader] for every row the engine cannot decide and for every run without an engine.
 * Like the existing reader it reads only within the caller's snapshot.
 *
 * [SupportScoreEngine.unavailableReason] is asked once per row. The engine keeps a failed load,
 * and its close, as its final state and never retries, so after the first row this costs one
 * uncontended lock; the first ask loads the engine, on the script thread.
 *
 * With diagnostics on, each row also gets a record under [DIAGNOSTIC_READER] saying which reader
 * answered and why the engine did not. The existing reader's own record is unchanged and kept in
 * its own folder, so nothing that reads those records sees a new shape.
 */
@ScriptScope
class EngineFirstSupportScoreReader(
    private val api: IFgoAutomataApi,
    private val engine: SupportScoreEngine,
    private val fallback: ISupportScoreReader,
    private val diagnostics: IReaderDiagnostics = NoReaderDiagnostics,
) : ISupportScoreReader {
    @Inject constructor(
        api: IFgoAutomataApi,
        engine: SupportScoreEngine,
        fallback: SupportScoreReader,
        diagnostics: IReaderDiagnostics,
    ) : this(api, engine, fallback as ISupportScoreReader, diagnostics)

    companion object {
        const val DIAGNOSTIC_READER = "support-score-engine"
        const val FIELD_TAG = "support_score_engine_field"

        // Fallback rows get a record here and one from the existing reader, so the two folders
        // stop at the same row and stay comparable.
        const val MAX_DIAGNOSTIC_CANDIDATES = SupportScoreReader.MAX_DIAGNOSTIC_CANDIDATES
    }

    private sealed interface Outcome {
        data class Unavailable(val reason: String) : Outcome
        data class FieldSize(val width: Int, val height: Int) : Outcome
        data class Incomplete(val score: EngineScore) : Outcome
        data class Answered(val score: EngineScore) : Outcome
    }

    private var diagnosticCandidates = 0

    override fun read(bounds: SupportBounds, checkClass: Boolean, checkGrand: Boolean, screen: Int, row: Int): SupportScores {
        if (!checkClass && !checkGrand) return fallback.read(bounds, checkClass, checkGrand, screen, row)
        val record = diagnostics.enabled && diagnosticCandidates < MAX_DIAGNOSTIC_CANDIDATES
        val started = if (record) System.nanoTime() else 0L

        val why = engine.unavailableReason
        if (why != null) {
            val scores = fallback.read(bounds, checkClass, checkGrand, screen, row)
            if (record) record(bounds, checkClass, checkGrand, screen, row, Outcome.Unavailable(why), scores, null, started)
            return scores
        }

        return SupportScoreEngineField.cut(api, bounds, FIELD_TAG).use { field ->
            val outcome: Outcome
            val scores: SupportScores
            if (!SupportScoreEngineField.fits(field)) {
                outcome = Outcome.FieldSize(field.width, field.height)
                scores = fallback.read(bounds, checkClass, checkGrand, screen, row)
            } else {
                val score = engine.read(field)
                val answer = EngineScoreCompleteness.answer(score, checkClass, checkGrand)
                if (answer != null) {
                    outcome = Outcome.Answered(score)
                    scores = answer
                } else {
                    outcome = Outcome.Incomplete(score)
                    scores = fallback.read(bounds, checkClass, checkGrand, screen, row)
                }
            }
            if (record) record(bounds, checkClass, checkGrand, screen, row, outcome, scores, field, started)
            scores
        }
    }

    private fun record(
        bounds: SupportBounds,
        checkClass: Boolean,
        checkGrand: Boolean,
        screen: Int,
        row: Int,
        outcome: Outcome,
        scores: SupportScores,
        field: Pattern?,
        started: Long,
    ) {
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val engineScore = when (outcome) {
            is Outcome.Answered -> outcome.score
            is Outcome.Incomplete -> outcome.score
            else -> null
        }
        val fallbackWhy: Map<String, Any?>? = when (outcome) {
            is Outcome.Answered -> null
            is Outcome.Unavailable -> linkedMapOf("why" to "unavailable", "detail" to outcome.reason)
            is Outcome.FieldSize -> linkedMapOf("why" to "fieldSize", "size" to listOf(outcome.width, outcome.height))
            is Outcome.Incomplete -> linkedMapOf(
                "why" to "incomplete",
                "class" to outcome.score.classScore.describe(),
                "grand" to outcome.score.grandScore.describe(),
                "display" to outcome.score.display.name,
                "detail" to outcome.score.detail,
            )
        }
        val json = DiagnosticJson.write(linkedMapOf(
            "schema" to 1,
            "reader" to "supportScoreEngineFirst",
            "server" to api.prefs.gameServer.simple,
            "time" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date()),
            "run" to diagnostics.runId,
            "screen" to screen,
            "row" to row,
            "rowY1440" to bounds.region.y,
            "checkClass" to checkClass,
            "checkGrand" to checkGrand,
            "answeredBy" to if (outcome is Outcome.Answered) "engine" else "kotlin",
            "fallback" to fallbackWhy,
            "engine" to engineScore?.let {
                linkedMapOf(
                    "class" to it.classScore.describe(),
                    "grand" to it.grandScore.describe(),
                    "display" to it.display.name,
                    "text" to it.text,
                    "confidence" to it.confidence,
                    "minCharConfidence" to it.minCharConfidence,
                    "engineTimeUs" to it.engineTime?.inWholeMicroseconds,
                    "callTimeUs" to it.duration.inWholeMicroseconds,
                    "detail" to it.detail,
                )
            },
            "engineLoadMs" to engine.loadTime?.inWholeMilliseconds,
            "result" to linkedMapOf(
                "class" to scores.classScore,
                "grand" to scores.grandScore,
                "display" to scores.display.name,
            ),
            "matched" to SupportScoreSelection.matches(scores, checkClass, checkGrand),
            "elapsedMs" to elapsedMs,
        ))
        // The engine's own crop goes with the records it answered; a row the existing reader
        // answered already has its crops in that reader's record.
        val images = if (outcome is Outcome.Answered && field != null) mapOf("" to field) else emptyMap()
        diagnostics.record(DIAGNOSTIC_READER, "$screen-$row", images, json)
        ++diagnosticCandidates
    }

    private fun EngineField.describe(): Any = when (this) {
        is EngineField.Value -> value
        is EngineField.Unknown -> reason.name
    }
}
