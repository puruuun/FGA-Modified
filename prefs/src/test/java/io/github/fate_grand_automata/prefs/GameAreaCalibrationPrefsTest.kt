package io.github.fate_grand_automata.prefs

import android.content.SharedPreferences
import io.github.fate_grand_automata.prefs.core.GameAreaMode
import io.github.fate_grand_automata.prefs.core.GameAreaPrefsCore
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.lib_automata.GameAreaOffsets
import java.lang.reflect.Proxy
import kotlin.test.*

class GameAreaCalibrationPrefsTest {
    private fun store(): SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when {
                method.name.startsWith("put") -> { values[args!![0] as String] = args[1]; proxy }
                method.name == "apply" -> null
                method.name == "commit" -> true
                method.name == "remove" -> { values.remove(args!![0]); proxy }
                method.name == "clear" -> { values.clear(); proxy }
                else -> error("Unexpected editor call ${method.name}")
            }
        }
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { proxy, method, args ->
            when {
                method.name == "getAll" -> values.toMap()
                method.name.startsWith("get") -> values[args!![0]] ?: args[1]
                method.name == "contains" -> values.containsKey(args!![0])
                method.name == "edit" -> editor
                method.name.endsWith("OnSharedPreferenceChangeListener") -> null
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args!![0]
                else -> error("Unexpected preferences call ${method.name}")
            }
        } as SharedPreferences
    }

    @Test fun `calibration adds a new snapshot without editing Custom or any existing preset`() {
        val shared = store()
        val core = GameAreaPrefsCore(PrefMaker(shared))
        val manual = GameAreaOffsets(1038, 97, 0, 19)
        core.setCustomOffsets(manual)
        core.mode.set(GameAreaMode.Custom)
        core.addPreset("Manual")
        val old = core.presets.get().single()
        val offsets = GameAreaOffsets(1048, 430, 1, 354)
        val calibrated = core.addPreset("Auto 10-30 15:00", offsets)
        assertEquals(listOf(old, calibrated), core.presets.get())
        assertEquals(offsets, calibrated.offsets())
        assertNotEquals(old.id, calibrated.id)
        assertEquals(manual, core.customOffsets())
        assertEquals(GameAreaMode.Custom, core.mode.get())
        assertEquals("", core.presetId.get())
        core.presetId.set(calibrated.id)
        core.mode.set(GameAreaMode.Preset)
        assertEquals(offsets, GameAreaPrefsCore(PrefMaker(shared)).selectedOffsets())
        assertEquals(manual, core.customOffsets())
    }

}
