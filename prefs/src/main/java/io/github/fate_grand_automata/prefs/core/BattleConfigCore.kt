package io.github.fate_grand_automata.prefs.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.fredporciuncula.flow.preferences.Serializer
import io.github.fate_grand_automata.prefs.import
import io.github.fate_grand_automata.scripts.enums.CommandStartMode
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.GameServers
import io.github.fate_grand_automata.scripts.enums.MaterialEnum

class BattleConfigCore(
    val id: String,
    val context: Context
) {
    val sharedPrefs: SharedPreferences = context.getSharedPreferences(
        id,
        Context.MODE_PRIVATE
    )

    private val maker = PrefMaker(sharedPrefs)

    fun import(map: Map<String, *>) =
        sharedPrefs.edit {
            import(map)
        }

    fun export(): Map<String, *> = sharedPrefs.all

    val name = maker.string("autoskill_name", "--")
    val execution = ExecutionProfileCore(maker)
    val secondaryExecution = ExecutionProfileCore(maker, "secondary_")
    fun executionProfile(index: Int): ExecutionProfileCore = when (index) {
        0 -> execution
        1 -> secondaryExecution
        else -> error("Invalid execution profile: $index")
    }
    val skillCommand get() = execution.skillCommand
    val cardPriority get() = execution.cardPriority
    val useServantPriority get() = execution.useServantPriority
    val servantPriority get() = execution.servantPriority
    val rearrangeCards get() = execution.rearrangeCards
    val braveChains get() = execution.braveChains
    val shuffleCards get() = execution.shuffleCards
    val shuffleCardsWave get() = execution.shuffleCardsWave
    val spam = SpamConfigCore(maker).spam
    val commandStartMode = maker.enum("command_start_mode", CommandStartMode.Basic)
    val commandStartServant = maker.int("command_start_servant", 1)
    val commandStartCardCount = maker.int("command_start_card_count", 2)
    val commandStartMaxTurns = maker.int("command_start_max_turns", 0)
    val secondaryStartEnabled = maker.bool("secondary_start_enabled")
    val secondaryStartServant = maker.int("secondary_start_servant", 2)
    val secondaryStartCardCount = maker.int("secondary_start_card_count", 2)
    val notes = maker.string("autoskill_notes")

    val party = maker.stringAsInt("autoskill_party", -1)
    val materials = maker.stringSet("battle_config_mat").map(
        defaultValue = emptySet(),
        convert = {
            it
                .mapNotNull { mat ->
                    try {
                        enumValueOf<MaterialEnum>(mat)
                    } catch (e: Exception) {
                        null
                    }
                }
                .toSet()
        },
        reverse = {
            it
                .map { m -> m.name }
                .toSet()
        }
    )

    sealed class Server {
        class Set(val server: GameServer) : Server()
        object NotSet : Server()

        fun asGameServer() = when (this) {
            NotSet -> null
            is Set -> server
        }
    }

    val server = maker.serialized(
        "battle_config_server",
        serializer = object : Serializer<Server> {
            override fun deserialize(serialized: String) =
                try {
                    GameServers.deserialize(serialized)?.let { Server.Set(it) } ?: Server.NotSet
                } catch (e: Exception) {
                    Server.NotSet
                }

            override fun serialize(value: Server) =
                when (value) {
                    Server.NotSet -> ""
                    is Server.Set -> value.server.serialize()
                }
        },
        default = Server.NotSet
    )

    val support = SupportPrefsCore(maker)

    val autoChooseTarget = maker.bool("auto_choose_target")

    val addRaidTurnDelay = maker.bool("add_raid_delay")

    val raidTurnDelaySeconds = maker.stringAsInt("raid_delay_seconds", 3)
}
