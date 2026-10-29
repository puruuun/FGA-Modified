package io.github.fate_grand_automata.prefs

import android.content.SharedPreferences
import io.github.fate_grand_automata.prefs.core.AddedFeatureTuningPrefsCore
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.scripts.prefs.FeatureTuning
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class AddedFeatureTuningPrefsTest {
    @Test fun `settings persist but an existing run retains its original snapshot`() {
        val store = MemoryPrefs()
        val core = AddedFeatureTuningPrefsCore(PrefMaker(store))
        core.weekly.delayPercent.set(200)
        core.weekly.similarityOffset.set(-3)
        core.questTapSettle.set(250)
        core.questPollInterval.set(700)
        val running = core.snapshot()
        core.weekly.delayPercent.set(300)
        core.questTapSettle.set(500)
        val next = AddedFeatureTuningPrefsCore(PrefMaker(store)).snapshot()
        assertEquals(FeatureTuning(200, -3), running.weekly)
        assertEquals(250.milliseconds, running.questTapSettle)
        assertEquals(FeatureTuning(300, -3), next.weekly)
        assertEquals(500.milliseconds, next.questTapSettle)
        assertEquals(700.milliseconds, next.questPollInterval)
        assertEquals(FeatureTuning(), next.fp)
    }

    @Test fun `all persisted defaults reproduce the script defaults`() {
        val core = AddedFeatureTuningPrefsCore(PrefMaker(MemoryPrefs()))
        val snapshot = core.snapshot()
        for (section in listOf(snapshot.weekly, snapshot.fp, snapshot.skill, snapshot.ce, snapshot.quest, snapshot.bond, snapshot.support))
            assertEquals(FeatureTuning(), section)
        assertEquals(100.milliseconds, snapshot.questTapSettle)
        assertEquals(300.milliseconds, snapshot.questPollInterval)
    }

    @Test fun `invalid imported item intervals stay within the approved ranges`() {
        val core = AddedFeatureTuningPrefsCore(PrefMaker(MemoryPrefs()))
        core.questTapSettle.set(-1)
        core.questPollInterval.set(10000)
        val snapshot = core.snapshot()
        assertEquals(50.milliseconds, snapshot.questTapSettle)
        assertEquals(2000.milliseconds, snapshot.questPollInterval)
    }
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

}
