package io.github.fate_grand_automata.ui.skill_maker

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.scripts.models.AutoSkillAction
import io.github.fate_grand_automata.scripts.models.AutoSkillCommand
import io.github.fate_grand_automata.scripts.models.DirectCardSequence
import kotlin.test.Test

class SkillMakerDirectCardsTest {
    @Test
    fun `fixed attacks survive opening editing and saving the command history`() {
        val source = "a1b2&ba4,&bba,&b5a,#,&baq"
        val model = SkillMakerModel(source)
        assertThat(model.toString()).isEqualTo(source)
        val index = model.skillCommand.indexOfFirst { it is SkillMakerEntry.Next.Turn && it.action.directCards?.code == "bba" }
        model.skillCommand[index] = SkillMakerEntry.Next.Turn(AutoSkillAction.Atk.direct(DirectCardSequence.parse("a4b")))
        assertThat(model.toString()).isEqualTo("a1b2&ba4,&a4b,&b5a,#,&baq")
        assertThat(SkillMakerModel(model.toString()).toString()).isEqualTo(model.toString())
    }

    @Test
    fun `face only attack is not removed as a no op and trailing delimiters normalize`() {
        for (source in listOf("&bba", "&bba,", "&bba,#,")) {
            assertThat(SkillMakerModel(source).toString()).isEqualTo("&bba")
        }
        assertThat(SkillMakerEntry.Next.Turn(AutoSkillAction.Atk.direct(DirectCardSequence.parse("bba"))).toString())
            .isEqualTo("&bba,")
    }

    @Test
    fun `the fixed mode can be replaced with a normal attack and old commands still roundtrip`() {
        val model = SkillMakerModel("ab&b5a")
        val last = model.skillCommand.lastIndex
        model.skillCommand[last] = SkillMakerEntry.Action(AutoSkillAction.Atk.cardsBeforeNP(1) + AutoSkillAction.Atk.np(
            io.github.fate_grand_automata.scripts.models.CommandCard.NP.A))
        assertThat(model.toString()).isEqualTo("abn14")
        assertThat((AutoSkillCommand.parse(model.toString())[0, 0].last() as AutoSkillAction.Atk).directCards).isEqualTo(null)
        val legacy = mapOf("a1b2n14,0,#,d3n25" to "a1b2n14,,#,d3n25", "4,5,#,6" to "4,5,#,6")
        for ((source, normalized) in legacy) {
            val result = SkillMakerModel(source).toString()
            assertThat(result).isEqualTo(normalized)
            assertThat(AutoSkillCommand.parse(result).stages.map { it.size })
                .isEqualTo(AutoSkillCommand.parse(source).stages.map { it.size })
        }
    }
}
