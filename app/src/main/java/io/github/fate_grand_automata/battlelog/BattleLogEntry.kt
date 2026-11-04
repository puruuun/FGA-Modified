package io.github.fate_grand_automata.battlelog

import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.MaterialEnum
import io.github.fate_grand_automata.scripts.models.BondSlotSummary
import io.github.fate_grand_automata.scripts.models.BondSummary
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/** Persisted snapshots, independent of preferences and of exit reason implementation classes. */
@Serializable
data class BattleLogEntry(
    val id: String,
    val endedAt: Long,
    val configName: String,
    val reasonKey: String,
    val reasonText: String,
    val timesRan: Int,
    val runLimit: Int?,
    val timesRefilled: Int,
    val refillLimit: Int,
    val ceDropCount: Int,
    val wipeWithdrawCount: Int,
    val turnLimitRetreatCount: Int,
    val totalTime: Long,
    val averageTimePerRun: Long,
    val minTurnsPerRun: Int,
    val averageTurnsPerRun: Double,
    val maxTurnsPerRun: Int,
    val materials: Map<String, Int>? = null,
    val bond: BattleLogBond? = null,
    val refillEnabled: Boolean = false
) {
    fun exitState() = AutoBattle.ExitState(
        timesRan = timesRan, runLimit = runLimit,
        timesRefilled = timesRefilled, refillLimit = refillLimit,
        ceDropCount = ceDropCount,
        // Unknown future/removed material names must not make an old record unreadable.
        materials = materials.orEmpty().mapNotNull { (name, count) ->
            MaterialEnum.entries.find { it.name == name }?.let { it to count }
        }.toMap(),
        wipeWithdrawCount = wipeWithdrawCount, turnLimitRetreatCount = turnLimitRetreatCount,
        totalTime = totalTime.milliseconds, averageTimePerRun = averageTimePerRun.milliseconds,
        minTurnsPerRun = minTurnsPerRun, averageTurnsPerRun = averageTurnsPerRun,
        maxTurnsPerRun = maxTurnsPerRun, bond = bond?.summary()
    )

    companion object {
        fun snapshot(
            exception: AutoBattle.ExitException,
            configName: String,
            reasonText: String,
            refillEnabled: Boolean,
            endedAt: Long = System.currentTimeMillis(),
            id: String = UUID.randomUUID().toString()
        ): BattleLogEntry = with(exception.state) {
            BattleLogEntry(
                id = id, endedAt = endedAt, configName = configName,
                reasonKey = exception.reason.battleLogKey(), reasonText = reasonText,
                timesRan = timesRan, runLimit = runLimit,
                timesRefilled = timesRefilled, refillLimit = refillLimit,
                ceDropCount = ceDropCount, wipeWithdrawCount = wipeWithdrawCount,
                turnLimitRetreatCount = turnLimitRetreatCount,
                totalTime = totalTime.inWholeMilliseconds,
                averageTimePerRun = averageTimePerRun.inWholeMilliseconds,
                minTurnsPerRun = minTurnsPerRun, averageTurnsPerRun = averageTurnsPerRun,
                maxTurnsPerRun = maxTurnsPerRun,
                materials = materials.takeIf { it.isNotEmpty() }?.mapKeys { it.key.name },
                bond = bond?.let { summary ->
                    BattleLogBond(summary.slots.map {
                        BattleLogBondSlot(it.position, it.total, it.readRuns, it.missedRuns, it.maxObserved)
                    }, summary.unmappedLevelUpRuns, summary.levelUpMissedRuns)
                },
                refillEnabled = refillEnabled
            )
        }
    }
}

@Serializable
data class BattleLogBond(
    val slots: List<BattleLogBondSlot>,
    val unmappedLevelUpRuns: Int,
    val levelUpMissedRuns: Int
) {
    fun summary() = BondSummary(
        slots.map { BondSlotSummary(it.position, it.total, it.readRuns, it.missedRuns, it.maxObserved) },
        unmappedLevelUpRuns, levelUpMissedRuns
    )
}

@Serializable
data class BattleLogBondSlot(
    val position: Int,
    val total: Long,
    val readRuns: Int,
    val missedRuns: Int,
    val maxObserved: Boolean
)

enum class BattleLogSource { BattleExit, WeeklyRouteFinished, PausedStatus }

fun shouldRecordBattleLog(source: BattleLogSource, reason: AutoBattle.ExitReason, timesRan: Int): Boolean =
    source != BattleLogSource.PausedStatus && reason != AutoBattle.ExitReason.Paused && timesRan >= 1

/** These are storage keys: keep existing values even if the script classes are renamed. */
fun AutoBattle.ExitReason.battleLogKey(): String = when (this) {
    AutoBattle.ExitReason.Abort -> "abort"
    is AutoBattle.ExitReason.Unexpected -> "unexpected"
    AutoBattle.ExitReason.CEGet -> "ce_get"
    is AutoBattle.ExitReason.LimitCEs -> "limit_ces"
    AutoBattle.ExitReason.FirstClearRewards -> "first_clear_rewards"
    is AutoBattle.ExitReason.LimitMaterials -> "limit_materials"
    AutoBattle.ExitReason.WithdrawDisabled -> "withdraw_disabled"
    AutoBattle.ExitReason.APRanOut -> "ap_ran_out"
    AutoBattle.ExitReason.InventoryFull -> "inventory_full"
    is AutoBattle.ExitReason.LimitRuns -> "limit_runs"
    AutoBattle.ExitReason.SupportSelectionManual -> "support_selection_manual"
    AutoBattle.ExitReason.SupportSelectionPreferredNotSet -> "support_selection_preferred_not_set"
    AutoBattle.ExitReason.SupportSkillDisplayFailed -> "support_skill_display_failed"
    AutoBattle.ExitReason.QuestItemSettingFailed -> "quest_item_setting_failed"
    is AutoBattle.ExitReason.BondMax -> "bond_max"
    is AutoBattle.ExitReason.DirectCardsUnavailable -> "direct_cards_unavailable"
    is AutoBattle.ExitReason.SkillCommandParseError -> "skill_command_parse_error"
    is AutoBattle.ExitReason.CardPriorityParseError -> "card_priority_parse_error"
    AutoBattle.ExitReason.Paused -> "paused"
    AutoBattle.ExitReason.StopAfterThisRun -> "stop_after_this_run"
    AutoBattle.ExitReason.OutOfCommandSpells -> "out_of_command_spells"
    AutoBattle.ExitReason.CommandStartRecognitionFailed -> "command_start_recognition_failed"
    is AutoBattle.ExitReason.CommandStartTargetUnavailable -> "command_start_target_unavailable"
    is AutoBattle.ExitReason.CommandStartWaitLimit -> "command_start_wait_limit"
    AutoBattle.ExitReason.CommandStartReturnFailed -> "command_start_return_failed"
    AutoBattle.ExitReason.ShuffleUntilReturnFailed -> "shuffle_until_return_failed"
    is AutoBattle.ExitReason.ShuffleUntilUnmet -> "shuffle_until_unmet"
    AutoBattle.ExitReason.CommandStartDuplicateTargets -> "command_start_duplicate_targets"
    AutoBattle.ExitReason.CommandStartTargetsUnavailable -> "command_start_targets_unavailable"
    AutoBattle.ExitReason.TurnLimitRetreatFailed -> "turn_limit_retreat_failed"
    is AutoBattle.ExitReason.TurnLimitRetreatStreak -> "turn_limit_retreat_streak"
}
