package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.SupportImageKind
import io.github.fate_grand_automata.scripts.locations.SupportNpLayout
import io.github.fate_grand_automata.scripts.supportSelection.NoblePhantasmSelection
import io.github.fate_grand_automata.scripts.supportSelection.SupportBounds
import io.github.lib_automata.Match
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test

class NoblePhantasmSelectionTest {
    private class Fixture {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val images = mockk<IImageLoader>()
        val messages = mockk<IScriptMessages>(relaxed = true)
        val bounds = SupportBounds(Region(76, 408, 2356, 428))
        val band = SupportNpLayout.noblePhantasmBand(bounds.region)
        val selection = NoblePhantasmSelection(api)

        init {
            every { api.images } returns images
            every { api.messages } returns messages
        }

        fun template(name: String, vararg scores: Double): Pattern {
            val pattern = mockk<Pattern>()
            every { images.loadSupportPattern(SupportImageKind.NoblePhantasm, name) } returns listOf(pattern)
            every { with(api) { band.findAll(pattern, any()) } } returns scores.map { Match(band, it) }
            return pattern
        }
    }

    @Test
    fun `an empty NP list is a pass that touches no image and logs nothing`() {
        val f = Fixture()
        assertThat(f.selection.check(emptyList(), f.bounds)).isEqualTo(true)
        verify(exactly = 0) { f.images.loadSupportPattern(any(), any()) }
        verify(exactly = 0) { f.messages.log(any()) }
    }

    @Test
    fun `one saved NP name at or above the threshold passes`() {
        val f = Fixture()
        f.template("Melusine Arts", 0.91)
        assertThat(f.selection.check(listOf("Melusine Arts"), f.bounds)).isEqualTo(true)
        verify(exactly = 1) { f.messages.log(any()) }
    }

    @Test
    fun `a best score below the threshold fails`() {
        val f = Fixture()
        f.template("Melusine Arts", 0.84)
        assertThat(f.selection.check(listOf("Melusine Arts"), f.bounds)).isEqualTo(false)
    }

    @Test
    fun `any of several saved images of the same entry can satisfy the condition`() {
        val f = Fixture()
        f.template("Melusine Arts", 0.40, 0.88)
        assertThat(f.selection.check(listOf("Melusine Arts"), f.bounds)).isEqualTo(true)
    }

    @Test
    fun `the threshold is not lowered below the design floor`() {
        assertThat(NoblePhantasmSelection.NP_NAME_SIMILARITY >= 0.80).isEqualTo(true)
    }

    @Test
    fun `the search band is the NP band of the row, not the whole row`() {
        val f = Fixture()
        f.template("Melusine Arts", 0.95)
        f.selection.check(listOf("Melusine Arts"), f.bounds)
        verify(exactly = 1) { with(f.api) { f.band.findAll(any(), any()) } }
    }
}
