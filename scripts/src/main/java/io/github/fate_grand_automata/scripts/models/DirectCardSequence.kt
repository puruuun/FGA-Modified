package io.github.fate_grand_automata.scripts.models

import io.github.fate_grand_automata.scripts.enums.CardAffinityEnum
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum

/**
 * A complete three-card attack. Slots are a color (b/a/q), an NP (4/5/6) or unselected (p).
 * Unselected slots, and colors missing from the hand when [fallback] is set, are filled by priority.
 */
@ConsistentCopyVisibility
data class DirectCardSequence private constructor(val code: String, val fallback: Boolean = false) {
    init {
        require(code.length == 3 && code.all { it in "baq456p" }) {
            "Direct attack requires exactly three choices: b, a, q, p, 4, 5 or 6"
        }
        require(fallback || 'p' !in code) { "Unselected cards require the priority fallback" }
        val npCodes = code.filter { np(it) != null }
        require(npCodes.toSet().size == npCodes.length) { "The same NP cannot be selected twice" }
    }
    val nps: Set<CommandCard.NP> get() = code.mapNotNull { np(it) }.toSet()
    val cardsBeforeNP: Int get() = code.indexOfFirst { np(it) != null }.coerceAtLeast(0)

    /** The picked cards plus whether any slot was filled by priority. */
    class Resolution(val cards: List<CommandCard>, val usedPriority: Boolean)

    fun resolve(
        cards: List<ParsedCard>,
        prioritize: (List<ParsedCard>) -> List<ParsedCard> = { it }
    ): List<CommandCard>? = resolveDetailed(cards, prioritize)?.cards

    fun resolveDetailed(
        cards: List<ParsedCard>,
        prioritize: (List<ParsedCard>) -> List<ParsedCard> = { it }
    ): Resolution? {
        if (cards.size != 5 || cards.map { it.card }.toSet() != CommandCard.Face.list.toSet() ||
            cards.any { !it.isStunned && it.type == CardTypeEnum.Unknown }) return null
        val remaining = cards.filter { !it.isStunned }.sortedWith(compareBy<ParsedCard> {
            when (it.affinity) {
                CardAffinityEnum.Weak -> 0
                CardAffinityEnum.Normal -> 1
                CardAffinityEnum.Resist -> 2
            }
        }.thenBy { it.card.index }).toMutableList()
        // Reserve matching colors across ALL slots before any priority replacement.
        // Repeated colors belong to the earliest requesting slot first.
        // Unselected slots stay empty here so they never take a card a color slot needs.
        val result = code.map { token ->
            when (token) {
                'p' -> null
                else -> np(token) ?: remaining.firstOrNull { it.type == type(token) }?.also { remaining.remove(it) }?.card
            }
        }.toMutableList<CommandCard?>()
        var usedPriority = false
        if (result.any { it == null }) {
            if (!fallback) return null
            // The shared sorter can contain repeated entries for malformed saved priorities.
            // Only distinct, still-available cards may fill a gap; never invent a candidate.
            val replacements = prioritize(remaining.toList()).filter { it in remaining }
                .distinctBy { it.card }.iterator()
            for (index in result.indices) {
                if (result[index] == null) {
                    if (!replacements.hasNext()) return null
                    result[index] = replacements.next().card
                    usedPriority = true
                }
            }
        }
        return Resolution(result.filterNotNull(), usedPriority)
    }

    override fun toString() = "${SpecialCommand.DirectCards.autoSkillCode}${if (fallback) "?" else ""}$code"

    companion object {
        fun parse(value: String): DirectCardSequence {
            return DirectCardSequence(value.removePrefix("?").lowercase(), value.startsWith("?"))
        }

        private fun np(token: Char): CommandCard.NP? = CommandCard.NP.list.firstOrNull { it.autoSkillCode == token }

        private fun type(token: Char) = when (token) {
            'b' -> CardTypeEnum.Buster
            'a' -> CardTypeEnum.Arts
            'q' -> CardTypeEnum.Quick
            else -> error("Invalid direct face card")
        }
    }
}
