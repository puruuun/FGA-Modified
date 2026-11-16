package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.ScriptNotify
import io.github.fate_grand_automata.scripts.enums.CardAffinityEnum
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.ParsedCard
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

/** Converts Buster/Quick cards with the swap icon to Arts when [enabled]; skips Arts, Unknown, and stunned cards. */
internal fun swapType(
    type: CardTypeEnum,
    stunned: Boolean,
    enabled: Boolean,
    isPresent: () -> Boolean
): CardTypeEnum =
    if (enabled && !stunned && (type == CardTypeEnum.Buster || type == CardTypeEnum.Quick) && isPresent()) {
        CardTypeEnum.Arts
    } else {
        type
    }

@ScriptScope
class CardParser @Inject constructor(
    api: IFgoAutomataApi,
    private val servantTracker: ServantTracker,
    private val battleConfig: IBattleConfig,
    private val cardSwapIcon: CardSwapIcon
) : IFgoAutomataApi by api {

    private fun CommandCard.Face.affinity(): CardAffinityEnum {
        val region = locations.attack.affinityRegion(this)

        if (images[Images.Weak] in region) {
            return CardAffinityEnum.Weak
        }

        if (images[Images.Resist] in region) {
            return CardAffinityEnum.Resist
        }

        return CardAffinityEnum.Normal
    }

    private fun CommandCard.Face.isStunned(): Boolean {
        val stunRegion = locations.attack.typeRegion(this).copy(
            y = 930,
            width = 248,
            height = 188
        )

        return listOf(
            images[Images.Stun],
            images[Images.Immobilized],
            images[Images.StunBuster],
            images[Images.StunArts],
            images[Images.StunQuick],
        ) in stunRegion
    }

    private fun CommandCard.Face.type(): CardTypeEnum {
        val region = locations.attack.typeRegion(this)

        if (images[Images.Buster] in region) {
            return CardTypeEnum.Buster
        }

        if (images[Images.Arts] in region) {
            return CardTypeEnum.Arts
        }

        if (images[Images.Quick] in region) {
            return CardTypeEnum.Quick
        }

        return CardTypeEnum.Unknown
    }

    fun parse(): List<ParsedCard> {
        val swapEnabled = battleConfig.cardSwapDetection
        val cardsGroupedByServant = servantTracker.faceCardsGroupedByServant()

        val cards = CommandCard.Face.list
            .map {
                val stunned = it.isStunned()
                val originalType = if (stunned)
                    CardTypeEnum.Unknown
                else it.type()
                val type = swapType(originalType, stunned, swapEnabled) { cardSwapIcon.isPresent(it) }
                val swappedFrom = if (type != originalType) originalType else null
                if (swappedFrom != null) {
                    val label = when (it) {
                        CommandCard.Face.A -> "A"
                        CommandCard.Face.B -> "B"
                        CommandCard.Face.C -> "C"
                        CommandCard.Face.D -> "D"
                        CommandCard.Face.E -> "E"
                    }
                    messages.log(ScriptLog.FaceCardCheck("$label: $swappedFrom -> $type (swap icon)"))
                }
                val affinity = if (type == CardTypeEnum.Unknown)
                    CardAffinityEnum.Normal // Couldn't detect card type, so don't care about affinity
                else it.affinity()

                val servant = cardsGroupedByServant
                    .filterValues { cards -> it in cards }
                    .keys
                    .firstOrNull()
                    ?: TeamSlot.Unknown

                val fieldSlot = servantTracker.deployed
                    .entries
                    .firstOrNull { (_, teamSlot) -> teamSlot == servant }
                    ?.key

                ParsedCard(
                    card = it,
                    isStunned = stunned,
                    type = type,
                    affinity = affinity,
                    servant = servant,
                    fieldSlot = fieldSlot,
                    swappedFrom = swappedFrom
                )
            }

        var unknownCardTypes = false
        var unknownServants = false
        val failedToDetermine = cards
            .filter {
                when {
                    it.isStunned -> false
                    it.type == CardTypeEnum.Unknown -> {
                        unknownCardTypes = true
                        true
                    }

                    it.servant is TeamSlot.Unknown && servantTracker.faceCardCheckEnabled -> {
                        unknownServants = true
                        true
                    }

                    else -> false
                }
            }
            .map { it.card }

        if (failedToDetermine.isNotEmpty()) {
            messages.notify(
                ScriptNotify.FailedToDetermineCards(failedToDetermine, unknownCardTypes, unknownServants)
            )
        }

        return cards
    }
}
