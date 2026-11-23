package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.scripts.supportSelection.EngineDisplay
import io.github.fate_grand_automata.scripts.supportSelection.EngineField
import io.github.fate_grand_automata.scripts.supportSelection.EngineScore
import io.github.fate_grand_automata.scripts.supportSelection.EngineScoreCompleteness
import io.github.fate_grand_automata.scripts.supportSelection.EngineUnknownReason
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreDisplay
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreSelection
import io.github.fate_grand_automata.scripts.supportSelection.SupportScores
import kotlin.test.Test
import kotlin.time.Duration

/** Design 0026, D-1. */
class EngineScoreCompletenessTest {
    private enum class Conditions(val checkClass: Boolean, val checkGrand: Boolean) {
        ClassOnly(true, false), GrandOnly(false, true), Both(true, true)
    }

    private data class Row(val name: String, val score: EngineScore, val used: Set<Conditions>, val scores: SupportScores?)

    private val all = Conditions.entries.toSet()

    private val realAnswers = buildList {
        add(Row("ordinary +62", EngineAnswers.ordinary(62), all, SupportScores(62, null, SupportScoreDisplay.Ordinary)))
        add(Row("ordinary +40", EngineAnswers.ordinary(40), all, SupportScores(40, null, SupportScoreDisplay.Ordinary)))
        add(Row("compound +62/+16", EngineAnswers.compound(62, 16), all, SupportScores(62, 16, SupportScoreDisplay.Compound)))
        add(Row("compound +62/+0", EngineAnswers.compound(62, 0), all, SupportScores(62, 0, SupportScoreDisplay.Compound)))
        add(Row("compound +40/+16", EngineAnswers.compound(40, 16), all, SupportScores(40, 16, SupportScoreDisplay.Compound)))
        for (reason in EngineAnswers.REFUSALS) add(Row("refused $reason", EngineAnswers.refused(reason), emptySet(), null))
        add(Row("ordinary out of range", EngineAnswers.ordinaryOutOfRange(), emptySet(), null))
        add(Row("engine error", EngineAnswers.engineError(), emptySet(), null))
        add(Row("unavailable", EngineAnswers.unavailable(), emptySet(), null))
    }

    // ScoreWire decodes each field on its own, so these reach the reader if the engine's range
    // policy ever changes; the current RangePolicy::Joint never sends them.
    private fun shape(classScore: EngineField, grandScore: EngineField, display: EngineDisplay) =
        EngineScore(classScore, grandScore, display, "", null, null, Duration.ZERO)

    private val value62 = EngineField.Value(62)
    private val value16 = EngineField.Value(16)
    private val range = EngineField.Unknown(EngineUnknownReason.Range)
    private val notShown = EngineField.Unknown(EngineUnknownReason.NotShown)

    private val wireOnly = listOf(
        Row("compound, class unknown, Grand +16", shape(range, value16, EngineDisplay.Compound),
            setOf(Conditions.GrandOnly), SupportScores(null, 16, SupportScoreDisplay.Compound)),
        Row("compound, class +62, Grand unknown", shape(value62, range, EngineDisplay.Compound),
            setOf(Conditions.ClassOnly), SupportScores(62, null, SupportScoreDisplay.Compound)),
        Row("display unknown, class +62", shape(value62, range, EngineDisplay.Unknown),
            setOf(Conditions.ClassOnly), SupportScores(62, null, SupportScoreDisplay.Unknown)),
        Row("ordinary, class unknown", shape(range, notShown, EngineDisplay.Ordinary), emptySet(), null),
        Row("ordinary, class +62, Grand not 'not shown'", shape(value62, range, EngineDisplay.Ordinary),
            setOf(Conditions.ClassOnly), SupportScores(62, null, SupportScoreDisplay.Ordinary)),
        // Each row below fails when the rule line named in its comment is taken out.
        // Unknown display -> return null.
        Row("display unknown, class +62, Grand not shown", shape(value62, notShown, EngineDisplay.Unknown),
            setOf(Conditions.ClassOnly), SupportScores(62, null, SupportScoreDisplay.Unknown)),
        // Ordinary: Grand must be NotShown.
        Row("ordinary, class +62, Grand +16", shape(value62, value16, EngineDisplay.Ordinary),
            setOf(Conditions.ClassOnly), SupportScores(62, null, SupportScoreDisplay.Ordinary)),
        // Ordinary: either clause refuses it; it fails only when both are taken out.
        Row("ordinary, class unknown, Grand +16", shape(range, value16, EngineDisplay.Ordinary), emptySet(), null),
        // Unknown display -> return null.
        Row("display unknown, class +62, Grand +16", shape(value62, value16, EngineDisplay.Unknown),
            setOf(Conditions.ClassOnly), SupportScores(62, 16, SupportScoreDisplay.Unknown)),
        // Unknown display -> return null (under checkGrand alone nothing else refuses it).
        Row("display unknown, class unknown, Grand +16", shape(range, value16, EngineDisplay.Unknown), emptySet(), null),
        // Compound: Grand must be a value (under checkGrand alone); class value (with checkClass).
        Row("compound, both unknown", shape(range, range, EngineDisplay.Compound), emptySet(), null),
        // Compound: Grand must be a value.
        Row("compound, class +62, Grand not shown", shape(value62, notShown, EngineDisplay.Compound),
            setOf(Conditions.ClassOnly), SupportScores(62, null, SupportScoreDisplay.Compound)),
    )

    private fun check(rows: List<Row>) {
        for (row in rows) for (conditions in Conditions.entries) {
            val answer = EngineScoreCompleteness.answer(row.score, conditions.checkClass, conditions.checkGrand)
            val expected = if (conditions in row.used) row.scores else null
            assertThat(answer, "${row.name} under $conditions").isEqualTo(expected)
        }
    }

    @Test
    fun `every answer the engine sends is used or handed to the existing reader by the rule`() = check(realAnswers)

    @Test
    fun `shapes only the wire can carry are decided by the same rule`() = check(wireOnly)

    @Test
    fun `no condition is nothing the engine can decide`() {
        assertThat(EngineScoreCompleteness.answer(EngineAnswers.compound(62, 16), false, false)).isEqualTo(null)
    }

    /**
     * The selection must decide on an engine answer exactly as it would on the existing reader's
     * answer for the same true field: that reader gives Ordinary with no Grand for "+NN" and
     * Compound with both values for "+NN/+MM" when it reads them (SupportScoreReaderTest).
     */
    @Test
    fun `matches decides the engine answer as it decides the existing reader's answer of the same field`() {
        val fields = listOf(62 to null, 40 to null, 62 to 16, 62 to 0, 40 to 16, 0 to 16)
        for ((classScore, grandScore) in fields) for (conditions in Conditions.entries) {
            val engine = if (grandScore == null) EngineAnswers.ordinary(classScore) else EngineAnswers.compound(classScore, grandScore)
            val kotlin = if (grandScore == null) SupportScores(classScore, null, SupportScoreDisplay.Ordinary)
            else SupportScores(classScore, grandScore, SupportScoreDisplay.Compound)
            val answer = checkNotNull(EngineScoreCompleteness.answer(engine, conditions.checkClass, conditions.checkGrand))
            assertThat(SupportScoreSelection.matches(answer, conditions.checkClass, conditions.checkGrand), "$classScore/$grandScore $conditions")
                .isEqualTo(SupportScoreSelection.matches(kotlin, conditions.checkClass, conditions.checkGrand))
        }
    }
}
