package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.models.*
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.modules.*
import io.github.lib_automata.OcrText
import io.github.lib_automata.TextLineOcrOptions
import io.mockk.*
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class BondTrackingTest {
    @Test fun `tablet beta7b exact four reads confirm unanimously with independent shared bases`() {
        val lines = javaClass.getResource("/bond/tablet-beta7b-c1.txt")!!.readText().lineSequence().filter { it.isNotBlank() }
        val anchors = (1..3).map { index -> BondReader.ColumnReading(index,
            BondValue.Gain(3797, 8835), List(2) { OcrText("3797(+8835)", 95) }, "consensus") }
        for (line in lines) {
            val readings = Regex("(.+?)@([0-9]+)\\*([0-9]+)(?:, |$)").findAll(line.substringAfter("ocr=[").removeSuffix("]"))
                .flatMap { m -> List(m.groupValues[3].toInt()) { OcrText(m.groupValues[1], m.groupValues[2].toInt()) } }.toList()
            assertEquals(18, readings.size)
            val primary = BondReader.confirmedGain(readings) ?: BondReader.majorityGain(readings)
            val column = BondReader.ColumnReading(0, primary ?: BondValue.Unknown, readings, if (primary == null) null else "majority")
            val result = BondReader.crossCheck(listOf(column) + anchors).first()
            assertEquals(BondValue.Gain(3797, 11267), result.value)
            assertEquals(if (primary == null) "unanimous+base" else "majority", result.reason)
        }
    }

    @Test fun `unanimous base fallback rejects conflicts insufficient votes and insufficient independent bases`() {
        val votes = List(6) { OcrText("3797(+11267)", 60) }
        fun anchor(index: Int, base: Int = 3797) = BondReader.ColumnReading(index,
            BondValue.Gain(base, 8835), emptyList(), "consensus")
        fun weak(candidates: List<OcrText> = votes) = BondReader.ColumnReading(0, BondValue.Unknown, candidates, null)
        for (candidates in listOf(votes.take(5), votes + OcrText("3797(+11268)", 60),
                votes + OcrText("3798(+11267)", 60), votes + OcrText("bad", 60))) {
            assertEquals(BondValue.Unknown, BondReader.crossCheck(listOf(weak(candidates), anchor(1), anchor(2))).first().value)
        }
        assertEquals(BondValue.Unknown, BondReader.crossCheck(listOf(weak(), anchor(1))).first().value)
        assertEquals(BondValue.Unknown, BondReader.crossCheck(listOf(weak(), anchor(1, 42), anchor(2, 42))).first().value)
        val original = listOf(weak(), anchor(1), anchor(2))
        assertEquals(BondReader.crossCheck(original).reversed(), BondReader.crossCheck(original.reversed()))
    }

    private val gain = BondValue.Gain(4748, 2660)

    @Test fun `gain parsing sums base and bonus and never uses NEXT`() {
        assertEquals(7408L, BondReader.parseGain("+ 4,748 (+ 2,660)")?.total)
        assertEquals(31L, BondReader.parseGain("+25(+6)")?.total)
        assertEquals(4_294_967_294L, BondReader.parseGain("+2,147,483,647(+2,147,483,647)")?.total)
        for (text in listOf("NEXT 1,015,293", "4,748", "+4,748 2,660", "+4,748(+2,660) NEXT 10", "1,23(+5)", "MAX"))
            assertNull(BondReader.parseGain(text))
    }

    @Test fun `OCR needs two strong agreeing complete readings and rejects strong conflicts`() {
        val good = OcrText("+1,098(+263)", 93)
        assertNull(BondReader.confirmedGain(listOf(good)))
        assertNull(BondReader.confirmedGain(listOf(good, good.copy(confidence = 84))))
        assertEquals(1361L, BondReader.confirmedGain(listOf(good, good, OcrText("+1,098(+45)", 59)))?.total)
        assertNull(BondReader.confirmedGain(listOf(good, good, OcrText("+1,098(+45)", 92))))
        for (level in listOf(7, 10, 12, 16)) assertEquals(level, BondReader.confirmedLevel(List(2) { OcrText("$level", 95) }))
        assertNull(BondReader.confirmedLevel(listOf(OcrText("9 > 10", 99), OcrText("9 > 10", 99))))
    }

    @Test fun `gain width retry recovers thin digits without lowering confidence`() {
        val seen = mutableListOf<TextLineOcrOptions>()
        val result = BondReader.readGain(BondReader.GAIN_PROFILES) { options ->
            seen += options
            OcrText("+3,797(+11,267)", if (options.horizontalScale > 1.0) 92 else 84)
        }
        assertEquals(BondValue.Gain(3797, 11267), result)
        assertTrue(seen.any { it.horizontalScale > 1.0 })
        assertTrue(seen.filter { it.horizontalScale > 1.0 }.all { it.allowedCharacters == "+(),0123456789 " })
    }

    @Test fun `already confirmed gains skip width retries`() {
        var calls = 0
        val result = BondReader.readGain(BondReader.JP_GAIN_PROFILES) { options ->
            calls++
            assertEquals(1.0, options.horizontalScale)
            OcrText("+815(+590)", 95)
        }
        assertEquals(BondValue.Gain(815, 590), result)
        assertEquals(BondReader.JP_GAIN_PROFILES.size, calls)
    }

    @Test fun `majority fallback retains primary votes and requires a strong winner`() {
        var primary = 0
        assertEquals(BondValue.Gain(1830, 1976), BondReader.readGain(BondReader.GAIN_PROFILES) { options ->
            if (options.horizontalScale == 1.0 && primary++ == 0) OcrText("+1,830(+1,970)", 92)
            else OcrText("+1,830(+1,976)", 95)
        })
        var calls = 0
        assertEquals(BondValue.Gain(815, 590), BondReader.readGain(BondReader.GAIN_PROFILES) {
            OcrText("+815(+590)", if (calls++ == 0) 95 else 84)
        })
        assertNull(BondReader.readGain(BondReader.GAIN_PROFILES) { OcrText("NEXT 1,338,294", 99) })
    }

    @Test fun `compact gain retry recovers JP parentheses while preserving confidence and conflicts`() {
        for (bonus in listOf(372, 374)) {
            val result = BondReader.readGain(BondReader.JP_GAIN_PROFILES) { options ->
                if (options.horizontalScale < 1.0) OcrText("+365(+$bonus)", 91)
                else OcrText("+365(4+$bonus)", 90) // Incomplete pair is never repaired by text substitution.
            }
            assertEquals(BondValue.Gain(365, bonus), result)
        }
        assertNull(BondReader.readGain(BondReader.JP_GAIN_PROFILES) { options ->
            OcrText("+365(+372)", if (options.horizontalScale < 1.0) 84 else 40)
        })
        var calls = 0
        assertEquals(BondValue.Gain(365, 372), BondReader.readGain(BondReader.JP_GAIN_PROFILES) { options ->
            when {
                calls++ == 0 -> OcrText("+365(+374)", 91)
                options.horizontalScale < 1.0 -> OcrText("+365(+372)", 91)
                else -> OcrText("", 0)
            }
        })
    }

    @Test fun `majority rejects near ties weak winners and fewer than three votes`() {
        fun votes(good: Int, bad: Int, confidence: Int = 85) =
            List(good) { OcrText("1318(+805)", confidence) } + List(bad) { OcrText("18(+805)", 90) }
        for ((good, bad) in listOf(2 to 1, 3 to 2, 4 to 3, 5 to 3, 3 to 3))
            assertNull(BondReader.majorityGain(votes(good, bad)), "$good versus $bad")
        assertNull(BondReader.majorityGain(votes(8, 0, 84)))
        assertNull(BondReader.majorityGain(votes(1, 0) + votes(9, 0, 59)))
        assertEquals(BondValue.Gain(1318, 805), BondReader.majorityGain(votes(3, 1)))
        assertEquals(BondValue.Gain(1318, 805), BondReader.majorityGain(votes(4, 2)))
        assertEquals(BondValue.Gain(1318, 805), BondReader.majorityGain(votes(1, 0) + votes(2, 0, 60)))
    }

    @Test fun `base check rejects even consensus conflicts without inventing a gain`() {
        fun column(index: Int, base: Int, bonus: Int = 805) = BondReader.ColumnReading(index,
            BondValue.Gain(base, bonus), List(3) { OcrText("$base(+$bonus)", 90) }, "consensus")
        val anchors = listOf(column(0, 1318), column(1, 1318))
        val wrong = column(2, 18)
        assertEquals(BondValue.Unknown, BondReader.crossCheck(anchors + wrong)[2].value)
        assertEquals(wrong, BondReader.crossCheck(listOf(anchors[0], wrong))[1])
        val weak = wrong.copy(value = BondValue.Unknown,
            candidates = wrong.candidates + List(2) { OcrText("1318(+805)", 90) })
        assertEquals(BondValue.Unknown, BondReader.crossCheck(anchors + weak)[2].value)
        val recovered = weak.copy(candidates = weak.candidates + OcrText("1318(+805)", 60))
        val result = BondReader.crossCheck(anchors + recovered)[2]
        assertEquals(BondValue.Gain(1318, 805), result.value)
        assertEquals("majority+base", result.reason)
        val sameBaseTie = weak.copy(candidates = List(3) { OcrText("1318(+805)", 90) } +
            List(2) { OcrText("1318(+806)", 90) } + OcrText("18(+805)", 90))
        assertEquals(BondValue.Unknown, BondReader.crossCheck(anchors + sameBaseTie)[2].value)
        val ambiguous = anchors + listOf(column(2, 18), column(3, 18), column(4, 99))
        assertEquals(BondValue.Unknown, BondReader.crossCheck(ambiguous)[4].value)
        assertEquals(BondReader.crossCheck(anchors + recovered).reversed(),
            BondReader.crossCheck((anchors + recovered).reversed()))
    }

    @Test fun `level width retry preserves two strong reads and refuses conflicting cap levels`() {
        assertEquals(7, BondReader.readLevel { OcrText("7", if (it.horizontalScale > 1.0) 92 else 80) })
        assertEquals(9, BondReader.readLevel { OcrText(if (it.horizontalScale > 1.0) "10" else "9", 95) })
        var calls = 0
        assertNull(BondReader.readLevel { options ->
            if (options.horizontalScale == 1.0 && calls++ == 0) OcrText("9", 95)
            else if (options.horizontalScale == 1.0) OcrText("", 0)
            else OcrText("10", 95)
        })
    }

    @Test fun `OCR width correction defaults to one and rejects invalid factors`() {
        assertEquals(1.0, TextLineOcrOptions().horizontalScale)
        for (factor in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { TextLineOcrOptions(horizontalScale = factor) }
        }
    }

    @Test fun `result columns are always characters one through five with no party mapping`() {
        val ledger = BondLedger()
        ledger.normal(0, List(5) { BondValue.Gain(it + 1, 10) })
        assertEquals((1..5).toList(), ledger.summary().slots.map { it.position })
        assertEquals(listOf(11L, 12L, 13L, 14L, 15L), ledger.summary().slots.map { it.total })
    }

    @Test fun `retrying or revisiting the same result does not double count`() {
        val ledger = BondLedger()
        repeat(5) { ledger.normal(0, List(5) { gain }) }
        ledger.normal(1, List(5) { BondValue.Gain(25, 6) })
        assertTrue(ledger.summary().slots.all { it.total == 7439L && it.readRuns == 2 && it.missedRuns == 0 })
    }

    @Test fun `obscured level up never fabricates a gain and only ten or above sets MAX`() {
        for (level in listOf(7, 9, 10, 11, 12, 16)) {
            val ledger = BondLedger(); ledger.levelUp(0, level, 2)
            assertEquals(if (level >= 10) setOf(3) else emptySet(), ledger.maxPositions(0))
            assertTrue(ledger.summary().slots.all { it.missedRuns == 1 && it.readRuns == 0 })
        }
    }

    @Test fun `unmapped ten records the stop without fabricating a result column`() {
        val ledger = BondLedger()
        ledger.levelUp(0, 10, null)
        ledger.levelUp(0, 10, null)
        assertTrue(ledger.maxPositions(0).isEmpty())
        assertTrue(ledger.hasMaxLevelUp(0))
        assertEquals(1, ledger.summary().unmappedLevelUpRuns)
        ledger.normal(1, listOf(gain, gain, BondValue.Max, gain, gain))
        assertEquals(setOf(3), ledger.maxPositions(1))
        val slot = ledger.summary().slots.single { it.position == 3 }
        assertEquals(0L, slot.total); assertEquals(1, slot.readRuns); assertEquals(1, slot.missedRuns)
    }

    @Test fun `an unmapped new cap cannot turn its obscured gain into a measured zero`() {
        val ledger = BondLedger()
        ledger.normal(0, List(5) { BondValue.Max })
        ledger.levelUp(0, 10, null)
        assertTrue(ledger.summary().slots.all { it.readRuns == 0 && it.missedRuns == 1 })
    }

    @Test fun `partial OCR does not shift columns or count unknown as real zero`() {
        val ledger = BondLedger()
        ledger.normal(0, listOf(gain, BondValue.Unknown, BondValue.Max, gain, BondValue.Unknown))
        assertEquals(listOf(7408L, 0, 0, 7408, 0), ledger.summary().slots.map { it.total })
        assertEquals(listOf(0, 1, 0, 0, 1), ledger.summary().slots.map { it.missedRuns })
        val short = BondLedger(); short.normal(0, List(4) { BondValue.Max })
        assertTrue(short.maxPositions(0).isEmpty())
    }

    @Test fun `global bond switch disables all reader work and preserves config`() {
        val f = Fixture()
        every { f.api.prefs.bondTrackingFeatureEnabled } returns false
        assertFalse(f.tracker.enabled)
        f.tracker.beginBattle()
        assertFalse(f.tracker.isScreen())
        assertEquals(BondFrame.Other, f.tracker.process())
        f.tracker.finishResult()
        assertEquals(null, f.tracker.summary())
        io.mockk.verify { f.reader wasNot io.mockk.Called }
        assertTrue(f.config.trackBond)
        assertTrue(f.config.stopOnBondMax)
        every { f.api.prefs.bondTrackingFeatureEnabled } returns true
        assertTrue(f.tracker.enabled)
    }

    @Test fun `bond logs distinguish OCR failure from changing frames without changing stable totals`() {
        val f = Fixture()
        every { f.config.stopOnBondMax } returns false
        val logs = mutableListOf<ScriptLog>()
        every { f.api.messages.log(capture(logs)) } just Runs
        fun frame(bonus: Int) = BondFrame.Normal(listOf(
            BondValue.Gain(10, bonus), BondValue.Unknown, gain, BondValue.Max, gain))
        every { f.reader.read() } returnsMany listOf(frame(1), frame(2), frame(3), frame(4))
        every { f.reader.lastColumns } returns listOf(BondReader.ColumnReading(0, gain,
            listOf(OcrText("+4,748(+2,660)", 93)), null))
        f.tracker.process()
        val lines = logs.filterIsInstance<ScriptLog.Bond>().map { it.message }
        for (read in 0..3) assertTrue(lines.any { "read=$read" in it && "@93" in it })
        assertTrue(lines.any { "final c1=Unknown reason=no agreement between reads" in it })
        assertTrue(lines.any { "final c2=Unknown reason=OCR consensus failed" in it })
        assertTrue(lines.any { "final c3=" in it && "reason=agreed" in it })
        assertEquals(2, f.tracker.summary()!!.slots.count { it.missedRuns == 1 })
    }

    private class Fixture {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val reader = mockk<BondReader>().also { every { it.lastColumns } returns emptyList() }
        val state = BattleState()
        val config = mockk<io.github.fate_grand_automata.scripts.prefs.IBattleConfig>()
        val tracker = BondTracker(api, reader, state, config)
        init {
            every { api.prefs.bondTrackingFeatureEnabled } returns true
            every { api.prefs.gameServer } returns GameServer.Kr
            every { config.trackBond } returns true
            every { config.stopOnBondMax } returns true
            every { config.bondMaxIgnoredCharacters } returns emptySet()
        }
        fun start() { tracker.beginBattle() }
    }

    @Test fun `normal MAX respects watched result characters`() {
        for (ignored in listOf(false, true)) {
            val f = Fixture(); f.start()
            every { f.config.bondMaxIgnoredCharacters } returns if (ignored) setOf(3) else emptySet()
            every { f.reader.read() } returns BondFrame.Normal(List(5) { if (it == 2) BondValue.Max else BondValue.Gain(1, 2) })
            if (ignored) { f.tracker.process(); assertEquals(0, f.state.runs) }
            else {
                val error = assertFailsWith<AutoBattle.BattleExitException> { f.tracker.process() }
                assertEquals(listOf(3), (error.reason as AutoBattle.ExitReason.BondMax).positions)
                assertEquals(1, f.state.runs)
            }
        }
    }

    @Test fun `levels below ten and unknown continue while gains remain missing`() {
        for (frame in listOf(BondFrame.LevelUp(7, 2), BondFrame.LevelUp(9, 2), BondFrame.LevelUp(null, 2))) {
            val f = Fixture(); f.start(); every { f.reader.read() } returns frame
            f.tracker.process(); assertEquals(0, f.state.runs)
            assertTrue(f.tracker.summary()!!.slots.all { it.missedRuns == 1 })
        }
    }

    @Test fun `all reached levels ten through sixteen stop with or without a column`() {
        for (level in 10..16) for (column in listOf(null, 0, 2, 4)) {
            val f = Fixture()
            every { f.config.bondMaxIgnoredCharacters } returns (1..5).toSet()
            every { f.reader.read() } returns BondFrame.LevelUp(level, column)
            assertIs<AutoBattle.ExitReason.BondMax>(assertFailsWith<AutoBattle.BattleExitException> { f.tracker.process() }.reason)
            assertEquals(1, f.state.runs)
            verify(exactly = 2) { f.reader.read(true) }
        }
    }

    @Test fun `popup confirmation agrees on ten plus rather than exact level or column`() {
        val f = Fixture()
        every { f.reader.read() } returnsMany listOf(BondFrame.LevelUp(10, 1), BondFrame.LevelUp(16, null))
        assertFailsWith<AutoBattle.BattleExitException> { f.tracker.process() }
        val fallback = Fixture()
        every { fallback.reader.read() } returns BondFrame.LevelUp(null, null, true)
        assertFailsWith<AutoBattle.BattleExitException> { fallback.tracker.process() }
        for (next in listOf(BondFrame.LevelUp(7, null), BondFrame.LevelUp(null, null), BondFrame.Other)) {
            val changing = Fixture()
            every { changing.reader.read() } returnsMany listOf(BondFrame.LevelUp(10, null), next)
            changing.tracker.process()
            assertEquals(0, changing.state.runs)
        }
        val disabled = Fixture()
        every { disabled.config.stopOnBondMax } returns false
        every { disabled.reader.read() } returns BondFrame.LevelUp(16, null)
        disabled.tracker.process()
        assertEquals(0, disabled.state.runs)
    }

    @Test fun `digit width leaves an uncertainty margin and rejects blank or horizontal lines`() {
        for (width in listOf(27, 30, 35, 81)) assertNull(BondReader.widthAtLeastTen(width))
        assertEquals(false, BondReader.widthAtLeastTen(21))
        for (width in listOf(41, 49, 64)) assertEquals(true, BondReader.widthAtLeastTen(width))
        val blank = ByteArray(90 * 55)
        assertNull(BondReader.digitWidth(blank, 90, 55))
        val line = blank.copyOf().also { for (x in 10..60) it[20 * 90 + x] = 255.toByte() }
        assertNull(BondReader.digitWidth(line, 90, 55))
        val strokes = blank.copyOf().also { for (y in 12..47) for (x in listOf(30,31,32,68,69,70)) it[y * 90 + x] = 200.toByte() }
        assertEquals(41, BondReader.digitWidth(strokes, 90, 55))
        assertNull(BondReader.digitWidth(strokes, 91, 55))
    }

    @Test fun `counting animation is not accumulated until fresh readings agree`() {
        val f = Fixture(); f.start()
        every { f.config.stopOnBondMax } returns false
        fun normal(value: Int) = BondFrame.Normal(List(5) { BondValue.Gain(value, 0) })
        every { f.reader.read() } returnsMany listOf(normal(1), normal(2), normal(3), normal(4))
        f.tracker.process()
        assertTrue(f.tracker.summary()!!.slots.all { it.total == 0L && it.missedRuns == 1 })
        every { f.reader.read() } returns normal(10)
        f.tracker.process(); f.tracker.process()
        assertTrue(f.tracker.summary()!!.slots.all { it.total == 10L && it.readRuns == 1 && it.missedRuns == 0 })
    }

    @Test fun `last two non unknown gains agree independently for each column including owner tablet dropout`() {
        val a = BondValue.Gain(1318, 1214)
        val b = BondValue.Gain(1318, 805)
        val u = BondValue.Unknown
        val sequences = listOf(
            listOf(a, a, a, u), // Owner beta.5 log: final OCR lacks a strong candidate.
            listOf(a, a, b, u), // A later confirmed value blocks the earlier agreement.
            listOf(a, u, u, u), // A single confirmed reading is insufficient.
            listOf(a, b, BondValue.Gain(1318, 1079), BondValue.Gain(1318, 1080)),
            listOf(u, a, u, a)
        )
        val f = Fixture()
        every { f.config.stopOnBondMax } returns false
        val logs = mutableListOf<ScriptLog>()
        every { f.api.messages.log(capture(logs)) } just Runs
        every { f.reader.lastColumns } returns List(5) {
            BondReader.ColumnReading(it, a, emptyList(), "majority")
        }
        every { f.reader.read() } returnsMany (0..3).map { read ->
            BondFrame.Normal(sequences.map { it[read] })
        }
        f.tracker.process()
        assertEquals(listOf(2532L, 0L, 0L, 0L, 2532L), f.tracker.summary()!!.slots.map { it.total })
        assertEquals(listOf(0, 1, 1, 1, 0), f.tracker.summary()!!.slots.map { it.missedRuns })
        val lines = logs.filterIsInstance<ScriptLog.Bond>().map { it.message }
        assertTrue(lines.any { "final c1=$a reason=agreed(reads=1,2) rule=majority" in it })
        assertTrue(lines.any { "final c5=$a reason=agreed(reads=1,3) rule=majority" in it })
        for (column in 2..4) assertTrue(lines.any { "final c$column=Unknown" in it && "agreed(" !in it })
        verify(exactly = 4) { f.reader.read(true) }
    }

    @Test fun `unknown gaps do not relax the adjacent frame requirement for MAX`() {
        val m = BondValue.Max
        val u = BondValue.Unknown
        for (sequence in listOf(listOf(m, m, m, u), listOf(m, u, m, u), listOf(u, m, u, m))) {
            val f = Fixture()
            every { f.reader.read() } returnsMany sequence.map { BondFrame.Normal(List(5) { _ -> it }) }
            f.tracker.process()
            assertEquals(0, f.state.runs)
            assertTrue(f.tracker.summary()!!.slots.all { it.missedRuns == 1 })
        }
        val f = Fixture()
        every { f.reader.read() } returnsMany listOf(u, u, m, m).map { BondFrame.Normal(List(5) { _ -> it }) }
        assertFailsWith<AutoBattle.BattleExitException> { f.tracker.process() }
        assertEquals(1, f.state.runs)
    }

    @Test fun `a confirmed MAX between gains blocks the older gain agreement`() {
        val f = Fixture()
        every { f.config.stopOnBondMax } returns false
        every { f.reader.read() } returnsMany listOf(gain, gain, BondValue.Max, gain)
            .map { BondFrame.Normal(List(5) { _ -> it }) }
        f.tracker.process()
        assertTrue(f.tracker.summary()!!.slots.all { it.missedRuns == 1 })
    }

    @Test fun `each result waits out the counter animation before its first OCR even with faster waits`() {
        val f = Fixture()
        every { f.config.stopOnBondMax } returns false
        var elapsed = Duration.ZERO
        val readTimes = mutableListOf<Duration>()
        every { with(f.api) { 1200.milliseconds.wait(false) } } answers { elapsed += 1200.milliseconds }
        every { with(f.api) { 350.milliseconds.wait(true) } } answers { elapsed += 35.milliseconds }
        every { f.reader.read(true) } answers {
            readTimes += elapsed
            // Early frames can agree on an intermediate value before the animation finishes.
            BondFrame.Normal(List(5) { if (elapsed < 1200.milliseconds) BondValue.Gain(100, 0) else gain })
        }
        repeat(2) {
            elapsed = Duration.ZERO
            f.tracker.beginBattle()
            f.tracker.process()
            f.tracker.finishResult()
            f.state.nextRun()
        }
        assertTrue(readTimes.all { it >= 1200.milliseconds })
        assertTrue(f.tracker.summary()!!.slots.all { it.total == 14816L && it.readRuns == 2 && it.missedRuns == 0 })
    }

    @Test fun `disabled tracking and unsupported servers do not inspect results or OCR`() {
        for (server in listOf(GameServer.En.Original, GameServer.Jp.Original)) {
            val f = Fixture()
            every { f.api.prefs.gameServer } returns server
            if (server is GameServer.Jp) {
                every { f.config.trackBond } returns false
                every { f.config.stopOnBondMax } returns false
            }
            f.tracker.beginBattle(); f.tracker.finishResult()
            assertFalse(f.tracker.isScreen()); assertNull(f.tracker.summary())
            verify { f.reader wasNot Called }
        }
    }

    @Test fun `MAX only mode skips gain OCR and does not present a tracking summary`() {
        val f = Fixture()
        every { f.config.trackBond } returns false
        every { f.config.bondMaxIgnoredCharacters } returns (1..5).toSet()
        every { f.reader.read(false) } returns BondFrame.Normal(List(5) { BondValue.Max })
        f.start(); f.tracker.process()
        assertNull(f.tracker.summary())
        verify(exactly = 4) { f.reader.read(false) }
        verify(exactly = 0) { f.reader.read(true) }
    }
    @Test fun `tracking can begin directly on the result screen without seeing a battle or party`() {
        val f = Fixture()
        every { f.config.stopOnBondMax } returns false
        every { f.reader.read() } returns BondFrame.Normal(List(5) { BondValue.Gain(it + 10, 1) })
        f.tracker.process()
        assertEquals(listOf(11L, 12L, 13L, 14L, 15L), f.tracker.summary()!!.slots.map { it.total })
        verify(exactly = 4) { f.reader.read(true) }
    }

    @Test fun `each of the five watched result columns can independently stop the cleared quest`() {
        for (character in 1..5) {
            val f = Fixture()
            every { f.config.bondMaxIgnoredCharacters } returns (1..5).toSet() - character
            every { f.reader.read() } returns BondFrame.Normal(List(5) { BondValue.Max })
            val error = assertFailsWith<AutoBattle.BattleExitException> { f.tracker.process() }
            assertEquals(listOf(character), (error.reason as AutoBattle.ExitReason.BondMax).positions)
            assertEquals(1, f.state.runs)
        }
    }

    @Test fun `a confirmed MAX is not lost when the result changes during a later capture`() {
        val f = Fixture()
        val normal = BondFrame.Normal(List(5) { BondValue.Max })
        every { f.reader.read() } returnsMany listOf(normal, normal, BondFrame.Other)
        assertFailsWith<AutoBattle.BattleExitException> { f.tracker.process() }
        assertEquals(1, f.state.runs)
    }

    @Test fun `two consecutive quest results accumulate five columns independently and session totals reset`() {
        val f = Fixture()
        every { f.config.stopOnBondMax } returns false
        f.start()
        every { f.reader.read() } returns BondFrame.Normal(List(5) { BondValue.Gain(it + 1, 10) })
        f.tracker.process(); f.tracker.process(); f.tracker.finishResult()
        f.state.nextRun(); f.tracker.beginBattle()
        every { f.reader.read() } returns BondFrame.Normal(List(5) { BondValue.Gain(it + 1, 20) })
        f.tracker.process(); f.tracker.finishResult()
        assertEquals(listOf(32L, 34L, 36L, 38L, 40L), f.tracker.summary()!!.slots.map { it.total })
        assertTrue(f.tracker.summary()!!.slots.all { it.readRuns == 2 && it.missedRuns == 0 })
        assertTrue(Fixture().tracker.summary()!!.slots.isEmpty())
    }

    @Test fun `checked character two ignores only its MAX while its gains still accumulate`() {
        val f = Fixture()
        every { f.config.bondMaxIgnoredCharacters } returns setOf(2)
        every { f.reader.read() } returns BondFrame.Normal(List(5) { BondValue.Gain(10, 2) })
        f.tracker.process()
        f.state.nextRun()
        every { f.reader.read() } returns BondFrame.Normal(List(5) { if (it == 1) BondValue.Max else BondValue.Gain(20, 3) })
        f.tracker.process()
        assertEquals(1, f.state.runs)
        assertEquals(12L, f.tracker.summary()!!.slots[1].total)
        f.state.nextRun()
        every { f.reader.read() } returns BondFrame.Normal(List(5) { if (it == 0) BondValue.Max else BondValue.Gain(1, 0) })
        val error = assertFailsWith<AutoBattle.BattleExitException> { f.tracker.process() }
        assertEquals(listOf(1), (error.reason as AutoBattle.ExitReason.BondMax).positions)
        assertEquals(3, f.state.runs)
    }

}
