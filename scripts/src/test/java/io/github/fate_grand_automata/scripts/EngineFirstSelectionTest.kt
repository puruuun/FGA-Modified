package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.locations.SupportScoreLayout
import io.github.fate_grand_automata.scripts.prefs.ISupportPreferences
import io.github.fate_grand_automata.scripts.supportSelection.AppendSkillSelection
import io.github.fate_grand_automata.scripts.supportSelection.CESelection
import io.github.fate_grand_automata.scripts.supportSelection.EngineFirstSupportScoreReader
import io.github.fate_grand_automata.scripts.supportSelection.EngineUnknownReason
import io.github.fate_grand_automata.scripts.supportSelection.ISupportScoreReader
import io.github.fate_grand_automata.scripts.supportSelection.NoSupportScoreEngine
import io.github.fate_grand_automata.scripts.supportSelection.NoblePhantasmSelection
import io.github.fate_grand_automata.scripts.supportSelection.PreferredSupportSelection
import io.github.fate_grand_automata.scripts.supportSelection.ServantSelection
import io.github.fate_grand_automata.scripts.supportSelection.SupportBounds
import io.github.fate_grand_automata.scripts.supportSelection.SupportBoundsFinder
import io.github.fate_grand_automata.scripts.supportSelection.SupportNpSelection
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreDisplay
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreEngine
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreReader
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreSelection
import io.github.fate_grand_automata.scripts.supportSelection.SupportScores
import io.github.fate_grand_automata.scripts.supportSelection.SupportSelectionResult
import io.github.lib_automata.OcrService
import io.github.lib_automata.OcrText
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.github.lib_automata.ScriptAbortException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Design 0026: the selection with the engine-first reader, against the existing reader bound directly. */
class EngineFirstSelectionTest {
    @kotlin.test.AfterTest
    fun releaseMocks() = io.mockk.clearAllMocks()

    /** The existing reader on mocked OCR and templates, as SupportScoreReaderTest drives it. */
    private class ReaderFixture(server: GameServer, text: String, confidence: Int, engineFieldFits: Boolean) {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val messages = mockk<IScriptMessages>(relaxed = true)
        val logs = mutableListOf<String>()
        val images = mockk<IImageLoader>()
        val ocr = mockk<OcrService>()
        val crop = mockk<Pattern>(relaxed = true)
        val engineField = mockk<Pattern>(relaxed = true)
        val bounds = SupportBounds(Region(76, 408, 2356, 428))
        val region = SupportScoreLayout.numberRegion(bounds.region)
        val reader = SupportScoreReader(api, ocr)

        init {
            every { api.prefs.gameServer } returns server
            every { api.messages } returns messages
            every { messages.log(any()) } answers {
                val item = firstArg<ScriptLog>()
                logs += if (item is ScriptLog.SupportScoreConditions) {
                    "conditions ${item.classScore} ${item.grandScore} ${item.checkClass} ${item.checkGrand} ${item.display} ${item.matched}"
                } else item.javaClass.simpleName
            }
            every { api.images } returns images
            val legacy = mockk<Pattern>()
            every { images[Images.SupportScore62, null] } returns legacy
            every { with(api) { region.exists(legacy, any(), any()) } } returns false
            every { with(api) { region.getPattern("support_class_grand_scores") } } returns crop
            every { with(api) { region.getPattern(EngineFirstSupportScoreReader.FIELD_TAG) } } returns engineField
            every { engineField.width } returns 60
            every { engineField.height } returns if (engineFieldFits) 30 else 24
            every { crop.width } returns 60
            every { crop.height } returns 30
            val prepared = mockk<Pattern>(relaxed = true)
            val binary = mockk<Pattern>(relaxed = true)
            every { crop.resize(any()) } returns prepared
            every { prepared.threshold(any()) } returns binary
            every { binary.grayPixels() } returns null
            every { binary.fillText() } returns prepared
            every { prepared.width } returns 60
            every { prepared.height } returns 30
            every { prepared.grayPixels() } returns scoreInk()
            every { ocr.detectTextLine(crop, any()) } returns OcrText(text, confidence)
            (SupportScoreReader.COMPACT_CLASS_TEMPLATES + SupportScoreReader.GRAND_TEMPLATES).forEach { image ->
                val pattern = mockk<Pattern>()
                every { images[image, null] } returns pattern
                every { with(api) { region.exists(pattern, any(), any()) } } returns false
                every { with(api) { region.findAll(pattern, any()) } } returns emptyList()
                every { pattern.width } returns 29
                every { pattern.height } returns 19
            }
        }

