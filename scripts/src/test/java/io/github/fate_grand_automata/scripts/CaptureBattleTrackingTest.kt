package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.capture.*
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.*
import io.github.fate_grand_automata.scripts.models.BondFrame
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.modules.*
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.mockk.*
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CaptureBattleTrackingTest {
    private class Feed : ICaptureFeed {
        var collecting = true
        var starting = false
        var sequence = 7L
        var response: CapturedBattle? = CapturedBattle(mapOf(6516L to 3L, 6503L to 2L, 6510L to 99L), emptyList())
        val tokens = mutableListOf<Long>()
        override fun isCollecting() = collecting
        override fun isStarting() = starting
        override fun battleToken() = sequence
        override fun awaitBattle(token: Long, timeout: Duration): CapturedBattle? {
            assertEquals(3.seconds, timeout)
            tokens += token
            return response
        }
    }

    private class Fixture(server: GameServer = GameServer.Kr, collecting: Boolean = true) {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val config = mockk<IBattleConfig>(relaxed = true)
        val state = BattleState()
        val feed = Feed().apply { this.collecting = collecting }
        var limiting = false
        var limit = 10
        val capture: CaptureBattleTracker
        val materials: MaterialsTracker
        val reader = mockk<BondReader>(relaxed = true)
        val bonds: BondTracker
        init {
            every { api.prefs.gameServer } returns server
            every { api.prefs.bondTrackingFeatureEnabled } returns true
            every { config.materials } returns setOf(MaterialEnum.Bone, MaterialEnum.Proof)
            every { config.bondMaxIgnoredCharacters } returns emptySet()
            every { api.prefs.selectedServerConfigPref.shouldLimitMats } answers { limiting }
            every { api.prefs.selectedServerConfigPref.shouldLimitMats = any() } answers { limiting = firstArg() }
            every { api.prefs.selectedServerConfigPref.limitMats } answers { limit }
            every { api.prefs.selectedServerConfigPref.limitMats = any() } answers { limit = firstArg() }
            capture = CaptureBattleTracker(api, state, config, feed)
            materials = MaterialsTracker(api, config, state, capture)
            bonds = BondTracker(api, reader, state, config, capture = capture)
        }
        fun stop(action: () -> Unit) = assertFailsWith<AutoBattle.BattleExitException>(block = action).reason
    }

    @Test fun `only KR and official JP enable capture while collecting and keep the start decision`() {
        for (server in GameServers.values) for (collecting in listOf(false, true)) {
            val expected = collecting && (server == GameServer.Kr || server == GameServer.Jp.Original)
            val f = Fixture(server, collecting)
            every { f.config.trackBond } returns true
            f.capture.start()
            assertEquals(expected, f.capture.enabled, "$server collecting=$collecting")
            assertEquals(!expected && (server is GameServer.Kr || server is GameServer.Jp), f.bonds.enabled)
            f.feed.collecting = !collecting
            f.capture.start()
            assertEquals(expected, f.capture.enabled)
            verify(exactly = 1) { f.api.messages.log(match { it is ScriptLog.CaptureMode && it.enabled == expected }) }
        }
    }

    @Test fun `VPN fallback waits up to three seconds with unscaled cancellable waits`() {
        for (server in listOf(GameServer.Kr, GameServer.Jp.Original)) {
        val f = Fixture(server, collecting = false)
        f.feed.starting = true
        var waits = 0
        every { with(f.api) { 100.milliseconds.wait(false) } } answers {
            waits++
            if (waits == 2) f.feed.collecting = true
        }
        f.capture.start()
        assertTrue(f.capture.enabled); assertEquals(2, waits)
        val timeout = Fixture(server, collecting = false)
        timeout.feed.starting = true
        timeout.capture.start()
        assertFalse(timeout.capture.enabled)
        verify(exactly = 30) { with(timeout.api) { 100.milliseconds.wait(false) } }
        val off = Fixture(server, collecting = false)
        off.capture.start()
        verify(exactly = 0) { with(off.api) { 100.milliseconds.wait(any()) } }
        }
        for (server in GameServers.values.filter { it != GameServer.Kr && it != GameServer.Jp.Original }) {
            val other = Fixture(server, false)
            other.feed.starting = true
            other.capture.start()
            assertFalse(other.capture.enabled)
            verify(exactly = 0) { with(other.api) { 100.milliseconds.wait(any()) } }
        }
    }

    @Test fun `captured materials skip templates sum only selected items and avoid duplicate drop screens`() {
        checkCapturedMaterials(GameServer.Kr)
    }

    @Test fun `official JP uses captured drops without template matching`() {
        checkCapturedMaterials(GameServer.Jp.Original)
    }

    private fun checkCapturedMaterials(server: GameServer) {
        val f = Fixture(server); f.capture.start()
        f.materials.parseMaterials(); f.materials.parseMaterials()
        assertEquals(mapOf(MaterialEnum.Bone to 3, MaterialEnum.Proof to 2), f.materials.farmed)
        verify(exactly = 0) { f.api.images.loadMaterial(any()) }
        assertEquals(listOf(7L), f.feed.tokens)
        f.feed.sequence = 8; f.capture.beginBattle()
        f.materials.parseMaterials()
        assertEquals(6, f.materials.farmed[MaterialEnum.Bone])
        assertEquals(listOf(7L, 8L), f.feed.tokens)
    }

    @Test fun `capture material limit counts cleared run and auto decrement consumes the same count`() {
        val f = Fixture(); f.limiting = true; f.limit = 5; f.capture.start()
        val reason = f.stop { f.materials.parseMaterials() } as AutoBattle.ExitReason.LimitMaterials
        assertEquals(5, reason.count); assertEquals(1, f.state.runs)
        f.materials.autoDecrement()
        assertEquals(1, f.limit); assertFalse(f.limiting)
        val remaining = Fixture(); remaining.limiting = true; remaining.capture.start()
        remaining.materials.parseMaterials(); remaining.materials.autoDecrement()
        assertEquals(5, remaining.limit); assertTrue(remaining.limiting)
    }

    @Test fun `outside capture mode materials still use template matching`() {
        val f = Fixture(collecting = false); f.capture.start()
        f.materials.parseMaterials()
        verify(exactly = 2) { f.api.images.loadMaterial(any()) }
        assertTrue(f.feed.tokens.isEmpty())
    }

    @Test fun `bond OCR and end summary turn off while captured MAX stops at first result`() {
        checkCapturedBondMax(GameServer.Kr)
    }

    @Test fun `official JP disables bond OCR and stops on captured MAX at first result`() {
        checkCapturedBondMax(GameServer.Jp.Original)
    }

    private fun checkCapturedBondMax(server: GameServer) {
        val f = Fixture(server)
        every { f.config.trackBond } returns true
        every { f.config.stopOnBondMax } returns true
        f.feed.response = CapturedBattle(emptyMap(), listOf(CapturedBond(2, 901, 9, 10, 11, 100)))
        f.capture.start(); f.bonds.beginBattle()
        assertFalse(f.bonds.enabled); assertFalse(f.bonds.isScreen()); assertNull(f.bonds.summary())
        assertEquals(BondFrame.Other, f.bonds.process())
        val reason = f.stop { f.bonds.finishResult() } as AutoBattle.ExitReason.BondMax
        assertEquals(listOf(2), reason.positions); assertEquals(1, f.state.runs)
        assertEquals(listOf(7L), f.feed.tokens)
        verify { f.api.messages.log(match { it is ScriptLog.Bond && it.message.contains("2:svt=901") }) }
        verify { f.reader wasNot Called }
    }

    @Test fun `ignored captured MAX continues and result checks share one wait with materials`() {
        val f = Fixture()
        every { f.config.stopOnBondMax } returns true
        every { f.config.bondMaxIgnoredCharacters } returns setOf(2)
        f.feed.response = CapturedBattle(emptyMap(), listOf(CapturedBond(2, 901, 12, 12, 12, 0)))
        f.capture.start(); f.bonds.finishResult(); f.bonds.finishResult(); f.materials.parseMaterials()
        assertEquals(0, f.state.runs); assertEquals(listOf(7L), f.feed.tokens)
    }

    @Test fun `missing response never stops with or without stop conditions and does not fall back to OCR`() {
        for ((material, bond) in listOf(false to false, true to false, false to true, true to true)) {
            val f = Fixture(); f.limiting = material
            every { f.config.stopOnBondMax } returns bond
            f.feed.response = null; f.capture.start()
            f.bonds.finishResult(); f.materials.parseMaterials(); f.bonds.finishResult()
            assertEquals(0, f.state.runs); assertTrue(f.materials.farmed.isEmpty())
            verify(exactly = 0) { f.api.images.loadMaterial(any()) }
            verify { f.reader wasNot Called }
            assertEquals(listOf(7L), f.feed.tokens)
        }
    }

    @Test fun `a response without drops or bond sections continues and the next battle still stops on MAX`() {
        val f = Fixture()
        every { f.config.stopOnBondMax } returns true
        f.feed.response = CapturedBattle(null, null); f.capture.start()
        f.capture.checkBond(); f.materials.parseMaterials()
        assertEquals(0, f.state.runs)
        f.feed.sequence = 8; f.feed.response = CapturedBattle(emptyMap(), listOf(CapturedBond(1, 901, 10, 10, 10, 0)))
        f.capture.beginBattle()
        val reason = f.stop { f.capture.checkBond() } as AutoBattle.ExitReason.BondMax
        assertEquals(listOf(1), reason.positions)
    }

    @Test fun `master bond switch off means no wait and no slot log`() {
        val disabled = Fixture()
        every { disabled.api.prefs.bondTrackingFeatureEnabled } returns false
        every { disabled.config.stopOnBondMax } returns true
        disabled.feed.response = null; disabled.capture.start(); disabled.capture.checkBond()
        assertEquals(0, disabled.state.runs); assertTrue(disabled.feed.tokens.isEmpty())
    }

    @Test fun `slot log lists deck order and initPos order once per battle even without a bond stop`() {
        val f = Fixture()
        every { f.config.stopOnBondMax } returns false
        f.feed.response = CapturedBattle(emptyMap(), listOf(
            CapturedBond(1, 901, 9, 10, 10, 50, initPos = 3), CapturedBond(2, 902, 5, 5, 10, 7, initPos = 1)))
        val logs = mutableListOf<ScriptLog>()
        every { f.api.messages.log(capture(logs)) } just Runs
        f.capture.start(); f.capture.checkBond(); f.capture.checkBond(); f.materials.parseMaterials()
        assertEquals(1, logs.count { it is ScriptLog.Bond && it.message.contains("initPosOrder=[902, 901]") })
        assertEquals(0, f.state.runs)
    }

    @Test fun `slot log content`() {
        val text = CaptureBattleTracker.slotLog(CapturedBattle(emptyMap(), listOf(
            CapturedBond(1, 901, 9, 10, 10, 50, initPos = 3), CapturedBond(2, 902, 5, 5, 10, 7, initPos = 1))))
        assertEquals("capture bond slots=1:svt=901 r=9->10 cap=10 +50 | 2:svt=902 r=5->5 cap=10 +7 | initPosOrder=[902, 901]", text)
        assertEquals("capture bond slots=none", CaptureBattleTracker.slotLog(CapturedBattle(null, null)))
    }
}
