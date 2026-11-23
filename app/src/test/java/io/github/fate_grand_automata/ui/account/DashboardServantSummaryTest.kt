package io.github.fate_grand_automata.ui.account

import io.github.fate_grand_automata.capture.AtlasBasic
import io.github.fate_grand_automata.capture.DashboardServant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardServantSummaryTest {
    private fun owned(id: Long, rarity: Long?, cls: String, np: Long?) =
        DashboardServant(id, AtlasBasic(id, "S$id", "normal", cls, rarity, id), null, null, np, emptyList(), null)

    private fun listed(id: Long, rarity: Long, cls: String) = AtlasBasic(id, "S$id", "normal", cls, rarity, id)

    private val servants = listOf(
        owned(1, 5, "saber", 2), owned(2, 5, "saber", 4), owned(3, 4, "archer", 5), owned(4, 3, "saber", null), owned(5, 5, "caster", 1),
    )
    private val catalog = listOf(
        listed(1, 5, "saber"), listed(2, 5, "saber"), listed(6, 5, "saber"), listed(5, 5, "caster"), listed(3, 4, "archer"),
        listed(7, 4, "archer"), listed(4, 3, "saber"), listed(8, 2, "saber"), listed(9, 1, "lancer"),
    )

    @Test fun ownedTotalAndNpSumsCountDistinctServantsAndStoredNpLevels() {
        val s = servantSummary(servants, catalog)
        assertEquals(5, s.owned); assertEquals(9, s.total)
        assertEquals(2L + 4L + 1L, s.star5Np)
        assertEquals(2L + 4L + 5L + 0L + 1L, s.allNp)
        assertEquals(56, s.percent)
    }

    @Test fun aChipCountsOwnedTotalAndNpOfItsClassesOnly() {
        val saber = servantSummary(servants, catalog, setOf("saber"))
        assertEquals(3, saber.owned); assertEquals(5, saber.total)
        assertEquals(6L, saber.star5Np); assertEquals(6L, saber.allNp)
        val group = servantSummary(servants, catalog, setOf("archer", "caster"))
        assertEquals(2, group.owned); assertEquals(3, group.total)
        assertEquals(1L, group.star5Np); assertEquals(6L, group.allNp)
    }

    @Test fun everyRarityRowFromFiveDownToTheLowestOnTheServerIsThereEvenWithNothingOwned() {
        val rows = servantSummary(servants, catalog).rarities
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), rows.map { it.rarity })
        assertEquals(RaritySummary(5, 3, 4, 7), rows[0])
        assertEquals(RaritySummary(4, 1, 2, 5), rows[1])
        assertEquals(RaritySummary(3, 1, 1, 0), rows[2])
        assertEquals(RaritySummary(2, 0, 1, 0), rows[3])
        assertEquals(RaritySummary(1, 0, 1, 0), rows[4])
    }

    @Test fun theLowestRarityComesFromTheServerList() {
        val rows = servantSummary(servants, catalog.filter { it.rarity!! >= 3 }).rarities
        assertEquals(listOf(5L, 4L, 3L), rows.map { it.rarity })
    }

    @Test fun withoutTheServerListThereAreNoTotalsAndOnlyOwnedRarities() {
        val s = servantSummary(servants, null)
        assertNull(s.total); assertNull(s.percent)
        assertEquals(5, s.owned); assertEquals(7L, s.star5Np); assertEquals(12L, s.allNp)
        assertEquals(listOf(5L, 4L, 3L), s.rarities.map { it.rarity })
        s.rarities.forEach { assertNull(it.total) }
    }

    @Test fun aServantWithoutARarityCountsInTheTotalsButHasNoRarityRow() {
        val s = servantSummary(servants + owned(10, null, "saber", 3), null)
        assertEquals(6, s.owned); assertEquals(15L, s.allNp)
        assertEquals(5, s.rarities.sumOf { it.owned })
    }

    @Test fun nothingOwnedGivesZerosAndStillAllRaritiesOfTheServer() {
        val s = servantSummary(emptyList(), catalog)
        assertEquals(0, s.owned); assertEquals(0L, s.allNp); assertEquals(0, s.percent)
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), s.rarities.map { it.rarity })
    }
}
