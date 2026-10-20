package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.locations.WeeklyRouteLocations

/** D-16: read the repeat button as a signal, then exit through the LEFT close location only. */
class WeeklyRepeatDialog(api: IFgoAutomataApi, private val loc: WeeklyRouteLocations) : IFgoAutomataApi by api {
    fun visible(): Boolean = images[Images.Repeat] in loc.repeatDialogSignal

    fun closeIfVisible(): Boolean = useSameSnapIn {
        if (!visible()) return@useSameSnapIn false
        messages.log(ScriptLog.WeeklyRoute("repeatDialog signal=Repeat tap=left-close point=${loc.repeatDialogClose}"))
        loc.repeatDialogClose.click()
        true
    }
}
