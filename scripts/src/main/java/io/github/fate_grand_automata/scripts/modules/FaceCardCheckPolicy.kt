package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.CardPrioritySplit
import io.github.fate_grand_automata.scripts.enums.CardPriorityStyle
import io.github.fate_grand_automata.scripts.enums.CommandStartMode
import io.github.fate_grand_automata.scripts.enums.ShuffleCardsEnum
import io.github.fate_grand_automata.scripts.models.AutoSkillAction
import io.github.fate_grand_automata.scripts.models.AutoSkillCommand
import io.github.fate_grand_automata.scripts.models.PerEntrySettings
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.fate_grand_automata.scripts.prefs.IExecutionProfile

/** Why the servant face-card check is on. */
enum class FaceCardReasonKind {
    ServantStyle,
    ServantPriority,
    BraveChains,
    NpMatchingShuffle,
    HakunoShuffle,
    CardCountStart
}

/**
 * [entry] is the 0-based wave or turn entry where that applies. [profile] is 0 for the primary
 * profile and 1 for the secondary one. [turnSplit] tells whether the entry is a turn rather than a wave.
 */
data class FaceCardReason(
    val kind: FaceCardReasonKind,
    val entry: Int? = null,
    val profile: Int = 0,
    val turnSplit: Boolean = false
)

data class FaceCardCheckDecision(val enabled: Boolean, val reason: FaceCardReason?) {
    /** A plain English line for the log, for example `face-card check: on (brave chains, entry 2)`. */
    fun toLogText(): String {
        if (!enabled || reason == null) return "face-card check: off"
        val name = when (reason.kind) {
            FaceCardReasonKind.ServantStyle -> "servant style"
            FaceCardReasonKind.ServantPriority -> "servant priority"
            FaceCardReasonKind.BraveChains -> "brave chains"
            FaceCardReasonKind.NpMatchingShuffle -> "NP-matching shuffle"
            FaceCardReasonKind.HakunoShuffle -> "Hakuno shuffle"
            FaceCardReasonKind.CardCountStart -> "card-count start"
        }
        val entry = reason.entry?.let { ", entry ${it + 1}" } ?: ""
        val profile = if (reason.profile == 1) ", secondary" else ""
        return "face-card check: on ($name$entry$profile)"
    }
}

/**
 * Decides, once per script run, whether servant face cards have to be read. It is a pure function of the
 * battle config, shared by the battle ([ServantTracker]) and the battle config screen.
 */
object FaceCardCheckPolicy {
    /** The settings of one execution profile that matter to the decision, as stored (legacy values included). */
    data class ProfileInput(
        val skillCommand: String,
        /** Number of card priority entries. */
        val entryCount: Int,
        val prioritySplit: CardPrioritySplit,
        val legacyStyle: CardPriorityStyle,
        val styles: List<CardPriorityStyle>,
        val legacyServantPriority: Boolean,
        val servantPriorities: List<Boolean>,
        val braveChains: List<BraveChainEnum>
    ) {
        companion object {
            fun of(source: IExecutionProfile) = ProfileInput(
                skillCommand = source.skillCommand,
                entryCount = source.cardPriority.size,
                prioritySplit = source.cardPrioritySplit,
                legacyStyle = source.cardPriorityStyle,
                styles = source.cardPriorityStyles,
                legacyServantPriority = source.useServantPriority,
                servantPriorities = source.useServantPriorities,
                braveChains = source.braveChains
            )
        }
    }

    data class Input(
        val commandStartMode: CommandStartMode,
        val secondaryStartEnabled: Boolean,
        val shuffleCards: ShuffleCardsEnum,
        val primary: ProfileInput,
        /** Only considered when the secondary start is active. */
        val secondary: ProfileInput
    ) {
        companion object {
            fun of(config: IBattleConfig) = Input(
                commandStartMode = config.commandStartMode,
                secondaryStartEnabled = config.secondaryStartEnabled,
                shuffleCards = config.shuffleCards,
                primary = ProfileInput.of(config),
                secondary = ProfileInput.of(config.secondaryExecution)
            )
        }
    }

    fun decide(config: IBattleConfig) = decide(Input.of(config))

    fun decide(input: Input): FaceCardCheckDecision {
        val cardCount = input.commandStartMode == CommandStartMode.CardCount
        // Mirrors BattleExecution: the secondary profile only exists in card-count mode with the secondary start on.
        val profiles = listOfNotNull(
            input.primary,
            input.secondary.takeIf { cardCount && input.secondaryStartEnabled }
        )

        fun on(reason: FaceCardReason) = FaceCardCheckDecision(true, reason)

        profiles.forEachIndexed { index, profile ->
            entryReason(profile, index)?.let { return on(it) }
        }
        if (input.shuffleCards == ShuffleCardsEnum.NoNPMatching) {
            return on(FaceCardReason(FaceCardReasonKind.NpMatchingShuffle))
        }
        profiles.forEachIndexed { index, profile ->
            if (hasHakunoShuffle(profile.skillCommand)) {
                return on(FaceCardReason(FaceCardReasonKind.HakunoShuffle, profile = index))
            }
        }
        if (cardCount) return on(FaceCardReason(FaceCardReasonKind.CardCountStart))
        return FaceCardCheckDecision(false, null)
    }

    private fun entryReason(profile: ProfileInput, index: Int): FaceCardReason? {
        val styles = PerEntrySettings.withLegacy(profile.styles, profile.legacyStyle)
        val flags = PerEntrySettings.withLegacy(profile.servantPriorities, profile.legacyServantPriority)
        // The last value of every per-entry list repeats, so visiting each position covers every entry.
        val entries = maxOf(
            1, profile.entryCount, profile.styles.size, profile.servantPriorities.size, profile.braveChains.size
        )
        val turnSplit = profile.prioritySplit == CardPrioritySplit.Turn

        fun reason(kind: FaceCardReasonKind, entry: Int) = FaceCardReason(kind, entry, index, turnSplit)

        for (entry in 0 until entries) {
            // A Servant-style entry ignores servant priority and brave chains; its face cards are read anyway.
            if (PerEntrySettings.at(styles, entry, CardPriorityStyle.Affinity) == CardPriorityStyle.Servant) {
                return reason(FaceCardReasonKind.ServantStyle, entry)
            }
            if (PerEntrySettings.at(flags, entry, false)) {
                return reason(FaceCardReasonKind.ServantPriority, entry)
            }
            if (PerEntrySettings.at(profile.braveChains, entry, BraveChainEnum.None) != BraveChainEnum.None) {
                return reason(FaceCardReasonKind.BraveChains, entry)
            }
        }
        return null
    }

    private fun hasHakunoShuffle(command: String): Boolean =
        runCatching {
            AutoSkillCommand.parse(command).stages.any { turns ->
                turns.any { actions -> actions.any { it is AutoSkillAction.ShuffleUntil } }
            }
        }.getOrDefault(false)
}
