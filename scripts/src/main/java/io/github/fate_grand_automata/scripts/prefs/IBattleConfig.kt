package io.github.fate_grand_automata.scripts.prefs

import io.github.fate_grand_automata.scripts.enums.CommandStartMode
import io.github.fate_grand_automata.scripts.enums.CommandStartCombineMode
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.MaterialEnum
import io.github.fate_grand_automata.scripts.enums.ShuffleCardsEnum
import io.github.fate_grand_automata.scripts.models.ServantSpamConfig

interface IBattleConfig : IExecutionProfile {
    val id: String
    var name: String
    val commandStartMode: CommandStartMode
    val commandStartServant: Int
    val commandStartCardCount: Int
    val commandStartBuster: Int
    val commandStartArts: Int
    val commandStartQuick: Int
    val commandStartCombineMode: CommandStartCombineMode
    /** Maximum number of skipped turns; zero means unlimited. */
    val commandStartMaxTurns: Int
    val secondaryStartEnabled: Boolean
    val secondaryStartServant: Int
    val secondaryStartCardCount: Int
    val secondaryStartBuster: Int
    val secondaryStartArts: Int
    val secondaryStartQuick: Int
    val secondaryStartCombineMode: CommandStartCombineMode
    val secondaryExecution: IExecutionProfile
    /** Skill and NP automatic use is shared by both start conditions. */
    var spam: List<ServantSpamConfig>
    val trackBond: Boolean
    val stopOnBondMax: Boolean
    /** Result columns whose MAX is ignored, from left to right (1..5). */
    val bondMaxIgnoredCharacters: Set<Int>
    val shuffleCards: ShuffleCardsEnum
    val shuffleCardsWave: Int

    fun executionProfile(index: Int): IExecutionProfile = when (index) {
        0 -> this
        1 -> secondaryExecution
        else -> error("Invalid execution profile: $index")
    }
    val party: Int
    val materials: Set<MaterialEnum>
    val support: ISupportPreferences

    val autoChooseTarget: Boolean

    val server: GameServer?

    val addRaidTurnDelay: Boolean
    val raidTurnDelaySeconds : Int

    fun export(): Map<String, *>

    fun import(map: Map<String, *>)
}
