package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.modules.QuestItemReader.Kind
import io.github.fate_grand_automata.scripts.modules.QuestItemReader.View
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/** Synchronizes only the auto-use controls; item counts never determine ON/OFF. */
@ScriptScope
class QuestItems @Inject constructor(api: IFgoAutomataApi, private val reader: QuestItemReader) : IFgoAutomataApi by api {
    fun prepare() {
        // Bypass must do no captures, template loads or taps. Other servers keep their old behavior.
        if (prefs.ignoreQuestItemUsage || (prefs.gameServer !is GameServer.Kr && prefs.gameServer !is GameServer.Jp)) return
        val wantedStorm = prefs.selectedServerConfigPref.useStormPod
        val wantedTea = prefs.selectedServerConfigPref.useTeapot
        val initial = await { it != View.Unknown }
        when (initial) {
            View.Unavailable -> return
            is View.Dialog -> configureDialog(initial, wantedStorm, wantedTea)
            is View.Single -> {
                // Identical-looking main buttons can either toggle directly or open a dialog.
                initial.button.click.click()
                val changed = await { it is View.Dialog ||
                    (it is View.Single && it.kind == initial.kind && it.button.on != initial.button.on) }
                when (changed) {
                    is View.Dialog -> configureDialog(changed, wantedStorm, wantedTea)
                    is View.Single -> {
                        val wanted = if (changed.kind == Kind.StormPod) wantedStorm else wantedTea
                        if (changed.button.on != wanted) {
                            changed.button.click.click()
                            await { it is View.Single && it.kind == initial.kind && it.button.on == wanted }
                        }
                    }
                    else -> fail()
                }
            }
            View.Unknown -> fail()
        }
    }

    private fun configureDialog(initial: View.Dialog, storm: Boolean, tea: Boolean) {
        var view = initial
        if (view.stormPod.on != storm) {
            view.stormPod.click.click()
            view = await { it is View.Dialog && it.stormPod.on == storm && it.teapot.on == view.teapot.on } as View.Dialog
        }
        if (view.teapot.on != tea) {
            view.teapot.click.click()
            view = await { it is View.Dialog && it.stormPod.on == storm && it.teapot.on == tea } as View.Dialog
        }
        if (view.stormPod.on != storm || view.teapot.on != tea) fail()
        view.close.click()
        await { it is View.Single && it.button.on == (if (it.kind == Kind.StormPod) storm else tea) }
    }

    private fun await(accept: (View) -> Boolean): View {
        repeat(10) {
            0.3.seconds.wait()
            val view = reader.read()
            if (accept(view)) return view
        }
        fail()
    }

    private fun fail(): Nothing = throw AutoBattle.BattleExitException(AutoBattle.ExitReason.QuestItemSettingFailed)
}
