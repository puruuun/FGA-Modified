package io.github.fate_grand_automata.scripts.prefs

import io.github.fate_grand_automata.scripts.enums.CommandStartMode
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.MaterialEnum

interface IBattleConfig : IExecutionProfile {
    val id: String
    var name: String
    val commandStartMode: CommandStartMode
    val commandStartServant: Int
    val commandStartCardCount: Int
    /** Maximum number of skipped turns; zero means unlimited. */
    val commandStartMaxTurns: Int
    val secondaryStartEnabled: Boolean
    val secondaryStartServant: Int
    val secondaryStartCardCount: Int
    val secondaryExecution: IExecutionProfile

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
