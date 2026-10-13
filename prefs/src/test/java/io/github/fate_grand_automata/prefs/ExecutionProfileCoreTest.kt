package io.github.fate_grand_automata.prefs

import android.content.SharedPreferences
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.prefs.core.ExecutionProfileCore
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.SpamConfigCore
import io.github.fate_grand_automata.prefs.core.ShuffleConfigCore
import io.github.fate_grand_automata.prefs.core.SupportPrefsCore
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.SpamEnum
import io.github.fate_grand_automata.scripts.enums.ShuffleCardsEnum
import io.github.fate_grand_automata.scripts.enums.SupportScoreMatchMode
import io.github.fate_grand_automata.scripts.models.NpSpamConfig
import io.github.fate_grand_automata.scripts.models.ServantSpamConfig
import io.github.fate_grand_automata.scripts.models.ServantPriorityPerWave
import kotlin.test.Test

class ExecutionProfileCoreTest {
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
        assertThat(result.scoreMatchMode.get()).isEqualTo(SupportScoreMatchMode.All)
    }

    @Test
    fun `grand only and score combination mode survive export import independently`() {
        val storage = MemoryPrefs()
        val support = SupportPrefsCore(PrefMaker(storage))
        support.grandScore16Check.set(true)
        support.scoreMatchMode.set(SupportScoreMatchMode.Any)
        support.npLevelCheck.set(true)
        val restored = MemoryPrefs()
        restored.edit().also { it.import(BattleConfigFile.decode(BattleConfigFile.encode(storage.all))) }.apply()
        val result = SupportPrefsCore(PrefMaker(restored))
        assertThat(result.score62Check.get()).isEqualTo(false)
        assertThat(result.grandScore16Check.get()).isEqualTo(true)
        assertThat(result.scoreMatchMode.get()).isEqualTo(SupportScoreMatchMode.Any)
        result.score62Check.set(true)
        result.grandScore16Check.set(false)
        assertThat(result.scoreMatchMode.get()).isEqualTo(SupportScoreMatchMode.Any)
        assertThat(result.npLevelCheck.get()).isEqualTo(true)
    }
}
