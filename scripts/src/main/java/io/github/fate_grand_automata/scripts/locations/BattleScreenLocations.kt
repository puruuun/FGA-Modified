package io.github.fate_grand_automata.scripts.locations

import io.github.fate_grand_automata.scripts.models.EnemyTarget
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.OrderChangeMember
import io.github.fate_grand_automata.scripts.models.ServantTarget
import io.github.fate_grand_automata.scripts.models.Skill
import io.github.fate_grand_automata.scripts.models.skill2
import io.github.fate_grand_automata.scripts.models.skill3
import io.github.lib_automata.Location
import io.github.lib_automata.Region
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

@ScriptScope
class BattleScreenLocations @Inject constructor(
    scriptAreaTransforms: IScriptAreaTransforms,
    val master: MasterLocations
) : IScriptAreaTransforms by scriptAreaTransforms {
    fun locate(orderChangeMember: OrderChangeMember) = when (orderChangeMember) {
        OrderChangeMember.Starting.A -> -1000
        OrderChangeMember.Starting.B -> -600
        OrderChangeMember.Starting.C -> -200
        OrderChangeMember.Sub.A -> 200
        OrderChangeMember.Sub.B -> 600
        OrderChangeMember.Sub.C -> 1000
    }.let { x -> Location(x, 700) }.xFromCenter()

    fun locate(servantTarget: ServantTarget) = when (servantTarget) {
        ServantTarget.A -> -580
        ServantTarget.B -> 0
        ServantTarget.C -> 660
        ServantTarget.Left -> -290
        ServantTarget.Right -> 330
        ServantTarget.Transform -> null
        ServantTarget.Option1, ServantTarget.SpecialTarget.Choice2OptionA -> 0
        ServantTarget.Option2, ServantTarget.SpecialTarget.Choice2OptionB -> 470
        ServantTarget.SpecialTarget.Choice3OptionA -> -200
        ServantTarget.SpecialTarget.Choice3OptionB -> 300
        ServantTarget.SpecialTarget.Choice3OptionC -> 670
    }?.let { x -> Location(x, 880) }?.xFromCenter()

    fun locate(skill: Skill.Servant) = when (skill) {
        Skill.Servant.A1 -> 148
        Skill.Servant.A2 -> 324
        Skill.Servant.A3 -> 500
        Skill.Servant.B1 -> 784
        Skill.Servant.B2 -> 960
        Skill.Servant.B3 -> 1136
        Skill.Servant.C1 -> 1418
        Skill.Servant.C2 -> 1594
        Skill.Servant.C3 -> 1770
    }.let { x -> Location(x + if (isWide) 108 else 0, if (isWide) 1117 else 1158) }

    fun locate(enemy: EnemyTarget): Location = when (enemy) {
        EnemyTarget.A -> normalEnemy(90)
        EnemyTarget.B -> normalEnemy(570)
        EnemyTarget.C -> normalEnemy(1050)

        // Six-enemy layout: HP bars of the top (y 80) and bottom (y 276) rows.
        EnemyTarget.SixTopLeft -> layoutEnemy(460, 80)
        EnemyTarget.SixTopMiddle -> layoutEnemy(860, 80)
        EnemyTarget.SixTopRight -> layoutEnemy(1260, 80)
        EnemyTarget.SixBottomLeft -> layoutEnemy(260, 276)
        EnemyTarget.SixBottomMiddle -> layoutEnemy(660, 276)
        EnemyTarget.SixBottomRight -> layoutEnemy(1060, 276)

        // Raid layout: the boss HP bar, then up to two rows of three enemies below it.
        EnemyTarget.RaidBoss -> layoutEnemy(780, 80)

        // These points sit where both raid shapes overlap (Grand Marie: HP bars at the screen edges;
        // six-below raid: the grid under the boss).
        EnemyTarget.RaidLeft -> layoutEnemy(300, 288)
        EnemyTarget.RaidMiddle -> layoutEnemy(800, 288)
        EnemyTarget.RaidRight -> layoutEnemy(1250, 288)

        // Second row of the six-below raid: only the right slot was measured; left and middle follow the same columns.
        EnemyTarget.RaidLowerLeft -> layoutEnemy(460, 433)
        EnemyTarget.RaidLowerMiddle -> layoutEnemy(860, 433)
        EnemyTarget.RaidLowerRight -> layoutEnemy(1260, 433)
    }

    private fun normalEnemy(x: Int) = Location(x + if (isWide) 183 else 0, 80)

    // Measured on 21:9 captures: every six-enemy and raid widget sits 155 to the right of its 16:9 position.
    private fun layoutEnemy(x: Int, y: Int) = Location(x + if (isWide) 155 else 0, y)

    fun dangerRegion(enemy: EnemyTarget.Normal) = when (enemy) {
        EnemyTarget.A -> Region(0, 0, 485, 220)
        EnemyTarget.B -> Region(485, 0, 482, 220)
        EnemyTarget.C -> Region(967, 0, 476, 220)
    } + Location(if (isWide) 150 else 0, 0)

    val screenCheckRegion =
        (if (isWide)
            Region(-660, -210, 400, 175)
        else Region(-455, -181, 336, 116))
            .xFromRight()
            .yFromBottom()

    fun servantPresentRegion(slot: FieldSlot) =
        slot.skill3().let {
            val skill3Location = locate(it)

            Region(
                skill3Location.x + 35,
                skill3Location.y + 67,
                120,
                120
            )
        }

    val attackClick =
        (if (isWide)
            Location(-460, -230)
        else Location(-260, -240))
            .xFromRight()
            .yFromBottom()

    val skillOkClick = Location(400, 850).xFromCenter()
    val orderChangeOkClick = Location(0, 1260).xFromCenter()
    val extraInfoWindowCloseClick = Location(-300, 940).xFromRight()
    val skillUseRegion = Region(-210, 320, 420, 85).xFromCenter()
    val shuffleNPShortageRegion = Region(-160, 1060, 320, 130).xFromCenter()

    fun servantOpenDetailsClick(slot: FieldSlot) =
        Location(locate(slot.skill2()).x, 810)

    fun servantChangeCheckRegion(slot: FieldSlot) =
        slot.skill2().let {
            val x = locate(it).x

            Region(x + 20, 865, 40, 80)
        }

    fun servantChangeSupportCheckRegion(slot: FieldSlot) =
        slot.skill2().let {
            val x = locate(it).x

            Region(x + 25, 710, 300, 170)
        }

    fun imageRegion(skill: Skill.Servant) =
        Region(22, 28, 30, 30) + locate(skill)

    val servantDetailsInfoClick = Location(-660, 110).xFromCenter()
    
    val servantDetailsFaceCardRegion = Region(-685, 410, 110, 60).xFromCenter()

    val battleSafeMiddleOfScreenClick = Location(0, 550).xFromCenter()
}
