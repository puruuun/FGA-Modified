package io.github.fate_grand_automata.prefs

import android.content.SharedPreferences
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.prefs.core.ExecutionProfileCore
import io.github.fate_grand_automata.prefs.core.GameAreaPrefsCore
import io.github.fate_grand_automata.prefs.core.GameAreaMode
import io.github.lib_automata.GameAreaOffsets
import io.github.lib_automata.Region
import io.github.lib_automata.Size
import io.github.fate_grand_automata.prefs.core.QuestItemPrefsCore
import io.github.fate_grand_automata.prefs.core.QuestItemControlPrefsCore
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.SpamConfigCore
import io.github.fate_grand_automata.prefs.core.ShuffleConfigCore
import io.github.fate_grand_automata.prefs.core.SupportPrefsCore
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.SpamEnum
import io.github.fate_grand_automata.scripts.enums.ShuffleCardsEnum
import io.github.fate_grand_automata.scripts.models.NpSpamConfig
import io.github.fate_grand_automata.scripts.models.ServantSpamConfig
import io.github.fate_grand_automata.scripts.models.ServantPriorityPerWave
import kotlin.test.Test

class ExecutionProfileCoreTest {
    @Test
    fun `retired Duo setting reads as Default and keeps custom offsets`() {
        val store = MemoryPrefs()
        store.data["game_area_mode"] = "Duo"
        store.data["game_offset_left"] = 1037
        val settings = GameAreaPrefsCore(PrefMaker(store))
        assertThat(settings.mode.get()).isEqualTo(GameAreaMode.Default)
        assertThat(settings.selectedOffsets()).isEqualTo(null)
        assertThat(settings.left.get()).isEqualTo(1037)
    }

    @Test
    fun `saved areas retain stable ids names offsets and selection after reopening`() {
        val store = MemoryPrefs()
        val settings = GameAreaPrefsCore(PrefMaker(store))
        settings.setCustomOffsets(GameAreaOffsets(1037, 97, 0, 11))
        settings.addPreset("분할화면 1 \"FGO\"")
        val preset = settings.presets.get().single()
        settings.presetId.set(preset.id)
        settings.mode.set(GameAreaMode.Preset)
        settings.setCustomOffsets(GameAreaOffsets())
        val reopened = GameAreaPrefsCore(PrefMaker(store))
        assertThat(reopened.selectedPreset()).isEqualTo(preset)
        assertThat(reopened.selectedOffsets()?.region(Size(3040, 1904), false))
            .isEqualTo(Region(1037, 97, 2003, 1796))
        assertThat(reopened.customOffsets()).isEqualTo(GameAreaOffsets())
    }

    @Test
    fun `deleting selected preset returns to Default and keeps other same name presets`() {
        val settings = GameAreaPrefsCore(PrefMaker(MemoryPrefs()))
        settings.addPreset("same name")
        settings.addPreset("same name")
        val presets = settings.presets.get()
        settings.mode.set(GameAreaMode.Preset)
        settings.presetId.set(presets.first().id)
        settings.deleteSelectedPreset()
        assertThat(settings.mode.get()).isEqualTo(GameAreaMode.Default)
        assertThat(settings.selectedOffsets()).isEqualTo(null)
        assertThat(settings.presets.get()).isEqualTo(listOf(presets.last()))
    }

    @Test
    fun `invalid saved list or missing selected id falls back to the full display`() {
        val store = MemoryPrefs()
        store.data["game_area_presets"] = "invalid"
        val settings = GameAreaPrefsCore(PrefMaker(store))
        assertThat(settings.presets.get()).isEqualTo(emptyList())
        settings.mode.set(GameAreaMode.Preset)
        settings.presetId.set("missing")
        assertThat(settings.selectedOffsets()).isEqualTo(null)
    }

