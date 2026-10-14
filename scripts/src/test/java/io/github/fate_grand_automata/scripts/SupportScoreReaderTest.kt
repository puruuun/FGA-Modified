package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreDisplay
import io.github.fate_grand_automata.scripts.locations.SupportScoreLayout
import io.github.fate_grand_automata.scripts.supportSelection.SupportBounds
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreReader
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreSelection
import io.github.fate_grand_automata.scripts.supportSelection.SupportScores
import io.github.lib_automata.OcrService
import io.github.lib_automata.OcrText
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test

class SupportScoreReaderTest {
    private class Fixture {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val ocr = mockk<OcrService>()
        val legacy = mockk<Pattern>()
        val crop = mockk<Pattern>(relaxed = true)
        val bounds = SupportBounds(Region(76, 408, 2356, 428))
        val region = SupportScoreLayout.numberRegion(bounds.region)
        val reader = SupportScoreReader(api, ocr)
        init {
            every { api.images[Images.SupportScore62, null] } returns legacy
            every { with(api) { region.exists(legacy, any(), any()) } } returns false
            every { with(api) { region.getPattern("support_class_grand_scores") } } returns crop
            every { crop.width } returns 55
            every { crop.height } returns 30
            every { ocr.detectTextLine(crop, any()) } returns OcrText("+62/+16", 92)
        }
    }

    @Test
    fun `slash separates class from grand and an ordinary score has no implicit grand`() {
        assertThat(SupportScoreReader.parse(OcrText("+62/+16", 92))).isEqualTo(SupportScores(62, 16))
        assertThat(SupportScoreReader.parse(OcrText(" +0 / +16\n", 92))).isEqualTo(SupportScores(0, 16))
        assertThat(SupportScoreReader.parse(OcrText("+16/+62", 92))).isEqualTo(SupportScores(16, 62))
        assertThat(SupportScoreReader.parse(OcrText("62/+16", 92))).isEqualTo(SupportScores(62, 16))
        assertThat(SupportScoreReader.parse(OcrText("+62/16", 92))).isEqualTo(SupportScores(62, 16))
        for (text in listOf("+62+16", "+62/+16/+0", "+62/-16", "score +62/+16")) {
            assertThat(SupportScoreReader.parse(OcrText(text, 95))).isNull()
        }
        assertThat(SupportScoreReader.parse(OcrText("+62/+16", 74))).isNull()
        assertThat(SupportScoreReader.parse(OcrText("+62",92))).isEqualTo(SupportScores(62,null,SupportScoreDisplay.Ordinary))
        assertThat(SupportScoreReader.parse(OcrText("+0",92))).isEqualTo(SupportScores(0,null,SupportScoreDisplay.Ordinary))
    }

    @Test
    fun `applicable conditions are always AND while Grand is ignored only in confirmed ordinary displays`() {
        val cases = listOf(
            SupportScores(62,16) to listOf(true,true,true),
            SupportScores(0,16) to listOf(false,true,false),
            SupportScores(62,0) to listOf(true,false,false),
            SupportScores(16,62) to listOf(false,false,false),
            SupportScores() to listOf(false,false,false),
            SupportScores(62,null,SupportScoreDisplay.Compound) to listOf(true,false,false),
            SupportScores(62,null,SupportScoreDisplay.Ordinary) to listOf(true,true,true),
            SupportScores(0,null,SupportScoreDisplay.Ordinary) to listOf(false,true,false)
        )
        for ((scores, expected) in cases) {
            assertThat(SupportScoreSelection.matches(scores,false,false)).isEqualTo(true)
            assertThat(SupportScoreSelection.matches(scores,true,false)).isEqualTo(expected[0])
            assertThat(SupportScoreSelection.matches(scores,false,true)).isEqualTo(expected[1])
            assertThat(SupportScoreSelection.matches(scores,true,true)).isEqualTo(expected[2])
        }
    }

    @Test
    fun `compound reading requires two strong agreeing results and rejects later disagreement`() {
        assertThat(SupportScoreReader.confirmed(listOf(OcrText("+62/+16",92)))).isNull()
        assertThat(SupportScoreReader.confirmed(listOf(OcrText("+62/+16",92),OcrText("+62/+16",84)))).isNull()
        assertThat(SupportScoreReader.confirmed(listOf(
            OcrText("+62/+16",92),OcrText("+62/+16",91),OcrText("+62/+0",75)
        ))).isEqualTo(SupportScores(62,null,SupportScoreDisplay.Compound))
        assertThat(SupportScoreReader.confirmed(listOf(
            OcrText("+62/+16",92),OcrText("+62/+16",91),OcrText("+62/+0",74)
        ))).isEqualTo(SupportScores(62,16))
    }

    @Test
    fun `uncertain class readings do not veto a consistently confirmed grand score`() {
        val scores = SupportScoreReader.confirmed(listOf(
            OcrText("+0/+16",92),OcrText("+6/+16",91),OcrText("+0/+16",90)
        ))!!
        assertThat(scores).isEqualTo(SupportScores(null,16))
        assertThat(SupportScoreSelection.matches(scores,false,true)).isEqualTo(true)
        assertThat(SupportScoreSelection.matches(scores,true,true)).isEqualTo(false)
    }

