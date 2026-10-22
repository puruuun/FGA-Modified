package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.scripts.diagnostics.DiagnosticJson
import io.github.fate_grand_automata.scripts.diagnostics.IReaderDiagnostics
import io.github.fate_grand_automata.scripts.diagnostics.NoReaderDiagnostics
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.locations.SupportScoreLayout
import io.github.fate_grand_automata.scripts.supportSelection.SupportBounds
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreReader
import io.github.fate_grand_automata.scripts.supportSelection.SupportScores
import io.github.lib_automata.Match
import io.github.lib_automata.OcrService
import io.github.lib_automata.OcrText
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.github.lib_automata.TextLineOcrOptions
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test

class SupportScoreDiagnosticsTest {
    @kotlin.test.AfterTest
    fun releaseMocks() = io.mockk.clearAllMocks()

    private class RecordingDiagnostics(override val enabled: Boolean = true) : IReaderDiagnostics {
        data class Record(val reader: String, val stem: String, val images: Map<String, Pattern>, val json: String)

        override val runId = "test-run"
        val records = mutableListOf<Record>()

        override fun record(reader: String, stem: String, images: Map<String, Pattern>, json: String) {
            records += Record(reader, stem, images, json)
        }
    }

    private class Fixture(val diagnostics: IReaderDiagnostics = NoReaderDiagnostics) {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val images = mockk<IImageLoader>()
        val ocr = mockk<OcrService>()
        val legacy = mockk<Pattern>()
        val crop = mockk<Pattern>(relaxed = true)
        val bounds = SupportBounds(Region(76, 408, 2356, 428))
        val region = SupportScoreLayout.numberRegion(bounds.region)
        val reader = SupportScoreReader(api, ocr, diagnostics)
        val visual = (SupportScoreReader.COMPACT_CLASS_TEMPLATES + SupportScoreReader.GRAND_TEMPLATES)
            .associateWith { mockk<Pattern>() }
        val digits = SupportScoreReader.GRAND_TEMPLATES.associateWith { mockk<Pattern>(relaxed = true) }

        init {
            every { api.prefs.gameServer } returns GameServer.Jp.Original
            every { api.images } returns images
            every { images[Images.SupportScore62, null] } returns legacy
            every { with(api) { region.exists(legacy, any(), any()) } } returns false
            // Diagnostics take their own copy of the same snapshot; the reading path uses the tagged crop.
            every { with(api) { region.getPattern(any()) } } returns crop
            every { with(api) { region.getPattern("support_class_grand_scores") } } returns crop
            every { crop.width } returns 60
            every { crop.height } returns 30
            val prepared = mockk<Pattern>(relaxed = true)
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

    /** The same reads, each on a fresh fixture, so the two diagnostics modes see identical inputs. */
    private fun readings(diagnostics: IReaderDiagnostics): List<SupportScores> = listOf(
        Fixture(diagnostics).run { reader.read(bounds, true, true) },
        Fixture(diagnostics).run {
            every { api.prefs.gameServer } returns GameServer.Kr
            every { ocr.detectTextLine(crop, any()) } answers {
                if (secondArg<TextLineOcrOptions>().threshold == SupportScoreReader.OCR_DARK_THRESHOLD)
                    OcrText("+62", 96) else OcrText("", 0)
            }
            reader.read(bounds, true, true)
        },
        Fixture(diagnostics).run {
            every { ocr.detectTextLine(crop, any()) } returns OcrText("+0/+16", 92)
            reader.read(bounds, false, true)
        },
        Fixture(diagnostics).run {
            every { with(api) { region.exists(legacy, any(), any()) } } returns true
            reader.read(bounds, true, false)
        },
        Fixture(diagnostics).run {
            every { ocr.detectTextLine(crop, any()) } returnsMany listOf(
                OcrText("56/416", 33), OcrText("56/ +16", 60), OcrText("+6", 50), OcrText("56/ +16", 55)
            )
            show(Images.SupportGrand16A)
            reader.read(bounds, false, true)
        },
        Fixture(diagnostics).run {
            every { crop.width } returns 30
            reader.read(bounds, false, true)
        },
        Fixture(diagnostics).run { reader.read(bounds, false, false) }
    )

    @Test
    fun `diagnostics on and off give identical readings`() {
        val recording = RecordingDiagnostics()
        assertThat(readings(recording)).isEqualTo(readings(NoReaderDiagnostics))
        // Every read that reaches the reader is recorded; the both-disabled read never reaches it.
        assertThat(recording.records.size).isEqualTo(6)
    }

    @Test
    fun `disabled diagnostics record nothing and take no extra pattern`() {
        val off = RecordingDiagnostics(enabled = false)
        val f = Fixture(off)
        f.reader.read(f.bounds, true, true)
        assertThat(off.records.size).isEqualTo(0)
        verify(exactly = 1) { with(f.api) { f.region.getPattern(any()) } }
    }

    @Test
    fun `record JSON carries the evidence fields and the reader's own result`() {
        val recording = RecordingDiagnostics()
        val f = Fixture(recording)
        f.reader.read(f.bounds, true, true, screen = 3, row = 2)

        val record = recording.records.single()
        assertThat(record.reader).isEqualTo("support-score")
        assertThat(record.stem).isEqualTo("3-2")
        assertThat(record.images.keys).isEqualTo(setOf("", "-wide"))

        val json = record.json
        val expected = listOf(
            "\"schema\":1",
            "\"reader\":\"supportScore\"",
            "\"server\":\"${GameServer.Jp.Original.simple}\"",
            "\"time\":\"",
            "\"run\":\"test-run\"",
            "\"screen\":3",
            "\"row\":2",
            "\"rowY1440\":408",
            "\"cropSize\":[60,30]",
            "\"colorMode\":\"gray\"",
            "\"checkClass\":true",
            "\"checkGrand\":true",
            "\"ocr\":[{\"stage\":\"primary\",\"profile\":0,\"text\":\"+62/+16\",\"confidence\":92}",
            "\"templates\":{\"SupportScore62\":false",
            "\"result\":{\"class\":62,\"grand\":16,\"display\":\"Compound\"}",
            "\"matched\":true",
            "\"elapsedMs\":"
        )
        for (field in expected) {
            assertThat(json.contains(field)).isEqualTo(true)
        }
    }

    @Test
    fun `recording stops at the candidate cap and reading continues`() {
        val recording = RecordingDiagnostics()
        val f = Fixture(recording)
        val messages = f.api.messages
        repeat(SupportScoreReader.MAX_DIAGNOSTIC_CANDIDATES + 3) {
            assertThat(f.reader.read(f.bounds, true, true)).isEqualTo(SupportScores(62, 16))
            io.mockk.clearMocks(f.api, f.images, f.crop, f.ocr, answers = false, childMocks = false)
        }
        assertThat(recording.records.size).isEqualTo(SupportScoreReader.MAX_DIAGNOSTIC_CANDIDATES)
        // The limit is reported once, not per skipped candidate.
        verify(exactly = 1) { messages.log(any()) }
    }

    @Test
    fun `JSON writer escapes strings and keeps nested order`() {
        assertThat(DiagnosticJson.write(mapOf("text" to "a\"b\n\u0001", "list" to listOf(1, null, true))))
            .isEqualTo("""{"text":"a\"b\n\u0001","list":[1,null,true]}""")
    }
}
