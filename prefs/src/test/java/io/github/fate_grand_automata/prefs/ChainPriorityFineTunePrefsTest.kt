package io.github.fate_grand_automata.prefs

import android.content.ContextWrapper
import android.content.SharedPreferences
import io.github.fate_grand_automata.prefs.core.CompletedRunsHolder
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/** Upstream's four Chain Priority fine-tune settings (#654): its keys and defaults. */
class ChainPriorityFineTunePrefsTest {
    @Test
    fun `defaults are upstream's, and the two waits equal the fixed waits they replace`() {
        val prefs = preferencesWith(emptyMap())
        assertEquals(0.5, prefs.npCardTypeSimilarity)
        assertEquals(0.8, prefs.npCardDetectionSimilarity)
        assertEquals(1000.milliseconds, prefs.waitAfterOrderChange)
        assertEquals(250.milliseconds, prefs.servantDetailsWait)
    }

    @Test
    fun `stored values use upstream's keys`() {
        val prefs = preferencesWith(
            mapOf(
                "card_type_similarity" to 65,
                "np_card_detection_similarity" to 70,
                "wait_after_order_change" to 1500,
                "servant_details_wait" to 400
            )
        )
        assertEquals(0.65, prefs.npCardTypeSimilarity)
        assertEquals(0.7, prefs.npCardDetectionSimilarity)
        assertEquals(1500.milliseconds, prefs.waitAfterOrderChange)
        assertEquals(400.milliseconds, prefs.servantDetailsWait)
    }

    private fun preferencesWith(values: Map<String, Int>): PreferencesImpl {
        // Startup migrations write their done flags into this store; the writes are dropped because
        // these tests only read the setting keys back.
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, _ -> if (method.name == "commit") true else proxy }
        val store = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            // Only the given keys are stored; every other key reads its default, as SharedPreferences does.
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
}
