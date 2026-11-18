package io.github.fate_grand_automata.prefs

import android.content.ContextWrapper
import android.content.SharedPreferences
import io.github.fate_grand_automata.prefs.core.MASTER_SKILL_MENU_DELAY_DEFAULT_MS
import io.github.fate_grand_automata.prefs.core.CompletedRunsHolder
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.prefs.core.masterSkillMenuDelayOf
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class MasterSkillMenuDelayPrefsTest {
    @Test
    fun `default is 1500 ms and maps to a Duration`() {
        assertEquals(1500, MASTER_SKILL_MENU_DELAY_DEFAULT_MS)
        assertEquals(1500.milliseconds, masterSkillMenuDelayOf(MASTER_SKILL_MENU_DELAY_DEFAULT_MS))
        assertEquals(750.milliseconds, masterSkillMenuDelayOf(750))
    }

    @Test
    fun `stored values outside 300 to 3000 ms are clamped when read`() {
        assertEquals(300.milliseconds, masterSkillMenuDelayOf(300))
        assertEquals(3000.milliseconds, masterSkillMenuDelayOf(3000))
        assertEquals(300.milliseconds, masterSkillMenuDelayOf(0))
        assertEquals(300.milliseconds, masterSkillMenuDelayOf(-500))
        assertEquals(3000.milliseconds, masterSkillMenuDelayOf(60000))
    }

    // Reads through the real PreferencesImpl mapping, so replacing the clamp with a plain
    // `it.milliseconds` in Preferences.kt changes the stored-0 and stored-60000 results.
    @Test
    fun `PreferencesImpl reads the default and clamps stored values`() {
        assertEquals(1500.milliseconds, preferencesWith(null).masterSkillMenuDelay)
        assertEquals(300.milliseconds, preferencesWith(0).masterSkillMenuDelay)
        assertEquals(3000.milliseconds, preferencesWith(60000).masterSkillMenuDelay)
        assertEquals(700.milliseconds, preferencesWith(700).masterSkillMenuDelay)
    }

    private fun preferencesWith(storedMs: Int?): PreferencesImpl {
        val values = if (storedMs == null) emptyMap() else mapOf(KEY to storedMs)
        // Startup migrations write their done flags into this store; the writes are dropped because
        // these tests only read the delay key back.
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, _ -> if (method.name == "commit") true else proxy }
        val store = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            // Only the master skill key is stored; every other key reads its default, as SharedPreferences does.
            when (method.name) {
                "getInt" -> values[args[0] as String] ?: args[1]
                "getString", "getStringSet", "getBoolean", "getLong", "getFloat" -> args[1]
                "contains" -> false
                "edit" -> editor
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences
        // PrefsCore only touches its Context inside functions that these tests never call.
        val prefs = PrefsCore(PrefMaker(store), ContextWrapper(null))
        return PreferencesImpl(prefs, CompletedRunsHolder())
    }

    private companion object {
        const val KEY = "master_skill_menu_delay"
    }
}
