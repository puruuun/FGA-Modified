package io.github.fate_grand_automata.scripts.prefs

import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.CardPrioritySplit
import io.github.fate_grand_automata.scripts.enums.CardPriorityStyle
import io.github.fate_grand_automata.scripts.models.ServantCardPriorityPerWave
import io.github.fate_grand_automata.scripts.models.CardPriorityPerWave
import io.github.fate_grand_automata.scripts.models.ServantPriorityPerWave

/** Battle actions that may differ between the two start conditions. */
interface IExecutionProfile {
    var skillCommand: String
    var cardPriority: CardPriorityPerWave
    /** Whether the card priority entries follow waves (default) or the turns of the run. */
    val cardPrioritySplit: CardPrioritySplit
    /** Servant style ranks cards by field position and type; it ignores servant priority, brave chains and rearrange. */
    val cardPriorityStyle: CardPriorityStyle
    val servantCardPriority: ServantCardPriorityPerWave
    val useServantPriority: Boolean
    val servantPriority: ServantPriorityPerWave
    val rearrangeCards: List<Boolean>
    val braveChains: List<BraveChainEnum>
}
