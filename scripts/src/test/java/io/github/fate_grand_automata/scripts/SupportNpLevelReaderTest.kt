package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.locations.SupportNpLayout
import io.github.fate_grand_automata.scripts.supportSelection.SupportBounds
import io.github.fate_grand_automata.scripts.supportSelection.SupportNpLevelReader
import io.github.lib_automata.Match
import io.github.lib_automata.OcrService
import io.github.lib_automata.OcrText
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test

class SupportNpLevelReaderTest {
    private class Fixture {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val ocr = mockk<OcrService>()
        val white = mockk<Pattern>()
        val gold = mockk<Pattern>()
        val captured = mockk<Pattern>(relaxed = true)
        val bounds = SupportBounds(Region(76, 408, 2356, 428))
        val search = SupportNpLayout.nameRegion(bounds.region)
        val prefix = Match(Region(1300, 680, 62, 48), 0.95)
        val reader = SupportNpLevelReader(api, ocr)
        init {
            every { api.prefs.gameServer } returns GameServer.Kr
            every { api.images[Images.SupportNpLevelPrefix, GameServer.Kr] } returns white
            every { api.images[Images.SupportNpLevelPrefixGold, GameServer.Kr] } returns gold
            every { with(api) { search.findAll(white, any()) } } returns listOf(prefix)
            every { with(api) { search.findAll(gold, any()) } } returns emptyList()
            every { with(api) { any<Region>().getPattern(any()) } } returns captured
            every { captured.width } returns 52
            every { captured.height } returns 28
            every { ocr.detectTextLine(captured, SupportNpLevelReader.OCR_OPTIONS) } returns OcrText("Lv.5\n", 90)
        }
    }

    @Test
    fun `all five NP levels parse while invalid or low confidence text stays unknown`() {
        for (level in 1..5) {
            assertThat(SupportNpLevelReader.parse(OcrText("Lv.$level\n", 90))).isEqualTo(level)
        }
        for (text in listOf("", "Lv.0", "Lv.6", "Lv.10", "Lv.124", "Lv.I", "Lv.S", "name Lv.5", "Lv.5 Lv.1")) {
            assertThat(SupportNpLevelReader.parse(OcrText(text, 95))).isNull()
        }
        assertThat(SupportNpLevelReader.parse(OcrText("Lv.5", 74))).isNull()
        assertThat(SupportNpLevelReader.parse(OcrText("lv 3", 75))).isEqualTo(3)
    }

    @Test
    fun `suffix follows the moving Lv marker and cropped patterns are always closed`() {
        val f = Fixture()
        for (x in listOf(900, 1300, 1450)) {
            val prefix = Match(f.prefix.region.copy(x = x), 0.95)
            every { with(f.api) { f.search.findAll(f.white, any()) } } returns listOf(prefix)
            assertThat(f.reader.read(f.bounds)).isEqualTo(5)
            verify(exactly = 1) { with(f.api) { SupportNpLayout.suffixRegion(prefix.region).getPattern("support_np_level") } }
        }
        verify(exactly = 3) { f.captured.close() }
        verify(exactly = 0) { with(f.api) { any<Region>().detectText(any()) } }
    }

    @Test
    fun `missing Lv marker avoids OCR entirely`() {
        val f = Fixture()
        every { with(f.api) { f.search.findAll(f.white, any()) } } returns emptyList()
        assertThat(f.reader.read(f.bounds)).isNull()
        verify(exactly = 0) { f.ocr.detectTextLine(any(), any()) }
    }

    @Test
    fun `two nearby color templates identify one prefix but competing prefixes fail closed`() {
        val f = Fixture()
        every { with(f.api) { f.search.findAll(f.gold, any()) } } returns listOf(Match(f.prefix.region.copy(x = 1302), 0.94))
        assertThat(f.reader.read(f.bounds)).isEqualTo(5)
        every { with(f.api) { f.search.findAll(f.gold, any()) } } returns listOf(Match(f.prefix.region.copy(x = 1100), 0.94))
        assertThat(f.reader.read(f.bounds)).isNull()
        verify(exactly = 1) { f.ocr.detectTextLine(any(), any()) }
    }

    @Test
    fun `a suffix cut off by the candidate region is not read as another level`() {
        val f = Fixture()
        every { with(f.api) { f.search.findAll(f.white, any()) } } returns listOf(Match(f.prefix.region.copy(x = f.search.right - 70), 0.95))
        assertThat(f.reader.read(f.bounds)).isNull()
        verify(exactly = 0) { f.ocr.detectTextLine(any(), any()) }
    }

    @Test
    fun `NP region stays in the name panel and follows wide screen and scrolled row offsets`() {
        assertThat(SupportNpLayout.nameRegion(Region(76,408,2356,428)))
            .isEqualTo(Region(724,668,896,104))
        assertThat(SupportNpLayout.nameRegion(Region(287,808,2356,428)))
            .isEqualTo(Region(935,1068,896,104))
    }

    @Test
    fun `OCR exceptions still release the screenshot crop`() {
        val f = Fixture()
        every { f.ocr.detectTextLine(any(), any()) } throws IllegalStateException("OCR failed")
        kotlin.test.assertFailsWith<IllegalStateException> { f.reader.read(f.bounds) }
        verify(exactly = 1) { f.captured.close() }
    }

    @Test
    fun `screen edge clipping after a prefix match rejects the candidate without OCR`() {
        val f = Fixture()
        every { f.captured.height } returns 24
        assertThat(f.reader.read(f.bounds)).isNull()
        verify(exactly = 0) { f.ocr.detectTextLine(any(), any()) }
        verify(exactly = 1) { f.captured.close() }
    }
}
