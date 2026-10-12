package io.github.fate_grand_automata.prefs

import android.content.SharedPreferences
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.fate_grand_automata.prefs.core.ExecutionProfileCore
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
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
}