    /** In-memory Android interface, so these tests need neither a device nor a new dependency. */
    private class MemoryPrefs : SharedPreferences {
        val data = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?) = data[key] as? String ?: defValue
        override fun getInt(key: String?, defValue: Int) = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = data[key] as? Boolean ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = (data[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun contains(key: String?) = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            private var clear = false
            private fun put(key: String?, value: Any?): SharedPreferences.Editor {
                changes[checkNotNull(key)] = value
                return this
            }
            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)
            override fun remove(key: String?) = put(key, null)
            override fun clear(): SharedPreferences.Editor { clear = true; return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clear) data.clear()
                for ((key, value) in changes) if (value == null) data.remove(key) else data[key] = value
            }
        }
    }

    @Test
    fun `weekly chapter and quest choices survive reopening and feed solver by server`() {
        val store = MemoryPrefs()
        fun settings() = io.github.fate_grand_automata.prefs.core.WeeklyRoutePrefsCore(PrefMaker(store))
        val q1 = io.github.fate_grand_automata.scripts.weekly.RouteQuest(1, 100, 100, "chapter", "spot", "q1", 2, "free", "repeatLast")
        val q2 = q1.copy(id = 2, warId = 101, ap = 5)
        val quests = listOf(q1, q2)
        val mission = io.github.fate_grand_automata.scripts.weekly.RouteMission(1, "clear", 1,
            io.github.fate_grand_automata.scripts.weekly.MissionKind.QuestClear)
        fun solve(server: String) = io.github.fate_grand_automata.scripts.weekly.WeeklyRouteSolver.solve(listOf(mission),
            io.github.fate_grand_automata.scripts.weekly.WeeklyCandidateSettings.candidates(server, quests, settings().disabled.get()), emptySet())
        assertThat(settings().disabled.get()).isEqualTo(emptySet())
        assertThat(solve("JP").totalAp).isEqualTo(2L)
        val chapter = io.github.fate_grand_automata.scripts.weekly.WeeklyCandidateSettings.chapterKey("JP", 100)
        val quest = io.github.fate_grand_automata.scripts.weekly.WeeklyCandidateSettings.questKey("JP", 2)
        settings().disabled.set(setOf(chapter))
        assertThat(settings().disabled.get()).isEqualTo(setOf(chapter))
        assertThat(solve("JP").totalAp).isEqualTo(5L)
        assertThat(solve("KR").totalAp).isEqualTo(2L)
        settings().disabled.set(setOf(chapter, quest))
        assertThat(solve("JP").items).isEqualTo(emptyList())
        assertThat(solve("JP").manual.map { it.id }).isEqualTo(listOf(1))
        settings().disabled.set(settings().disabled.get() - chapter)
        assertThat(settings().disabled.get()).isEqualTo(setOf(quest))
        assertThat(solve("JP").totalAp).isEqualTo(2L)
    }

    @Test
    fun `legacy primary fields retain their values and secondary defaults are independent`() {
        val prefs = MemoryPrefs()
        prefs.data["autoskill_cmd"] = "a4"
        prefs.data["use_servant_priority"] = true
        val primary = ExecutionProfileCore(PrefMaker(prefs))
        val secondary = ExecutionProfileCore(PrefMaker(prefs), "secondary_")
        assertThat(primary.skillCommand.get()).isEqualTo("a4")
        assertThat(primary.useServantPriority.get()).isEqualTo(true)
        assertThat(secondary.skillCommand.get()).isEqualTo("")
        assertThat(secondary.useServantPriority.get()).isEqualTo(false)
    }

    @Test
    fun `editing second commands priorities and brave chains does not overwrite first`() {
        val prefs = MemoryPrefs()
        val primary = ExecutionProfileCore(PrefMaker(prefs))
        val secondary = ExecutionProfileCore(PrefMaker(prefs), "secondary_")
        primary.skillCommand.set("a4")
        secondary.skillCommand.set("d5")
        secondary.useServantPriority.set(true)
        secondary.servantPriority.set(ServantPriorityPerWave.of("2,1,3,4,5,6"))
        secondary.braveChains.set(listOf(BraveChainEnum.WithNP))
        assertThat(primary.skillCommand.get()).isEqualTo("a4")
        assertThat(primary.useServantPriority.get()).isEqualTo(false)
        assertThat(primary.braveChains.get()).isEqualTo(emptyList())
        assertThat(secondary.skillCommand.get()).isEqualTo("d5")
        assertThat(secondary.servantPriority.get().toString()).isEqualTo("2,1,3,4,5,6")
        assertThat(secondary.braveChains.get()).isEqualTo(listOf(BraveChainEnum.WithNP))
        val restored = MemoryPrefs()
        val editor = restored.edit()
        editor.import(BattleConfigFile.decode(BattleConfigFile.encode(prefs.all)))
        editor.apply()
        assertThat(ExecutionProfileCore(PrefMaker(restored)).skillCommand.get()).isEqualTo("a4")
        assertThat(ExecutionProfileCore(PrefMaker(restored), "secondary_").skillCommand.get()).isEqualTo("d5")
        assertThat(ExecutionProfileCore(PrefMaker(restored), "secondary_").useServantPriority.get()).isEqualTo(true)
    }

    @Test
    fun `common automatic use retains the legacy key and ignores old secondary automatic use`() {
        val prefs = MemoryPrefs()
        val settings = listOf(ServantSpamConfig(np = NpSpamConfig(spam = SpamEnum.Spam, waves = setOf(2))))
        val common = SpamConfigCore(PrefMaker(prefs))
        common.spam.set(settings)
        val original = prefs.data["spam_x"]
        prefs.data["secondary_spam_x"] = "[]"
        assertThat(SpamConfigCore(PrefMaker(prefs)).spam.get()).isEqualTo(settings)
        ExecutionProfileCore(PrefMaker(prefs), "secondary_").skillCommand.set("d5")
        assertThat(prefs.data["spam_x"]).isEqualTo(original)
        val restored = MemoryPrefs()
        restored.edit().also { it.import(BattleConfigFile.decode(BattleConfigFile.encode(prefs.all))) }.apply()
        assertThat(SpamConfigCore(PrefMaker(restored)).spam.get()).isEqualTo(settings)
    }

    @Test
    fun `bad common automatic-use data falls back to six disabled servants`() {
        val prefs = MemoryPrefs()
        prefs.data["spam_x"] = "invalid"
        assertThat(SpamConfigCore(PrefMaker(prefs)).spam.get()).isEqualTo((1..6).map { ServantSpamConfig() })
    }

    @Test
    fun `common shuffle retains legacy mode and wave and ignores old secondary shuffle`() {
        val prefs = MemoryPrefs()
        prefs.data["shuffle_cards"] = "NoEffective"
        prefs.data["shuffle_cards_wave"] = 2
        prefs.data["secondary_shuffle_cards"] = "NoNPMatching"
        prefs.data["secondary_shuffle_cards_wave"] = 3
        val shuffle = ShuffleConfigCore(PrefMaker(prefs))
        assertThat(shuffle.mode.get()).isEqualTo(ShuffleCardsEnum.NoEffective)
        assertThat(shuffle.wave.get()).isEqualTo(2)
        shuffle.mode.set(ShuffleCardsEnum.None)
        assertThat(prefs.data["shuffle_cards"]).isEqualTo("None")
        assertThat(prefs.data["secondary_shuffle_cards"]).isEqualTo("NoNPMatching")
    }

    @Test
    fun `first turn shuffle mode survives export and import with its existing saved wave`() {
        val prefs = MemoryPrefs()
        val shuffle = ShuffleConfigCore(PrefMaker(prefs))
        shuffle.mode.set(ShuffleCardsEnum.FirstTurnStartCondition)
        shuffle.wave.set(3)
        val restored = MemoryPrefs()
        restored.edit().also { it.import(BattleConfigFile.decode(BattleConfigFile.encode(prefs.all))) }.apply()
        val result = ShuffleConfigCore(PrefMaker(restored))
        assertThat(result.mode.get()).isEqualTo(ShuffleCardsEnum.FirstTurnStartCondition)
        assertThat(result.wave.get()).isEqualTo(3)
    }

    @Test
    fun `append settings default off and retain five independent slots through export import`() {
        val prefs = MemoryPrefs()
        val support = SupportPrefsCore(PrefMaker(prefs))
        assertThat(support.appendSkillCheck.get()).isEqualTo(false)
        assertThat(support.appendSkillsMax.map { it.get() }).isEqualTo(List(5) { false })
        support.appendSkillCheck.set(true)
        support.appendSkillsMax[1].set(true)
        support.appendSkillsMax[4].set(true)
        val restored = MemoryPrefs()
        restored.edit().also { it.import(BattleConfigFile.decode(BattleConfigFile.encode(prefs.all))) }.apply()
        val result = SupportPrefsCore(PrefMaker(restored))
        assertThat(result.appendSkillCheck.get()).isEqualTo(true)
        assertThat(result.appendSkillsMax.map { it.get() }).isEqualTo(listOf(false, true, false, false, true))
        result.appendSkillCheck.set(false)
        assertThat(result.appendSkillsMax.map { it.get() }).isEqualTo(listOf(false, true, false, false, true))
    }

    @Test
    fun `score plus 62 defaults off and exports independently from append settings`() {
        val prefs = MemoryPrefs()
        val support = SupportPrefsCore(PrefMaker(prefs))
        assertThat(support.score62Check.get()).isEqualTo(false)
        support.score62Check.set(true)
        val restored = MemoryPrefs()
        restored.edit().also { it.import(BattleConfigFile.decode(BattleConfigFile.encode(prefs.all))) }.apply()
        val result = SupportPrefsCore(PrefMaker(restored))
        assertThat(result.score62Check.get()).isEqualTo(true)
        assertThat(result.appendSkillCheck.get()).isEqualTo(false)
        result.appendSkillCheck.set(true)
        result.appendSkillsMax[1].set(true)
        result.score62Check.set(false)
        assertThat(result.appendSkillCheck.get()).isEqualTo(true)
        assertThat(result.appendSkillsMax[1].get()).isEqualTo(true)
    }

    @Test
    fun `NP settings default off and retain minimum independently of append and score after import`() {
        val prefs = MemoryPrefs()
        val support = SupportPrefsCore(PrefMaker(prefs))
        assertThat(support.npLevelCheck.get()).isEqualTo(false)
        assertThat(support.minimumNpLevel.get()).isEqualTo(5)
        support.npLevelCheck.set(true)
        support.minimumNpLevel.set(3)
        support.score62Check.set(true)
        support.appendSkillCheck.set(true)
        support.appendSkillsMax[1].set(true)
        val restored = MemoryPrefs()
        restored.edit().also { it.import(BattleConfigFile.decode(BattleConfigFile.encode(prefs.all))) }.apply()
        val result = SupportPrefsCore(PrefMaker(restored))
        assertThat(result.npLevelCheck.get()).isEqualTo(true)
        assertThat(result.minimumNpLevel.get()).isEqualTo(3)
        result.npLevelCheck.set(false)
        assertThat(result.minimumNpLevel.get()).isEqualTo(3)
        assertThat(result.score62Check.get()).isEqualTo(true)
        assertThat(result.appendSkillCheck.get()).isEqualTo(true)
        assertThat(result.appendSkillsMax[1].get()).isEqualTo(true)
    }

    @Test
    fun `legacy class score preferences retain class only while grand defaults off`() {
        val storage = MemoryPrefs()
        storage.edit().putBoolean("support_score_62_check", true).apply()
        val result = SupportPrefsCore(PrefMaker(storage))
        assertThat(result.score62Check.get()).isEqualTo(true)
        assertThat(result.grandScore16Check.get()).isEqualTo(false)
    }

    @Test
    fun `grand only survives export import independently and old Any setting is ignored`() {
        val storage = MemoryPrefs()
        val support = SupportPrefsCore(PrefMaker(storage))
        storage.data["support_score_match_mode"] = "Any" // Ignored by the current policy.
        support.grandScore16Check.set(true)
        support.npLevelCheck.set(true)
        val restored = MemoryPrefs()
        restored.edit().also { it.import(BattleConfigFile.decode(BattleConfigFile.encode(storage.all))) }.apply()
        val result = SupportPrefsCore(PrefMaker(restored))
        assertThat(result.score62Check.get()).isEqualTo(false)
        assertThat(result.grandScore16Check.get()).isEqualTo(true)
        result.score62Check.set(true)
        result.grandScore16Check.set(false)
        assertThat(result.npLevelCheck.get()).isEqualTo(true)
    }

    @Test
    fun `launcher item choices default off persist independently and stay in separate server stores`() {
        val krStore = MemoryPrefs()
        val jpStore = MemoryPrefs()
        val kr = QuestItemPrefsCore(PrefMaker(krStore))
        val jp = QuestItemPrefsCore(PrefMaker(jpStore))
        assertThat(kr.useStormPod.get()).isEqualTo(false)
        assertThat(kr.useTeapot.get()).isEqualTo(false)
        kr.useTeapot.set(true)
        jp.useStormPod.set(true)
        val restoredKr = QuestItemPrefsCore(PrefMaker(krStore))
        val restoredJp = QuestItemPrefsCore(PrefMaker(jpStore))
        assertThat(restoredKr.useStormPod.get()).isEqualTo(false)
        assertThat(restoredKr.useTeapot.get()).isEqualTo(true)
        assertThat(restoredJp.useStormPod.get()).isEqualTo(true)
        assertThat(restoredJp.useTeapot.get()).isEqualTo(false)
        restoredKr.useStormPod.set(true)
        assertThat(restoredKr.useTeapot.get()).isEqualTo(true)
    }
    @Test
    fun `item control defaults on and toggling its opt in preserves launcher item choices`() {
        val store = MemoryPrefs()
        val control = QuestItemControlPrefsCore(PrefMaker(store))
        val items = QuestItemPrefsCore(PrefMaker(store))
        items.useTeapot.set(true)
        assertThat(control.enabled.get()).isEqualTo(true)
        assertThat(control.ignore.get()).isEqualTo(false)
        control.enabled.set(true)
        assertThat(QuestItemControlPrefsCore(PrefMaker(store)).ignore.get()).isEqualTo(false)
        assertThat(items.useTeapot.get()).isEqualTo(true)
        control.enabled.set(false)
        val restored = QuestItemControlPrefsCore(PrefMaker(store))
        assertThat(restored.enabled.get()).isEqualTo(false)
        assertThat(restored.ignore.get()).isEqualTo(true)
        assertThat(items.useTeapot.get()).isEqualTo(true)
    }

    @Test
    fun `explicit old item bypass choices retain behavior under the positive switch`() {
        for (ignored in listOf(true, false)) {
            val store = MemoryPrefs()
            store.data["ignore_teapot_storm_pod_usage"] = ignored
            val result = QuestItemControlPrefsCore(PrefMaker(store))
            assertThat(result.ignore.get()).isEqualTo(ignored)
            assertThat(result.enabled.get()).isEqualTo(!ignored)
        }
    }

    @Test
    fun `bond settings stay per script and preserve all five result choices through copy and export`() {
        val first = MemoryPrefs()
        val second = MemoryPrefs()
        val bond = io.github.fate_grand_automata.prefs.core.BondPrefsCore(PrefMaker(first))
        assertThat(bond.track.get()).isEqualTo(false)
        assertThat(bond.stopOnMax.get()).isEqualTo(false)
        assertThat(bond.ignoredCharacters.get()).isEqualTo(emptySet())
        bond.track.set(true)
        bond.stopOnMax.set(true)
        bond.ignoredCharacters.set(setOf(1, 3, 5))
        val other = io.github.fate_grand_automata.prefs.core.BondPrefsCore(PrefMaker(second))
        assertThat(other.track.get()).isEqualTo(false)
        assertThat(other.ignoredCharacters.get()).isEqualTo(emptySet())
        val copied = MemoryPrefs()
        copied.edit().also { it.import(first.all) }.apply()
        val restored = MemoryPrefs()
        restored.edit().also { it.import(BattleConfigFile.decode(BattleConfigFile.encode(first.all))) }.apply()
        for (store in listOf(copied, restored)) {
            val result = io.github.fate_grand_automata.prefs.core.BondPrefsCore(PrefMaker(store))
            assertThat(result.track.get()).isEqualTo(true)
            assertThat(result.stopOnMax.get()).isEqualTo(true)
            assertThat(result.ignoredCharacters.get()).isEqualTo(setOf(1, 3, 5))
            result.ignoredCharacters.set(emptySet())
            assertThat(result.ignoredCharacters.get()).isEqualTo(emptySet())
        }
        assertThat(bond.ignoredCharacters.get()).isEqualTo(setOf(1, 3, 5))
    }

    @Test
    fun `legacy launcher exceptions are not interpreted as result characters and invalid positions are excluded`() {
        val store = MemoryPrefs()
        store.data["bond_track"] = true
        store.data["bond_stop_on_max"] = true
        store.data["bond_max_ignored_positions"] = setOf("1", "6")
        val bond = io.github.fate_grand_automata.prefs.core.BondPrefsCore(PrefMaker(store))
        assertThat(bond.track.get()).isEqualTo(false)
        assertThat(bond.stopOnMax.get()).isEqualTo(false)
        store.data["bond_result_stop_characters"] = "0,1,5,6,abc"
        assertThat(bond.ignoredCharacters.get()).isEqualTo(setOf(2, 3, 4))
        bond.ignoredCharacters.set(setOf(0, 2, 6))
        assertThat(bond.ignoredCharacters.get()).isEqualTo(setOf(2))
    }
    @Test
    fun `version 090 watched selections keep stop behavior when displayed as ignore checkboxes`() {
        for (watched in listOf(setOf(2), (1..5).toSet(), emptySet())) {
            val store = MemoryPrefs()
            store.data["bond_result_stop_characters"] = watched.sorted().joinToString(",")
            val bond = io.github.fate_grand_automata.prefs.core.BondPrefsCore(PrefMaker(store))
            assertThat(bond.ignoredCharacters.get()).isEqualTo((1..5).toSet() - watched)
            val restored = MemoryPrefs()
            restored.edit().also { it.import(BattleConfigFile.decode(BattleConfigFile.encode(store.all))) }.apply()
            assertThat(io.github.fate_grand_automata.prefs.core.BondPrefsCore(PrefMaker(restored)).ignoredCharacters.get())
                .isEqualTo((1..5).toSet() - watched)
        }
    }


    @Test
    fun `direct fallback and strict turns in both profiles survive reopen copy and fga export import`() {
        val store = MemoryPrefs()
        val commands = listOf("a&?b4a,&bba,#,&?456", "&?a5b,&4ba")
        val prefixes = listOf("", "secondary_")
        for ((prefix, command) in prefixes.zip(commands)) {
            ExecutionProfileCore(PrefMaker(store), prefix).skillCommand.set(command)
        }
        val copied = MemoryPrefs()
        copied.edit().also { it.import(store.all) }.apply()
        val restored = MemoryPrefs()
        restored.edit().also { it.import(BattleConfigFile.decode(BattleConfigFile.encode(store.all))) }.apply()
        for (storage in listOf(store, copied, restored)) {
            for ((prefix, command) in prefixes.zip(commands)) {
                val result = ExecutionProfileCore(PrefMaker(storage), prefix).skillCommand.get()
                assertThat(result).isEqualTo(command)
                val action = io.github.fate_grand_automata.scripts.models.AutoSkillCommand.parse(result)[0, 0]
                    .last() as io.github.fate_grand_automata.scripts.models.AutoSkillAction.Atk
                assertThat(action.directCards?.fallback).isEqualTo(true)
            }
        }
        ExecutionProfileCore(PrefMaker(copied)).skillCommand.set("&b4a")
        assertThat(ExecutionProfileCore(PrefMaker(store)).skillCommand.get()).isEqualTo(commands[0])
        assertThat(ExecutionProfileCore(PrefMaker(copied), "secondary_").skillCommand.get()).isEqualTo(commands[1])
    }
}
