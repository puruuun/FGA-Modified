package io.github.fate_grand_automata.prefs.core

/** Target level per slot, 0 = off. Defaults are all off so nothing is spent unless the user asks for it. */
class SkillLevelPrefsCore(maker: PrefMaker) {
    val skillTargets = (1..3).map { maker.int("skill_level_target_skill_$it", 0) }
    val appendTargets = (1..5).map { maker.int("skill_level_target_append_$it", 0) }
}
