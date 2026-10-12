package io.github.fate_grand_automata.prefs.core

import com.fredporciuncula.flow.preferences.Serializer
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.ShuffleCardsEnum
import io.github.fate_grand_automata.scripts.models.CardPriorityPerWave
import io.github.fate_grand_automata.scripts.models.ServantPriorityPerWave
import io.github.fate_grand_automata.scripts.models.ServantSpamConfig
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/*
 * A config exported from a newer version has to stay loadable here, and one exported from here
 * loadable there: unknown keys are skipped, and every field is written out even at its default.
 */
private val spamJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

class ExecutionProfileCore(maker: PrefMaker, prefix: String = "") {
    val skillCommand = maker.string(prefix + "autoskill_cmd")

    val cardPriority = maker.serialized(
        prefix + "card_priority",
        serializer = object : Serializer<CardPriorityPerWave> {
            override fun deserialize(serialized: String) =
                CardPriorityPerWave.of(serialized)

            override fun serialize(value: CardPriorityPerWave) =
                value.toString()
        },
        default = CardPriorityPerWave.default
    )

    val rearrangeCards = maker.serialized(
        prefix + "auto_skill_rearrange_cards",
        serializer = object : Serializer<List<Boolean>> {
            private val separator = ","
            private val Yes = "T"
            private val No = "F"

            override fun deserialize(serialized: String) =
                serialized
                    .split(separator)
                    .map { m -> m == Yes }

            override fun serialize(value: List<Boolean>) =
                value
                    .joinToString(separator) { m -> if (m) Yes else No }
        },
        default = emptyList()
    )

    val braveChains = maker.serialized(
        prefix + "auto_skill_brave_chains",
        serializer = object : Serializer<List<BraveChainEnum>> {
            private val separator = ","

            override fun deserialize(serialized: String) =
                serialized
                    .split(separator)
                    .map { m ->
                        try {
                            enumValueOf(m)
                        } catch (e: Exception) {
                            BraveChainEnum.None
                        }
                    }

            override fun serialize(value: List<BraveChainEnum>) =
                value
                    .joinToString(separator) { m -> m.toString() }
        },
        default = emptyList()
    )

    val shuffleCards = maker.enum(prefix + "shuffle_cards", ShuffleCardsEnum.None)
    val shuffleCardsWave = maker.int(prefix + "shuffle_cards_wave", 3)

    val useServantPriority = maker.bool(prefix + "use_servant_priority")
    val servantPriority = maker.serialized(
        prefix + "servant_priority",
        serializer = object : Serializer<ServantPriorityPerWave> {
            override fun deserialize(serialized: String): ServantPriorityPerWave =
                try {
                    ServantPriorityPerWave.of(serialized)
                } catch (e: Exception) {
                    ServantPriorityPerWave.default
                }

            override fun serialize(value: ServantPriorityPerWave) =
                value.toString()
        },
        default = ServantPriorityPerWave.default
    )

    private val defaultSpamConfig = (1..6).map { ServantSpamConfig() }

    val spam = maker.serialized(
        prefix + "spam_x",
        serializer = object : Serializer<List<ServantSpamConfig>> {
            override fun deserialize(serialized: String) =
                try {
                    spamJson.decodeFromString<List<ServantSpamConfig>>(serialized)
                } catch (e: SerializationException) {
                    defaultSpamConfig
                }

            override fun serialize(value: List<ServantSpamConfig>) =
                spamJson.encodeToString(value)
        },
        defaultSpamConfig
    )

}
