package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Only present/receive and battle/result have been confirmed in KR captures.
 * [script] is captured at record time; old files retain their original group through the false default.
 */
@Serializable
data class ChangeSource(val group: Group, val path: String? = null, val script: Boolean = false) {
    @Serializable(with = GroupSerializer::class)
    enum class Group { GiftBox, Shop, Summon, Lottery, Enhancement, ApRecovery, ManualQuest, Missions, Login, Other }

    /**
     * A file written by a newer version may name a group this version lacks; reading it as Other keeps the rest of the
     * log readable. Only string names are tolerated: a number, null or object stays a malformed file.
     */
    object GroupSerializer : KSerializer<Group> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ChangeSource.Group", PrimitiveKind.STRING)
        override fun serialize(encoder: Encoder, value: Group) = encoder.encodeString(value.name)
        override fun deserialize(decoder: Decoder): Group {
            val name = decoder.decodeString()
            return Group.entries.firstOrNull { it.name == name } ?: Group.Other
        }
    }

    companion object {
        fun of(target: String): ChangeSource {
            val path = CaptureNames.path(target)
            val group = when {
                path.startsWith("present/") -> Group.GiftBox
                path.startsWith("shop/") && path != "shop/Verify" -> Group.Shop
                path.startsWith("gacha/") -> Group.Summon
                path.startsWith("boxGacha/") -> Group.Lottery
                path.startsWith("card/") || path.startsWith("svt") -> Group.Enhancement
                path == "item/recover" -> Group.ApRecovery
                path.startsWith("battle/") -> Group.ManualQuest
                path.startsWith("eventMission/") || path.startsWith("mission/") -> Group.Missions
                else -> Group.Other
            }
            return ChangeSource(group, path.takeIf { group == Group.Other })
        }
    }
}
