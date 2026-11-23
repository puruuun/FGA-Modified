package io.github.fate_grand_automata.ui.account

import io.github.fate_grand_automata.capture.DashboardItem
import io.github.fate_grand_automata.capture.InventoryGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardItemsLogicTest {
    private fun gem(id: Long, count: Long) = DashboardItem(id, count, InventoryGroup.Gems)
    private fun piece(id: Long, count: Long) = DashboardItem(id, count, InventoryGroup.Pieces)

    @Test fun gemTableHasSevenClassColumnsAndAllThreeRows() {
        val table = buildItemTable(ItemTableKind.Gems, listOf(gem(6001, 5), gem(6103, 40), gem(6207, 12)))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), table.suffixes)
        assertEquals(listOf(6000L, 6100L, 6200L), table.rows.map { it.base })
        assertEquals(listOf<Long?>(5, null, null, null, null, null, null), table.rows[0].cells)
        assertEquals(listOf<Long?>(null, null, 40, null, null, null, null), table.rows[1].cells)
        assertEquals(listOf<Long?>(null, null, null, null, null, null, 12), table.rows[2].cells)
    }

    @Test fun suffixEightAddsAColumnOnlyWhenTheAccountHoldsOne() {
        val without = buildItemTable(ItemTableKind.Gems, listOf(gem(6001, 5)))
        assertFalse(ITEM_EXTRA_COLUMN in without.suffixes)
        val with = buildItemTable(ItemTableKind.Gems, listOf(gem(6001, 5), gem(6108, 7)))
        assertEquals((1..8).toList(), with.suffixes)
        assertEquals(7L, with.rows[1].cells.last())
        assertNull(with.rows[0].cells.last())
        assertNull(with.rows[2].cells.last())
    }

    @Test fun pieceTableRowsArePieceAndMonumentAndIgnoreGemSuffixEight() {
        val items = listOf(gem(6008, 3), piece(7002, 9), piece(7107, 100))
        val table = buildItemTable(ItemTableKind.Pieces, items)
        assertEquals(listOf(7000L, 7100L), table.rows.map { it.base })
        assertEquals((1..7).toList(), table.suffixes)
        assertEquals(9L, table.rows[0].cells[1])
        assertEquals(100L, table.rows[1].cells[6])
        assertEquals(8, buildItemTable(ItemTableKind.Pieces, items + piece(7108, 1)).suffixes.size)
    }

    @Test fun anItemOfAnotherRowDoesNotLeakIntoACell() {
        val table = buildItemTable(ItemTableKind.Gems, listOf(gem(7001, 99), gem(6301, 99)))
        assertTrue(table.rows.all { row -> row.cells.all { it == null } })
    }

    @Test fun lowCountIsStrictlyBelowTheThreshold() {
        assertEquals(10L, LOW_ITEM_COUNT)
        assertTrue(isLowItemCount(LOW_ITEM_COUNT - 1))
        assertFalse(isLowItemCount(LOW_ITEM_COUNT))
        assertFalse(isLowItemCount(LOW_ITEM_COUNT + 1))
    }

    private val names = mapOf(1L to "Dragon Fang", 2L to "dragon scale", 3L to "Phoenix Feather", 4L to "검의 휘석")
    private val nameOf: (Long) -> String = { names[it] ?: "Item #$it" }
    private fun item(id: Long, group: InventoryGroup) = DashboardItem(id, 1, group)

    @Test fun queryMatchesSubstringsIgnoringCaseAndSurroundingSpaces() {
        assertTrue(matchesItemQuery("Dragon Fang", "FANG"))
        assertTrue(matchesItemQuery("Dragon Fang", " on f "))
        assertTrue(matchesItemQuery("검의 휘석", "휘석"))
        assertFalse(matchesItemQuery("Dragon Fang", "scale"))
        assertTrue(matchesItemQuery("anything", "   "))
    }

    @Test fun filterKeepsOnlyMatchingItemsAndABlankQueryKeepsAll() {
        val items = listOf(1L, 2L, 3L).map { item(it, InventoryGroup.Materials) }
        assertEquals(listOf(1L, 2L), filterItems(items, "dragon", nameOf).map { it.id })
        assertEquals(items, filterItems(items, "", nameOf))
    }

    @Test fun fallbackNamesAreSearchableToo() {
        assertEquals(1, filterItems(listOf(item(99, InventoryGroup.Other)), "item #99", nameOf).size)
    }

    @Test fun groupsWithoutAMatchAreHiddenAndTheRestKeepEnumOrder() {
        val groups = mapOf(
            InventoryGroup.Other to listOf(item(2, InventoryGroup.Other)),
            InventoryGroup.Materials to listOf(item(1, InventoryGroup.Materials), item(3, InventoryGroup.Materials)),
            InventoryGroup.Gems to listOf(item(4, InventoryGroup.Gems)),
        )
        val all = visibleItemGroups(groups, "", nameOf)
        assertEquals(listOf(InventoryGroup.Materials, InventoryGroup.Gems, InventoryGroup.Other), all.map { it.group })
        val dragon = visibleItemGroups(groups, "dragon", nameOf)
        assertEquals(listOf(InventoryGroup.Materials, InventoryGroup.Other), dragon.map { it.group })
        assertEquals(listOf(1L), dragon[0].matches.map { it.id })
        assertEquals(2, dragon[0].all.size)
        assertTrue(visibleItemGroups(groups, "zzz", nameOf).isEmpty())
    }

    @Test fun theGrailGroupComesFirstAndTheOthersKeepTheirOrder() {
        val groups = InventoryGroup.entries.associateWith { listOf(item(it.ordinal + 1L, it)) }
        assertEquals(
            listOf(InventoryGroup.Grails, InventoryGroup.Materials, InventoryGroup.Gems, InventoryGroup.Pieces,
                InventoryGroup.Event, InventoryGroup.Coins, InventoryGroup.Other),
            visibleItemGroups(groups, "", nameOf).map { it.group },
        )
    }

    @Test fun materialsAreListedInTheReverseOfTheCapturedOrderAndOtherGroupsAreNot() {
        val groups = mapOf(
            InventoryGroup.Materials to listOf(item(46, InventoryGroup.Materials), item(10, InventoryGroup.Materials), item(7, InventoryGroup.Materials)),
            InventoryGroup.Grails to listOf(item(46, InventoryGroup.Grails), item(10, InventoryGroup.Grails)),
        )
        val views = visibleItemGroups(groups, "", nameOf).associateBy { it.group }
        assertEquals(listOf(7L, 10L, 46L), views.getValue(InventoryGroup.Materials).all.map { it.id })
        assertEquals(listOf(7L, 10L, 46L), views.getValue(InventoryGroup.Materials).matches.map { it.id })
        assertEquals(listOf(46L, 10L), views.getValue(InventoryGroup.Grails).all.map { it.id })
    }

    @Test fun aQueryOpensGroupsAndWithoutOneTheStoredStateDecides() {
        assertTrue(isItemGroupOpen("fang", openByUser = false))
        assertFalse(isItemGroupOpen("", openByUser = false))
        assertTrue(isItemGroupOpen("", openByUser = true))
        assertFalse(isItemGroupOpen("  ", openByUser = false))
    }

    @Test fun listColumnsFollowTheWidth() {
        assertEquals(1, itemListColumns(328f))
        assertEquals(2, itemListColumns(480f))
        assertEquals(3, itemListColumns(720f))
        assertEquals(ITEM_PREVIEW_COUNT, itemListLimit(InventoryGroup.Materials))
        assertEquals(ITEM_COLLAPSED_PREVIEW_COUNT, itemListLimit(InventoryGroup.Coins))
    }
}
