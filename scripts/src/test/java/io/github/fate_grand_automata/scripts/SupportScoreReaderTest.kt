package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreDisplay
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.locations.SupportScoreLayout
import io.github.fate_grand_automata.scripts.supportSelection.SupportBounds
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreReader
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreSelection
import io.github.fate_grand_automata.scripts.supportSelection.SupportScores
import io.github.lib_automata.OcrService
import io.github.lib_automata.Match
import io.github.lib_automata.OcrText
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test

class SupportScoreReaderTest {
    @kotlin.test.AfterTest
    fun releaseMocks() = io.mockk.clearAllMocks()

    private class Fixture {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val images = mockk<IImageLoader>()
        val ocr = mockk<OcrService>()
        val legacy = mockk<Pattern>()
        val crop = mockk<Pattern>(relaxed = true)
        val prepared = mockk<Pattern>(relaxed = true)
        val bounds = SupportBounds(Region(76, 408, 2356, 428))
        val region = SupportScoreLayout.numberRegion(bounds.region)
        val reader = SupportScoreReader(api, ocr)
        val visual = (SupportScoreReader.COMPACT_CLASS_TEMPLATES + SupportScoreReader.GRAND_TEMPLATES)
            .associateWith { mockk<Pattern>() }
        val digits = SupportScoreReader.GRAND_TEMPLATES.associateWith { mockk<Pattern>(relaxed = true) }
        init {
            every { api.prefs.gameServer } returns GameServer.Jp.Original
            every { api.images } returns images
            every { images[Images.SupportScore62, null] } returns legacy
            every { with(api) { region.exists(legacy, any(), any()) } } returns false
            every { with(api) { region.getPattern("support_class_grand_scores") } } returns crop
            every { crop.width } returns 60
            every { crop.height } returns 30
            every { crop.resize(any()) } returns prepared
            val binary = mockk<Pattern>(relaxed = true)
            every { prepared.threshold(any()) } returns binary
            every { binary.grayPixels() } returns null
            every { binary.fillText() } returns prepared
            every { prepared.fillText() } returns prepared
            every { prepared.width } returns 60
            every { prepared.height } returns 30
            every { prepared.grayPixels() } returns scoreInk()

            every { ocr.detectTextLine(crop, any()) } returns OcrText("+62/+16", 92)
            visual.forEach { (image, pattern) ->
                every { images[image, null] } returns pattern
                every { with(api) { region.exists(pattern, any(), any()) } } returns false
                every { with(api) { region.findAll(pattern, any()) } } returns emptyList()
                every { pattern.width } returns if ("Large" in image.name) 34 else 29
                every { pattern.height } returns if ("Large" in image.name) 21 else 19
            }
        }
        fun show(image: Images, correctLastDigit: Boolean = true) {
            val pattern = visual.getValue(image)
            if (image !in SupportScoreReader.GRAND_TEMPLATES) {
                every { with(api) { region.exists(pattern, any(), any()) } } returns true
            } else {
                val matched = Region(region.x, region.y, pattern.width * 2, pattern.height * 2)
                every { with(api) { region.findAll(pattern, any()) } } returns listOf(Match(matched, .98))
                val pixels = SupportScoreLayout.grandLastDigit(pattern.width, pattern.height)
                val digit = digits.getValue(image)
                every { pattern.crop(pixels) } returns digit
                val digitRegion = SupportScoreLayout.matchedPixels(matched, pattern.width, pattern.height, pixels)
                every { with(api) { digitRegion.exists(digit, any(), any()) } } returns correctLastDigit
            }
        }
    }

    @Test
    fun `slash separates class from grand and an ordinary score has no implicit grand`() {
        assertThat(SupportScoreReader.parse(OcrText("+62/+16", 92))).isEqualTo(SupportScores(62, 16))
        assertThat(SupportScoreReader.parse(OcrText(" +0 / +16\n", 92))).isEqualTo(SupportScores(0, 16))
        assertThat(SupportScoreReader.parse(OcrText("+16/+62", 92))).isEqualTo(SupportScores(16, null, SupportScoreDisplay.Compound))
        assertThat(SupportScoreReader.parse(OcrText("62/+16", 92))).isEqualTo(SupportScores(62, 16))
        assertThat(SupportScoreReader.parse(OcrText("+62/16", 92))).isEqualTo(SupportScores(62, 16))
        for (text in listOf("+62+16", "+62/+16/+0", "score +62/+16")) {
            assertThat(SupportScoreReader.parse(OcrText(text, 95))).isNull()
        }
        assertThat(SupportScoreReader.parse(OcrText("+62/+16", 74))).isNull()
        assertThat(SupportScoreReader.parse(OcrText("+62",92))).isEqualTo(SupportScores(62,null,SupportScoreDisplay.Ordinary))
        assertThat(SupportScoreReader.parse(OcrText("+0",92))).isEqualTo(SupportScores(0,null,SupportScoreDisplay.Ordinary))
    }

