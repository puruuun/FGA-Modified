package io.github.fate_grand_automata.prefs

import io.github.fate_grand_automata.scripts.modules.SkillLevelMode
import io.github.fate_grand_automata.scripts.prefs.ISkillLevelPreferences

/**
 * D-18: the slot targets are NOT persisted. They only carry the dialog result to the script of the same run, in
 * memory and process-wide (the launcher and the script may hold different [PreferencesImpl] instances).
 * The dialog never reads them back; it always opens with every slot at 0.
 */
internal class SkillLevelPrefs : ISkillLevelPreferences {
    private companion object {
        val held = mutableMapOf<SkillLevelMode, List<Int>>()
        @Volatile var checkOnlyHeld = false
    }

    override var checkOnly: Boolean
        get() = checkOnlyHeld
        set(value) { checkOnlyHeld = value }

    override fun targets(mode: SkillLevelMode): List<Int> = synchronized(held) {
        val saved = held[mode].orEmpty()
        List(mode.slots) { saved.getOrElse(it) { 0 }.coerceIn(0, 10) }
    }

    override fun setTargets(mode: SkillLevelMode, targets: List<Int>) {
        synchronized(held) { held[mode] = targets.toList() }
    }
}