        fun check(scoreReader: ISupportScoreReader, class62: Boolean, grand16: Boolean): Boolean {
            val prefs = mockk<ISupportPreferences>()
            every { prefs.score62Check } returns class62
            every { prefs.grandScore16Check } returns grand16
            return SupportScoreSelection(api, prefs, scoreReader).check(bounds, 1, 0)
        }
    }

    private data class Outcome(val matched: Boolean, val ocrCalls: Int, val logs: List<String>)

    private fun run(server: GameServer, text: String, confidence: Int, class62: Boolean, grand16: Boolean, engine: ((ReaderFixture) -> SupportScoreEngine)?, fits: Boolean = true): Outcome {
        val f = ReaderFixture(server, text, confidence, fits)
        var ocrCalls = 0
        every { f.ocr.detectTextLine(f.crop, any()) } answers { ocrCalls++; OcrText(text, confidence) }
        val reader = engine?.let { EngineFirstSupportScoreReader(f.api, it(f), f.reader) } ?: f.reader
        return Outcome(f.check(reader, class62, grand16), ocrCalls, f.logs)
    }

    private val engines: Map<String, (ReaderFixture) -> SupportScoreEngine> = mapOf(
        "no engine" to { _ -> NoSupportScoreEngine },
        "failed load" to { _ -> FakeSupportScoreEngine(reason = "libfga_ffi.so did not load: UnsatisfiedLinkError: x") },
        "stopped before load" to { _ -> FakeSupportScoreEngine(reason = "the run was stopped before the engine loaded; nothing was loaded") },
        "engine error" to { _ -> FakeSupportScoreEngine { EngineAnswers.engineError() } },
    )

    private val screens = listOf("+62/+16" to 92, "+62" to 92, "+40/+16" to 92, "+62/+0" to 92, "" to 0, "+62/+16" to 60)

    @Test
    fun `without a usable engine every screen reads, logs and decides as with the existing reader alone`() {
        for (server in listOf<GameServer>(GameServer.Jp.Original, GameServer.Kr)) for ((text, confidence) in screens) {
            for ((class62, grand16) in listOf(true to false, false to true, true to true)) {
                val direct = run(server, text, confidence, class62, grand16, null)
                for ((name, engine) in engines) {
                    assertThat(run(server, text, confidence, class62, grand16, engine), "$name $server '$text' $class62 $grand16")
                        .isEqualTo(direct)
                }
                assertThat(run(server, text, confidence, class62, grand16, { _ -> FakeSupportScoreEngine { EngineAnswers.compound(62, 16) } }, fits = false),
                    "field size $server '$text' $class62 $grand16").isEqualTo(direct)
            }
        }
    }

    @Test
    fun `with a complete engine answer the existing reader runs no OCR and the log line keeps its form`() {
        val outcome = run(GameServer.Jp.Original, "", 0, true, true, { _ -> FakeSupportScoreEngine { EngineAnswers.compound(62, 16) } })
        assertThat(outcome).isEqualTo(Outcome(true, 0, listOf("conditions 62 16 true true Compound true")))
        val direct = run(GameServer.Jp.Original, "+62/+16", 92, true, true, null)
        assertThat(direct.logs).isEqualTo(outcome.logs)
    }

