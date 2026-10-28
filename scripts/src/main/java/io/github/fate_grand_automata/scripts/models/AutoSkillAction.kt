package io.github.fate_grand_automata.scripts.models

import io.github.fate_grand_automata.scripts.enums.CardTypeEnum

sealed class AutoSkillAction {
    data class ShuffleUntil(
        val skill: Skill.Servant,
        val min: Int,
        val max: Int,
        val conditions: List<ShuffleCondition>,
        val stopIfUnmet: Boolean = false
    ) : AutoSkillAction() {
        init {
            require(skill in Skill.Servant.skill1)
            require(max in 1..30 && min in 0..max)
            require(conditions.isNotEmpty())
        }

        fun toCommandString() = "${skill.autoSkillCode}{" +
            (if (min == 0) "$max" else "$min-$max") + ":" +
            conditions.joinToString("|") { it.toCommandString() } +
            (if (stopIfUnmet) "!" else "") + "}"
    }

    data class ShuffleCondition(val slot: FieldSlot, val count: Int, val type: CardTypeEnum? = null) {
        init {
            require(count in 1..5)
            require(type == null || type in listOf(CardTypeEnum.Buster, CardTypeEnum.Arts, CardTypeEnum.Quick))
        }

        val key: String get() = "${slot.position}" + when (type) {
            null -> ""
            CardTypeEnum.Buster -> "b"
            CardTypeEnum.Arts -> "a"
            CardTypeEnum.Quick -> "q"
            CardTypeEnum.Unknown -> error("Unknown is not a shuffle condition colour")
        }
        fun toCommandString() = "$key$count"

        companion object {
            fun fromCode(code: String): ShuffleCondition {
                require(code.length in 2..3 && code.first() in '1'..'3' && code.last() in '1'..'5')
                val type = if (code.length == 2) null else when (code[1].lowercaseChar()) {
                    'b' -> CardTypeEnum.Buster
                    'a' -> CardTypeEnum.Arts
                    'q' -> CardTypeEnum.Quick
                    else -> throw IllegalArgumentException("Invalid shuffle condition colour: $code")
                }
                return ShuffleCondition(FieldSlot.list[code.first().digitToInt() - 1], code.last().digitToInt(), type)
            }
        }
    }

    data class Atk constructor(
        val nps: Set<CommandCard.NP>,
        val cardsBeforeNP: Int,
        val directCards: DirectCardSequence? = null
    ) : AutoSkillAction() {
        init {
            require(cardsBeforeNP in 0..2) { "Only 0, 1 or 2 cards can be used before NP" }
            require(directCards == null || (nps == directCards.nps && cardsBeforeNP == directCards.cardsBeforeNP))
        }

        operator fun plus(other: Atk): Atk {
            require(directCards == null && other.directCards == null) { "Direct and automatic attacks cannot be combined" }
            return Atk(nps + other.nps, cardsBeforeNP + other.cardsBeforeNP)
        }

        fun toNPUsage() =
            NPUsage(nps, cardsBeforeNP, directCards)

        fun toCommandString(): String = directCards?.toString() ?: when {
            this == noOp() -> "0"
            else -> (if (cardsBeforeNP > 0) "${SpecialCommand.CardsBeforeNP.autoSkillCode}$cardsBeforeNP" else "") +
                nps.joinToString("") { it.autoSkillCode.toString() }
        }

        companion object {
            fun noOp() = Atk(emptySet(), 0)

            fun np(np: CommandCard.NP) = Atk(setOf(np), 0)

            fun cardsBeforeNP(cards: Int) = Atk(emptySet(), cards)

            fun direct(sequence: DirectCardSequence) = Atk(sequence.nps, sequence.cardsBeforeNP, sequence)
        }
    }

    class ServantSkill(val skill: Skill.Servant, val targets: List<ServantTarget>) : AutoSkillAction()

    class MasterSkill(val skill: Skill.Master, val target: ServantTarget?) : AutoSkillAction()

    class TargetEnemy(val enemy: EnemyTarget) : AutoSkillAction()

    class CommandSpell(
        val skill: Skill.CommandSpell,
        val target: ServantTarget?
    ) : AutoSkillAction()

    class OrderChange(
        val starting: OrderChangeMember.Starting,
        val sub: OrderChangeMember.Sub
    ) : AutoSkillAction()
}
