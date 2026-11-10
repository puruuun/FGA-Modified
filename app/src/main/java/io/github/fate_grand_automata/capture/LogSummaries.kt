package io.github.fate_grand_automata.capture

import android.content.Context
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.battlelog.BattleLogEntry
import io.github.fate_grand_automata.scripts.capture.ChangeSource

/** Second line of a battle log card, e.g. "1회 · 평균 5.0턴 · 추적됨". Shared by the log list and the widget. */
fun battleLogSummary(context: Context, entry: BattleLogEntry): String =
    listOfNotNull(
        context.getString(R.string.battle_log_card_runs, entry.timesRan, entry.averageTurnsPerRun),
        if (entry.gains != null) context.getString(R.string.capture_gains_tracked) else null,
        entry.reasonText.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

/** Second line of an outside-log card: the change sources, with "진행 중 · " in front of an open period. */
fun outsideLogSummary(context: Context, entry: OutsideLogEntry, inProgress: Boolean): String =
    (if (inProgress) context.getString(R.string.capture_log_in_progress) + " · " else "") +
        entry.period.sources.joinToString(" · ") { outsideSourceLabel(context, it.source) }

fun outsideSourceLabel(context: Context, source: ChangeSource): String {
    val label = context.getString(when (source.group) {
        ChangeSource.Group.GiftBox -> R.string.capture_source_gift_box
        ChangeSource.Group.Shop -> R.string.capture_source_shop
        ChangeSource.Group.Summon -> R.string.capture_source_summon
        ChangeSource.Group.Lottery -> R.string.capture_source_lottery
        ChangeSource.Group.Enhancement -> R.string.capture_source_enhancement
        ChangeSource.Group.ApRecovery -> R.string.capture_source_ap_recovery
        ChangeSource.Group.ManualQuest -> R.string.capture_source_manual_quest
        ChangeSource.Group.Missions -> R.string.capture_source_missions
        ChangeSource.Group.Other -> R.string.capture_source_other
    })
    return if (source.group == ChangeSource.Group.Other) "$label · ${source.path.orEmpty()}" else label
}
