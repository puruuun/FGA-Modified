package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.prefs.IAddedFeatureTuning
import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.locations.WeeklyRouteLocations

/** D-16: read the repeat button as a signal, then exit through the LEFT close location only. */
class WeeklyRepeatDialog(api: IFgoAutomataApi, private val loc: WeeklyRouteLocations,
    private val addedTuning: IAddedFeatureTuning = IAddedFeatureTuning.Default
) : IFgoAutomataApi by api {
    fun visible(): Boolean = loc.repeatDialogSignal.exists(images[Images.Repeat],
        similarity = addedTuning.weekly.defaultSimilarity { prefs.platformPrefs.minSimilarity })

    fun closeIfVisible(): Boolean = useSameSnapIn {
        if (!visible()) return@useSameSnapIn false
        messages.log(ScriptLog.WeeklyRoute("repeatDialog signal=Repeat tap=left-close point=${loc.repeatDialogClose}"))
        loc.repeatDialogClose.click()
        true
    }
}
