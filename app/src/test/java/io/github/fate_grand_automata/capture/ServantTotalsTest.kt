package io.github.fate_grand_automata.capture

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

/** The total of owned / total servants must come from the account's own server list, never from the merged names. */
class ServantTotalsTest {
    @TempDir lateinit var directory: File
    private val state get() = JpNamesFixture.state

    private fun catalogIds(names: GameNameMap) = dashboardData(state, names).servantCatalog?.map { it.id }?.sorted()

    @Test fun aKrAccountCountsOnlyTheKrListEvenWhenJpServantsAreAlsoKnown() = runBlocking {
        // The state holds JP-only servant 20, so the merged servantData has it; the KR total must not.
        val names = GameNames(directory, JpNamesFixture::download) { 1000 }.dashboard(listOf(state), hasJpAccount = false)
        assertTrue(20L in names.servantData)
        assertEquals(setOf(10L), names.accountServants.keys)
        assertEquals(listOf(10L), catalogIds(names))
    }

    @Test fun aJpAccountCountsOnlyTheJpListWithPlayableTypesAndACollectionNumber() = runBlocking {
        val names = GameNames(directory, JpNamesFixture::download) { 1000 }.dashboard(listOf(state), hasJpAccount = true)
        assertEquals(setOf(10L, 20L, 21L, 22L), names.accountServants.keys)
        // 21 is an enemy: normal and heroine are the playable types.
        assertEquals(listOf(10L, 20L, 22L), catalogIds(names))
    }

    @Test fun krAndJpTotalsDifferBecauseEachUsesItsOwnList() = runBlocking {
        val cache = GameNames(directory, JpNamesFixture::download) { 1000 }
        val kr = cache.dashboard(listOf(state), hasJpAccount = false)
        val jp = cache.dashboard(listOf(state), hasJpAccount = true)
        assertEquals(1, catalogIds(kr)!!.size)
        assertEquals(3, catalogIds(jp)!!.size)
    }

    @Test fun aJpAccountWhoseJpListIsNotAvailableHasNoTotalInsteadOfTheKrOne() = runBlocking {
        val download: (String) -> String = { if ("/export/JP/basic_servant" in it) error("offline") else JpNamesFixture.download(it) }
        val names = GameNames(directory, download) { 1000 }.dashboard(listOf(state), hasJpAccount = true)
        assertTrue(10L in names.servantData)
        assertTrue(names.accountServants.isEmpty())
        assertNull(dashboardData(state, names).servantCatalog)
    }

    @Test fun nothingLoadedMeansNoCatalog() {
        assertNull(dashboardData(state, GameNameMap()).servantCatalog)
    }

    @Test fun anEntryWithoutACollectionNumberIsNotCounted() {
        val names = GameNameMap(
            servantData = mapOf(1L to AtlasBasic(1, "A", "normal", "saber", 5, 1)),
            accountServants = mapOf(1L to AtlasBasic(1, "A", "normal", "saber", 5, 1), 2L to AtlasBasic(2, "B", "normal", "saber", 5, 0),
                3L to AtlasBasic(3, "C", "normal", "saber", 5, null), 4L to AtlasBasic(4, "D", "servantEquip", null, 5, 4)),
        )
        assertEquals(listOf(1L), catalogIds(names))
    }
}
