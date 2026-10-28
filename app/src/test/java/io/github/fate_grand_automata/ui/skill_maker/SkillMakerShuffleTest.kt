package io.github.fate_grand_automata.ui.skill_maker

import io.github.fate_grand_automata.scripts.models.*
import kotlin.test.*

class SkillMakerShuffleTest {
    @Test fun `shuffle dialog action survives history save reopen and undo`() {
        val action = AutoSkillAction.ShuffleUntil(Skill.Servant.A1, 2, 8, listOf(
            AutoSkillAction.ShuffleCondition(FieldSlot.A, 3), AutoSkillAction.ShuffleCondition(FieldSlot.B, 2)), true)
        val entry = SkillMakerEntry.Action(action)
        assertEquals("a{2-8:13|22!}", entry.toString())
        val model = SkillMakerModel("b1")
        model.skillCommand.add(entry)
        assertEquals("b1a{2-8:13|22!}", model.toString())
        assertEquals(action, AutoSkillCommand.parse(SkillMakerModel(model.toString()).toString())[0, 0].last())
        model.skillCommand.removeAt(model.skillCommand.lastIndex)
        assertEquals("b1", model.toString())
    }
}
