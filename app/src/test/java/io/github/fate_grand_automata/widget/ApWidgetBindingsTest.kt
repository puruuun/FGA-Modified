package io.github.fate_grand_automata.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ApWidgetBindingsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun clear() {
        context.getSharedPreferences("fga_widget_bindings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun savedBindingIsReadBackFromAFreshStore() {
        ApWidgetBindings(context).bind(12, "7001")
        assertEquals("7001", ApWidgetBindings(context).accountFor(12))
    }

    @Test fun eachWidgetKeepsItsOwnAccount() {
        val store = ApWidgetBindings(context)
        store.bind(1, "a")
        store.bind(2, "b")
        assertEquals("a", store.accountFor(1))
        assertEquals("b", store.accountFor(2))
        assertEquals(setOf("a", "b"), store.boundAccountIds())
    }

    @Test fun rebindingReplacesTheAccount() {
        val store = ApWidgetBindings(context)
        store.bind(1, "a")
        store.bind(1, "b")
        assertEquals("b", store.accountFor(1))
    }

    @Test fun removeDeletesOnlyThatWidgetBinding() {
        val store = ApWidgetBindings(context)
        store.bind(1, "a")
        store.bind(2, "a")
        store.remove(1)
        assertNull(store.accountFor(1))
        assertEquals("a", store.accountFor(2))
        assertTrue(store.hasBindings())
        store.remove(2)
        assertFalse(store.hasBindings())
    }

    @Test fun unknownWidgetHasNoAccount() {
        assertNull(ApWidgetBindings(context).accountFor(99))
        assertFalse(ApWidgetBindings(context).isBound("a"))
    }

    @Test fun aMissingAccountLeavesTheBindingAlone() {
        // The binding only goes away with the widget (onDeleted); a missing account just shows "no data".
        val store = ApWidgetBindings(context)
        store.bind(1, "gone")
        assertEquals(ApWidgetState.NoData, apWidgetState(store.accountFor(1), true, 33, emptyList(), 1000))
        assertEquals("gone", ApWidgetBindings(context).accountFor(1))
    }

    @Test fun savedAccountRefreshesOnlyWhenABoundWidgetShowsIt() {
        val updates = mutableListOf<Unit>()
        val original = ApWidgetRefresh.launcher
        ApWidgetRefresh.launcher = { updates += Unit }
        try {
            ApWidgetRefresh.onAccountSaved(context, "a")
            assertEquals(0, updates.size)
            ApWidgetBindings(context).bind(1, "a")
            ApWidgetRefresh.onAccountSaved(context, "b")
            assertEquals(0, updates.size)
            ApWidgetRefresh.onAccountSaved(context, "a")
            assertEquals(1, updates.size)
            ApWidgetRefresh.onAccountSaved(context, null)
            assertEquals(2, updates.size)
        } finally {
            ApWidgetRefresh.launcher = original
        }
    }
}
