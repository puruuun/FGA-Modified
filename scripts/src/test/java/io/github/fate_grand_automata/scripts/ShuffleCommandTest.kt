package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.models.*
import kotlin.test.*

class ShuffleCommandTest {
    @Test fun `tokens parse and round trip as equal actions`() {
        for (token in listOf("a{8:13|22}", "a{2-8:13|22!}", "d{30:23}", "g{0-1:15}", "g{30-30:11|25|32!}")) {
            val action = AutoSkillCommand.parse(token)[0, 0].single() as AutoSkillAction.ShuffleUntil
            assertEquals(action, AutoSkillCommand.parse(action.toCommandString())[0, 0].single())
        }
    }

    @Test fun `malformed tokens use the existing parse error`() {
        for (token in listOf("a{8:13", "b{8:13}", "c{8:13}", "e{8:13}", "h{8:13}", "a{0:13}", "a{31:13}",
            "a{3-2:13}", "a{-1-8:13}", "a{8:}", "a{8:10}", "a{8:16}", "a{8:43}", "a{8:123}", "a{8:13|}",
            "a{8:13!!}", "a{8:13,22}", "a{8:13#22}", "a{99999999999:13}")) {
            assertIs<AutoBattle.ExitReason.SkillCommandParseError>(
                assertFailsWith<AutoBattle.BattleExitException>(token) { AutoSkillCommand.parse(token) }.reason)
        }
    }

    @Test fun `legacy targets commands turns and attacks retain their actions`() {
        val command = AutoSkillCommand.parse("a1b[Ch2A]c(1[Ch3B])x13t3n14,0,#,d&ba4")
        val actions = command[0, 0]
        assertEquals(listOf(ServantTarget.A), (actions[0] as AutoSkillAction.ServantSkill).targets)
        assertEquals(listOf(ServantTarget.SpecialTarget.Choice2OptionA), (actions[1] as AutoSkillAction.ServantSkill).targets)
        assertEquals(listOf(ServantTarget.A, ServantTarget.SpecialTarget.Choice3OptionB), (actions[2] as AutoSkillAction.ServantSkill).targets)
        assertIs<AutoSkillAction.OrderChange>(actions[3])
        assertIs<AutoSkillAction.TargetEnemy>(actions[4])
        assertEquals(1, (actions[5] as AutoSkillAction.Atk).cardsBeforeNP)
        assertEquals(setOf(CommandCard.NP.A), (actions[5] as AutoSkillAction.Atk).nps)
        assertEquals(AutoSkillAction.Atk.noOp(), command[0, 1].single())
        assertEquals("&ba4", (command[1, 0].last() as AutoSkillAction.Atk).toCommandString())
    }

    @Test fun `shuffle can precede direct cards and NP actions in the same turn`() {
        for (suffix in listOf("&ba4", "456")) {
            val actions = AutoSkillCommand.parse("a{8:13}$suffix")[0, 0]
            assertIs<AutoSkillAction.ShuffleUntil>(actions.first())
            assertIs<AutoSkillAction.Atk>(actions.last())
        }
    }
}
