package io.github.fate_grand_automata.scripts.modules

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** D-39: all FP sell/return timing, including the explicitly unchanged selection and polling intervals. */
object FpSellTiming {
    val BUTTON_SETTLE = 1.seconds
    val AFTER_ACTION = 1.seconds // previously 0.5s
    val AFTER_ARROW = 1.2.seconds // previously 0.7s
    val SUMMON_CONFIRM = 0.8.seconds // previously 0.3s
    val SUMMON_ANIMATION = 3.5.seconds // previously 3s
    val SUMMON_LOOP = 1.seconds // previously 0.5s

    val SELECTION_SCROLL = 0.7.seconds // unchanged; no new delay is added between tile taps
    val SCREEN_POLL = 0.5.seconds // unchanged
    const val SCREEN_POLLS = 40 // unchanged 20s cap
    val FIND_POLL = 100.milliseconds // unchanged
    const val FIND_POLLS = 20 // short 2s find retry after settling
    const val NEXT_SCREEN_POLLS = 30 // unchanged 3s navigation check

    // Auto-sell off retains the original summon behavior.
    val LEGACY_SUMMON_CONFIRM = 0.3.seconds
    val LEGACY_SUMMON_ANIMATION = 3.seconds
    val LEGACY_SUMMON_LOOP = 0.5.seconds
    fun summonConfirm(autoSell: Boolean) = if (autoSell) SUMMON_CONFIRM else LEGACY_SUMMON_CONFIRM
    fun summonAnimation(autoSell: Boolean) = if (autoSell) SUMMON_ANIMATION else LEGACY_SUMMON_ANIMATION
    fun summonLoop(autoSell: Boolean) = if (autoSell) SUMMON_LOOP else LEGACY_SUMMON_LOOP
}
