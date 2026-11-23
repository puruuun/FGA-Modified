package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import io.github.fate_grand_automata.scripts.diagnostics.IReaderDiagnostics
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.locations.SupportScoreLayout
import io.github.fate_grand_automata.scripts.supportSelection.EngineFirstSupportScoreReader
import io.github.fate_grand_automata.scripts.supportSelection.EngineUnknownReason
import io.github.fate_grand_automata.scripts.supportSelection.ISupportScoreReader
import io.github.fate_grand_automata.scripts.supportSelection.NoSupportScoreEngine
import io.github.fate_grand_automata.scripts.supportSelection.SupportBounds
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreDisplay
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreEngine
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreReader
import io.github.fate_grand_automata.scripts.supportSelection.SupportScores
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.github.lib_automata.ScriptAbortException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertFailsWith

class EngineFirstSupportScoreReaderTest {
    @kotlin.test.AfterTest
    fun releaseMocks() = io.mockk.clearAllMocks()

    /** Returns what the existing reader returns for a field it could not read on JP: a compound display with no values. */
    private class Existing(var answer: SupportScores = SupportScores(null, null, SupportScoreDisplay.Compound)) : ISupportScoreReader {
        val calls = mutableListOf<List<Any>>()

        override fun read(bounds: SupportBounds, checkClass: Boolean, checkGrand: Boolean, screen: Int, row: Int): SupportScores {
            calls += listOf(bounds, checkClass, checkGrand, screen, row)
            return answer
        }
    }

    private class Recording(override val enabled: Boolean) : IReaderDiagnostics {
        data class Record(val reader: String, val stem: String, val images: Map<String, Pattern>, val json: String)

        override val runId = "run"
        val records = mutableListOf<Record>()

        override fun record(reader: String, stem: String, images: Map<String, Pattern>, json: String) {
            records += Record(reader, stem, images, json)
        }
    }

    private val bounds = SupportBounds(Region(76, 408, 2356, 428))
    private val api = mockk<IFgoAutomataApi>(relaxed = true)
    private val field = mockk<Pattern>(relaxed = true)

    init {
        every { api.prefs.gameServer } returns GameServer.Jp.Original
        every { field.width } returns 60
        every { field.height } returns 30
        every { with(api) { SupportScoreLayout.numberRegion(bounds.region).getPattern(EngineFirstSupportScoreReader.FIELD_TAG) } } returns field
    }

    private fun reader(engine: SupportScoreEngine, existing: ISupportScoreReader, diagnostics: IReaderDiagnostics = Recording(false)) =
        EngineFirstSupportScoreReader(api, engine, existing, diagnostics)

    private fun fieldCuts(times: Int) =
        verify(exactly = times) { with(api) { any<Region>().getPattern(EngineFirstSupportScoreReader.FIELD_TAG) } }

    @Test
    fun `a complete engine answer is returned and the existing reader is not called`() {
        val engine = FakeSupportScoreEngine { EngineAnswers.compound(62, 16) }
        val existing = Existing()
        val scores = reader(engine, existing).read(bounds, true, true, 2, 3)
        assertThat(scores).isEqualTo(SupportScores(62, 16, SupportScoreDisplay.Compound))
        assertThat(existing.calls).isEmpty()
        assertThat(engine.reads).containsExactly(field)
        verify(exactly = 1) { field.close() }
    }

    @Test
    fun `an incomplete engine answer hands the whole row to the existing reader once and returns its answer as it is`() {
        for (refused in EngineAnswers.REFUSALS.map { EngineAnswers.refused(it) } +
            listOf(EngineAnswers.ordinaryOutOfRange(), EngineAnswers.engineError())) {
            val existing = Existing(SupportScores(62, 16, SupportScoreDisplay.Compound))
            val scores = reader(FakeSupportScoreEngine { refused }, existing).read(bounds, true, true, 2, 3)
            assertThat(scores).isSameInstanceAs(existing.answer)
            assertThat(existing.calls).containsExactly(listOf(bounds, true, true, 2, 3))
        }
    }

