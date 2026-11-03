package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
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

    /** Wave or turn entry of the card priority settings, depending on the active profile. */
    private fun priorityIndex() =
        execution.active.priorityIndex(state.commandStage, state.commandTotalTurn)

    private fun pickCards(
        cards: List<ParsedCard>,
        npUsage: NPUsage
    ): List<CommandCard.Face> {
        val index = priorityIndex()
        val cardsOrderedByPriority = execution.active.priority.sort(cards, index)

        return braveChains.pick(
            cards = cardsOrderedByPriority,
            npUsage = npUsage,
            braveChains = execution.active.braveChainAt(index),
            rearrange = execution.active.rearrangeAt(index)
        ).map { it.card }
            .also { logDecision(cards, index, cardsOrderedByPriority.map { it.card }, it.take(3), direct = null) }
    }

    private fun logDecision(
        cards: List<ParsedCard>,
        index: Int,
        sorted: List<CommandCard.Face>,
        picked: List<CommandCard>,
        direct: String?
    ) {
        messages.log(
            ScriptLog.CardDecision(
                stage = state.stage,
                turn = state.turn,
                commandStage = state.commandStage,
                commandTurn = state.commandTurn,
                profile = execution.selectedIndex ?: 0,
                entry = index,
                style = execution.active.priority.styleAt(index).name,
                servantPriority = execution.active.priority.useServantPriorityAt(index),
                cards = cards,
                sorted = sorted,
                picked = picked,
                direct = direct
            )
        )
    }

    fun clickCommandCards(
        cards: List<ParsedCard>,
        npUsage: NPUsage,
        allowNpSpam: Boolean = true
    ) {
        npUsage.directCards?.let { sequence ->
            val resolution = sequence.resolveDetailed(cards) { remaining ->
                execution.active.priority.sort(remaining, priorityIndex())
            }
            val picked = resolution?.cards
            if (picked == null || sequence.nps.any { it.toFieldSlot() !in servantTracker.deployed }) {
                throw AutoBattle.BattleExitException(AutoBattle.ExitReason.DirectCardsUnavailable(sequence.toString()))
            }
            if (resolution.usedPriority) {
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
