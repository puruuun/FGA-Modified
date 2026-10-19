package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.modules.FpSellPolicy
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.mockk.*
import kotlin.test.*

class FpSellBuildPolicyTest {
    @Test fun `beta respects stored dry run and retains safety fallback`() {
        for (stored in listOf(true, false)) for (lock in listOf(true, false))
            for (server in listOf(GameServer.Kr, GameServer.Jp.Original, GameServer.En.Original)) {
                val prefs = mockk<IPreferences>(relaxed = true)
                every { prefs.gameServer } returns server
                every { prefs.fpSellDryRun } returns stored
                every { prefs.fpSellStableBuild } returns false
                val options = FpSellPolicy.options(prefs, lock)
                assertTrue(options.allowed)
                assertEquals(stored || !FpSellPolicy.realSellAllowed(server, lock), options.dryRun)
            }
    }
    @Test fun `stable ignores stored dry run and blocks without any dry fallback`() {
        for (stored in listOf(true, false)) for (lock in listOf(true, false))
            for (server in listOf(GameServer.Kr, GameServer.Jp.Original, GameServer.En.Original)) {
                val prefs = mockk<IPreferences>(relaxed = true)
                every { prefs.gameServer } returns server
                every { prefs.fpSellDryRun } returns stored
                every { prefs.fpSellStableBuild } returns true
                val options = FpSellPolicy.options(prefs, lock)
                assertFalse(options.dryRun)
                assertEquals(FpSellPolicy.realSellAllowed(server, lock), options.allowed)
                verify(exactly = 0) { prefs.fpSellDryRun }
                if (!options.allowed) assertFalse(FpSellPolicy.select(
                    FpSellPolicy.Cell(true, FpSellPolicy.Lock.Unlocked, false), options))
            }
    }
}
