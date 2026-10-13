package io.github.fate_grand_automata.scripts.supportSelection

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.prefs.ISupportPreferences
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

@ScriptScope
class SupportNpSelection @Inject constructor(
    api: IFgoAutomataApi,
    private val supportPrefs: ISupportPreferences,
    private val reader: SupportNpLevelReader
) : IFgoAutomataApi by api {
    fun check(bounds: SupportBounds): Boolean {
        if (!supportPrefs.npLevelCheck) return true
        val required = supportPrefs.minimumNpLevel
        val detected = reader.read(bounds)
        val matched = required in 1..5 && detected != null && detected in required..5
        messages.log(ScriptLog.SupportNpLevel(required, detected, matched))
        return matched
    }
}
