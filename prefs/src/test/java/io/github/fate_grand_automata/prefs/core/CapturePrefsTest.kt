package io.github.fate_grand_automata.prefs.core

import android.content.SharedPreferences
import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class CapturePrefsTest {
    @Test
    fun `connection mode defaults to Vpn and falls back on unknown values`() {
        val prefs = InMemorySharedPreferences()
        val maker = PrefMaker(prefs)
        val mode = maker.enum("capture_connection_mode", CaptureConnectionMode.Vpn)

        assertThat(mode.get()).isEqualTo(CaptureConnectionMode.Vpn)

        prefs.edit().putString("capture_connection_mode", "AdGuard").apply()
        assertThat(mode.get()).isEqualTo(CaptureConnectionMode.AdGuard)

        prefs.edit().putString("capture_connection_mode", "bogus_mode").apply()
        assertThat(mode.get()).isEqualTo(CaptureConnectionMode.Vpn)
    }

    @Test
    fun `proxy port defaults to 8899 and clamps out-of-range values on read`() {
        val prefs = InMemorySharedPreferences()
        val maker = PrefMaker(prefs)
        val port = maker.int("capture_proxy_port", CaptureConnectionMode.DEFAULT_PROXY_PORT).map(
            defaultValue = CaptureConnectionMode.DEFAULT_PROXY_PORT,
            convert = CaptureConnectionMode::clampProxyPort,
            reverse = CaptureConnectionMode::clampProxyPort,
        )

        assertThat(port.get()).isEqualTo(8899)

        prefs.edit().putInt("capture_proxy_port", 80).apply()
        assertThat(port.get()).isEqualTo(1024)

        prefs.edit().putInt("capture_proxy_port", 70000).apply()
        assertThat(port.get()).isEqualTo(65535)

        port.set(12345)
        assertThat(port.get()).isEqualTo(12345)
    }
}

private class InMemorySharedPreferences : SharedPreferences {
    private val data = linkedMapOf<String, Any?>()
    private val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()

    override fun getString(key: String, defValue: String?): String? =
        (data[key] as? String) ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (data[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String, defValue: Int): Int =
        (data[key] as? Int) ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        (data[key] as? Long) ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        (data[key] as? Float) ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        (data[key] as? Boolean) ?: defValue

    override fun contains(key: String): Boolean = key in data

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners += listener
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners -= listener
    }

    private inner class Editor : SharedPreferences.Editor {
        private val pending = linkedMapOf<String, Any?>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply { pending[key] = values?.toSet() }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { pending[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { pending[key] = null }
        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearAll) data.clear()
            val changed = mutableListOf<String>()
            for ((k, v) in pending) {
                if (v == null) data.remove(k) else data[k] = v
                changed += k
            }
            for (listener in listeners.toList()) {
                for (k in changed) listener.onSharedPreferenceChanged(this@InMemorySharedPreferences, k)
            }
        }
    }
}