    @Test
    fun `fields of the two readers are never mixed`() {
        // A class value the engine read with a Grand it could not read (a shape ScoreWire can
        // carry): under a Grand condition the existing reader's class is returned, not the engine's.
        val compound = io.github.fate_grand_automata.scripts.supportSelection.EngineScore(
            io.github.fate_grand_automata.scripts.supportSelection.EngineField.Value(62),
            io.github.fate_grand_automata.scripts.supportSelection.EngineField.Unknown(EngineUnknownReason.Range),
            io.github.fate_grand_automata.scripts.supportSelection.EngineDisplay.Compound,
            "+62/+99", 0.98f, 0.95f, kotlin.time.Duration.ZERO,
        )
        for (checkClass in listOf(false, true)) {
            val existing = Existing(SupportScores(57, 16, SupportScoreDisplay.Compound))
            assertThat(reader(FakeSupportScoreEngine { compound }, existing).read(bounds, checkClass, true))
                .isEqualTo(SupportScores(57, 16, SupportScoreDisplay.Compound))
            assertThat(existing.calls.size).isEqualTo(1)
        }
    }

    @Test
    fun `without an engine nothing is cut or read and the existing reader answers`() {
        for (engine in listOf<SupportScoreEngine>(NoSupportScoreEngine, FakeSupportScoreEngine(reason = "libfga_ffi.so did not load: x"))) {
            io.mockk.clearMocks(api, answers = false, recordedCalls = true, childMocks = false, exclusionRules = false, verificationMarks = true)
            val existing = Existing(SupportScores(62, null, SupportScoreDisplay.Ordinary))
            val scores = reader(engine, existing).read(bounds, true, false, 1, 0)
            assertThat(scores).isSameInstanceAs(existing.answer)
            assertThat(existing.calls).containsExactly(listOf(bounds, true, false, 1, 0))
            fieldCuts(0)
            if (engine is FakeSupportScoreEngine) assertThat(engine.reads).isEmpty()
        }
    }

    @Test
    fun `availability is asked once per row and a failed load is not retried by the reader`() {
        val engine = FakeSupportScoreEngine(reason = "model load failed: ERR: x")
        val reader = reader(engine, Existing())
        repeat(5) { reader.read(bounds, true, true, 0, it) }
        assertThat(engine.reasonAsks).isEqualTo(5)
        assertThat(engine.reads).isEmpty()
    }

    @Test
    fun `a field of another size never reaches the engine`() {
        every { field.width } returns 60
        every { field.height } returns 22
        val engine = FakeSupportScoreEngine { EngineAnswers.compound(62, 16) }
        val existing = Existing()
        assertThat(reader(engine, existing).read(bounds, true, true)).isSameInstanceAs(existing.answer)
        assertThat(engine.reads).isEmpty()
        assertThat(existing.calls.size).isEqualTo(1)
        verify(exactly = 1) { field.close() }
    }

    @Test
    fun `no condition reads nothing with the engine`() {
        val engine = FakeSupportScoreEngine { EngineAnswers.compound(62, 16) }
        val existing = Existing(SupportScores())
        assertThat(reader(engine, existing).read(bounds, false, false)).isSameInstanceAs(existing.answer)
        assertThat(engine.reasonAsks).isEqualTo(0)
        fieldCuts(0)
    }

    @Test
    fun `a stop thrown while the existing reader reads ends the read and frees the field`() {
        val engine = FakeSupportScoreEngine { EngineAnswers.refused(EngineUnknownReason.Clipped) }
        val stopping = object : ISupportScoreReader {
            override fun read(bounds: SupportBounds, checkClass: Boolean, checkGrand: Boolean, screen: Int, row: Int): SupportScores =
                throw ScriptAbortException()
        }
        assertFailsWith<ScriptAbortException> { reader(engine, stopping).read(bounds, true, true) }
        verify(exactly = 1) { field.close() }
    }

    @Test
    fun `a stop thrown while the field is cut reaches neither reader`() {
        every { with(api) { any<Region>().getPattern(EngineFirstSupportScoreReader.FIELD_TAG) } } throws ScriptAbortException()
        val engine = FakeSupportScoreEngine { EngineAnswers.compound(62, 16) }
        val existing = Existing()
        assertFailsWith<ScriptAbortException> { reader(engine, existing).read(bounds, true, true) }
        assertThat(engine.reads).isEmpty()
        assertThat(existing.calls).isEmpty()
    }

