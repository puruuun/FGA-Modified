package io.github.fate_grand_automata.ui.account

import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.scripts.capture.OwnFormations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardSectionsTest {
    private fun data(
        resources: Map<Long, Long> = emptyMap(),
        servants: List<DashboardServant>? = null,
        inventory: List<DashboardItem>? = null,
        presentBox: DashboardPresentBox? = null,
        commandCodes: DashboardCommandCodes? = null,
        classBoards: List<DashboardClassBoard>? = null,
        summons: DashboardSummons? = null,
        teapots: DashboardTeapots? = null,
        itemsCaptured: Boolean = false,
        hasQuestRecords: Boolean = false,
        hasShopRecords: Boolean = false,
    ) = DashboardData(
        profile = emptyMap(), snapshotTime = 0, lastUpdateTime = 0, game = null, resources = resources, freePaid = null,
        storage = null, servants = servants, inventory = inventory, equips = null, teapots = teapots,
        formations = null as OwnFormations?, presentBox = presentBox, commandCodes = commandCodes, classBoards = classBoards, summons = summons,
        itemsCaptured = itemsCaptured, hasQuestRecords = hasQuestRecords, hasShopRecords = hasShopRecords,
    )

    private val servant = DashboardServant(1, null, null, null, null, emptyList(), null)
    private val item = DashboardItem(6001, 1, InventoryGroup.Gems)
    private val board = DashboardClassBoard(1, "세이버", null, 1, 0, 10, 2)
    private val summons = DashboardSummons(null, emptyList(), emptyList())

    @Test fun anAccountWithoutDataListsOnlyOverviewAndFormations() {
        assertEquals(listOf(DashboardSection.Overview, DashboardSection.Formations), dashboardSections(data()))
    }

    @Test fun everySectionIsListedInTheSpecifiedOrder() {
        val all = data(
            servants = listOf(servant), inventory = listOf(item), presentBox = DashboardPresentBox(0, emptyList()),
            commandCodes = DashboardCommandCodes(emptyList(), null),
        )
        assertEquals(
            listOf(
                DashboardSection.Overview, DashboardSection.Servants, DashboardSection.Items, DashboardSection.Formations,
                DashboardSection.Collection, DashboardSection.PresentBox,
            ),
            dashboardSections(all),
        )
    }

    @Test fun aSectionWithoutDataIsLeftOut() {
        val sections = dashboardSections(data(servants = emptyList(), inventory = emptyList(), classBoards = emptyList()))
        assertFalse(DashboardSection.Servants in sections)
        assertFalse(DashboardSection.Items in sections)
        assertFalse(DashboardSection.Collection in sections)
        assertFalse(DashboardSection.PresentBox in sections)
    }

    @Test fun collectionNeedsAnyOneOfItsBlocks() {
        assertEquals(true, DashboardSection.Collection in dashboardSections(data(commandCodes = DashboardCommandCodes(emptyList(), 3))))
        assertEquals(true, DashboardSection.Collection in dashboardSections(data(classBoards = listOf(board))))
        assertEquals(true, DashboardSection.Collection in dashboardSections(data(summons = summons)))
        assertEquals(true, DashboardSection.Collection in dashboardSections(data(hasQuestRecords = true)))
    }

    @Test fun theShopTableAloneShowsThePresentBoxSection() {
        assertEquals(true, DashboardSection.PresentBox in dashboardSections(data(hasShopRecords = true)))
    }

    @Test fun anEmptyPresentBoxIsStillASection() {
        assertEquals(true, DashboardSection.PresentBox in dashboardSections(data(presentBox = DashboardPresentBox(0, emptyList()))))
    }

    @Test fun aSelectedSectionThatDisappearedFallsBackToOverview() {
        val available = listOf(DashboardSection.Overview, DashboardSection.Formations)
        assertEquals(DashboardSection.Formations, resolveSection(DashboardSection.Formations, available))
        assertEquals(DashboardSection.Overview, resolveSection(DashboardSection.PresentBox, available))
        assertEquals(DashboardSection.Overview, resolveSection(DashboardSection.Servants, available))
    }

    @Test fun legacyBlockIdsMapToTheirContainingSection() {
        val expected = mapOf(
            0 to DashboardSection.Overview, 1 to DashboardSection.Overview, 2 to DashboardSection.Overview, 6 to DashboardSection.Overview,
            3 to DashboardSection.Servants, 4 to DashboardSection.Items,
            5 to DashboardSection.Formations, 7 to DashboardSection.Formations, 8 to DashboardSection.Formations,
            10 to DashboardSection.PresentBox, 17 to DashboardSection.PresentBox,
            12 to DashboardSection.Collection, 13 to DashboardSection.Collection, 14 to DashboardSection.Collection, 16 to DashboardSection.Collection,
        )
        expected.forEach { (id, section) -> assertEquals("block $id", section, sectionOfBlock(id)) }
        assertEquals(DashboardSection.Overview, sectionOfBlock(99))
    }

    private fun keys(d: DashboardData) = overviewResources(d).resources.map { it.key }

    @Test fun overviewSplitsFruitsFromResourcesInTheOwnersOrder() {
        val resources = listOf(1L, 2L, 3L, 16L, 18L, 49L, 100L, 101L, 102L, 103L, 104L, 4001L).associateWith { it * 10 }
        val split = overviewResources(data(resources = resources, teapots = DashboardTeapots(7, 9)))
        assertEquals(listOf(100L, 101L, 104L, 102L), split.fruits.map { it.first })
        assertEquals(listOf("2", "16", "4001", TEAPOT_ROW, "49", "3", "18", "103", "1"), split.resources.map { it.key })
        assertEquals(100L to 1000L, split.fruits.first())
        assertEquals(OverviewResource(TEAPOT_ROW, null, 9, 7), split.resources.first { it.key == TEAPOT_ROW })
    }

    @Test fun anItemListThatWasCapturedShowsEveryResourceRowWithZeroForTheMissing() {
        val d = data(resources = mapOf(2L to 6L), teapots = DashboardTeapots(0, null), itemsCaptured = true)
        assertEquals(listOf("2", "16", "4001", TEAPOT_ROW, "49", "3", "18", "103", "1"), keys(d))
        assertEquals(listOf(6L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L), overviewResources(d).resources.map { it.count })
        assertNull(overviewResources(d).resources.first { it.key == TEAPOT_ROW }.iconId)
    }

    @Test fun withoutTheItemListNoValueIsInventedAsZero() {
        val d = data(resources = mapOf(2L to 6L, 1L to 5L, 101L to 3L, 18L to 0L))
        assertEquals(listOf(101L), overviewResources(d).fruits.map { it.first })
        // A real stored zero stays; an id the account has no value for does not appear, and there is no teapot row.
        assertEquals(listOf("2", "18", "1"), keys(d))
        assertEquals(listOf("2", "18", "1").zip(listOf(6L, 0L, 5L)), overviewResources(d).resources.map { it.key to it.count })
        assertEquals(emptyList<String>(), keys(data()))
    }

    @Test fun relativeAgeUsesTheLargestWholeUnit() {
        assertEquals(RelativeAge(AgeUnit.JustNow, 0), relativeAge(1_000, 1_000))
        assertEquals(RelativeAge(AgeUnit.JustNow, 0), relativeAge(1_059, 1_000))
        assertEquals(RelativeAge(AgeUnit.Minutes, 1), relativeAge(1_060, 1_000))
        assertEquals(RelativeAge(AgeUnit.Minutes, 59), relativeAge(1_000 + 59 * 60 + 59, 1_000))
        assertEquals(RelativeAge(AgeUnit.Hours, 1), relativeAge(1_000 + 3_600, 1_000))
        assertEquals(RelativeAge(AgeUnit.Hours, 23), relativeAge(1_000 + 23 * 3_600 + 3_599, 1_000))
        assertEquals(RelativeAge(AgeUnit.Days, 1), relativeAge(1_000 + 86_400, 1_000))
        assertEquals(RelativeAge(AgeUnit.Days, 3), relativeAge(1_000 + 3 * 86_400 + 5, 1_000))
    }

    @Test fun anUpdateTimeInTheFutureIsJustNow() {
        assertEquals(RelativeAge(AgeUnit.JustNow, 0), relativeAge(1_000, 5_000))
    }

    @Test fun friendLimitShowsCountOverLimitOnlyOnceTheListWasSeen() {
        assertEquals("143 / 146", friendLimitText("146", 143))
        assertEquals("146", friendLimitText("146", null))
        assertEquals("1,000", friendLimitText("1000", null))
    }
}
