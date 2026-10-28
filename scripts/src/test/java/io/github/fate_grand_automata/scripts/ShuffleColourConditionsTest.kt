package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.models.*
import io.github.fate_grand_automata.scripts.modules.ShuffleUntil
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class ShuffleColourConditionsTest {
    private fun parse(token: String) = AutoSkillCommand.parse(token)[0, 0].single() as AutoSkillAction.ShuffleUntil

    @Test fun `old conditions retain all colours and their command form`() {
        for (token in listOf("aH{10-30:13}", "dH{8:22|31!}")) {
            val action = parse(token)
            assertThat(action.conditions.map { it.type }).isEqualTo(List(action.conditions.size) { null })
            assertThat(action.toCommandString()).isEqualTo(token)
            assertThat(parse(action.toCommandString())).isEqualTo(action)
        }
    }

    @Test fun `coloured mixed and uppercase tokens round trip in lowercase with stop suffix`() {
        for ((token, expected) in listOf(
            "aH{10-30:1b2|1q2}" to "aH{10-30:1b2|1q2}",
            "dH{8:13|2a2}" to "dH{8:13|2a2}",
            "gH{3-9:1B2|2A3|3Q1!}" to "gH{3-9:1b2|2a3|3q1!}"
        )) {
            val action = parse(token)
            assertThat(action.toCommandString()).isEqualTo(expected)
            assertThat(parse(expected)).isEqualTo(action)
        }
        assertThat(parse("aH{10-30:1b2|1q2}").conditions).isEqualTo(listOf(
            AutoSkillAction.ShuffleCondition(FieldSlot.A, 2, CardTypeEnum.Buster),
            AutoSkillAction.ShuffleCondition(FieldSlot.A, 2, CardTypeEnum.Quick)))
    }

    @Test fun `invalid colours and counts fail through the existing parse error`() {
        for (condition in listOf("1x2", "1n2", "1bb2", "1b0", "1b6", "0b2", "4a2", "1?2", "1_2", "1b", "1b22")) {
            assertThat(assertFailsWith<AutoBattle.BattleExitException> { parse("aH{8:$condition}") }.reason::class)
                .isEqualTo(AutoBattle.ExitReason.SkillCommandParseError::class)
        }
        assertFailsWith<IllegalArgumentException> {
            AutoSkillAction.ShuffleCondition(FieldSlot.A, 2, CardTypeEnum.Unknown)
        }
    }

    @Test fun `counts follow each condition and current servant with stunned and Unknown excluded from colours`() {
        val cards = CommandCard.Face.list.mapIndexed { index, face -> ParsedCard(face,
            if (index == 4) TeamSlot.B else TeamSlot.A, null,
            listOf(CardTypeEnum.Buster, CardTypeEnum.Quick, CardTypeEnum.Buster, CardTypeEnum.Unknown, CardTypeEnum.Arts)[index],
            isStunned = index == 2) }
        val conditions = listOf(
            AutoSkillAction.ShuffleCondition(FieldSlot.A, 1),
            AutoSkillAction.ShuffleCondition(FieldSlot.A, 1, CardTypeEnum.Buster),
            AutoSkillAction.ShuffleCondition(FieldSlot.A, 1, CardTypeEnum.Quick),
            AutoSkillAction.ShuffleCondition(FieldSlot.A, 1, CardTypeEnum.Arts),
            AutoSkillAction.ShuffleCondition(FieldSlot.B, 1, CardTypeEnum.Arts),
            AutoSkillAction.ShuffleCondition(FieldSlot.C, 1, CardTypeEnum.Buster))
        val deployed = mapOf(FieldSlot.A to TeamSlot.A, FieldSlot.B to TeamSlot.B, FieldSlot.C to TeamSlot.Unknown)
        assertThat(ShuffleUntil.counts(cards, deployed, conditions)).isEqualTo(listOf(3, 1, 1, 0, 1, 0))
        // The old all-colours helper is preserved; the existing hand validator still rejects an unreadable hand.
        assertThat(ShuffleUntil.counts(cards, deployed)).isEqualTo(listOf(3, 1, 0))
        assertThat(ShuffleUntil.counts(cards, emptyMap(), conditions)).isEqualTo(List(conditions.size) { 0 })
    }
}
