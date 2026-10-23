package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.modules.QuestItemReader.Kind
import io.github.fate_grand_automata.scripts.modules.QuestItemReader.View
import io.github.lib_automata.Location
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

/** Synchronizes only the auto-use controls; item counts never determine ON/OFF. */
@ScriptScope
class QuestItems @Inject constructor(api: IFgoAutomataApi, private val reader: QuestItemReader) : IFgoAutomataApi by api {
    fun prepare() {
        // Bypass must do no captures, template loads or taps. Other servers keep their old behavior.
        if (prefs.ignoreQuestItemUsage || (prefs.gameServer !is GameServer.Kr && prefs.gameServer !is GameServer.Jp)) return
        val wantedStorm = prefs.selectedServerConfigPref.useStormPod
        val wantedTea = prefs.selectedServerConfigPref.useTeapot
        val initial = await(initial = true) { it != View.Unknown }
        when (initial) {
            View.Unavailable -> return
            is View.Dialog -> configureDialog(initial, wantedStorm, wantedTea)
            is View.Single -> {
                // Identical-looking main buttons can either toggle directly or open a dialog.
                tapControl(initial.button.click)
                val changed = await { it is View.Dialog ||
                    (it is View.Single && it.kind == initial.kind && it.button.on != initial.button.on) }
                when (changed) {
                    is View.Dialog -> configureDialog(changed, wantedStorm, wantedTea)
                    is View.Single -> {
                        val wanted = if (changed.kind == Kind.StormPod) wantedStorm else wantedTea
                        if (changed.button.on != wanted) {
                            tapControl(changed.button.click)
                            await { it is View.Single && it.kind == initial.kind && it.button.on == wanted }
                        }
                    }
                    else -> fail()
                }
            }
            View.Unknown -> return
        }
    }

    private fun configureDialog(initial: View.Dialog, storm: Boolean, tea: Boolean) {
        var view = initial
        if (view.stormPod.on != storm) {
            tapControl(view.stormPod.click)
            view = await { it is View.Dialog && it.stormPod.on == storm && it.teapot.on == view.teapot.on } as View.Dialog
        }
        if (view.teapot.on != tea) {
            tapControl(view.teapot.click)
            view = await { it is View.Dialog && it.stormPod.on == storm && it.teapot.on == tea } as View.Dialog
        }
        if (view.stormPod.on != storm || view.teapot.on != tea) fail()
        tapControl(view.close)
        await { it is View.Single && it.button.on == (if (it.kind == Kind.StormPod) storm else tea) }
    }

    private fun tapControl(location: Location) {
        location.click()
        // Additional settling time after every item-control tap, including dialog close.
        100.milliseconds.wait(applyMultiplier = false)
    }

    private fun await(initial: Boolean = false, accept: (View) -> Boolean): View {
        repeat(10) {
            0.3.seconds.wait()
            val view = reader.read()
            if (accept(view)) return view
        }
        if (initial) return View.Unknown
        fail()
    }

    private fun fail(): Nothing = throw AutoBattle.BattleExitException(AutoBattle.ExitReason.QuestItemSettingFailed)
}
