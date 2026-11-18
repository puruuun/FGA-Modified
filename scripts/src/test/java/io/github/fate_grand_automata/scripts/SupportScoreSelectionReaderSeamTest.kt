package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.prefs.ISupportPreferences
import io.github.fate_grand_automata.scripts.supportSelection.ISupportScoreReader
import io.github.fate_grand_automata.scripts.supportSelection.SupportBounds
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreDisplay
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreSelection
import io.github.fate_grand_automata.scripts.supportSelection.SupportScores
import io.github.lib_automata.Region
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test

/** The selection must decide from whatever engine answers behind the interface (design 0025, D-3). */
class SupportScoreSelectionReaderSeamTest {
    private class FakeReader(private val result: SupportScores) : ISupportScoreReader {
        var calls = 0

        override fun read(bounds: SupportBounds, checkClass: Boolean, checkGrand: Boolean, screen: Int, row: Int): SupportScores {
            calls++
            return result
        }
    }

    private fun selection(reader: ISupportScoreReader, class62: Boolean, grand16: Boolean): SupportScoreSelection {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val prefs = mockk<ISupportPreferences>(relaxed = true)
        every { api.messages } returns mockk<IScriptMessages>(relaxed = true)
        every { api.prefs.gameServer } returns GameServer.Jp.Original
        every { prefs.score62Check } returns class62
        every { prefs.grandScore16Check } returns grand16
        return SupportScoreSelection(api, prefs, reader)
    }

    private val bounds = SupportBounds(Region(76, 408, 2356, 428))

    @Test
    fun `row whose scores satisfy the wanted condition is accepted`() {
        val reader = FakeReader(SupportScores(62, 16, SupportScoreDisplay.Compound))
        assertThat(selection(reader, class62 = true, grand16 = true).check(bounds)).isEqualTo(true)
        assertThat(reader.calls).isEqualTo(1)
    }

    @Test
    fun `row whose scores miss the wanted condition is rejected`() {
        val reader = FakeReader(SupportScores(62, 0, SupportScoreDisplay.Compound))
        assertThat(selection(reader, class62 = true, grand16 = true).check(bounds)).isEqualTo(false)
        assertThat(reader.calls).isEqualTo(1)
    }

    @Test
    fun `ordinary display cannot satisfy a Grand condition`() {
        val reader = FakeReader(SupportScores(62, null, SupportScoreDisplay.Ordinary))
        assertThat(selection(reader, class62 = false, grand16 = true).check(bounds)).isEqualTo(false)
        assertThat(reader.calls).isEqualTo(1)
    }
}
