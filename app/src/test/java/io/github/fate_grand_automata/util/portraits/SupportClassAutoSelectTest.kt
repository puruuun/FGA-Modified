package io.github.fate_grand_automata.util.portraits

import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.SupportPrefsCore
import io.github.fate_grand_automata.scripts.enums.SupportClass
import io.github.fate_grand_automata.scripts.enums.supportClassOfServantClass
import io.github.fate_grand_automata.util.chaldea.BuiltinSupportId
import io.github.fate_grand_automata.util.chaldea.MemoryPrefs
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class SupportClassAutoSelectTest {
    private fun rec(id: Int, number: Int, clazz: String) =
        SupportCatalogRecord(id, number, "n$number", "n$number", clazz, 5, null)

    private class Source(
        val catalogs: Map<String, List<SupportCatalogRecord>>, val fail: Boolean = false
    ) : SupportCatalogSource {
        override suspend fun catalog(kind: SupportCatalogKind, region: String): List<SupportCatalogRecord> {
            if (fail) throw PortraitImportException(PortraitImportError.Network)
            return catalogs[region].orEmpty()
        }
        override suspend fun detail(kind: SupportCatalogKind, region: String, id: Int): String? = null
        override suspend fun servantNames(region: String) = emptyMap<Int, Set<String>>()
        override suspend fun koreanNames() = emptyMap<Int, Set<String>>()
    }

    private val builtins = listOf(
        BuiltinSupportId("servant", "Arcueid", 2300500, 351, null),
        BuiltinSupportId("servant", "NoId", null, null, null),
        BuiltinSupportId("ce", "Arcueid", 2300500, 351, null)
    )

    private fun select(fail: Boolean = false) = SupportClassAutoSelect(
        builtins,
        Source(
            mapOf(
                "JP" to listOf(
                    rec(2300500, 351, "caster"), rec(100, 5, "archer"), rec(200, 300, "beastEresh"),
                    rec(300, 301, "moonCancer"), rec(400, 302, "mysteryClass")
                ),
                "KR" to listOf(rec(900, 5, "lancer")),
                "NA" to listOf(rec(950, 6, "rider"))
            ),
            fail
        )
    )

    private fun prefs() = SupportPrefsCore(PrefMaker(MemoryPrefs()))

    @Test fun `built-in servant gives its class tab`() = runBlocking {
        assertEquals(SupportClass.Caster, select().classFor(setOf("Arcueid")))
    }

    @Test fun `downloaded folders use the collection number and region prefix`() = runBlocking {
        assertEquals(SupportClass.Archer, select().classFor(setOf("No.5 Someone")))
        assertEquals(SupportClass.Lancer, select().classFor(setOf("KR.No.5 Someone")))
        assertEquals(SupportClass.Rider, select().classFor(setOf("NA.No.6 Someone")))
    }

    @Test fun `extra classes and beast variants give Extra`() = runBlocking {
        assertEquals(SupportClass.Extra, select().classFor(setOf("No.300 Beast")))
        assertEquals(SupportClass.Extra, select().classFor(setOf("No.301 Moon")))
    }

    @Test fun `zero or several servants give no change`() = runBlocking {
        assertNull(select().classFor(emptySet()))
        assertNull(select().classFor(setOf("Arcueid", "No.5 Someone")))
    }

    @Test fun `custom folder unknown class unknown number and missing id give no change`() = runBlocking {
        assertNull(select().classFor(setOf("My custom folder")))
        assertNull(select().classFor(setOf("No.302 Mystery")))
        assertNull(select().classFor(setOf("No.999 Nobody")))
        assertNull(select().classFor(setOf("NoId")))
    }

    @Test fun `catalog failure gives no change`() = runBlocking {
        assertNull(select(fail = true).classFor(setOf("No.5 Someone")))
        assertNull(select(fail = true).classFor(setOf("Arcueid")))
    }

    @Test fun `update sets the class only when the servant selection changed`() = runBlocking {
        val prefs = prefs()
        val select = select()
        select.update(prefs, emptySet(), setOf("Arcueid"))
        assertEquals(SupportClass.Caster, prefs.supportClass.get())
        // CE-only change: servants are equal, so a class picked by hand stays.
        prefs.supportClass.set(SupportClass.Saber)
        select.update(prefs, setOf("Arcueid"), setOf("Arcueid"))
        assertEquals(SupportClass.Saber, prefs.supportClass.get())
        // The next servant change picks the class again.
        select.update(prefs, setOf("Arcueid"), setOf("No.5 Someone"))
        assertEquals(SupportClass.Archer, prefs.supportClass.get())
        // Several servants leave it alone.
        select.update(prefs, setOf("No.5 Someone"), setOf("No.5 Someone", "Arcueid"))
        assertEquals(SupportClass.Archer, prefs.supportClass.get())
    }

    @Test fun `shared class mapping covers base classes extra classes and unknown names`() {
        listOf(
            "saber" to SupportClass.Saber, "Archer" to SupportClass.Archer, "lancer" to SupportClass.Lancer,
            "rider" to SupportClass.Rider, "caster" to SupportClass.Caster, "assassin" to SupportClass.Assassin,
            "berserker" to SupportClass.Berserker
        ).forEach { assertEquals(it.second, supportClassOfServantClass(it.first)) }
        listOf(
            "shielder", "ruler", "avenger", "moonCancer", "alterEgo", "foreigner", "pretender", "beast",
            "beastEresh", "beastII"
        ).forEach { assertEquals(SupportClass.Extra, supportClassOfServantClass(it), it) }
        assertNull(supportClassOfServantClass("unknown"))
        assertNull(supportClassOfServantClass("none"))
    }
}
