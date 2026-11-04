package io.github.fate_grand_automata.util.chaldea

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChaldeaConversionChoiceTest {
    @Test fun `one wave applies the all turn preset without a choice`() {
        val result = ChaldeaConversion(listOf("4", "5", "6"), 1, listOf(false, false), ambiguous = false)
        assertFalse(result.needsUserChoice())
    }

    @Test fun `fewer attacks than waves applies the all wave preset without a choice`() {
        val result = ChaldeaConversion(listOf("4", "5"), 3, listOf(true), ambiguous = false)
        assertFalse(result.needsUserChoice())
    }

    @Test fun `as many attacks as waves applies the all wave preset without a choice`() {
        val result = ChaldeaConversion(listOf("4", "5"), 2, listOf(true), ambiguous = false)
        assertFalse(result.needsUserChoice())
    }

    @Test fun `more attacks than waves requires a choice`() {
        val result = ChaldeaConversion(listOf("4", "5", "6"), 2, listOf(true, true), ambiguous = true)
        assertTrue(result.needsUserChoice())
    }

    @Test fun `failed quest lookup requires a choice`() {
        val result = ChaldeaConversion(listOf("4", "5"), null, listOf(true), ambiguous = true)
        assertTrue(result.needsUserChoice())
    }

    @Test fun `zero quest waves requires a choice`() {
        val result = ChaldeaConversion(listOf("4", "5"), 0, listOf(true), ambiguous = true)
        assertTrue(result.needsUserChoice())
    }

    @Test fun `one attack needs no choice even when the wave count is unknown`() {
        val result = ChaldeaConversion(listOf("4"), null, emptyList(), ambiguous = true)
        assertFalse(result.needsUserChoice())
    }
}
