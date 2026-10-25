package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.models.DirectCardSequence
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.NPUsage
import io.github.fate_grand_automata.scripts.models.ParsedCard
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.models.toFieldSlot
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

@ScriptScope
class Card @Inject constructor(
    api: IFgoAutomataApi,
    private val servantTracker: ServantTracker,
    private val state: BattleState,
    private val caster: Caster,
    private val parser: CardParser,
    private val braveChains: ApplyBraveChains,
    private val execution: BattleExecution
) : IFgoAutomataApi by api {

    fun readCommandCards(): List<ParsedCard> = useSameSnapIn {
        parser.parse()
    }

    private val spamNps: Set<CommandCard.NP>
        get() =
            (FieldSlot.list.zip(CommandCard.NP.list))
                .mapNotNull { (servantSlot, np) ->
                    val teamSlot = servantTracker.deployed[servantSlot] ?: return@mapNotNull null
                    val npSpamConfig = execution.spam[teamSlot].np

                    if (caster.canSpam(npSpamConfig.spam) && (state.commandStage + 1) in npSpamConfig.waves)
                        np
                    else null
                }
                .toSet()

    private fun pickCards(
        cards: List<ParsedCard>,
        npUsage: NPUsage
    ): List<CommandCard.Face> {
        val cardsOrderedByPriority = execution.active.priority.sort(cards, state.commandStage)

        fun <T> List<T>.inCurrentWave(default: T) =
            if (isNotEmpty())
                this[state.commandStage.coerceIn(indices)]
            else default

        val braveChainsPerWave = execution.active.braveChains
        val rearrangeCardsPerWave = execution.active.rearrangeCards

        return braveChains.pick(
            cards = cardsOrderedByPriority,
            npUsage = npUsage,
            braveChains = braveChainsPerWave.inCurrentWave(BraveChainEnum.None),
            rearrange = rearrangeCardsPerWave.inCurrentWave(false)
        ).map { it.card }
    }

    fun clickCommandCards(
        cards: List<ParsedCard>,
        npUsage: NPUsage,
        allowNpSpam: Boolean = true
    ) {
        npUsage.directCards?.let { sequence ->
            val picked = sequence.resolve(cards) { remaining ->
                execution.active.priority.sort(remaining, state.commandStage)
            }
            if (picked == null || sequence.nps.any { it.toFieldSlot() !in servantTracker.deployed }) {
                throw AutoBattle.BattleExitException(AutoBattle.ExitReason.DirectCardsUnavailable(sequence.toString()))
            }
            if (sequence.fallback && picked != DirectCardSequence.parse(sequence.code).resolve(cards)) {
                messages.log(ScriptLog.DirectCardsFallback(sequence.toString(), picked))
            }
            // Resolve all three before the first tap. Do not rearrange or append NP Spam.
            picked.forEach {
                when (it) {
                    is CommandCard.NP -> caster.use(it)
                    is CommandCard.Face -> caster.use(it)
                }
            }
            return
        }
        val pickedCards = pickCards(cards, npUsage)
            .take(3)

        if (npUsage.cardsBeforeNP > 0) {
            pickedCards
                .take(npUsage.cardsBeforeNP)
                .also { messages.log(ScriptLog.ClickingCards(it)) }
                .forEach { caster.use(it) }
        }

        val nps = npUsage.nps + if (allowNpSpam) spamNps else emptySet()

        if (nps.isNotEmpty()) {
            nps
                .also { messages.log(ScriptLog.ClickingNPs(it)) }
                .forEach { caster.use(it) }
        }

        pickedCards
            .drop(npUsage.cardsBeforeNP)
            .also { messages.log(ScriptLog.ClickingCards(it)) }
            .forEach { caster.use(it) }
    }
}