    /** The support list, at the level of the append-support tests: every other condition passes. */
    private class ListFixture(
        engineAnswers: Map<Int, io.github.fate_grand_automata.scripts.supportSelection.EngineScore>,
        val existingAnswers: Map<Int, SupportScores>,
        engineReason: String? = null,
    ) {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val prefs = mockk<ISupportPreferences>(relaxed = true)
        val rows = List(3) { SupportBounds(Region(76, 408 + 400 * it, 2356, 428)) }
        val fields = rows.map { mockk<Pattern>(relaxed = true) }
        val engine = FakeSupportScoreEngine(engineReason) { field -> engineAnswers.getValue(fields.indexOf(field)) }
        val existingCalls = mutableListOf<Int>()
        val existing = object : ISupportScoreReader {
            override fun read(bounds: SupportBounds, checkClass: Boolean, checkGrand: Boolean, screen: Int, row: Int): SupportScores {
                existingCalls += rows.indexOf(bounds)
                return existingAnswers.getValue(rows.indexOf(bounds))
            }
        }
        val preferred: PreferredSupportSelection

        init {
            every { api.prefs.gameServer } returns GameServer.Jp.Original
            every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
            every { prefs.preferredServants } returns listOf("servant")
            every { prefs.preferredCEs } returns emptyList()
            every { prefs.preferredNoblePhantasms } returns emptyList()
            every { prefs.friendNames } returns emptyList()
            every { prefs.friendsOnly } returns false
            every { prefs.score62Check } returns true
            every { prefs.grandScore16Check } returns true
            rows.forEachIndexed { i, row ->
                every { fields[i].width } returns 60
                every { fields[i].height } returns 30
                every { with(api) { SupportScoreLayout.numberRegion(row.region).getPattern(EngineFirstSupportScoreReader.FIELD_TAG) } } returns fields[i]
            }
            val finder = mockk<SupportBoundsFinder>()
            every { finder.all() } returns rows
            val servants = mockk<ServantSelection>()
            every { servants.check(any(), any()) } returns true
            val ces = mockk<CESelection>()
            every { ces.check(any(), any()) } returns true
            val append = mockk<AppendSkillSelection>()
            every { append.enabled } returns false
            val np = mockk<SupportNpSelection>()
            every { np.check(any()) } returns true
            val noblePhantasms = mockk<NoblePhantasmSelection>()
            every { noblePhantasms.check(any(), any()) } returns true
            preferred = PreferredSupportSelection(prefs, api, finder, mockk(relaxed = true), servants, ces, mockk(relaxed = true), append,
                SupportScoreSelection(api, prefs, EngineFirstSupportScoreReader(api, engine, existing)), np, noblePhantasms)
        }

        fun clicked(row: Int) = verify(exactly = 1) { with(api) { rows[row].region.click(any()) } }
    }

    // What the existing reader answers on a JP "+62/+16" it cannot read, and on one it reads.
    private val unread = SupportScores(null, null, SupportScoreDisplay.Compound)
    private val read6216 = SupportScores(62, 16, SupportScoreDisplay.Compound)

    @Test
    fun `the engine reads the +62 +16 row the existing reader cannot and that row is chosen`() {
        val f = ListFixture(
            mapOf(0 to EngineAnswers.ordinary(62), 1 to EngineAnswers.compound(62, 15), 2 to EngineAnswers.compound(62, 16)),
            mapOf(0 to SupportScores(62, null, SupportScoreDisplay.Ordinary), 1 to unread, 2 to unread),
        )
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.Done)
        f.clicked(2)
        assertThat(f.existingCalls).isEmpty()
        assertThat(f.engine.reads).containsExactly(f.fields[0], f.fields[1], f.fields[2])
    }

    @Test
    fun `a row the engine refuses is read by the existing reader and chosen on its answer`() {
        val f = ListFixture(
            mapOf(0 to EngineAnswers.compound(40, 16), 1 to EngineAnswers.refused(EngineUnknownReason.Clipped), 2 to EngineAnswers.compound(62, 16)),
            mapOf(0 to unread, 1 to read6216, 2 to unread),
        )
        assertThat(f.preferred.select()).isEqualTo(SupportSelectionResult.Done)
        f.clicked(1)
        verify(exactly = 0) { with(f.api) { f.rows[2].region.click(any()) } }
        assertThat(f.existingCalls).containsExactly(1)
    }

    @Test
    fun `a run stopped before the engine loaded reads with the existing reader and ends at the stop`() {
        val f = ListFixture(
            mapOf(0 to EngineAnswers.compound(62, 16), 1 to EngineAnswers.compound(62, 16), 2 to EngineAnswers.compound(62, 16)),
            mapOf(0 to unread, 1 to read6216, 2 to unread),
            engineReason = "the run was stopped before the engine loaded; nothing was loaded",
        )
        // Clicker checks the stop before it taps.
        every { with(f.api) { any<Region>().click(any()) } } throws ScriptAbortException()
        assertFailsWith<ScriptAbortException> { f.preferred.select() }
        assertThat(f.engine.reads).isEmpty()
        assertThat(f.existingCalls).containsExactly(0, 1)
        verify(exactly = 0) { with(f.api) { any<Region>().getPattern(EngineFirstSupportScoreReader.FIELD_TAG) } }
    }
}
