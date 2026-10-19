package io.github.fate_grand_automata.util

import android.content.Context
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.scripts.modules.SlotResult
import io.github.fate_grand_automata.scripts.modules.SlotStop

/** D-12: one line per slot, `start Lv -> end Lv / target / reason`. */
fun skillLevelSummary(context: Context, results: List<SlotResult>): String = results.joinToString("\n") { r ->
    val reason = context.getString(when (r.stop) {
        SlotStop.Off -> R.string.skill_level_stop_off
        SlotStop.Reached -> R.string.skill_level_stop_reached
        SlotStop.Max -> R.string.skill_level_stop_max
        SlotStop.Shortage -> R.string.skill_level_stop_shortage
        SlotStop.CannotEnhance -> R.string.skill_level_stop_cannot
        SlotStop.Locked -> R.string.skill_level_stop_locked
        SlotStop.Unreadable -> R.string.skill_level_stop_unreadable
        SlotStop.NoIncrease -> R.string.skill_level_stop_no_increase
        SlotStop.UnexpectedDialog -> R.string.skill_level_stop_dialog
        SlotStop.Stuck -> R.string.skill_level_stop_stuck
    })
    context.getString(R.string.skill_level_slot_line, r.slot + 1, r.startLevel?.toString() ?: "-",
        r.endLevel?.toString() ?: "-", r.target, reason)
}
