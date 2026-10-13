package io.github.fate_grand_automata.scripts.supportSelection

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.prefs.ISupportPreferences
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

@ScriptScope
class AppendSkillSelection @Inject constructor(
    api: IFgoAutomataApi,
    prefs: ISupportPreferences,
    private val display: SupportSkillDisplay,
    private val reader: SupportSkillLevelReader
) : IFgoAutomataApi by api {
    private val required = List(5) { prefs.appendSkillsMax.getOrElse(it) { false } }
    val enabled = prefs.appendSkillCheck && prefs.preferredServants.isNotEmpty() && required.any { it }

    fun ensureNormal() = display.ensureNormal()

    fun check(bounds: SupportBounds): Boolean {
        if (!enabled || required.none { it }) return true
        display.showAppend()
        val detected = useSameSnapIn { reader.read(bounds, append = true) }
        val matched = SupportSkillLevelReader.matches(required, detected)
        messages.log(ScriptLog.MaxAppendSkills(required, detected))
        // Also restore on success, so later quests and turning this setting off keep normal skill checks valid.
        display.restoreNormal()
        return matched
    }
}
