package io.github.fate_grand_automata.prefs

import android.content.SharedPreferences
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.RetreatMergeMigration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetreatMergeMigrationTest {
    private class Setup(legacyOn: Boolean) {
        val global = RetreatMemoryPrefs()
        val globalMaker = PrefMaker(global)
        val done = globalMaker.bool("retreat_merge_migrated")
        val legacy = globalMaker.bool("withdraw_enabled")

        init {
            legacy.set(legacyOn)
        }

        private val configMakers = mutableListOf<PrefMaker>()

        fun config(enabled: Boolean, turns: Int? = null): RetreatMergeMigration.Target {
            val maker = PrefMaker(RetreatMemoryPrefs())
            configMakers += maker
            val enabledPref = maker.bool("turn_limit_retreat_enabled")
            val turnsPref = maker.stringAsInt("turn_limit_retreat_turns", 0)
            enabledPref.set(enabled)
            if (turns != null) turnsPref.set(turns)
            return RetreatMergeMigration.Target(enabledPref, turnsPref)
        }

        fun run(vararg targets: RetreatMergeMigration.Target) =
            RetreatMergeMigration.run(done, legacy, targets.toList())
    }

    @Test
    fun `global on turns configs that were off on with zero turns and leaves configs that were on`() {
        val s = Setup(legacyOn = true)
        val off = s.config(enabled = false, turns = 4)
        val offDefault = s.config(enabled = false)
        val on = s.config(enabled = true, turns = 6)
        assertTrue(s.run(off, offDefault, on))
        assertTrue(off.enabled.get())
        assertEquals(0, off.turns.get())
        assertTrue(offDefault.enabled.get())
        assertEquals(0, offDefault.turns.get())
        assertTrue(on.enabled.get())
        assertEquals(6, on.turns.get())
    }

    @Test
    fun `global off changes nothing`() {
        val s = Setup(legacyOn = false)
        val off = s.config(enabled = false, turns = 4)
        val on = s.config(enabled = true, turns = 6)
        assertTrue(s.run(off, on))
        assertFalse(off.enabled.get())
        assertEquals(4, off.turns.get())
        assertTrue(on.enabled.get())
        assertEquals(6, on.turns.get())
    }

    @Test
    fun `it runs only once`() {
        val s = Setup(legacyOn = true)
        val first = s.config(enabled = false)
        assertTrue(s.run(first))
        assertTrue(s.done.get())

        // The owner turns the config off again, then a new config appears: a second run must not touch either.
        first.enabled.set(false)
        val second = s.config(enabled = false)
        assertFalse(s.run(first, second))
        assertFalse(first.enabled.get())
        assertFalse(second.enabled.get())
    }

    @Test
    fun `the turn count of a new config defaults to zero`() {
        val maker = PrefMaker(RetreatMemoryPrefs())
        assertEquals(0, maker.stringAsInt("turn_limit_retreat_turns", 0).get())
    }
}

private class RetreatMemoryPrefs : SharedPreferences {
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
