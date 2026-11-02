package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.GameServers
import io.github.fate_grand_automata.scripts.locations.BattleScreenLocations
import io.github.fate_grand_automata.scripts.locations.IScriptAreaTransforms
import io.github.fate_grand_automata.scripts.models.AutoSkillAction
import io.github.fate_grand_automata.scripts.models.AutoSkillCommand
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.EnemyTarget
import io.github.fate_grand_automata.scripts.models.Skill
import io.github.lib_automata.Location
import io.github.lib_automata.Region
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EnemyLayoutTargetsTest {
    private fun locations(width: Int): BattleScreenLocations {
        val transforms = object : IScriptAreaTransforms {
            override val scriptArea = Region(0, 0, width, 1440)
            override val isWide = scriptArea.size.isWide()
            override val gameServer: GameServer = GameServers.default
            override val canLongSwipe = false
            override fun Location.xFromCenter() = this + Location(scriptArea.center.x, 0)
            override fun Region.xFromCenter() = this + Location(scriptArea.center.x, 0)
            override fun Location.xFromRight() = this + Location(scriptArea.right, 0)
            override fun Region.xFromRight() = this + Location(scriptArea.right, 0)
            override fun Location.yFromBottom() = this + Location(0, scriptArea.bottom)
            override fun Region.yFromBottom() = this + Location(0, scriptArea.bottom)
        }
        return BattleScreenLocations(transforms, mockk())
    }

    private val sixteenNine = locations(2560)
    private val wide = locations(3360)

    private fun parse(command: String) = AutoSkillCommand.parse(command)[0, 0]

    private fun enemy(action: AutoSkillAction) = assertIs<AutoSkillAction.TargetEnemy>(action).enemy

    @Test fun `every enemy code parses to its target`() {
        val expected = mapOf(
            "t1" to EnemyTarget.A, "t2" to EnemyTarget.B, "t3" to EnemyTarget.C,
            "t4" to EnemyTarget.SixTopLeft, "t5" to EnemyTarget.SixTopMiddle, "t6" to EnemyTarget.SixTopRight,
            "t7" to EnemyTarget.SixBottomLeft, "t8" to EnemyTarget.SixBottomMiddle, "t9" to EnemyTarget.SixBottomRight,
            "tR" to EnemyTarget.RaidBoss, "tA" to EnemyTarget.RaidLeft,
            "tB" to EnemyTarget.RaidMiddle, "tC" to EnemyTarget.RaidRight,
            "tD" to EnemyTarget.RaidLowerLeft, "tE" to EnemyTarget.RaidLowerMiddle,
            "tF" to EnemyTarget.RaidLowerRight,
            // Beta-only two digit aliases
            "t10" to EnemyTarget.RaidLeft, "t11" to EnemyTarget.RaidMiddle, "t12" to EnemyTarget.RaidRight
        )
        for ((code, target) in expected) {
            val actions = parse(code)
            assertEquals(1, actions.size, code)
            assertEquals(target, enemy(actions[0]), code)
        }
    }

    @Test fun `raid codes sit next to other commands`() {
        val a = parse("tCa")
        assertEquals(EnemyTarget.RaidRight, enemy(a[0]))

        val a2 = parse("t12a")
        assertEquals(EnemyTarget.RaidRight, enemy(a2[0]))
        assertIs<AutoSkillAction.ServantSkill>(a2[1])
        assertEquals(Skill.Servant.A1, assertIs<AutoSkillAction.ServantSkill>(a[1]).skill)

        val b = parse("tAb4")
        assertEquals(EnemyTarget.RaidLeft, enemy(b[0]))
        assertEquals(Skill.Servant.A2, assertIs<AutoSkillAction.ServantSkill>(b[1]).skill)
        assertIs<AutoSkillAction.Atk>(b[2])

        val c = parse("t9c")
        assertEquals(EnemyTarget.SixBottomRight, enemy(c[0]))
        assertEquals(Skill.Servant.A3, assertIs<AutoSkillAction.ServantSkill>(c[1]).skill)

        val r = parse("tRa")
        assertEquals(EnemyTarget.RaidBoss, enemy(r[0]))
        assertIs<AutoSkillAction.ServantSkill>(r[1])
    }

    @Test fun `t1 followed by an NP is still t1 then the NP`() {
        val actions = parse("t14")
        assertEquals(2, actions.size)
        assertEquals(EnemyTarget.A, enemy(actions[0]))
        val atk = assertIs<AutoSkillAction.Atk>(actions[1])
        assertTrue(CommandCard.NP.A in atk.nps)
    }

    @Test fun `t13 and t15 keep their old meaning`() {
        // t1 followed by the next action; '3' is not an action, so t13 is still a parse error.
        val error = assertFailsWith<AutoBattle.BattleExitException> { parse("t13") }
        assertIs<AutoBattle.ExitReason.SkillCommandParseError>(error.reason)
        val actions = parse("t15")
        assertEquals(2, actions.size)
        assertEquals(EnemyTarget.A, enemy(actions[0]))
    }

    @Test fun `autoSkillCode of raid targets is a letter`() {
        assertEquals(listOf("R", "A", "B", "C", "D", "E", "F"), EnemyTarget.raidList.map { it.autoSkillCode })
    }

    @Test fun `unknown enemy codes fail to parse`() {
        for (code in listOf("t0", "tX", "tG", "t")) {
            val error = assertFailsWith<AutoBattle.BattleExitException>(code) { parse(code) }
            assertIs<AutoBattle.ExitReason.SkillCommandParseError>(error.reason)
        }
    }

    @Test fun `the normal list is still exactly the three normal targets`() {
        assertEquals(listOf<EnemyTarget>(EnemyTarget.A, EnemyTarget.B, EnemyTarget.C), EnemyTarget.list)
        assertEquals(6, EnemyTarget.sixList.size)
        assertEquals(7, EnemyTarget.raidList.size)
        assertEquals(16, EnemyTarget.all.size)
        assertEquals(16, EnemyTarget.all.map { it.autoSkillCode }.toSet().size)
    }

    @Test fun `new targets are located at 16 9 and shifted 155 on wide screens`() {
        val expected = mapOf(
            EnemyTarget.SixTopLeft to Location(460, 80),
            EnemyTarget.SixTopMiddle to Location(860, 80),
            EnemyTarget.SixTopRight to Location(1260, 80),
            EnemyTarget.SixBottomLeft to Location(260, 276),
            EnemyTarget.SixBottomMiddle to Location(660, 276),
            EnemyTarget.SixBottomRight to Location(1060, 276),
            EnemyTarget.RaidBoss to Location(780, 80),
            EnemyTarget.RaidLeft to Location(300, 288),
            EnemyTarget.RaidMiddle to Location(800, 288),
            EnemyTarget.RaidRight to Location(1250, 288),
            EnemyTarget.RaidLowerLeft to Location(460, 433),
            EnemyTarget.RaidLowerMiddle to Location(860, 433),
            EnemyTarget.RaidLowerRight to Location(1260, 433)
        )
        for ((target, location) in expected) {
            assertEquals(location, sixteenNine.locate(target), target.autoSkillCode)
            assertEquals(location + Location(155, 0), wide.locate(target), target.autoSkillCode)
        }
    }

    @Test fun `normal targets keep their locations and the 183 wide offset`() {
        assertEquals(Location(90, 80), sixteenNine.locate(EnemyTarget.A))
        assertEquals(Location(570, 80), sixteenNine.locate(EnemyTarget.B))
        assertEquals(Location(1050, 80), sixteenNine.locate(EnemyTarget.C))
        assertEquals(Location(273, 80), wide.locate(EnemyTarget.A))
        assertEquals(Location(753, 80), wide.locate(EnemyTarget.B))
        assertEquals(Location(1233, 80), wide.locate(EnemyTarget.C))
    }
}
