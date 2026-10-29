package io.github.fate_grand_automata.prefs.core

import com.fredporciuncula.flow.preferences.Serializer
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.CardPrioritySplit
import io.github.fate_grand_automata.scripts.enums.CardPriorityStyle
import io.github.fate_grand_automata.scripts.models.ServantCardPriorityPerWave
import io.github.fate_grand_automata.scripts.models.CardPriorityPerWave
import io.github.fate_grand_automata.scripts.models.ServantPriorityPerWave

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

    val cardPrioritySplit = maker.enum(prefix + "card_priority_split", CardPrioritySplit.Wave)

    val cardPriorityStyle = maker.enum(prefix + "card_priority_style", CardPriorityStyle.Affinity)

    val servantCardPriority = maker.serialized(
        prefix + "card_priority_servant_list",
        serializer = object : Serializer<ServantCardPriorityPerWave> {
            override fun deserialize(serialized: String): ServantCardPriorityPerWave =
                try {
                    ServantCardPriorityPerWave.of(serialized)
                } catch (e: Exception) {
                    ServantCardPriorityPerWave.default
                }

            override fun serialize(value: ServantCardPriorityPerWave) =
                value.toString()
        },
        default = ServantCardPriorityPerWave.default
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

}
