package io.github.fate_grand_automata.prefs.core

import io.github.fate_grand_automata.scripts.enums.BondCEEffectEnum
import io.github.fate_grand_automata.scripts.enums.SupportClass
import io.github.fate_grand_automata.scripts.enums.SupportSelectionModeEnum

class SupportPrefsCore(
    maker: PrefMaker
) {
    val friendNames = maker.stringSet("support_friend_names_list")
    val preferredServants: Pref<Set<String>> = ServantSelectionPref(maker.stringSet("support_pref_servant_list")) {
        preferredNoblePhantasms.set(emptySet())
    }
    val mlb = maker.bool("support_pref_ce_mlb")
    val preferredCEs = maker.stringSet("support_pref_ce_list")
    val preferredNoblePhantasms = maker.stringSet("support_pref_np_list")
    val friendsOnly = maker.bool("support_friends_only")

    val selectionMode = maker.enum(
        "support_mode",
        SupportSelectionModeEnum.Preferred
    )

    val fallbackTo = maker.enum(
        "support_fallback",
        SupportSelectionModeEnum.Manual
    )

    val supportClass = maker.enum(
        "autoskill_support_class",
        SupportClass.None
    )

    val alsoCheckAll = maker.bool("also_check_all")

    val maxAscended = maker.bool("support_max_ascended")

    val skill1Max = maker.bool("support_skill_max_1")
    val skill2Max = maker.bool("support_skill_max_2")
    val skill3Max = maker.bool("support_skill_max_3")
    val appendSkillCheck = maker.bool("support_append_skill_check")
    val appendSkillsMax = (1..5).map { maker.bool("support_append_skill_max_$it") }
    val score62Check = maker.bool("support_score_62_check")
    val grandScore16Check = maker.bool("support_grand_score_16_check")
    val minimumNpLevel = maker.int("support_np_min_level", 5)
    private val storedNpCheck = maker.bool("support_np_level_check")
    private val effectiveNpCheck = storedNpCheck.map(false,
        convert = { it && minimumNpLevel.get() > 1 },
        reverse = { enabled ->
            // An old Lv.1 setting is off until explicitly enabled with a meaningful default.
            if (enabled && minimumNpLevel.get() <= 1) minimumNpLevel.set(5)
            enabled
        })
    val npLevelCheck: Pref<Boolean> = object : Pref<Boolean> by effectiveNpCheck {
        override fun asFlow() = kotlinx.coroutines.flow.combine(storedNpCheck.asFlow(), minimumNpLevel.asFlow()) { enabled, level ->
            enabled && level > 1
        }
    }

    val grandServant = maker.bool("support_grand_servant")
    val bondCEEffect = maker.enum(
        "support_bond_ce_effect",
        BondCEEffectEnum.Ignore
    )
    val requireBothNormalAndRewardMatch = maker.bool("support_require_both_normal_and_reward_match")
}

/** One write boundary shared by pickers, importers, collectors and delegated properties. */
private class ServantSelectionPref(
    private val base: Pref<Set<String>>, private val changed: () -> Unit
) : Pref<Set<String>> by base {
    private fun before(value: Set<String>) { if (base.get() != value) changed() }
    override fun set(value: Set<String>) { before(value); base.set(value) }
    override suspend fun setAndCommit(value: Set<String>): Boolean { before(value); return base.setAndCommit(value) }
    override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: Set<String>) = set(value)
    override fun delete() { before(defaultValue); base.delete() }
    override suspend fun deleteAndCommit(): Boolean { before(defaultValue); return base.deleteAndCommit() }
    override fun resetToDefault() = set(defaultValue)
    override fun asCollector() = object : kotlinx.coroutines.flow.FlowCollector<Set<String>> {
        override suspend fun emit(value: Set<String>) = set(value)
    }
    override fun asSyncCollector(throwOnFailure: Boolean) = object : kotlinx.coroutines.flow.FlowCollector<Set<String>> {
        override suspend fun emit(value: Set<String>) {
            before(value)
            base.asSyncCollector(throwOnFailure).emit(value)
        }
    }
}
