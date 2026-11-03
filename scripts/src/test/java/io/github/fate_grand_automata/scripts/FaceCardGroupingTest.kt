package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.doesNotContainKey
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.scripts.modules.FaceCardGrouping
import kotlin.test.Test

class FaceCardGroupingTest {
    private val cards = CommandCard.Face.list
    private val own = TeamSlot.A
    private val support = TeamSlot.D

    private fun group(
        tagged: Set<CommandCard.Face> = emptySet(),
        supportOnField: Boolean = true,
        ownedOnField: List<TeamSlot> = listOf(own),
        supportFaceUsable: Boolean = true,
        score: (CommandCard.Face, TeamSlot) -> Double
    ) = FaceCardGrouping.group(
        cards = cards,
        supportSlot = support.takeIf { supportOnField },
        supportTagged = tagged,
        ownedOnField = ownedOnField,
        supportFaceUsable = supportFaceUsable,
        score = score
    )

    @Test
    fun `tagged cards are support and excluded from face matching`() {
        val tagged = setOf(cards[0], cards[1])
        // The owned face would match everything perfectly, yet tagged cards stay with the support.
        val result = group(tagged) { _, _ -> 0.9 }

        assertThat(result[support]!!).isEqualTo(tagged)
        assertThat(result[own]!!).isEqualTo(cards.toSet() - tagged)
    }

    @Test
    fun `an identical face makes the support face unusable`() {
        assertThat(FaceCardGrouping.supportFaceUsable(listOf(0.3, 0.99))).isFalse()
        assertThat(FaceCardGrouping.supportFaceUsable(listOf(1.0))).isFalse()
        assertThat(FaceCardGrouping.supportFaceUsable(listOf(0.3, 0.98))).isTrue()
        assertThat(FaceCardGrouping.supportFaceUsable(emptyList())).isTrue()
    }

    @Test
    fun `unusable support face is not a candidate`() {
        // The support face would win, but it is excluded; the cards fall to the owned servant.
        val result = group(supportFaceUsable = false) { _, servant -> if (servant == support) 0.9 else 0.6 }

        assertThat(result[support]!!).isEmpty()
        assertThat(result[own]!!).isEqualTo(cards.toSet())
    }

    @Test
    fun `a card with a missed tag goes to the support when its face matches best`() {
        val missed = cards[2]
        val result = group { card, servant ->
            when {
                card == missed && servant == support -> 0.8
                card == missed -> 0.6
                servant == own -> 0.9
                else -> 0.0
            }
        }

        assertThat(result[support]!!).isEqualTo(setOf(missed))
        assertThat(result[own]!!).isEqualTo(cards.toSet() - missed)
    }

    @Test
    fun `support not on the field is not a candidate`() {
        val result = group(supportOnField = false, tagged = setOf(cards[0])) { _, servant ->
            if (servant == support) 0.95 else 0.6
        }

        assertThat(result).doesNotContainKey(support)
        assertThat(result[own]!!).isEqualTo(cards.toSet())
    }

    @Test
    fun `cards under the threshold stay unassigned`() {
        val result = group { _, _ -> 0.49 }

        assertThat(result[support]!!).isEmpty()
        assertThat(result.keys).containsOnly(support)
    }

    @Test
    fun `the best score wins between owned servants`() {
        val other = TeamSlot.B
        val result = group(ownedOnField = listOf(own, other)) { card, servant ->
            if (card == cards[0] && servant == other) 0.8 else if (servant == own) 0.7 else 0.5
        }

        assertThat(result[other]!!).isEqualTo(setOf(cards[0]))
        assertThat(result[own]!!).isEqualTo(cards.toSet() - cards[0])
    }
}
