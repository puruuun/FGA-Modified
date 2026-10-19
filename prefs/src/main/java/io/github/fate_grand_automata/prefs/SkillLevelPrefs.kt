package io.github.fate_grand_automata.prefs

import io.github.fate_grand_automata.prefs.core.SkillLevelPrefsCore
import io.github.fate_grand_automata.scripts.modules.SkillLevelMode
import io.github.fate_grand_automata.scripts.prefs.ISkillLevelPreferences

internal class SkillLevelPrefs(private val core: SkillLevelPrefsCore) : ISkillLevelPreferences {
    private fun prefs(mode: SkillLevelMode) = if (mode == SkillLevelMode.Skill) core.skillTargets else core.appendTargets

    override fun targets(mode: SkillLevelMode) = prefs(mode).map { it.get().coerceIn(0, 10) }

    override fun setTargets(mode: SkillLevelMode, targets: List<Int>) {
        prefs(mode).forEachIndexed { index, pref -> pref.set(targets.getOrElse(index) { 0 }.coerceIn(0, 10)) }
    }
}
