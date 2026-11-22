package io.github.fate_grand_automata.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.capture.SupportListCriteria
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class SupportFilterPrefsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var prefs: PrefsCore

    @Before
    fun setup() {
        val store = context.getSharedPreferences("support-filter-test", Context.MODE_PRIVATE)
        store.edit().clear().commit()
        prefs = PrefsCore(PrefMaker(store), context)
    }

    @Test
    fun switchOffGivesNoCriteriaEvenWithServantsChosen() {
        prefs.captureSupportFilterServants.set(setOf("100"))
        assertNull(supportFilterCriteria(prefs))
    }

    @Test
    fun switchOnWithoutServantsGivesNoCriteria() {
        prefs.captureSupportFilterEnabled.set(true)
        prefs.captureSupportFilterCes.set(setOf("7"))
        assertNull(supportFilterCriteria(prefs))
    }

    @Test
    fun switchOnGivesTheChosenIdsAndIgnoresUnreadableOnes() {
        prefs.captureSupportFilterEnabled.set(true)
        prefs.captureSupportFilterServants.set(setOf("100", "200", "bad"))
        prefs.captureSupportFilterCes.set(setOf("7"))
        assertEquals(SupportListCriteria(setOf(100L, 200L), setOf(7L)), supportFilterCriteria(prefs))
    }

    @Test
    fun defaultIsOff() {
        assertNull(supportFilterCriteria(prefs))
    }
}
