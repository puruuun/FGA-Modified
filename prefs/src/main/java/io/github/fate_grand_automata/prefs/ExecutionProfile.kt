package io.github.fate_grand_automata.prefs

import io.github.fate_grand_automata.prefs.core.ExecutionProfileCore
import io.github.fate_grand_automata.scripts.prefs.IExecutionProfile

internal class ExecutionProfile(core: ExecutionProfileCore) : IExecutionProfile {
    override var skillCommand by core.skillCommand
    override var cardPriority by core.cardPriority
    override val cardPrioritySplit by core.cardPrioritySplit
    override val cardPriorityStyle by core.cardPriorityStyle
    override val servantCardPriority by core.servantCardPriority
    override val useServantPriority by core.useServantPriority
    override val servantPriority by core.servantPriority
    override val rearrangeCards by core.rearrangeCards
    override val braveChains by core.braveChains
}
