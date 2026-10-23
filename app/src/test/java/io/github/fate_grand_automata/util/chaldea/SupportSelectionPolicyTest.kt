package io.github.fate_grand_automata.util.chaldea

import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.SupportPrefsCore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlin.test.*

class SupportSelectionPolicyTest {
    @Test fun `servant set changes clear NP identity but equal sets retain it on all writes`() = runBlocking {
        val prefs = SupportPrefsCore(PrefMaker(MemoryPrefs()))
        prefs.preferredServants.set(setOf("A", "B"))
        prefs.preferredNoblePhantasms.set(setOf("NP"))
        prefs.preferredServants.set(setOf("B", "A"))
        assertEquals(setOf("NP"), prefs.preferredNoblePhantasms.get())
        prefs.preferredServants.asCollector().emit(setOf("C"))
        assertTrue(prefs.preferredNoblePhantasms.get().isEmpty())
        prefs.preferredNoblePhantasms.set(setOf("newNP"))
        prefs.preferredServants.setAndCommit(setOf("C"))
        assertEquals(setOf("newNP"), prefs.preferredNoblePhantasms.get())
        prefs.preferredServants.set(setOf("D"))
        assertTrue(prefs.preferredNoblePhantasms.get().isEmpty())
        prefs.preferredNoblePhantasms.set(setOf("DNP"))
        prefs.preferredServants.delete()
        assertTrue(prefs.preferredNoblePhantasms.get().isEmpty())
    }

    @Test fun `import level one disables NP filter without overwriting minimum and level five enables it`() {
        val prefs = SupportPrefsCore(PrefMaker(MemoryPrefs()))
        val settings = ChaldeaSupportSettings(true, 1, List(3) { false }, List(5) { false }, null)
        prefs.minimumNpLevel.set(4)
        prefs.npLevelCheck.set(true)
        prefs.preferredServants.set(setOf("A"))
        prefs.preferredNoblePhantasms.set(setOf("NP"))
        settings.apply(prefs, "A", null, false)
        assertFalse(prefs.npLevelCheck.get())
        assertEquals(4, prefs.minimumNpLevel.get())
        assertEquals(setOf("NP"), prefs.preferredNoblePhantasms.get())
        settings.copy(npLevel = 5).apply(prefs, "B", null, false)
        assertTrue(prefs.npLevelCheck.get())
        assertEquals(5, prefs.minimumNpLevel.get())
        assertTrue(prefs.preferredNoblePhantasms.get().isEmpty())
    }

    @Test fun `stored level one is effectively off for every config and reenable chooses a meaningful minimum`() = runBlocking {
        repeat(2) {
            val store = MemoryPrefs()
            store.data["support_np_min_level"] = 1
            store.data["support_np_level_check"] = true
            val prefs = SupportPrefsCore(PrefMaker(store))
            assertFalse(prefs.npLevelCheck.get())
            assertFalse(prefs.npLevelCheck.asFlow().first())
            assertEquals(1, prefs.minimumNpLevel.get()) // Reading never mutates the saved minimum.
            prefs.npLevelCheck.set(true)
            assertEquals(5, prefs.minimumNpLevel.get())
            assertTrue(SupportPrefsCore(PrefMaker(store)).npLevelCheck.get())
        }
    }
}