    @Test
    fun `diagnostics off record nothing and ask nothing extra`() {
        val diagnostics = Recording(false)
        val engine = FakeSupportScoreEngine { EngineAnswers.compound(62, 16) }
        reader(engine, Existing(), diagnostics).read(bounds, true, true)
        assertThat(diagnostics.records).isEmpty()
        verify(exactly = 0) { api.prefs }
    }

    @Test
    fun `diagnostics say which reader answered and why the engine did not`() {
        val diagnostics = Recording(true)
        val answers = ArrayDeque(listOf(EngineAnswers.compound(62, 16), EngineAnswers.refused(EngineUnknownReason.Clipped), EngineAnswers.engineError("malformed engine reply: magic 0")))
        val engine = FakeSupportScoreEngine { answers.removeFirst() }
        val reader = reader(engine, Existing(), diagnostics)
        repeat(3) { reader.read(bounds, true, true, 4, it) }
        engine.reason = "the engine was closed at the end of the run"
        reader.read(bounds, true, true, 4, 3)

        assertThat(diagnostics.records.map { it.reader }.distinct()).containsExactly(EngineFirstSupportScoreReader.DIAGNOSTIC_READER)
        assertThat(diagnostics.records.map { it.stem }).containsExactly("4-0", "4-1", "4-2", "4-3")
        val (answered, clipped, error, unavailable) = diagnostics.records
        assertThat(answered.images).isEqualTo(mapOf("" to field))
        assertThat(answered.json).contains("\"answeredBy\":\"engine\"")
        assertThat(answered.json).contains("\"fallback\":null")
        assertThat(answered.json).contains("\"text\":\"+62/+16\"")
        assertThat(answered.json).contains("\"engineTimeUs\":27000")
        assertThat(answered.json).contains("\"engineLoadMs\":120")
        assertThat(answered.json).contains("\"result\":{\"class\":62,\"grand\":16,\"display\":\"Compound\"}")
        assertThat(clipped.images).isEqualTo(emptyMap())
        assertThat(clipped.json).contains("\"answeredBy\":\"kotlin\"")
        assertThat(clipped.json).contains("\"fallback\":{\"why\":\"incomplete\",\"class\":\"Clipped\",\"grand\":\"Clipped\",\"display\":\"Unknown\",\"detail\":null}")
        assertThat(error.json).contains("\"detail\":\"malformed engine reply: magic 0\"")
        assertThat(unavailable.json).contains("\"fallback\":{\"why\":\"unavailable\",\"detail\":\"the engine was closed at the end of the run\"}")
        assertThat(unavailable.json).contains("\"engine\":null")
        assertThat(unavailable.json).doesNotContain("engineTimeUs")
    }

    @Test
    fun `an engine closed between the availability ask and the read hands the row to the existing reader`() {
        val engine = FakeSupportScoreEngine { EngineAnswers.unavailable() }
        val existing = Existing(SupportScores(62, 16, SupportScoreDisplay.Compound))
        assertThat(reader(engine, existing).read(bounds, true, true, 0, 0)).isSameInstanceAs(existing.answer)
        assertThat(engine.reads).containsExactly(field)
        assertThat(existing.calls.size).isEqualTo(1)
    }

    @Test
    fun `the field-size fallback is recorded without an engine block or an image`() {
        every { field.height } returns 22
        val diagnostics = Recording(true)
        reader(FakeSupportScoreEngine { EngineAnswers.compound(62, 16) }, Existing(), diagnostics).read(bounds, true, true, 1, 2)
        val record = diagnostics.records.single()
        assertThat(record.stem).isEqualTo("1-2")
        assertThat(record.images).isEqualTo(emptyMap())
        assertThat(record.json).contains("\"answeredBy\":\"kotlin\"")
        assertThat(record.json).contains("\"fallback\":{\"why\":\"fieldSize\",\"size\":[60,22]}")
        assertThat(record.json).contains("\"engine\":null")
    }

    @Test
    fun `diagnostics stop at the existing reader's cap`() {
        val diagnostics = Recording(true)
        val reader = reader(FakeSupportScoreEngine { EngineAnswers.compound(62, 16) }, Existing(), diagnostics)
        repeat(EngineFirstSupportScoreReader.MAX_DIAGNOSTIC_CANDIDATES + 2) { reader.read(bounds, true, true, 0, it) }
        assertThat(diagnostics.records.size).isEqualTo(SupportScoreReader.MAX_DIAGNOSTIC_CANDIDATES)
    }
}
