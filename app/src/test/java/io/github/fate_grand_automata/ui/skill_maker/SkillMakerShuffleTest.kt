package io.github.fate_grand_automata.ui.skill_maker

import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.scripts.models.*
import kotlin.test.*

class SkillMakerShuffleTest {
    @Test fun `all servant shuffle skills save and reopen with uppercase H`() {
        for (skill in Skill.Servant.list) {
            val action = AutoSkillAction.ShuffleUntil(skill, 0, 8, listOf(AutoSkillAction.ShuffleCondition(FieldSlot.A, 3)))
            val entry = SkillMakerEntry.Action(action)
            val expected = "${skill.autoSkillCode}H{8:13}"
            assertEquals(expected, entry.toString())
            val reopened = SkillMakerModel(expected)
            assertEquals(expected, reopened.toString())
            assertEquals(action, AutoSkillCommand.parse(reopened.toString())[0, 0].single())
            assertEquals(when (skill) {
                Skill.Servant.A1, Skill.Servant.A2, Skill.Servant.A3 -> R.color.colorServant1
                Skill.Servant.B1, Skill.Servant.B2, Skill.Servant.B3 -> R.color.colorServant2
                else -> R.color.colorServant3
            }, entry.colorRes)
        }
    }

    @Test fun `editor model rejects old and mistyped shuffle forms`() {
        for (command in listOf("a{8:13}", "ah{8:13}")) {
            assertFailsWith<Exception>(command) { SkillMakerModel(command) }
        }
    }

    @Test fun `colour row codes survive edits history save reopen and undo`() {
        val buster = AutoSkillAction.ShuffleCondition.fromCode("13").copy(type =
            io.github.fate_grand_automata.scripts.enums.CardTypeEnum.Buster, count = 2)
        val quick = AutoSkillAction.ShuffleCondition.fromCode("1q2")
        assertEquals("1b2", buster.toCommandString())
        val action = AutoSkillAction.ShuffleUntil(Skill.Servant.A1, 10, 30, listOf(buster, quick))
        val model = SkillMakerModel("b1")
        model.skillCommand.add(SkillMakerEntry.Action(action))
        assertEquals("b1aH{10-30:1b2|1q2}", model.toString())
        assertEquals(action, AutoSkillCommand.parse(SkillMakerModel(model.toString()).toString())[0, 0].last())
        model.skillCommand.removeAt(model.skillCommand.lastIndex)
        assertEquals("b1", model.toString())
    }

    @Test fun `shuffle dialog action survives history save reopen and undo`() {
        val action = AutoSkillAction.ShuffleUntil(Skill.Servant.A1, 2, 8, listOf(
            AutoSkillAction.ShuffleCondition(FieldSlot.A, 3), AutoSkillAction.ShuffleCondition(FieldSlot.B, 2)), true)
        val entry = SkillMakerEntry.Action(action)
        assertEquals("aH{2-8:13|22!}", entry.toString())
        val model = SkillMakerModel("b1")
        model.skillCommand.add(entry)
        assertEquals("b1aH{2-8:13|22!}", model.toString())
        assertEquals(action, AutoSkillCommand.parse(SkillMakerModel(model.toString()).toString())[0, 0].last())
        model.skillCommand.removeAt(model.skillCommand.lastIndex)
        assertEquals("b1", model.toString())
    }
}
