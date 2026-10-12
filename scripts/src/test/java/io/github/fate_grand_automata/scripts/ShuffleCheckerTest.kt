package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.enums.ShuffleCardsEnum
import io.github.fate_grand_automata.scripts.models.NPUsage
import io.github.fate_grand_automata.scripts.modules.ShuffleChecker
import kotlin.test.Test
import kotlin.test.assertFalse

class ShuffleCheckerTest {
    @Test
    fun noShuffle() {
        val checker = ShuffleChecker()

        val should = checker.shouldShuffle(ShuffleCardsEnum.None, FaceCardPriorityTest.lineup1, NPUsage.none)
        assertFalse(should)
    }

    @Test
    fun `first turn start-condition mode cannot shuffle after commands start`() {
        val checker = ShuffleChecker()
        assertFalse(checker.shouldShuffle(ShuffleCardsEnum.FirstTurnStartCondition, FaceCardPriorityTest.lineup1, NPUsage.none))
    }
}
