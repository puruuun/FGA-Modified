package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.enums.CardPriorityStyle
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.models.CardPriorityPerWave
import io.github.fate_grand_automata.scripts.models.CardScore
import io.github.fate_grand_automata.scripts.models.ParsedCard
import io.github.fate_grand_automata.scripts.models.ServantCardKey
import io.github.fate_grand_automata.scripts.models.ServantCardPriorityPerWave
import io.github.fate_grand_automata.scripts.models.ServantPriorityPerWave
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

@ScriptScope
class FaceCardPriority @Inject constructor(
    private val cardPriority: CardPriorityPerWave,
    private val servantPriority: ServantPriorityPerWave?,
    private val style: CardPriorityStyle = CardPriorityStyle.Affinity,
    private val servantCardPriority: ServantCardPriorityPerWave = ServantCardPriorityPerWave.default
) {

    private fun applyCardPriority(
        cards: List<ParsedCard>,
        stage: Int
    ): List<ParsedCard> {
        val groupedByScore = cards.groupBy { CardScore(it.type, it.affinity) }

        return cardPriority
            .atWave(stage)
            .mapNotNull { groupedByScore[it] }
            .flatten()
    }

    private fun applyServantPriority(
        cards: List<ParsedCard>,
        priority: ServantPriorityPerWave,
        stage: Int
    ): List<ParsedCard> {
        val groupedByServant = cards.groupBy { it.servant }

        return priority
            .atWave(stage)
            .mapNotNull { groupedByServant[it] }
            .map { servantCards ->
                applyCardPriority(
                    // Stunned cards at the end
                    cards = servantCards.filter { it.type != CardTypeEnum.Unknown },
                    stage = stage
                )
            }
            .flatten()
            .let { picked ->
                // In case less than 3 cards are picked
                val notPicked = cards - picked

                picked + notPicked
            }
    }

    /**
     * Servant style: rank each card by (field position, type) in the list order. Equal ranks keep
     * the hand order; stunned cards and cards with an unknown position or type go last.
     */
    private fun applyServantCardPriority(
        cards: List<ParsedCard>,
        stage: Int
    ): List<ParsedCard> {
        val order = servantCardPriority.atWave(stage)

        fun rank(card: ParsedCard): Int? {
            if (card.isStunned || card.type == CardTypeEnum.Unknown) return null
            val position = card.fieldSlot?.position ?: return null
            return order.indexOf(ServantCardKey(position, card.type)).takeIf { it >= 0 }
        }

        val (ranked, unranked) = cards.partition { rank(it) != null }
        return ranked.sortedBy { rank(it) } + unranked
    }

    fun sort(
        cards: List<ParsedCard>,
        stage: Int
    ): List<ParsedCard> =
        if (style == CardPriorityStyle.Servant) applyServantCardPriority(cards, stage)
        else servantPriority
            ?.let { applyServantPriority(cards, it, stage) }
            ?: applyCardPriority(cards, stage)
}