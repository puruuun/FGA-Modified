package io.github.fate_grand_automata.scripts.models

import io.github.fate_grand_automata.scripts.enums.CardTypeEnum

/** A complete three-card attack. Affinity and servant priority do not participate. */
@ConsistentCopyVisibility
data class DirectCardSequence private constructor(val code: String) {
    init {
        require(code.length == 3 && code.all { it in "baq456" }) {
            "Direct attack requires exactly three choices: b, a, q, 4, 5 or 6"
        }
        val npCodes = code.filter { np(it) != null }
        require(npCodes.toSet().size == npCodes.length) { "The same NP cannot be selected twice" }
    }
    val nps: Set<CommandCard.NP> get() = code.mapNotNull { np(it) }.toSet()
    val cardsBeforeNP: Int get() = code.indexOfFirst { np(it) != null }.coerceAtLeast(0)

    fun resolve(cards: List<ParsedCard>): List<CommandCard>? {
        if (cards.size != 5 || cards.map { it.card }.toSet() != CommandCard.Face.list.toSet() ||
            cards.any { !it.isStunned && it.type == CardTypeEnum.Unknown }) return null
        val remaining = cards.filter { !it.isStunned }.sortedBy { it.card.index }.toMutableList()
        val result = mutableListOf<CommandCard>()
        for (token in code) {
            val np = np(token)
            if (np != null) {
                result += np
            } else {
                val match = remaining.firstOrNull { it.type == type(token) } ?: return null
                result += match.card
                remaining.remove(match)
            }
        }
        return result
    }

    override fun toString() = "${SpecialCommand.DirectCards.autoSkillCode}$code"

    companion object {
        fun parse(value: String): DirectCardSequence {
            return DirectCardSequence(value.lowercase())
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
