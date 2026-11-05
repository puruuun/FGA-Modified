package io.github.fate_grand_automata.capture

import android.content.Context
import io.github.fate_grand_automata.prefs.PreferencesImpl
import io.github.fate_grand_automata.prefs.core.CompletedRunsHolder
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.enums.GameServer

internal class CaptureLauncherFixture(context: Context, server: GameServer = GameServer.En.Original) {
    val core = PrefsCore(PrefMaker(context.getSharedPreferences("launcher-test", Context.MODE_PRIVATE).apply {
        edit().clear().commit()
    }), context)
    val prefs = PreferencesImpl(core, CompletedRunsHolder()).apply { gameServer = server }
    val bond = core.forBattleConfig("capture-launcher").bond
    init {
        core.battleConfigList.set(setOf("capture-launcher"))
        core.forBattleConfig("capture-launcher").apply {
            name.set("테스트 주회")
            this.server.set(io.github.fate_grand_automata.prefs.core.BattleConfigCore.Server.NotSet)
        }
        prefs.selectedBattleConfig = prefs.forBattleConfig("capture-launcher")
        bond.track.set(true)
        bond.stopOnMax.set(true)
        bond.ignoredCharacters.set(setOf(2))
    }
}
