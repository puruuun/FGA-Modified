package io.github.fate_grand_automata.ui.launcher

import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.ScriptModeEnum

object EnhancementLauncherPolicy {
    fun switchTarget(mode: ScriptModeEnum, server: GameServer): ScriptModeEnum? =
        if (server !is GameServer.Jp && server !is GameServer.Kr) null else when (mode) {
            ScriptModeEnum.ServantLevel -> ScriptModeEnum.CEEnhance
            ScriptModeEnum.CEEnhance -> ScriptModeEnum.ServantLevel
            else -> null
        }

    fun response(mode: ScriptModeEnum): ScriptLauncherResponse = when (mode) {
        ScriptModeEnum.CEEnhance -> ScriptLauncherResponse.CEEnhance
        ScriptModeEnum.ServantLevel -> ScriptLauncherResponse.ServantEnhancement
        else -> error("Not an enhancement launcher: $mode")
    }
}
