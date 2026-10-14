package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.models.*
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.modules.*
import io.github.lib_automata.OcrText
import io.mockk.*
import kotlin.test.*

class BondTrackingTest {
    private val party = BondParty(listOf(1, 2, 4, 5, 6))
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

    @Test fun `support compression preserves all six possible original positions`() {
        for (support in 1..6) {
            val p = BondParty((1..6).filter { it != support })
            val ledger = BondLedger()
            ledger.normal(0, p, List(5) { BondValue.Gain(it + 1, 10) })
            assertEquals(p.ownedPositions, ledger.summary().slots.map { it.position })
            assertEquals(listOf(11L, 12L, 13L, 14L, 15L), ledger.summary().slots.map { it.total })
        }
    }

    @Test fun `retrying or revisiting the same result does not double count`() {
        val ledger = BondLedger()
        repeat(5) { ledger.normal(0, party, List(5) { gain }) }
        ledger.normal(1, party, List(5) { BondValue.Gain(25, 6) })
        assertTrue(ledger.summary().slots.all { it.total == 7439L && it.readRuns == 2 && it.missedRuns == 0 })
    }

    @Test fun `obscured level up never fabricates a gain and only ten or above sets MAX`() {
        for (level in listOf(7, 9, 10, 11, 12, 16)) {
            val ledger = BondLedger(); ledger.levelUp(0, party, level, 2)
            assertEquals(if (level >= 10) setOf(4) else emptySet(), ledger.maxPositions(0))
            assertTrue(ledger.summary().slots.all { it.missedRuns == 1 && it.readRuns == 0 })
        }
    }

    @Test fun `unknown popup position waits for a normal MAX in the next quest`() {
        val ledger = BondLedger()
        ledger.levelUp(0, party, 10, null)
        ledger.levelUp(0, party, 10, null)
        assertTrue(ledger.maxPositions(0).isEmpty())
        assertEquals(1, ledger.summary().unmappedLevelUpRuns)
        ledger.normal(1, party, listOf(gain, gain, BondValue.Max, gain, gain))
        assertEquals(setOf(4), ledger.maxPositions(1))
        val slot = ledger.summary().slots.single { it.position == 4 }
        assertEquals(0L, slot.total); assertEquals(1, slot.readRuns); assertEquals(1, slot.missedRuns)
    }

    @Test fun `an unmapped new cap cannot turn its obscured gain into a measured zero`() {
        val ledger = BondLedger()
        ledger.normal(0, party, List(5) { BondValue.Max })
        ledger.levelUp(0, party, 10, null)
        assertTrue(ledger.summary().slots.all { it.readRuns == 0 && it.missedRuns == 1 })
    }

    @Test fun `partial OCR does not shift columns or count unknown as real zero`() {
        val ledger = BondLedger()
        ledger.normal(0, party, listOf(gain, BondValue.Unknown, BondValue.Max, gain, BondValue.Unknown))
        assertEquals(listOf(7408L, 0, 0, 7408, 0), ledger.summary().slots.map { it.total })
        assertEquals(listOf(0, 1, 0, 0, 1), ledger.summary().slots.map { it.missedRuns })
        // A four-member mapping with five visible values is not a confirmed result layout.
        val short = BondLedger(); short.normal(0, BondParty(listOf(1, 2, 4, 5)), List(5) { BondValue.Max })
        assertTrue(short.maxPositions(0).isEmpty())
    }

    private class Fixture {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val reader = mockk<BondReader>()
        val state = BattleState()
        val party = BondParty(listOf(1, 2, 4, 5, 6))
        val tracker = BondTracker(api, reader, state)
        init {
            every { api.prefs.gameServer } returns GameServer.Kr
            every { api.prefs.selectedServerConfigPref.trackBond } returns true
            every { api.prefs.selectedServerConfigPref.stopOnBondMax } returns true
            every { api.prefs.selectedServerConfigPref.bondMaxIgnoredPositions } returns emptySet()
            every { reader.readParty() } returns party
        }
        fun start() { tracker.captureParty(); tracker.beginBattle() }
    }

    @Test fun `normal and popup MAX respect exceptions at original position`() {
        for (popup in listOf(false, true)) for (ignored in listOf(false, true)) {
            val f = Fixture(); f.start()
            every { f.api.prefs.selectedServerConfigPref.bondMaxIgnoredPositions } returns if (ignored) setOf(4) else emptySet()
            every { f.reader.read() } returns if (popup) BondFrame.LevelUp(12, 2) else
                BondFrame.Normal(List(5) { if (it == 2) BondValue.Max else BondValue.Gain(1, 2) })
            if (ignored) { f.tracker.process(); assertEquals(0, f.state.runs) }
            else {
                val error = assertFailsWith<AutoBattle.BattleExitException> { f.tracker.process() }
                assertEquals(listOf(4), (error.reason as AutoBattle.ExitReason.BondMax).positions)
                assertEquals(1, f.state.runs)
            }
        }
    }

    @Test fun `seven and unmapped ten continue while unknown gains remain missing`() {
        for (frame in listOf(BondFrame.LevelUp(7, 2), BondFrame.LevelUp(10, null), BondFrame.LevelUp(null, 2))) {
            val f = Fixture(); f.start(); every { f.reader.read() } returns frame
            f.tracker.process(); assertEquals(0, f.state.runs)
            assertTrue(f.tracker.summary()!!.slots.all { it.missedRuns == 1 })
        }
    }

    @Test fun `counting animation is not accumulated until fresh readings agree`() {
        val f = Fixture(); f.start()
        every { f.api.prefs.selectedServerConfigPref.stopOnBondMax } returns false
        fun normal(value: Int) = BondFrame.Normal(List(5) { BondValue.Gain(value, 0) })
        every { f.reader.read() } returnsMany listOf(normal(1), normal(2), normal(3), normal(4))
        f.tracker.process()
        assertTrue(f.tracker.summary()!!.slots.all { it.total == 0L && it.missedRuns == 1 })
        every { f.reader.read() } returns normal(10)
        f.tracker.process(); f.tracker.process()
        assertTrue(f.tracker.summary()!!.slots.all { it.total == 10L && it.readRuns == 1 && it.missedRuns == 0 })
    }

    @Test fun `disabled tracking and unsupported servers do not capture or OCR`() {
        for (server in listOf(GameServer.En.Original, GameServer.Jp.Original)) {
            val f = Fixture()
            every { f.api.prefs.gameServer } returns server
            if (server is GameServer.Jp) {
                every { f.api.prefs.selectedServerConfigPref.trackBond } returns false
                every { f.api.prefs.selectedServerConfigPref.stopOnBondMax } returns false
            }
            f.tracker.captureParty(); f.tracker.beginBattle(); f.tracker.finishResult()
            assertFalse(f.tracker.isScreen()); assertNull(f.tracker.summary())
            verify { f.reader wasNot Called }
        }
    }

    @Test fun `MAX only mode skips gain OCR and does not present a tracking summary`() {
        val f = Fixture()
        every { f.api.prefs.selectedServerConfigPref.trackBond } returns false
        every { f.api.prefs.selectedServerConfigPref.bondMaxIgnoredPositions } returns setOf(1, 2, 4, 5, 6)
        every { f.reader.read(false) } returns BondFrame.Normal(List(5) { BondValue.Max })
        f.start(); f.tracker.process()
        assertNull(f.tracker.summary())
        verify(exactly = 4) { f.reader.read(false) }
        verify(exactly = 0) { f.reader.read(true) }
    }
}
