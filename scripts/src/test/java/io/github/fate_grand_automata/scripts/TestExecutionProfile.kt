package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.ShuffleCardsEnum
import io.github.fate_grand_automata.scripts.models.CardPriorityPerWave
import io.github.fate_grand_automata.scripts.models.ServantPriorityPerWave
import io.github.fate_grand_automata.scripts.prefs.IExecutionProfile

class TestExecutionProfile(
    override var skillCommand: String = "",
    override var cardPriority: CardPriorityPerWave = CardPriorityPerWave.default,
    override val useServantPriority: Boolean = false,
    override val servantPriority: ServantPriorityPerWave = ServantPriorityPerWave.default,
    override val braveChains: List<BraveChainEnum> = emptyList(),
    override val rearrangeCards: List<Boolean> = emptyList(),
    override val shuffleCards: ShuffleCardsEnum = ShuffleCardsEnum.None,
    override val shuffleCardsWave: Int = 3
) : IExecutionProfile
