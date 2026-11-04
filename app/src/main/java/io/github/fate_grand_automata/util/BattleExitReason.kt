package io.github.fate_grand_automata.util

import android.content.Context
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle

fun AutoBattle.ExitReason.displayText(context: Context): String = when (this) {
    AutoBattle.ExitReason.Abort -> context.getString(R.string.stopped_by_user)
    is AutoBattle.ExitReason.Unexpected -> {
        cause.let {
            if (it is KnownException) context.getString(it.reason.resId, *it.reason.args)
            else "${context.getString(R.string.unexpected_error)}: ${cause?.message}"
        }
    }

    AutoBattle.ExitReason.CEGet -> context.getString(R.string.ce_get)
    is AutoBattle.ExitReason.LimitCEs -> context.getString(R.string.ces_dropped, count)
    is AutoBattle.ExitReason.LimitMaterials -> context.getString(R.string.mats_farmed, count)
    AutoBattle.ExitReason.WithdrawDisabled -> context.getString(R.string.withdraw_disabled)
    AutoBattle.ExitReason.APRanOut -> context.getString(R.string.script_msg_ap_ran_out)
    AutoBattle.ExitReason.InventoryFull -> context.getString(R.string.inventory_full)
    is AutoBattle.ExitReason.LimitRuns -> context.getString(R.string.times_ran, count)
    AutoBattle.ExitReason.SupportSelectionManual -> context.getString(R.string.support_selection_manual)
    AutoBattle.ExitReason.SupportSelectionPreferredNotSet -> context.getString(R.string.support_selection_preferred_not_set)
    AutoBattle.ExitReason.SupportSkillDisplayFailed -> context.getString(R.string.support_skill_display_failed)
    AutoBattle.ExitReason.QuestItemSettingFailed -> context.getString(R.string.quest_item_setting_failed)
    is AutoBattle.ExitReason.BondMax -> if (positions.isEmpty()) context.getString(R.string.bond_max_popup_stop)
        else context.getString(R.string.bond_max_stop, positions.joinToString(", "))
    is AutoBattle.ExitReason.DirectCardsUnavailable -> context.getString(R.string.direct_cards_unavailable, sequence)
    is AutoBattle.ExitReason.SkillCommandParseError -> "AutoSkill Parse error:\n\n${cause?.message}"
    is AutoBattle.ExitReason.CardPriorityParseError -> msg
    AutoBattle.ExitReason.FirstClearRewards -> context.getString(R.string.first_clear_rewards)
    AutoBattle.ExitReason.Paused -> context.getString(R.string.script_paused)
    AutoBattle.ExitReason.StopAfterThisRun -> context.getString(R.string.stop_after_this_run)
    AutoBattle.ExitReason.OutOfCommandSpells -> context.getString(R.string.p_stop_on_out_of_command_spell)
    AutoBattle.ExitReason.CommandStartRecognitionFailed -> context.getString(R.string.command_start_recognition_failed)
    is AutoBattle.ExitReason.CommandStartTargetUnavailable -> context.getString(R.string.command_start_target_unavailable, servant)
    is AutoBattle.ExitReason.CommandStartWaitLimit -> context.getString(R.string.command_start_wait_limit, turns)
    AutoBattle.ExitReason.CommandStartDuplicateTargets -> context.getString(R.string.command_start_duplicate_targets)
    AutoBattle.ExitReason.CommandStartTargetsUnavailable -> context.getString(R.string.command_start_targets_unavailable)
    AutoBattle.ExitReason.CommandStartReturnFailed -> context.getString(R.string.command_start_return_failed)
    AutoBattle.ExitReason.TurnLimitRetreatFailed -> context.getString(R.string.turn_limit_retreat_failed)
    is AutoBattle.ExitReason.TurnLimitRetreatStreak -> context.getString(R.string.turn_limit_retreat_streak_stop, count)
    AutoBattle.ExitReason.ShuffleUntilReturnFailed -> context.getString(R.string.hakuno_shuffle_return_failed)
    is AutoBattle.ExitReason.ShuffleUntilUnmet -> context.getString(R.string.hakuno_shuffle_unmet, presses,
        context.getString(when (reason) {
            io.github.fate_grand_automata.scripts.modules.ShuffleEndReason.NPShortage -> R.string.hakuno_shuffle_np_shortage
            io.github.fate_grand_automata.scripts.modules.ShuffleEndReason.MaxReached -> R.string.hakuno_shuffle_max_reached
            else -> R.string.hakuno_shuffle_read_failed
        }))
}

