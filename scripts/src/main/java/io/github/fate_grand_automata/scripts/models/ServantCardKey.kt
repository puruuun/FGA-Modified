package io.github.fate_grand_automata.scripts.models

import io.github.fate_grand_automata.scripts.enums.CardTypeEnum

/** A card identified by the on-screen field position of its servant (1..3, left to right) and its type. */
data class ServantCardKey(val fieldPosition: Int, val type: CardTypeEnum) {
    init {
        require(fieldPosition in 1..3) { "Field position must be 1..3" }
        require(type != CardTypeEnum.Unknown) { "Card type must be known" }
    }

    override fun toString() = "$fieldPosition${type.name.first()}"

    companion object {
        val all: List<ServantCardKey> by lazy {
            (1..3).flatMap { position ->
                listOf(CardTypeEnum.Buster, CardTypeEnum.Arts, CardTypeEnum.Quick)
                    .map { ServantCardKey(position, it) }
            }
        }

        fun parse(text: String): ServantCardKey? {
            val t = text.trim().uppercase()
            if (t.length != 2) return null
            val position = t[0].digitToIntOrNull()?.takeIf { it in 1..3 } ?: return null
            val type = when (t[1]) {
                'B' -> CardTypeEnum.Buster
                'A' -> CardTypeEnum.Arts
                'Q' -> CardTypeEnum.Quick
                else -> return null
            }
            return ServantCardKey(position, type)
        }
    }
}
