package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.prefs.ISupportPreferences
import io.github.fate_grand_automata.scripts.supportSelection.AppendSkillSelection
import io.github.fate_grand_automata.scripts.supportSelection.CESelection
import io.github.fate_grand_automata.scripts.supportSelection.FriendSelection
import io.github.fate_grand_automata.scripts.supportSelection.NoblePhantasmSelection
import io.github.fate_grand_automata.scripts.supportSelection.PreferredSupportSelection
import io.github.fate_grand_automata.scripts.supportSelection.ServantSelection
import io.github.fate_grand_automata.scripts.supportSelection.SupportBounds
import io.github.fate_grand_automata.scripts.supportSelection.SupportBoundsFinder
import io.github.fate_grand_automata.scripts.supportSelection.SupportFriendChecker
import io.github.fate_grand_automata.scripts.supportSelection.SupportNpSelection
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreSelection
import io.github.lib_automata.Region
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertFailsWith

class NoblePhantasmOrderTest {
    private class Fixture(
        servants: List<String> = listOf("servant"),
        ces: List<String> = emptyList(),
        noblePhantasms: List<String> = listOf("np"),
        servantMatches: Boolean = true,
        npMatches: Boolean = true
    ) {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val prefs = mockk<ISupportPreferences>(relaxed = true)
        val boundsFinder = mockk<SupportBoundsFinder>()
        val servantSelection = mockk<ServantSelection>()
        val ceSelection = mockk<CESelection>()
        val npSelection = mockk<SupportNpSelection>()
        val noblePhantasm = mockk<NoblePhantasmSelection>()
        val score = mockk<SupportScoreSelection>()
        val bound = SupportBounds(Region(76, 408, 2356, 428))
        val preferred: PreferredSupportSelection

        init {
            every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
            every { prefs.preferredServants } returns servants
            every { prefs.preferredCEs } returns ces
            every { prefs.preferredNoblePhantasms } returns noblePhantasms
            every { prefs.score62Check } returns true
            every { prefs.grandScore16Check } returns false
            every { prefs.npLevelCheck } returns false
            every { prefs.friendsOnly } returns false
            every { prefs.friendNames } returns emptyList()
            every { boundsFinder.all() } returns listOf(bound)
            every { servantSelection.check(any(), any()) } returns servantMatches
            every { ceSelection.check(any(), any()) } returns true
            every { npSelection.check(any()) } returns true
            every { noblePhantasm.check(any(), any()) } returns npMatches
            every { score.check(any(), any(), any()) } returns true
            preferred = PreferredSupportSelection(
                prefs, api, boundsFinder, mockk<SupportFriendChecker>(relaxed = true), servantSelection, ceSelection,
                mockk<FriendSelection>(relaxed = true),
                mockk<AppendSkillSelection>(relaxed = true).also { every { it.enabled } returns false },
                score, npSelection, noblePhantasm
            )
        }
    }

    @Test
    fun `a servant mismatch never reaches the NP condition or the score read`() {
        val f = Fixture(servantMatches = false)
        f.preferred.select()
        verify(exactly = 0) { f.noblePhantasm.check(any(), any()) }
        verify(exactly = 0) { f.score.check(any(), any(), any()) }
    }

    @Test
    fun `an NP mismatch never reaches the score read`() {
        val f = Fixture(npMatches = false)
        f.preferred.select()
        verify(exactly = 1) { f.noblePhantasm.check(listOf("np"), f.bound) }
        verify(exactly = 0) { f.score.check(any(), any(), any()) }
    }

    @Test
    fun `NP is checked after the servant and before the score when both pass`() {
        val f = Fixture()
        assertThat(f.preferred.select()).isEqualTo(io.github.fate_grand_automata.scripts.supportSelection.SupportSelectionResult.Done)
        verify(exactly = 1) { f.noblePhantasm.check(listOf("np"), f.bound) }
        verify(exactly = 1) { f.score.check(any(), any(), any()) }
    }

    @Test
    fun `an NP list alone is enough to keep the preferred selection from failing as not set`() {
        val f = Fixture(servants = emptyList(), ces = emptyList(), noblePhantasms = listOf("np"))
        f.preferred.select()
        verify(atLeast = 1) { f.boundsFinder.all() }
    }

    @Test
    fun `with no servant, CE or NP set the preferred selection still fails as before`() {
        val f = Fixture(servants = emptyList(), ces = emptyList(), noblePhantasms = emptyList())
        every { f.prefs.score62Check } returns false
        assertFailsWith<AutoBattle.BattleExitException> { f.preferred.select() }
    }
}
