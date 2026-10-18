package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.prefs.ISupportPreferences
import io.github.fate_grand_automata.scripts.supportSelection.SupportBounds
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreDisplay
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreReader
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreSelection
import io.github.fate_grand_automata.scripts.supportSelection.SupportScores
import io.github.lib_automata.Region
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test

class GrandScoreServerTest {
    private class Fixture(server: GameServer, class62: Boolean, grand16: Boolean) {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val prefs = mockk<ISupportPreferences>(relaxed = true)
        val reader = mockk<SupportScoreReader>()
        val messages = mockk<IScriptMessages>(relaxed = true)
        val bounds = SupportBounds(Region(76, 408, 2356, 428))
        val selection: SupportScoreSelection

        init {
            every { api.messages } returns messages
            every { api.prefs.gameServer } returns server
            every { prefs.score62Check } returns class62
            every { prefs.grandScore16Check } returns grand16
            selection = SupportScoreSelection(api, prefs, reader)
        }
    }

    @Test
    fun `only Japanese servers show Grand scores`() {
        assertThat(SupportScoreSelection.showsGrandScore(GameServer.Jp.Original)).isEqualTo(true)
        assertThat(SupportScoreSelection.showsGrandScore(GameServer.Jp.BetterFGO)).isEqualTo(true)
        for (server in listOf(GameServer.Kr, GameServer.En.Original, GameServer.En.BetterFGO, GameServer.Cn, GameServer.Tw)) {
            assertThat(SupportScoreSelection.showsGrandScore(server)).isEqualTo(false)
        }
    }

    @Test
    fun `Korean +16 only is ignored so the candidate passes without reading`() {
        val f = Fixture(GameServer.Kr, class62 = false, grand16 = true)
        assertThat(f.selection.check(f.bounds)).isEqualTo(true)
        verify(exactly = 0) { f.reader.read(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `Korean with both switches checks Class 62 only`() {
        val f = Fixture(GameServer.Kr, class62 = true, grand16 = true)
        every { f.reader.read(f.bounds, true, false, -1, -1) } returns SupportScores(62)
        assertThat(f.selection.check(f.bounds)).isEqualTo(true)
        verify(exactly = 1) { f.reader.read(f.bounds, true, false, -1, -1) }

        val other = Fixture(GameServer.Kr, class62 = true, grand16 = true)
        every { other.reader.read(other.bounds, true, false, -1, -1) } returns SupportScores(0)
        assertThat(other.selection.check(other.bounds)).isEqualTo(false)
    }

    @Test
    fun `Japanese +16 still reads Grand and raises no notice`() {
        val f = Fixture(GameServer.Jp.Original, class62 = false, grand16 = true)
        every { f.reader.read(f.bounds, false, true, -1, -1) } returns SupportScores(null, 16, SupportScoreDisplay.Compound)
        assertThat(f.selection.check(f.bounds)).isEqualTo(true)
        verify(exactly = 1) { f.reader.read(f.bounds, false, true, -1, -1) }
        verify(exactly = 0) { f.messages.notify(any()) }
    }

    @Test
    fun `the Grand-ignored notice and log are raised once per run`() {
        val f = Fixture(GameServer.Kr, class62 = false, grand16 = true)
        repeat(3) { assertThat(f.selection.check(f.bounds)).isEqualTo(true) }
        verify(exactly = 1) { f.messages.notify(ScriptNotify.GrandScoreNotSupported) }
        verify(exactly = 1) { f.messages.log(any()) }
    }
}
