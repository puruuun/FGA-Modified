package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.enums.ChainTypeEnum
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.NPUsage
import io.github.fate_grand_automata.scripts.models.ParsedCard
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.models.toFieldSlot
import io.github.fate_grand_automata.scripts.modules.attack.AttackPriorityHandler
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
        npUsage: NPUsage,
        allowNpSpam: Boolean
    ): List<CommandCard.Face> {
        val index = priorityIndex()
        val cardsOrderedByPriority = execution.active.priority.sort(cards, index)

        // Off (the only state while the feature is hidden) takes the unchanged path below.
        execution.active.chainPriorityAt(index)?.let { chainPriority ->
            return pickWithChainPriority(cards, cardsOrderedByPriority, npUsage, allowNpSpam, index, chainPriority)
        }

        return braveChains.pick(
            cards = cardsOrderedByPriority,
            npUsage = npUsage,
            braveChains = execution.active.braveChainAt(index),
            rearrange = execution.active.rearrangeAt(index)
        ).map { it.card }
            .also { logDecision(cards, index, cardsOrderedByPriority.map { it.card }, it.take(3), direct = null) }
    }

    /** Upstream's Chain Priority (#2087) on the fork's per-entry settings. */
    private fun pickWithChainPriority(
        cards: List<ParsedCard>,
        cardsOrderedByPriority: List<ParsedCard>,
        npUsage: NPUsage,
        allowNpSpam: Boolean,
        index: Int,
        chainPriority: List<ChainTypeEnum>
    ): List<CommandCard.Face> {
        // Only the NPs that fit after the cards clicked before them are part of the attack.
        // Spam NPs count only when their portrait is on an NP card; scripted NPs are assumed ready, as upstream.
        val spam = if (allowNpSpam) spamNps.detected() else emptySet()
        val npUsageToUse = NPUsage(
            (npUsage.nps + spam)
                .take((3 - npUsage.cardsBeforeNP).coerceAtLeast(0))
                .toSet(),
            npUsage.cardsBeforeNP
        )
        val npTypes = npUsageToUse.nps.associate { it.toFieldSlot() to it.type() }

        val picked = AttackPriorityHandler.pick(
            cards = cards,
            priorityOrder = cardsOrderedByPriority,
            npUsage = npUsageToUse,
            braveChainEnum = execution.active.braveChainAt(index),
            chainPriority = chainPriority,
            rearrange = execution.active.rearrangeAt(index),
            npTypes = npTypes,
            defaultPicker = braveChains
        ).map { it.card }

        val chain = ScriptLog.ChainDecision(
            formed = formedChain(cards, picked, npUsageToUse, npTypes),
            order = chainPriority,
            npTypes = npTypes
        )
        logDecision(cards, index, cardsOrderedByPriority.map { it.card }, picked.take(3), direct = null, chain = chain)
        return picked
    }

    /** The chain made by the three cards that are selected in click order, as far as their types are known. */
    private fun formedChain(
        cards: List<ParsedCard>,
        picked: List<CommandCard.Face>,
        npUsage: NPUsage,
        npTypes: Map<FieldSlot, CardTypeEnum>
    ): ChainTypeEnum? {
        val faceTypes = picked.map { face -> cards.firstOrNull { it.card == face }?.type ?: CardTypeEnum.Unknown }
        val types = (faceTypes.take(npUsage.cardsBeforeNP) +
            npUsage.nps.map { npTypes[it.toFieldSlot()] ?: CardTypeEnum.Unknown } +
            faceTypes.drop(npUsage.cardsBeforeNP)).take(3)
        if (types.size < 3 || CardTypeEnum.Unknown in types) return null
        return when (types.toSet().size) {
            3 -> ChainTypeEnum.Mighty
            1 -> when (types.first()) {
                CardTypeEnum.Buster -> ChainTypeEnum.Buster
                CardTypeEnum.Arts -> ChainTypeEnum.Arts
                CardTypeEnum.Quick -> ChainTypeEnum.Quick
                CardTypeEnum.Unknown -> null
            }
            else -> null
        }
    }

    private fun CommandCard.NP.type(): CardTypeEnum {
        val teamSlot = servantTracker.deployed[toFieldSlot()] ?: return CardTypeEnum.Unknown
        if (teamSlot is TeamSlot.Unknown) return CardTypeEnum.Unknown
        return servantTracker.getNpCardType(teamSlot)
    }

    private fun Set<CommandCard.NP>.detected(): Set<CommandCard.NP> {
        // Matching the NP portraits is slow, so it is skipped when there are no NPs to check
        if (isEmpty()) return emptySet()
        val npCardsDetected = servantTracker.npCardsDetectedUsingServantFaces()
        return filter { it in npCardsDetected }.toSet()
    }

    private fun logDecision(
        cards: List<ParsedCard>,
        index: Int,
        sorted: List<CommandCard.Face>,
        picked: List<CommandCard>,
        direct: String?,
        chain: ScriptLog.ChainDecision? = null
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
                direct = direct,
                chain = chain
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
        val pickedCards = pickCards(cards, npUsage, allowNpSpam)
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
