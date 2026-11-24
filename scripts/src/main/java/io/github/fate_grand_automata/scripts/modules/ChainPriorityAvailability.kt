package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.enums.BraveChainEnum

/**
 * Owner decision (#654): Chain Priority ships hidden until the planner has measured it on devices.
 * While [SHOWN] is false the app hides its settings and a stored "on" counts as off everywhere,
 * so the battle reads, taps and picks exactly as without the feature.
 */
object ChainPriorityAvailability {
    const val SHOWN = false

    /**
     * The Brave Chains value a stored [BraveChainEnum.Always] acts as. While the feature is hidden it is the
     * None that builds without Chain Priority read an unknown value as, so nothing changes (planner, #654).
     * Shown but off for the entry, it acts as With NP (owner decision 4, #654).
     */
    fun effectiveBraveChain(stored: BraveChainEnum, chainShown: Boolean, chainPriorityOn: Boolean) = when {
        stored != BraveChainEnum.Always || chainPriorityOn -> stored
        chainShown -> BraveChainEnum.WithNP
        else -> BraveChainEnum.None
    }
}