    @Test
    fun `compact +62 +16 uses all profiles and closes the one snapshot crop`() {
        val f=Fixture()
        assertThat(f.reader.read(f.bounds,true,true)).isEqualTo(SupportScores(62,16))
        SupportScoreReader.OCR_PROFILES.forEach { verify(exactly=1) { f.ocr.detectTextLine(f.crop,it) } }
        verify(exactly=1) { f.crop.close() }
    }

    @Test
    fun `grand only accepts +0 +16 regardless of the class value`() {
        val f=Fixture()
        every { f.ocr.detectTextLine(f.crop,any()) } returns OcrText("+0/+16",92)
        val scores=f.reader.read(f.bounds,false,true)
        assertThat(SupportScoreSelection.matches(scores,false,true)).isEqualTo(true)
        verify(exactly=1) { f.api.images[Images.SupportScore62,null] }
        verify(exactly=1) { with(f.api) { f.region.exists(any(),any(),any()) } }
    }

    @Test
    fun `ordinary +62 preserves the existing fast class matcher`() {
        val f=Fixture()
        every { with(f.api) { f.region.exists(f.legacy,any(),any()) } } returns true
        assertThat(f.reader.read(f.bounds,true,false)).isEqualTo(SupportScores(62,null,SupportScoreDisplay.Ordinary))
        verify(exactly=0) { f.ocr.detectTextLine(any(),any()) }
        verify(exactly=0) { with(f.api) { f.region.getPattern(any()) } }
    }

    @Test
    fun `ordinary +62 ignores Grand even when both toggles are on`() {
        val f=Fixture()
        every { with(f.api) { f.region.exists(f.legacy,any(),any()) } } returns true
        every { f.ocr.detectTextLine(f.crop,any()) } returns OcrText("+62",92)
        val scores=f.reader.read(f.bounds,true,true)
        assertThat(scores).isEqualTo(SupportScores(62,null,SupportScoreDisplay.Ordinary))
        assertThat(SupportScoreSelection.matches(scores,true,true)).isEqualTo(true)
    }

    @Test
    fun `grand +62 cannot be reused as class +62 after a compound read`() {
        val f=Fixture()
        every { f.ocr.detectTextLine(f.crop,any()) } returns OcrText("+16/+62",92)
        val scores=f.reader.read(f.bounds,true,true)
        assertThat(scores).isEqualTo(SupportScores(16,62))
    }

    @Test
    fun `a suspicious or conflicting compound format cannot fall back to a legacy match`() {
        val f=Fixture()
        every { with(f.api) { f.region.exists(f.legacy,any(),any()) } } returns true
        every { f.ocr.detectTextLine(f.crop,any()) } returnsMany listOf(
            OcrText("+62/+16",92),OcrText("+62/+16",91),OcrText("+62/+16",90),OcrText("+16/+62",88)
        )
        assertThat(f.reader.read(f.bounds,true,true)).isEqualTo(SupportScores(display = SupportScoreDisplay.Compound))
        verify(exactly=4) { f.ocr.detectTextLine(f.crop,any()) }
    }

    @Test
    fun `clipping or an OCR exception never leaves a crop open`() {
        val f=Fixture()
        every { f.crop.width } returns 30
        assertThat(f.reader.read(f.bounds,false,true)).isEqualTo(SupportScores())
        verify(exactly=0) { f.ocr.detectTextLine(any(),any()) }
        verify(exactly=1) { f.crop.close() }
        every { f.crop.width } returns 55
        every { f.ocr.detectTextLine(any(),any()) } throws IllegalStateException("score OCR failed")
        kotlin.test.assertFailsWith<IllegalStateException> { f.reader.read(f.bounds,false,true) }
        verify(exactly=2) { f.crop.close() }
    }

    @Test
    fun `Grand only on ordinary non 62 is ignored but adding Class rejects it`() {
        val f = Fixture()
        every { f.ocr.detectTextLine(f.crop,any()) } returns OcrText("+0",92)
        val scores = f.reader.read(f.bounds,false,true)
        assertThat(scores).isEqualTo(SupportScores(0,null,SupportScoreDisplay.Ordinary))
        assertThat(SupportScoreSelection.matches(scores,false,true)).isEqualTo(true)
        assertThat(SupportScoreSelection.matches(scores,true,true)).isEqualTo(false)
    }

    @Test
    fun `unreadable or mixed format never silently ignores Grand`() {
        val f = Fixture()
        for (texts in listOf(List(4) { OcrText("+62",74) },
            listOf(OcrText("+62",92),OcrText("+62",92),OcrText("+62/+?",60),OcrText("+62",92)))) {
            every { f.ocr.detectTextLine(f.crop,any()) } returnsMany texts
            assertThat(SupportScoreSelection.matches(f.reader.read(f.bounds,false,true),false,true)).isEqualTo(false)
        }
    }

    @Test
    fun `both disabled avoid capture matching and OCR`() {
        val f=Fixture()
        assertThat(f.reader.read(f.bounds,false,false)).isEqualTo(SupportScores())
        verify(exactly=0) { f.api.images[any(),any()] }
        verify(exactly=0) { with(f.api) { f.region.getPattern(any()) } }
        verify(exactly=0) { f.ocr.detectTextLine(any(),any()) }
    }
}
