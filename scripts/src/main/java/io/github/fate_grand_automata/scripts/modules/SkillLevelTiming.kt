package io.github.fate_grand_automata.scripts.modules

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** D-11 / D-14: every wait of the skill / append level loop in one place. */
object SkillLevelTiming {
    /** After tapping a slot icon, before the instruction band is read and the button is tapped. */
    val SELECT_SETTLE = 1.seconds
    /** D-17: after enhance, before the existing two-second dialog search. */
    val AFTER_ENHANCE = 0.4.seconds
    /** D-17: after tapping cancel in the dialog, before the close check and the next slot. */
    val AFTER_CANCEL = 0.4.seconds
    val FIND_POLL = 100.milliseconds
    /** Confirm dialog after the button tap: 2 s. */
    const val DIALOG_POLLS = 20
    /** The dialog is closed again after cancel: 2 s. */
    const val CLOSE_POLLS = 20
    /** Skip taps while the animation and the result play. */
    val SKIP_INTERVAL = 0.5.seconds
    const val SKIP_POLLS = 40 // 20 s cap
    /** After this many polls the skill screen counts as back even if the animation was never seen. */
    const val SKIP_MIN_POLLS = 3
    /** Final settle before the next slot icon is tapped. */
    val AFTER_SKIP = 0.5.seconds
}
