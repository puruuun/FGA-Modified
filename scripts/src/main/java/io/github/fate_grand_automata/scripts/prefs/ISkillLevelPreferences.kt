package io.github.fate_grand_automata.scripts.prefs

import io.github.fate_grand_automata.scripts.modules.SkillLevelMode

/** Target level per slot of the skill (3) and append (5) enhancement screens: 0 is off, 1 to 10 is the level to stop at. */
interface ISkillLevelPreferences {
    /** Always [SkillLevelMode.slots] values, each clamped to 0..10. Defaults to every slot off. */
    fun targets(mode: SkillLevelMode): List<Int>
    fun setTargets(mode: SkillLevelMode, targets: List<Int>)
}
