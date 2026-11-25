package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.enums.BraveChainEnum

/**
 * Chain Priority is shown (owner, #721; it shipped hidden in #654 until measured on devices). [SHOWN] stays as the
 * one switch to hide the feature again: false hides its settings and makes a stored "on" count as off everywhere,
 * so the battle reads, taps and picks exactly as without the feature. The pure functions take the flag as a
 * parameter, so the hidden branch stays under test.
 */
object ChainPriorityAvailability {
    const val SHOWN = true

    /**
     * The Brave Chains value a stored [BraveChainEnum.Always] acts as. Shown but off for the entry, it acts as
     * With NP (owner decision 4, #654). Hidden, it is the None that builds without Chain Priority read an unknown
     * value as, so nothing changes (planner, #654).
     */
    fun effectiveBraveChain(stored: BraveChainEnum, chainShown: Boolean, chainPriorityOn: Boolean) = when {
        stored != BraveChainEnum.Always || chainPriorityOn -> stored
        chainShown -> BraveChainEnum.WithNP
        else -> BraveChainEnum.None
    }
}
