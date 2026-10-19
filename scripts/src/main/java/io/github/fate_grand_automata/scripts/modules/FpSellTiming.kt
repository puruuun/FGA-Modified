package io.github.fate_grand_automata.scripts.modules

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** D-39: all FP sell/return timing, including the explicitly unchanged selection and polling intervals. */
object FpSellTiming {
    val SELL_SCREEN_LOAD = 2.seconds // D-45: additional settling AFTER tapping the sell button
    val AFTER_CONVERT_EXTRA = 0.3.seconds // D-45: in addition to AFTER_ACTION
    val BUTTON_SETTLE = 1.seconds
    val AFTER_ACTION = 1.seconds // previously 0.5s
    val AFTER_ARROW = 1.2.seconds // previously 0.7s
    // D-42: the original FGA FP summon loop keeps its original waits.
    val SUMMON_CONFIRM = 0.3.seconds
    val SUMMON_ANIMATION = 3.seconds
    val SUMMON_LOOP = 0.5.seconds

    // D-40: fixed wait after tapping the summon icon, then poll for the summon screen (no re-tap).
    val SUMMON_LOAD_WAIT = 5.seconds
    const val SUMMON_LOAD_POLLS = 150 // 15s at FIND_POLL

    val SELECTION_SCROLL = 0.7.seconds // unchanged; no new delay is added between tile taps
    val SCREEN_POLL = 0.5.seconds // unchanged
    const val SCREEN_POLLS = 40 // unchanged 20s cap
    val FIND_POLL = 100.milliseconds // unchanged
    const val FIND_POLLS = 20 // short 2s find retry after settling
    const val NEXT_SCREEN_POLLS = 30 // unchanged 3s navigation check
}
