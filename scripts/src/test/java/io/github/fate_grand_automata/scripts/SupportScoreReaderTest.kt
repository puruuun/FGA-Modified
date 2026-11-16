package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreDisplay
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.locations.SupportScoreLayout
import io.github.fate_grand_automata.scripts.supportSelection.SupportBounds
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreDarkRetry
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreReadProfile
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
    fun `JP block recovery still obeys OCR and final digit vetoes while KR keeps its path`() {
        val f = Fixture()
        val image = javax.imageio.ImageIO.read(java.io.File(
            "../docs/reference-images/support-score-d2/scores/d2-1920x1080-01-row-2.png"))
        val pixels = ByteArray(image.width * image.height) { p ->
            val rgb = image.getRGB(p % image.width, p / image.width)
            (((rgb shr 16 and 255) * 299 + (rgb shr 8 and 255) * 587 + (rgb and 255) * 114 + 500) / 1000).toByte()
        }
        every { f.crop.grayPixels() } returns pixels
        every { f.ocr.detectTextLine(f.crop, any()) } returns OcrText("", 0)
        assertThat(f.reader.read(f.bounds, true, true)).isEqualTo(SupportScores(62, 16))
        every { f.ocr.detectTextLine(f.crop, any()) } returns OcrText("51/15", 92)
        assertThat(f.reader.read(f.bounds, true, true)).isEqualTo(SupportScores(display = SupportScoreDisplay.Compound))
        every { f.ocr.detectTextLine(f.crop, any()) } returns OcrText("", 0)
        f.show(Images.SupportGrand16A, correctLastDigit = false)
        assertThat(f.reader.read(f.bounds, true, true).grandScore).isNull()
        every { f.api.prefs.gameServer } returns GameServer.Kr
        assertThat(f.reader.read(f.bounds, true, true).classScore).isNull()
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

    @Test
    fun `profile selection per server picks KR JP and Default on every read call`() {
        assertThat(SupportScoreReadProfile.forServer(GameServer.Kr)).isEqualTo(SupportScoreReadProfile.Kr)
        assertThat(SupportScoreReadProfile.forServer(GameServer.Jp.Original)).isEqualTo(SupportScoreReadProfile.Jp)
        assertThat(SupportScoreReadProfile.forServer(GameServer.Jp.BetterFGO)).isEqualTo(SupportScoreReadProfile.Jp)
        assertThat(SupportScoreReadProfile.forServer(GameServer.En.Original)).isEqualTo(SupportScoreReadProfile.Default)
        assertThat(SupportScoreReadProfile.forServer(GameServer.Cn)).isEqualTo(SupportScoreReadProfile.Default)
        assertThat(SupportScoreReadProfile.forServer(GameServer.Tw)).isEqualTo(SupportScoreReadProfile.Default)

        val f = Fixture()
        every { f.ocr.detectTextLine(f.crop, any()) } returns OcrText("62/16", 80)
        every { f.api.prefs.gameServer } returns GameServer.Jp.Original
        f.reader.read(f.bounds, true, false)
        verify(exactly = 4) { f.ocr.detectTextLine(f.crop, any()) }

        every { f.api.prefs.gameServer } returns GameServer.Kr
        f.reader.read(f.bounds, true, false)
        verify(exactly = 10) { f.ocr.detectTextLine(f.crop, any()) }
    }

    @Test
    fun `KR JP and Default profiles equal the old constants field by field`() {
        val expectedBase = SupportScoreReadProfile(
            name = "default",
            primaryOcrProfiles = SupportScoreReader.OCR_PROFILES,
            darkOcrProfiles = SupportScoreReader.OCR_DARK_PROFILES,
            darkRetry = SupportScoreDarkRetry.Never,
            minOcrConfidence = SupportScoreReader.MIN_OCR_CONFIDENCE,
            minConfirmConfidence = SupportScoreReader.MIN_CONFIRM_CONFIDENCE,
            minAgreement = SupportScoreReader.MIN_AGREEMENT,
            compactFieldSimilarity = SupportScoreReader.COMPACT_FIELD_SIMILARITY,
            grandFieldSimilarity = SupportScoreReader.GRAND_FIELD_SIMILARITY,
            legacyScoreSimilarity = SupportScoreSelection.SCORE_SIMILARITY,
            useBlocks = false,
            ignoreSlashlessPartialsInCompound = false,
            grandTail416AsPlus16 = false,
            noGlyphNotClippedWithVerifiedGrand = false
        )
        assertThat(SupportScoreReadProfile.Default).isEqualTo(expectedBase)
        assertThat(SupportScoreReadProfile.Kr).isEqualTo(
            expectedBase.copy(name = "kr", darkRetry = SupportScoreDarkRetry.WhenInconclusive)
        )
        assertThat(SupportScoreReadProfile.Jp).isEqualTo(
            expectedBase.copy(
                name = "jp",
                darkRetry = SupportScoreDarkRetry.Never,
                useBlocks = true,
                ignoreSlashlessPartialsInCompound = true,
                grandTail416AsPlus16 = true,
                noGlyphNotClippedWithVerifiedGrand = true
            )
        )
    }

    @Test
    fun `dark retry runs on KR when inconclusive and never on JP or Default`() {
        for ((server, expectedCalls) in listOf(
            GameServer.Kr to 6,
            GameServer.Jp.Original to 4,
            GameServer.En.Original to 4
        )) {
            val f = Fixture()
            every { f.api.prefs.gameServer } returns server
            every { f.ocr.detectTextLine(f.crop, any()) } returns OcrText("", 0)
            f.reader.read(f.bounds, true, false)
            verify(exactly = expectedCalls) { f.ocr.detectTextLine(f.crop, any()) }
        }
    }

    @Test
    fun `injected profile override changes agreement requirement proving wiring`() {
        val f = Fixture()
        every { f.ocr.detectTextLine(f.crop, any()) } returnsMany listOf(
            OcrText("+62/+16", 92), OcrText("+62/+16", 91), OcrText("+62/+16", 74), OcrText("+62/+16", 74)
        )
        assertThat(f.reader.read(f.bounds, true, true)).isEqualTo(SupportScores(62, 16))

        val f2 = Fixture()
        f2.reader.profileOverrideForTest = SupportScoreReadProfile.Jp.copy(minAgreement = 3)
        every { f2.ocr.detectTextLine(f2.crop, any()) } returnsMany listOf(
            OcrText("+62/+16", 92), OcrText("+62/+16", 91), OcrText("+62/+16", 74), OcrText("+62/+16", 74)
        )
        assertThat(f2.reader.read(f2.bounds, true, true)).isEqualTo(SupportScores(display = SupportScoreDisplay.Compound))
    }

    @Test
    fun `Fix A ignores slashless partials matching a slash read grand tail on JP only`() {
        // JP, 010: 62/416(50), 62 +16(48), 62/416(72), +16(92), Class62A + Grand16A -> 62/16
        val f010 = Fixture()
        every { f010.ocr.detectTextLine(f010.crop, any()) } returnsMany listOf(
            OcrText("62/416", 50), OcrText("62 +16", 48), OcrText("62/416", 72), OcrText("+16", 92)
        )
        f010.show(Images.SupportCompactClass62A)
        f010.show(Images.SupportGrand16A)
        assertThat(f010.reader.read(f010.bounds, true, true)).isEqualTo(SupportScores(62, 16))

        // JP, 048: 62/+16(75), 12/416(0), 62/+16(75), +16(92), Class62B + Grand16B, differentGrandDigit=true on A -> 62/16
        val f048 = Fixture()
        every { f048.ocr.detectTextLine(f048.crop, any()) } returnsMany listOf(
            OcrText("62/+16", 75), OcrText("12/416", 0), OcrText("62/+16", 75), OcrText("+16", 92)
        )
        f048.show(Images.SupportCompactClass62B)
        f048.show(Images.SupportGrand16A, correctLastDigit = false)
        f048.show(Images.SupportGrand16B)
        assertThat(f048.reader.read(f048.bounds, true, true)).isEqualTo(SupportScores(62, 16))

        // JP, 061: 62/ 416(14), 62 +16(80), 62/ +416(42), +16(96), Class62A + Grand16A -> 62/16
        val f061 = Fixture()
        every { f061.ocr.detectTextLine(f061.crop, any()) } returnsMany listOf(
            OcrText("62/ 416", 14), OcrText("62 +16", 80), OcrText("62/ +416", 42), OcrText("+16", 96)
        )
        f061.show(Images.SupportCompactClass62A)
        f061.show(Images.SupportGrand16A)
        assertThat(f061.reader.read(f061.bounds, true, true)).isEqualTo(SupportScores(62, 16))

        // Guard: +56x2 and +16x2 with class62 template and no slash read -> Class unknown (templates do not prove slash).
        val f56And16 = Fixture()
        every { f56And16.ocr.detectTextLine(f56And16.crop, any()) } returnsMany listOf(
            OcrText("+56", 90), OcrText("+56", 90), OcrText("+16", 92), OcrText("+16", 92)
        )
        f56And16.show(Images.SupportCompactClass62A)
        assertThat(f56And16.reader.read(f56And16.bounds, true, false).classScore).isNull()

        // Guard: 62/+16(80) plus +56(90) with class62 template -> Class unknown (+56 is not the Grand tail, so it stays as a veto).
        val fSlashPlus56 = Fixture()
        every { fSlashPlus56.ocr.detectTextLine(fSlashPlus56.crop, any()) } returnsMany listOf(
            OcrText("62/+16", 80), OcrText("+56", 90), OcrText("", 0), OcrText("", 0)
        )
        fSlashPlus56.show(Images.SupportCompactClass62A)
        assertThat(fSlashPlus56.reader.read(fSlashPlus56.bounds, true, false).classScore).isNull()

        // Guard: +16x4 with no slash anywhere, no template -> class unknown (16 != 62), as today.
        val fNoSlash = Fixture()
        every { fNoSlash.ocr.detectTextLine(fNoSlash.crop, any()) } returns OcrText("+16", 92)
        val noSlashScores = fNoSlash.reader.read(fNoSlash.bounds, true, false)
        assertThat(SupportScoreSelection.matches(noSlashScores, true, false)).isEqualTo(false)
        // Guard: template-only, +16x4 with no slash read -> Class unknown (template alone no longer drops +16).
        val fOnlyClassTpl = Fixture()
        every { fOnlyClassTpl.ocr.detectTextLine(fOnlyClassTpl.crop, any()) } returns OcrText("+16", 92)
        fOnlyClassTpl.show(Images.SupportCompactClass62A)
        assertThat(fOnlyClassTpl.reader.read(fOnlyClassTpl.bounds, true, false).classScore).isNull()

        // Guard: 58/+16 at high confidence with the 62 template -> class unknown
        val f58 = Fixture()
        every { f58.ocr.detectTextLine(f58.crop, any()) } returns OcrText("58/+16", 92)
        f58.show(Images.SupportCompactClass62A)
        assertThat(f58.reader.read(f58.bounds, true, false).classScore).isNull()

        // Guard: +1/+16 and +16/+62 -> unchanged from today
        val fPlus1 = Fixture()
        every { fPlus1.ocr.detectTextLine(fPlus1.crop, any()) } returns OcrText("+1/+16", 92)
        fPlus1.show(Images.SupportCompactClass62A)
        assertThat(fPlus1.reader.read(fPlus1.bounds, true, true)).isEqualTo(SupportScores(null, 16, SupportScoreDisplay.Compound))

        val fSwapped = Fixture()
        every { fSwapped.ocr.detectTextLine(fSwapped.crop, any()) } returns OcrText("+16/+62", 92)
        assertThat(fSwapped.reader.read(fSwapped.bounds, true, true)).isEqualTo(SupportScores(16, null, SupportScoreDisplay.Compound))

        // KR, same inputs -> today's result (blocked by +16 partial read)
        val fKr = Fixture()
        every { fKr.api.prefs.gameServer } returns GameServer.Kr
        every { fKr.ocr.detectTextLine(fKr.crop, any()) } returnsMany listOf(
            OcrText("62/416", 50), OcrText("62 +16", 48), OcrText("62/416", 72), OcrText("+16", 92),
            OcrText("", 0), OcrText("", 0)
        )
        fKr.show(Images.SupportCompactClass62A)
        assertThat(fKr.reader.read(fKr.bounds, true, false).classScore).isNull()
    }

    @Test
    fun `Grand tails for the slashless filter need a Class digit and an in-range tail on JP`() {
        // Finding 1: "/+16" has no digit before the slash, so it gives no tail and "+16" keeps vetoing Class.
        val fNoPrefix = Fixture()
        every { fNoPrefix.ocr.detectTextLine(fNoPrefix.crop, any()) } returnsMany listOf(
            OcrText("/+16", 90), OcrText("+16", 92), OcrText("", 0), OcrText("", 0)
        )
        fNoPrefix.show(Images.SupportCompactClass62A)
        assertThat(fNoPrefix.reader.read(fNoPrefix.bounds, true, false).classScore).isNull()

        // KR with the same input: same result as before (Class unknown).
        val fNoPrefixKr = Fixture()
        every { fNoPrefixKr.api.prefs.gameServer } returns GameServer.Kr
        every { fNoPrefixKr.ocr.detectTextLine(fNoPrefixKr.crop, any()) } returnsMany listOf(
            OcrText("/+16", 90), OcrText("+16", 92), OcrText("", 0), OcrText("", 0), OcrText("", 0), OcrText("", 0)
        )
        fNoPrefixKr.show(Images.SupportCompactClass62A)
        assertThat(fNoPrefixKr.reader.read(fNoPrefixKr.bounds, true, false).classScore).isNull()

        // Finding 2: a +56 tail is outside the Grand range, so "+56" is not ignored and vetoes Class.
        val fTail56 = Fixture()
        every { fTail56.ocr.detectTextLine(fTail56.crop, any()) } returnsMany listOf(
            OcrText("62/+56", 90), OcrText("+56", 90), OcrText("", 0), OcrText("", 0)
        )
        fTail56.show(Images.SupportCompactClass62A)
        assertThat(fTail56.reader.read(fTail56.bounds, true, false).classScore).isNull()

        // An out-of-range raw +416 tail is not a tail either.
        val fTail416 = Fixture()
        every { fTail416.ocr.detectTextLine(fTail416.crop, any()) } returnsMany listOf(
            OcrText("62/+416", 90), OcrText("+416", 90), OcrText("", 0), OcrText("", 0)
        )
        fTail416.show(Images.SupportCompactClass62A)
        assertThat(fTail416.reader.read(fTail416.bounds, true, false).classScore).isNull()

        // A slash read that does not show Class 62 is no Grand-tail source, so the "+16" keeps its veto
        // and a false-positive Class62 template cannot make Class 62.
        for (garbage in listOf("58/16" to 40, "S9/+16" to 90, "16+/+16" to 90, "16//16" to 90)) {
            val fVeto = Fixture()
            every { fVeto.ocr.detectTextLine(fVeto.crop, any()) } returnsMany listOf(
                OcrText(garbage.first, garbage.second), OcrText("+16", 92), OcrText("", 0), OcrText("", 0)
            )
            fVeto.show(Images.SupportCompactClass62A)
            assertThat(fVeto.reader.read(fVeto.bounds, true, false).classScore).isNull()
        }

        // Two strong plain /16 reads are used as a tail: "+16" is ignored, so 62/16 resolves.
        val fPlain = Fixture()
        every { fPlain.ocr.detectTextLine(fPlain.crop, any()) } returnsMany listOf(
            OcrText("62/16", 60), OcrText("62/16", 60), OcrText("+16", 92), OcrText("", 0)
        )
        fPlain.show(Images.SupportCompactClass62A)
        fPlain.show(Images.SupportGrand16A)
        assertThat(fPlain.reader.read(fPlain.bounds, true, true)).isEqualTo(SupportScores(62, 16))
    }

    @Test
    fun `Fix B normalizes grand tail 416 as neutral on JP only`() {
        // JP: 62/416 from several profiles, with the Grand16 template verified -> grand 16
        val f024 = Fixture()
        every { f024.ocr.detectTextLine(f024.crop, any()) } returnsMany listOf(
            OcrText("62/416", 75), OcrText("62/+16", 67), OcrText("62/416", 67), OcrText("62/416", 59)
        )
        f024.show(Images.SupportCompactClass62A)
        f024.show(Images.SupportGrand16A)
        assertThat(f024.reader.read(f024.bounds, true, true)).isEqualTo(SupportScores(62, 16))

        // Mix of 62/16(90)x2 and 62/416(90) with no Grand template -> Grand 16 comes from the two real /16 reads.
        val fMix416 = Fixture()
        every { fMix416.ocr.detectTextLine(fMix416.crop, any()) } returnsMany listOf(
            OcrText("62/16", 90), OcrText("62/16", 90), OcrText("62/416", 90), OcrText("", 0)
        )
        assertThat(fMix416.reader.read(fMix416.bounds, true, true)).isEqualTo(SupportScores(62, 16))

        // Guard: 62/416x4 with no Grand template -> grand unknown
        val fNoTpl = Fixture()
        every { fNoTpl.ocr.detectTextLine(fNoTpl.crop, any()) } returns OcrText("62/416", 92)
        assertThat(fNoTpl.reader.read(fNoTpl.bounds, true, true)).isEqualTo(SupportScores(62, null, SupportScoreDisplay.Compound))

        // Guard: 62/414, 62/4160, 62/41 -> not neutral, stay invalid/blocked
        for (badTail in listOf("62/414", "62/4160", "62/41")) {
            val fBad = Fixture()
            every { fBad.ocr.detectTextLine(fBad.crop, any()) } returns OcrText(badTail, 92)
            fBad.show(Images.SupportGrand16A)
            assertThat(fBad.reader.read(fBad.bounds, false, true).grandScore).isNull()
        }

        // Guard: 62/+416 -> unknown, as today
        val fPlus416 = Fixture()
        every { fPlus416.ocr.detectTextLine(fPlus416.crop, any()) } returns OcrText("62/+416", 92)
        fPlus416.show(Images.SupportGrand16A)
        assertThat(fPlus416.reader.read(fPlus416.bounds, false, true).grandScore).isNull()

        // Guard: 62/416 with a different final digit -> unknown
        val fDiffDigit = Fixture()
        every { fDiffDigit.ocr.detectTextLine(fDiffDigit.crop, any()) } returns OcrText("62/416", 92)
        fDiffDigit.show(Images.SupportGrand16A, correctLastDigit = false)
        assertThat(fDiffDigit.reader.read(fDiffDigit.bounds, false, true).grandScore).isNull()

        // KR: 62/416 still blocks Grand as invalidGrand
        val fKr = Fixture()
        every { fKr.api.prefs.gameServer } returns GameServer.Kr
        every { fKr.ocr.detectTextLine(fKr.crop, any()) } returns OcrText("62/416", 92)
        fKr.show(Images.SupportGrand16A)
        assertThat(fKr.reader.read(fKr.bounds, false, true).grandScore).isNull()
    }

    @Test
    fun `Fix C2 handles dimmed rows with no-glyph exemption on JP only without dark retry`() {
        val blankInk = ByteArray(60 * 30) { 255.toByte() }

        // (a) Non-null pixels with no glyph, Grand16 verified and Class62 matched -> 62/16, primary OCR only (4 calls).
        val fDim = Fixture()
        every { fDim.prepared.grayPixels() } returns blankInk
        every { fDim.ocr.detectTextLine(fDim.crop, any()) } returns OcrText("", 0)
        fDim.show(Images.SupportCompactClass62A)
        fDim.show(Images.SupportGrand16A)
        assertThat(fDim.reader.read(fDim.bounds, true, true)).isEqualTo(SupportScores(62, 16))
        verify(exactly = 4) { fDim.ocr.detectTextLine(fDim.crop, any()) }
        verify(exactly = 4) { fDim.crop.resize(any()) }

        // (a) Same, but with no Grand template -> grand unknown.
        val fDimNoGrand = Fixture()
        every { fDimNoGrand.prepared.grayPixels() } returns blankInk
        every { fDimNoGrand.ocr.detectTextLine(fDimNoGrand.crop, any()) } returns OcrText("", 0)
        fDimNoGrand.show(Images.SupportCompactClass62A)
        assertThat(fDimNoGrand.reader.read(fDimNoGrand.bounds, true, true)).isEqualTo(SupportScores(62, null, SupportScoreDisplay.Compound))

        // C2 guard: no-glyph inks with D-2 grand16=Positive but no Grand16 template -> not exempt, clipped=true, grand unknown.
        val d2Image = javax.imageio.ImageIO.read(java.io.File(
            "../docs/reference-images/support-score-d2/scores/d2-1920x1080-01-row-2.png"))
        val d2Pixels = ByteArray(d2Image.width * d2Image.height) { p ->
            val rgb = d2Image.getRGB(p % d2Image.width, p / d2Image.width)
            (((rgb shr 16 and 255) * 299 + (rgb shr 8 and 255) * 587 + (rgb and 255) * 114 + 500) / 1000).toByte()
        }
        var recordedJson = ""
        val diag = object : io.github.fate_grand_automata.scripts.diagnostics.IReaderDiagnostics {
            override val enabled = true
            override val runId = "test"
            override fun record(reader: String, stem: String, images: Map<String, Pattern>, json: String) {
                recordedJson = json
            }
        }
        val fD2NoGrandTpl = Fixture()
        val diagReader = SupportScoreReader(fD2NoGrandTpl.api, fD2NoGrandTpl.ocr, diag)
        every { fD2NoGrandTpl.crop.grayPixels() } returns d2Pixels
        every { fD2NoGrandTpl.prepared.grayPixels() } returns blankInk
        every { fD2NoGrandTpl.ocr.detectTextLine(fD2NoGrandTpl.crop, any()) } returns OcrText("", 0)
        val d2Scores = diagReader.read(fD2NoGrandTpl.bounds, true, true, screen = 0, row = 0)
        assertThat(d2Scores).isEqualTo(SupportScores(62, null, SupportScoreDisplay.Compound))
        kotlin.test.assertTrue(recordedJson.contains("\"grand16\":\"Positive\""))
        kotlin.test.assertTrue(recordedJson.contains("\"clipped\":true"))

        // Exemption proof: blank ink + Class62 + a verified Grand16 template -> noGlyphExempt=true, clipped=false.
        fun diagRead(fx: Fixture): Pair<SupportScores, String> {
            var json = ""
            val d = object : io.github.fate_grand_automata.scripts.diagnostics.IReaderDiagnostics {
                override val enabled = true
                override val runId = "test"
                override fun record(reader: String, stem: String, images: Map<String, Pattern>, json: String) {
                    recorded = json
                }
                var recorded = ""
            }
            val scores = SupportScoreReader(fx.api, fx.ocr, d).read(fx.bounds, true, true, screen = 0, row = 0)
            json = d.recorded
            return scores to json
        }
        val fExempt = Fixture()
        every { fExempt.prepared.grayPixels() } returns blankInk
        every { fExempt.ocr.detectTextLine(fExempt.crop, any()) } returns OcrText("", 0)
        fExempt.show(Images.SupportCompactClass62A)
        fExempt.show(Images.SupportGrand16A)
        val (exemptScores, exemptJson) = diagRead(fExempt)
        assertThat(exemptScores).isEqualTo(SupportScores(62, 16))
        kotlin.test.assertTrue(exemptJson.contains("\"noGlyphExempt\":true"))
        kotlin.test.assertTrue(exemptJson.contains("\"clipped\":false"))

        // No Grand16 template: not exempt, clipped=true, and the OCR /+16 tail does not rescue Grand.
        val fTailNoGrandTpl = Fixture()
        every { fTailNoGrandTpl.prepared.grayPixels() } returns blankInk
        every { fTailNoGrandTpl.ocr.detectTextLine(fTailNoGrandTpl.crop, any()) } returns OcrText("62/+16", 90)
        fTailNoGrandTpl.show(Images.SupportCompactClass62A)
        val (tailScores, tailJson) = diagRead(fTailNoGrandTpl)
        assertThat(tailScores.grandScore).isNull()
        kotlin.test.assertTrue(tailJson.contains("\"noGlyphExempt\":false"))
        kotlin.test.assertTrue(tailJson.contains("\"clipped\":true"))

        // Grand16 A has a wrong final digit while Grand16 B matches with the right one: the template
        // check is true (B) and differentGrandDigit is true (A), so no exemption and Grand stays unknown.
        val fTwoTpl = Fixture()
        every { fTwoTpl.prepared.grayPixels() } returns blankInk
        every { fTwoTpl.ocr.detectTextLine(fTwoTpl.crop, any()) } returns OcrText("", 0)
        fTwoTpl.show(Images.SupportCompactClass62A)
        fTwoTpl.show(Images.SupportGrand16A, correctLastDigit = false)
        fTwoTpl.show(Images.SupportGrand16B)
        val (twoScores, twoJson) = diagRead(fTwoTpl)
        assertThat(twoScores.grandScore).isNull()
        kotlin.test.assertTrue(twoJson.contains("\"differentGrandDigit\":true"))
        kotlin.test.assertTrue(twoJson.contains("\"noGlyphExempt\":false"))
        kotlin.test.assertTrue(twoJson.contains("\"clipped\":true"))

        // C2 guard: Grand16 prefix found with a different final digit -> no-glyph exemption does NOT apply (clipped=true, grandScore=null).
        val fDiffGrandDigit = Fixture()
        every { fDiffGrandDigit.prepared.grayPixels() } returns blankInk
        every { fDiffGrandDigit.ocr.detectTextLine(fDiffGrandDigit.crop, any()) } returns OcrText("", 0)
        fDiffGrandDigit.show(Images.SupportCompactClass62A)
        fDiffGrandDigit.show(Images.SupportGrand16A, correctLastDigit = false)
        assertThat(fDiffGrandDigit.reader.read(fDiffGrandDigit.bounds, true, true)).isEqualTo(SupportScores(62, null, SupportScoreDisplay.Compound))

        // (b) Null pixels -> grand unknown, still clipped.
        val fNullInk = Fixture()
        every { fNullInk.prepared.grayPixels() } returns null
        every { fNullInk.ocr.detectTextLine(fNullInk.crop, any()) } returns OcrText("", 0)
        fNullInk.show(Images.SupportCompactClass62A)
        fNullInk.show(Images.SupportGrand16A)
        assertThat(fNullInk.reader.read(fNullInk.bounds, true, true)).isEqualTo(SupportScores(62, null, SupportScoreDisplay.Compound))

        // Guard: a real right-edge clip -> blocked.
        val fEdgeClip = Fixture()
        val clippedInk = scoreInk().also { p -> for (y in 11..23) for (x in 53..59) p[y * 60 + x] = 0 }
        every { fEdgeClip.prepared.grayPixels() } returns clippedInk
        every { fEdgeClip.ocr.detectTextLine(fEdgeClip.crop, any()) } returns OcrText("", 0)
        fEdgeClip.show(Images.SupportCompactClass62A)
        fEdgeClip.show(Images.SupportGrand16A)
        assertThat(fEdgeClip.reader.read(fEdgeClip.bounds, true, true)).isEqualTo(SupportScores(62, null, SupportScoreDisplay.Compound))

        // KR: no glyph still counts as clipped, and the dark retry rule is unchanged.
        val fKrNoGlyph = Fixture()
        every { fKrNoGlyph.api.prefs.gameServer } returns GameServer.Kr
        every { fKrNoGlyph.prepared.grayPixels() } returns blankInk
        every { fKrNoGlyph.ocr.detectTextLine(fKrNoGlyph.crop, any()) } returnsMany listOf(
            OcrText("62/+16", 80), OcrText("", 0), OcrText("", 0), OcrText("", 0),
            OcrText("", 0), OcrText("", 0)
        )
        fKrNoGlyph.show(Images.SupportGrand16A)
        assertThat(fKrNoGlyph.reader.read(fKrNoGlyph.bounds, false, true).grandScore).isNull()
        verify(exactly = 6) { fKrNoGlyph.ocr.detectTextLine(fKrNoGlyph.crop, any()) }
        verify(exactly = 6) { fKrNoGlyph.crop.resize(any()) }
    }

    @Test
    fun `KR dark-profile no-glyph ink counts as clipped just like before 431`() {
        val blankInk = ByteArray(60 * 30) { 255.toByte() }
        val f = Fixture()
        every { f.api.prefs.gameServer } returns GameServer.Kr
        every { f.ocr.detectTextLine(f.crop, any()) } returns OcrText("", 0)
        // Primary inspects (first 4 calls) see valid glyphs; dark inspects (last 2 calls) see blank ink.
        every { f.prepared.grayPixels() } returnsMany (List(4) { scoreInk() } + List(2) { blankInk })
        f.show(Images.SupportGrand16A)

        // With noGlyphDark set by the dark inspect, (compound && clipped) blocks Grand just as pre-#431 did.
        // Before this fix (when inspect(..., null) ignored !ink.hasGlyphs), noGlyphProfiles stayed empty,
        // clipped stayed false, and this exact input returned SupportScores(null, 16, Compound) unblocked.
        assertThat(f.reader.read(f.bounds, false, true)).isEqualTo(SupportScores(null, null, SupportScoreDisplay.Compound))
        verify(exactly = 6) { f.ocr.detectTextLine(f.crop, any()) }
        verify(exactly = 6) { f.crop.resize(any()) }

        // Control: with non-blank dark ink on the same inputs, Grand 16 is unblocked.
        val fControl = Fixture()
        every { fControl.api.prefs.gameServer } returns GameServer.Kr
        every { fControl.ocr.detectTextLine(fControl.crop, any()) } returns OcrText("", 0)
        every { fControl.prepared.grayPixels() } returns scoreInk()
        fControl.show(Images.SupportGrand16A)
        assertThat(fControl.reader.read(fControl.bounds, false, true)).isEqualTo(SupportScores(null, 16, SupportScoreDisplay.Compound))
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
