package io.github.fate_grand_automata.ui.account

import io.github.fate_grand_automata.capture.*

/** The navigation sections, in display order. */
internal enum class DashboardSection { Overview, Servants, Items, Formations, Collection, PresentBox }

/**
 * A section appears only when it has data, by the rules that decided which cards were shown before the navigation.
 * Formations always appears because the support formation card always did (it shows "no formation data").
 */
internal fun dashboardSections(data: DashboardData): List<DashboardSection> = buildList {
    add(DashboardSection.Overview)
    if (!data.servants.isNullOrEmpty()) add(DashboardSection.Servants)
    if (!data.inventory.isNullOrEmpty()) add(DashboardSection.Items)
    add(DashboardSection.Formations)
    if (data.commandCodes != null || !data.classBoards.isNullOrEmpty() || data.summons != null || data.hasQuestRecords) add(DashboardSection.Collection)
    if (data.presentBox != null || data.hasShopRecords) add(DashboardSection.PresentBox)
}

/** A section that is no longer available falls back to Overview, which is always there. */
internal fun resolveSection(selected: DashboardSection, available: List<DashboardSection>): DashboardSection =
    if (selected in available) selected else DashboardSection.Overview

/** The navigation section that contains the legacy block id `initialSection` names. */
internal fun sectionOfBlock(blockId: Int): DashboardSection = when (blockId) {
    DashboardBlockId.Servants -> DashboardSection.Servants
    DashboardBlockId.Items -> DashboardSection.Items
    DashboardBlockId.MysticCodes, DashboardBlockId.Parties, DashboardBlockId.SupportFormation, DashboardBlockId.GrandFormation -> DashboardSection.Formations
    DashboardBlockId.CommandCodes, DashboardBlockId.ClassScore, DashboardBlockId.Summons, DashboardBlockId.QuestRecords -> DashboardSection.Collection
    DashboardBlockId.PresentBox, DashboardBlockId.ShopRecords -> DashboardSection.PresentBox
    else -> DashboardSection.Overview
}

/** Blocks that exist as their own group in a section; Overview blocks are not addressable. */
internal fun isBlockAddressable(blockId: Int) = sectionOfBlock(blockId) != DashboardSection.Overview

private val FRUIT_ORDER = listOf(100L, 101L, 104L, 102L)

/** Key of the teapot row, which sums several items and so has no resource id of its own. */
internal const val TEAPOT_ROW = "teapots"

/** The order of Overview's resource list, fixed by the owner (2026-11-22). */
private val RESOURCE_ORDER: List<String> = listOf(2L, 16L, SUMMON_TICKET_ID).map { it.toString() } + TEAPOT_ROW +
    listOf(STORM_POD_ID, 3L, 18L, 103L, 1L).map { it.toString() }

/** [iconId] is the item whose icon is drawn; null draws no icon. */
internal data class OverviewResource(val key: String, val id: Long?, val iconId: Long?, val count: Long)

internal data class OverviewResources(val fruits: List<Pair<Long, Long>>, val resources: List<OverviewResource>)

/**
 * Fruit ids the account has no value for are left out. A resource row the account does not hold is a real 0 only when
 * its item list was captured; without the list a missing value is not invented as 0.
 */
internal fun overviewResources(data: DashboardData) = OverviewResources(
    fruits = FRUIT_ORDER.mapNotNull { id -> data.resources[id]?.let { id to it } },
    resources = RESOURCE_ORDER.mapNotNull { key ->
        if (key == TEAPOT_ROW) data.teapots?.let { OverviewResource(key, null, it.iconId, it.total) }
        else key.toLong().let { id -> (data.resources[id] ?: 0L.takeIf { data.itemsCaptured })?.let { OverviewResource(key, id, id, it) } }
    },
)

internal enum class AgeUnit { JustNow, Minutes, Hours, Days }

/** How long ago the dashboard data was updated, in the largest whole unit. */
internal data class RelativeAge(val unit: AgeUnit, val amount: Int)

internal fun relativeAge(nowSeconds: Long, updatedSeconds: Long): RelativeAge {
    val minutes = ((nowSeconds - updatedSeconds) / 60).coerceAtLeast(0)
    return when {
        minutes < 1 -> RelativeAge(AgeUnit.JustNow, 0)
        minutes < 60 -> RelativeAge(AgeUnit.Minutes, minutes.toInt())
        minutes < 60 * 24 -> RelativeAge(AgeUnit.Hours, (minutes / 60).toInt())
        else -> RelativeAge(AgeUnit.Days, (minutes / (60 * 24)).toInt())
    }
}
