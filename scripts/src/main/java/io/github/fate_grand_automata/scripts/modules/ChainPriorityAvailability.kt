package io.github.fate_grand_automata.scripts.modules

/**
 * Owner decision (#654): Chain Priority ships hidden until the planner has measured it on devices.
 * While [SHOWN] is false the app hides its settings and a stored "on" counts as off everywhere,
 * so the battle reads, taps and picks exactly as without the feature.
 */
object ChainPriorityAvailability {
    const val SHOWN = false
}
