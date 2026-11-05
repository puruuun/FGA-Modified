package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.Serializable

/** Only present/receive and battle/result have been confirmed in KR captures. */
@Serializable
data class ChangeSource(val group: Group, val path: String? = null) {
    @Serializable
    enum class Group { GiftBox, Shop, Summon, Lottery, Enhancement, ApRecovery, ManualQuest, Missions, Other }

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
