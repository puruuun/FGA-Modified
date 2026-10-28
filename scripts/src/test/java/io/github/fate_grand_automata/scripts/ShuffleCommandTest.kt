package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.models.*
import kotlin.test.*

class ShuffleCommandTest {
    @Test fun `uppercase H parses every servant skill with optional min colours and stop`() {
        for (skill in Skill.Servant.list) {
            for (min in listOf("", "0-", "2-")) {
                for (conditions in listOf("13|22", "1b2|2a3|3q1")) {
                    for (stop in listOf("", "!")) {
                        val token = "${skill.autoSkillCode}H{${min}8:$conditions$stop}"
                        val action = assertIs<AutoSkillAction.ShuffleUntil>(AutoSkillCommand.parse(token)[0, 0].single(), token)
                        assertEquals(skill, action.skill, token)
                        assertEquals(if (min == "2-") 2 else 0, action.min, token)
                        assertEquals(8, action.max, token)
                        assertEquals(conditions.split('|').map { AutoSkillAction.ShuffleCondition.fromCode(it) }, action.conditions, token)
                        assertEquals(stop == "!", action.stopIfUnmet, token)
                        assertEquals(token.replace("H{0-", "H{"), action.toCommandString(), token)
                        assertEquals(action, AutoSkillCommand.parse(action.toCommandString())[0, 0].single(), token)
                    }
                }
            }
        }
    }

    @Test fun `old shuffle form and lowercase h typo are rejected for every servant skill`() {
        for (skill in Skill.Servant.list) {
            for (marker in listOf("", "h")) {
                val token = "${skill.autoSkillCode}$marker{8:13}"
                assertIs<AutoBattle.ExitReason.SkillCommandParseError>(
                    assertFailsWith<AutoBattle.BattleExitException>(token) { AutoSkillCommand.parse(token) }.reason)
            }
        }
    }

    @Test fun `uppercase H requires a preceding servant skill and an opening brace`() {
        for (token in listOf("H", "H{8:13}", "aH", "aH8:13}", "aH[Ch2A]", "aHH{8:13}", "a1H{8:13}", "jH{8:13}")) {
            assertIs<AutoBattle.ExitReason.SkillCommandParseError>(
                assertFailsWith<AutoBattle.BattleExitException>(token) { AutoSkillCommand.parse(token) }.reason)
        }
    }

    @Test fun `lowercase h and targeted servant skills without braces retain their meaning`() {
        val actions = AutoSkillCommand.parse("aha1c[Ch2A]")[0, 0].map { assertIs<AutoSkillAction.ServantSkill>(it) }
        assertEquals(listOf(Skill.Servant.A1, Skill.Servant.C2, Skill.Servant.A1, Skill.Servant.A3), actions.map { it.skill })
        assertEquals(listOf(emptyList(), emptyList(), listOf(ServantTarget.A), listOf(ServantTarget.SpecialTarget.Choice2OptionA)),
            actions.map { it.targets })
    }

    @Test fun `tokens parse and round trip as equal actions`() {
        for (token in listOf("aH{8:13|22}", "aH{2-8:13|22!}", "dH{30:23}", "gH{0-1:15}", "gH{30-30:11|25|32!}")) {
            val action = AutoSkillCommand.parse(token)[0, 0].single() as AutoSkillAction.ShuffleUntil
            assertEquals(action, AutoSkillCommand.parse(action.toCommandString())[0, 0].single())
        }
    }

    @Test fun `malformed tokens use the existing parse error`() {
        for (token in listOf("aH{8:13", "aH{0:13}", "aH{31:13}",
            "aH{3-2:13}", "aH{-1-8:13}", "aH{8:}", "aH{8:10}", "aH{8:16}", "aH{8:43}", "aH{8:123}", "aH{8:13|}",
            "aH{8:13!!}", "aH{8:13,22}", "aH{8:13#22}", "aH{99999999999:13}")) {
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
            val actions = AutoSkillCommand.parse("aH{8:13}$suffix")[0, 0]
            assertIs<AutoSkillAction.ShuffleUntil>(actions.first())
            assertIs<AutoSkillAction.Atk>(actions.last())
        }
    }
}
