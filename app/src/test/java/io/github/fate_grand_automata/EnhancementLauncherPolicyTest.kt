package io.github.fate_grand_automata

import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.ScriptModeEnum
import io.github.fate_grand_automata.ui.launcher.EnhancementLauncherPolicy
import io.github.fate_grand_automata.ui.launcher.ScriptLauncherResponse
import kotlin.test.*

class EnhancementLauncherPolicyTest {
    @Test fun `start returns the currently shown enhancement mode`() {
        assertEquals(ScriptLauncherResponse.CEEnhance, EnhancementLauncherPolicy.response(ScriptModeEnum.CEEnhance))
        assertEquals(ScriptLauncherResponse.ServantEnhancement, EnhancementLauncherPolicy.response(ScriptModeEnum.ServantLevel))
    }

    @Test fun `JP and KR switch in both directions and start the switched mode`() {
        for (server in listOf(GameServer.Jp.Original, GameServer.Jp.BetterFGO, GameServer.Kr)) {
            val ce = EnhancementLauncherPolicy.switchTarget(ScriptModeEnum.ServantLevel, server)!!
            assertEquals(ScriptLauncherResponse.CEEnhance, EnhancementLauncherPolicy.response(ce))
            val servant = EnhancementLauncherPolicy.switchTarget(ce, server)!!
            assertEquals(ScriptLauncherResponse.ServantEnhancement, EnhancementLauncherPolicy.response(servant))
            assertNull(EnhancementLauncherPolicy.switchTarget(ScriptModeEnum.Battle, server))
        }
    }

    @Test fun `other servers have no enhancement switch`() {
        for (mode in listOf(ScriptModeEnum.ServantLevel, ScriptModeEnum.CEEnhance)) {
            for (server in listOf(GameServer.En.Original, GameServer.En.BetterFGO, GameServer.Cn, GameServer.Tw)) {
                assertNull(EnhancementLauncherPolicy.switchTarget(mode, server))
            }
        }
    }
}