    @Test
    fun `Grand requires a compound display and enabled conditions are always AND`() {
        val cases = listOf(
            SupportScores(62,16) to listOf(true,true,true),
            SupportScores(0,16) to listOf(false,true,false),
            SupportScores(62,0) to listOf(true,false,false),
            SupportScores(16,62) to listOf(false,false,false),
            SupportScores() to listOf(false,false,false),
            SupportScores(62,null,SupportScoreDisplay.Compound) to listOf(true,false,false),
            SupportScores(62,null,SupportScoreDisplay.Ordinary) to listOf(true,false,false),
            SupportScores(0,null,SupportScoreDisplay.Ordinary) to listOf(false,false,false),
            SupportScores(62,16,SupportScoreDisplay.Unknown) to listOf(true,false,false)
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
        verify(exactly=1) { f.images[Images.SupportScore62,null] }
        verify(exactly=1) { with(f.api) { f.region.exists(any(),any(),any()) } }
    }

    @Test
    fun `ordinary +62 checks OCR before accepting the legacy matcher`() {
        val f=Fixture()
        every { with(f.api) { f.region.exists(f.legacy,any(),any()) } } returns true
        every { f.ocr.detectTextLine(f.crop,any()) } returns OcrText("+62",92)
        assertThat(f.reader.read(f.bounds,true,false)).isEqualTo(SupportScores(62,null,SupportScoreDisplay.Ordinary))
        verify(exactly=4) { f.ocr.detectTextLine(any(),any()) }
        verify(exactly=1) { with(f.api) { f.region.getPattern(any()) } }
    }

    @Test
    fun `ordinary +62 passes Class only but never a requested Grand condition`() {
        val f=Fixture()
        every { with(f.api) { f.region.exists(f.legacy,any(),any()) } } returns true
        every { f.ocr.detectTextLine(f.crop,any()) } returns OcrText("+62",92)
        val scores=f.reader.read(f.bounds,true,true)
        assertThat(scores).isEqualTo(SupportScores(62,null,SupportScoreDisplay.Ordinary))
        assertThat(SupportScoreSelection.matches(scores,true,false)).isEqualTo(true)
        assertThat(SupportScoreSelection.matches(scores,false,true)).isEqualTo(false)
        assertThat(SupportScoreSelection.matches(scores,true,true)).isEqualTo(false)
    }

    @Test
    fun `grand +62 cannot be reused as class +62 after a compound read`() {
        val f=Fixture()
        every { f.ocr.detectTextLine(f.crop,any()) } returns OcrText("+16/+62",92)
        val scores=f.reader.read(f.bounds,true,true)
        assertThat(scores).isEqualTo(SupportScores(16,null,SupportScoreDisplay.Compound))
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
        every { f.crop.width } returns 60
        every { f.ocr.detectTextLine(any(),any()) } throws IllegalStateException("score OCR failed")
        kotlin.test.assertFailsWith<IllegalStateException> { f.reader.read(f.bounds,false,true) }
        verify(exactly=2) { f.crop.close() }
    }

    @Test
    fun `KR dark-panel retry recovers exact ordinary scores without inventing Grand`() {
        for (number in listOf(0, 61, 62)) {
            val f = Fixture()
            every { f.api.prefs.gameServer } returns GameServer.Kr
            every { f.ocr.detectTextLine(f.crop, any()) } answers {
                if (secondArg<io.github.lib_automata.TextLineOcrOptions>().threshold == SupportScoreReader.OCR_DARK_THRESHOLD)
                    OcrText("+$number", 96) else OcrText("", 0)
            }
            val scores = f.reader.read(f.bounds, true, true)
            assertThat(scores).isEqualTo(SupportScores(number, null, SupportScoreDisplay.Ordinary))
            assertThat(SupportScoreSelection.matches(scores, true, false)).isEqualTo(number == 62)
            assertThat(SupportScoreSelection.matches(scores, true, true)).isEqualTo(false)
            verify(exactly = 1) { f.crop.close() }
        }
    }

    @Test
    fun `dark-panel retry retains primary conflicts and requires two strong readings`() {
        val f = Fixture()
        every { f.api.prefs.gameServer } returns GameServer.Kr
        var calls = 0
        every { f.ocr.detectTextLine(f.crop, any()) } answers {
            when {
                calls++ == 0 -> OcrText("+61", 92)
                secondArg<io.github.lib_automata.TextLineOcrOptions>().threshold == SupportScoreReader.OCR_DARK_THRESHOLD -> OcrText("+62", 96)
                else -> OcrText("", 0)
            }
        }
        assertThat(SupportScoreSelection.matches(f.reader.read(f.bounds, true, true), true, false)).isEqualTo(false)
        every { f.ocr.detectTextLine(f.crop, any()) } answers {
            if (secondArg<io.github.lib_automata.TextLineOcrOptions>().threshold == SupportScoreReader.OCR_DARK_THRESHOLD)
                OcrText("+62", 84) else OcrText("", 0)
        }
        assertThat(SupportScoreSelection.matches(f.reader.read(f.bounds, true, true), true, false)).isEqualTo(false)
    }

    @Test
    fun `ordinary non 62 cannot satisfy Class or Grand`() {
        val f = Fixture()
        every { f.ocr.detectTextLine(f.crop,any()) } returns OcrText("+0",92)
        val scores = f.reader.read(f.bounds,false,true)
        assertThat(scores).isEqualTo(SupportScores(0,null,SupportScoreDisplay.Ordinary))
        assertThat(SupportScoreSelection.matches(scores,false,true)).isEqualTo(false)
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
        verify(exactly=0) { f.images[any(),any()] }
        verify(exactly=0) { with(f.api) { f.region.getPattern(any()) } }
        verify(exactly=0) { f.ocr.detectTextLine(any(),any()) }
    }

    @Test
    fun `real JP 56 16 OCR failure can recover Grand without requiring Class 62`() {
        val f = Fixture()
        every { f.ocr.detectTextLine(f.crop, any()) } returnsMany listOf(
            OcrText("56/416", 33), OcrText("56/ +16", 60), OcrText("+6", 50), OcrText("56/ +16", 55)
        )
        f.show(Images.SupportGrand16A)
        val scores = f.reader.read(f.bounds, false, true)
        assertThat(scores).isEqualTo(SupportScores(null, 16, SupportScoreDisplay.Compound))
        assertThat(SupportScoreSelection.matches(scores, false, true)).isEqualTo(true)
        SupportScoreReader.COMPACT_CLASS_TEMPLATES.forEach { verify(exactly = 0) { f.images[it, null] } }
    }

    @Test
    fun `recovering Grand alone cannot invent Class 62 when both are requested`() {
        val f = Fixture()
        every { f.ocr.detectTextLine(f.crop, any()) } returns OcrText("56/+16", 60)
        f.show(Images.SupportGrand16LargeA)
        val scores = f.reader.read(f.bounds, true, true)
        assertThat(scores).isEqualTo(SupportScores(null, 16))
        assertThat(SupportScoreSelection.matches(scores, true, true)).isEqualTo(false)
    }

    @Test
    fun `two visually confirmed compact fields recover both conditions`() {
        val f = Fixture()
        every { f.ocr.detectTextLine(f.crop, any()) } returns OcrText("62/+16", 2)
        f.show(Images.SupportCompactClass62B)
        f.show(Images.SupportGrand16C)
        val scores = f.reader.read(f.bounds, true, true)
        assertThat(scores).isEqualTo(SupportScores(62, 16))
        assertThat(SupportScoreSelection.matches(scores, true, true)).isEqualTo(true)
    }

    @Test
    fun `compact Class recovery never silently ignores an unreadable Grand`() {
        val f = Fixture()
        every { f.ocr.detectTextLine(f.crop, any()) } returns OcrText("62/+?", 25)
        f.show(Images.SupportCompactClass62A)
        val scores = f.reader.read(f.bounds, true, true)
        assertThat(scores).isEqualTo(SupportScores(62, null, SupportScoreDisplay.Compound))
        assertThat(SupportScoreSelection.matches(scores, true, true)).isEqualTo(false)
    }

    @Test
    fun `compact Class only does not inspect Grand templates`() {
        val f = Fixture()
        every { f.ocr.detectTextLine(f.crop, any()) } returns OcrText("62/+0", 30)
        f.show(Images.SupportCompactClass62C)
        assertThat(f.reader.read(f.bounds, true, false)).isEqualTo(SupportScores(62, null, SupportScoreDisplay.Compound))
        SupportScoreReader.GRAND_TEMPLATES.forEach { verify(exactly = 0) { f.images[it, null] } }
    }

    @Test
    fun `template conflicts veto each field even when OCR disagrees internally`() {
        val f = Fixture()
        every { f.ocr.detectTextLine(f.crop, any()) } returnsMany listOf(
            OcrText("56/+0", 92), OcrText("56/+16", 91), OcrText("56/+0", 90), OcrText("56/+16", 89)
        )
        f.show(Images.SupportCompactClass62A)
        f.show(Images.SupportGrand16A)
        assertThat(f.reader.read(f.bounds, true, true)).isEqualTo(SupportScores(display = SupportScoreDisplay.Compound))
    }

    @Test
    fun `a confirmed Grand zero is never overridden by visual recovery`() {
        val f = Fixture()
        every { f.ocr.detectTextLine(f.crop, any()) } returns OcrText("+62/+0", 92)
        f.show(Images.SupportGrand16A)
        assertThat(f.reader.read(f.bounds, false, true)).isEqualTo(SupportScores(62, null, SupportScoreDisplay.Compound))
    }

    @Test
    fun `a clipped score cannot bypass Grand using the ordinary legacy match`() {
        val f = Fixture()
        every { with(f.api) { f.region.exists(f.legacy, any(), any()) } } returns true
        every { f.crop.width } returns 30
        assertThat(f.reader.read(f.bounds, true, true)).isEqualTo(SupportScores())
        verify(exactly = 0) { f.ocr.detectTextLine(any(), any()) }
        f.visual.keys.forEach { verify(exactly = 0) { f.images[it, null] } }
    }

    @Test
    fun `a similar prefix cannot supply Grand 16 when the final digit differs`() {
        val f = Fixture()
        every { f.ocr.detectTextLine(f.crop, any()) } returns OcrText("56/+18", 20)
        f.show(Images.SupportGrand16A, correctLastDigit = false)
        val scores = f.reader.read(f.bounds, false, true)
        assertThat(SupportScoreSelection.matches(scores, false, true)).isEqualTo(false)
        verify(exactly = 1) { f.digits.getValue(Images.SupportGrand16A).close() }
    }

    @Test
    fun `2340 frame templates recover Grand 16 when OCR fails`() {
        for (image in listOf(Images.SupportGrand16D, Images.SupportGrand16LargeD)) {
            val f = Fixture()
            every { f.ocr.detectTextLine(f.crop, any()) } returnsMany listOf(
                OcrText("6/+1", 0), OcrText("+4", 0), OcrText("+1", 0), OcrText("6 +16", 42)
            )
            f.show(image)
            assertThat(f.reader.read(f.bounds, false, true)).isEqualTo(SupportScores(null, 16, SupportScoreDisplay.Compound))
        }
    }

    @Test
    fun `range violations poison only their own field across profiles and templates`() {
        val good = List(2) { OcrText("62/16", 92) }
        assertThat(SupportScoreReader.confirmed(good + OcrText("63/16", 75)))
            .isEqualTo(SupportScores(null, 16))
        assertThat(SupportScoreReader.confirmed(good + OcrText("62/17", 75)))
            .isEqualTo(SupportScores(62, null, SupportScoreDisplay.Compound))
        assertThat(SupportScoreReader.parse(OcrText("0/0", 92))).isEqualTo(SupportScores(0, 0))
        assertThat(SupportScoreReader.parse(OcrText("-1/16", 92))).isEqualTo(SupportScores(null, 16))
        assertThat(SupportScoreReader.parse(OcrText("62/-1", 92)))
            .isEqualTo(SupportScores(62, null, SupportScoreDisplay.Compound))
        assertThat(SupportScoreReader.parse(OcrText("999999999999999999/16", 92)))
            .isEqualTo(SupportScores(null, 16))
        assertThat(SupportScoreReader.parse(OcrText("999/999", 92)))
            .isEqualTo(SupportScores(display = SupportScoreDisplay.Compound))
        val f = Fixture()
        every { f.ocr.detectTextLine(f.crop, any()) } returns OcrText("63/17", 92)
        f.show(Images.SupportCompactClass62A); f.show(Images.SupportGrand16A)
        assertThat(f.reader.read(f.bounds, true, true)).isEqualTo(SupportScores(display = SupportScoreDisplay.Compound))
    }

    @Test
    fun `ordinary legacy template disagreement is unknown for KR too`() {
        val f = Fixture()
        every { f.api.prefs.gameServer } returns GameServer.Kr
        every { with(f.api) { f.region.exists(f.legacy, any(), any()) } } returns true
        every { f.ocr.detectTextLine(f.crop, any()) } returns OcrText("61", 92)
        assertThat(f.reader.read(f.bounds, true, false).classScore).isNull()
    }

    @Test
    fun `199 row 2 OCR one cannot override the Grand 16 template`() {
        val f = Fixture()
        every { f.ocr.detectTextLine(f.crop, any()) } returns OcrText("62/+1", 92)
        f.show(Images.SupportGrand16A)
        assertThat(f.reader.read(f.bounds, true, true)).isEqualTo(SupportScores(62, null, SupportScoreDisplay.Compound))
    }

    @Test
    fun `both directions of grand digit count mismatch veto OCR and tail recovery`() {
        val f = Fixture()
        every { f.ocr.detectTextLine(f.crop, any()) } returns OcrText("62/1", 92)
        assertThat(f.reader.read(f.bounds, true, true).grandScore).isNull()
        val single = scoreInk().also { p -> for (y in 0..29) for (x in 47..53) p[y*60+x] = 255.toByte() }
        every { f.prepared.grayPixels() } returns single
        every { f.ocr.detectTextLine(f.crop, any()) } returns OcrText("S9/16", 92)
        assertThat(f.reader.read(f.bounds, true, true).grandScore).isNull()
    }

    @Test
    fun `right margin ink vetoes only the final field including template fallback`() {
        val f = Fixture()
        val clipped = scoreInk().also { p -> for (y in 11..23) for (x in 53..59) p[y*60+x] = 0 }
        every { f.prepared.grayPixels() } returns clipped
        f.show(Images.SupportGrand16A)
        assertThat(f.reader.read(f.bounds, true, true)).isEqualTo(SupportScores(62, null, SupportScoreDisplay.Compound))
        every { f.ocr.detectTextLine(f.crop, any()) } returns OcrText("62", 92)
        every { with(f.api) { f.region.exists(f.legacy, any(), any()) } } returns true
        assertThat(f.reader.read(f.bounds, true, false).classScore).isNull()
    }

    @Test
    fun `open outline lost during filling is still clipped in the original binary margin`() {
        val raw = ByteArray(58 * 28) { 255.toByte() }
        for (y in 10..24) raw[(y-1)*58+57] = if (y in 13..20) 255.toByte() else 0
        val ink = io.github.fate_grand_automata.scripts.supportSelection.SupportScoreInk(scoreInk(), 60, 30, raw)
        assertThat(ink.clipped(2)).isEqualTo(true)
    }

    @Test
    fun `OCR 16 is vetoed when a similar template prefix has a different final digit`() {
        val f = Fixture()
        f.show(Images.SupportGrand16A, correctLastDigit = false)
        assertThat(f.reader.read(f.bounds, true, true).grandScore).isNull()
    }

    @Test
    fun `Grand tail OCR fallback needs exactly 16 after the slash and strong agreement`() {
        assertThat(SupportScoreReader.grandTail16(listOf(OcrText("S9/+16", 90), OcrText("/+16", 88)))).isEqualTo(true)
        assertThat(SupportScoreReader.grandTail16(listOf(OcrText("S9/+16", 90)))).isEqualTo(false)
        assertThat(SupportScoreReader.grandTail16(listOf(OcrText("S9/+16", 90), OcrText("S9/+16", 84)))).isEqualTo(false)
        assertThat(SupportScoreReader.grandTail16(listOf(OcrText("S9/+16", 90), OcrText("S9/+16", 88), OcrText("5/+18", 80)))).isEqualTo(false)
        assertThat(SupportScoreReader.grandTail16(listOf(OcrText("S9/+1", 90), OcrText("S9/+1", 88)))).isEqualTo(false)
        assertThat(SupportScoreReader.grandTail16(listOf(OcrText("S9/+116", 90), OcrText("S9/+116", 88)))).isEqualTo(false)
        assertThat(SupportScoreReader.grandTail16(listOf(OcrText("+16", 90), OcrText("+16", 88)))).isEqualTo(false)
        val f = Fixture()
        every { f.ocr.detectTextLine(f.crop, any()) } returns OcrText("S9/+16", 90)
        assertThat(f.reader.read(f.bounds, false, true)).isEqualTo(SupportScores(null, 16, SupportScoreDisplay.Compound))
        assertThat(f.reader.read(f.bounds, true, false)).isEqualTo(SupportScores(display = SupportScoreDisplay.Compound))
    }
}

/** Filled OCR ink with a slash, plus and two independent digit bodies. */
internal fun scoreInk(): ByteArray = ByteArray(60 * 30) { 255.toByte() }.also { pixels ->
    fun box(x: Int, y: Int, w: Int, h: Int) {
        for (yy in y until y+h) for (xx in x until x+w) pixels[yy*60+xx] = 0
    }
    for (y in 10..24) box(29-(y-10)/3,y,2,1)
    box(33,15,6,5)
    box(41,11,3,13)
    box(47,11,6,13)
}
