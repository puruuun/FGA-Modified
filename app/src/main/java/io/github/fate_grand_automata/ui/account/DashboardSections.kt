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
    if (data.commandCodes != null || !data.classBoards.isNullOrEmpty() || data.summons != null) add(DashboardSection.Collection)
    if (data.presentBox != null) add(DashboardSection.PresentBox)
}

/** A section that is no longer available falls back to Overview, which is always there. */
internal fun resolveSection(selected: DashboardSection, available: List<DashboardSection>): DashboardSection =
    if (selected in available) selected else DashboardSection.Overview

/** The navigation section that contains the legacy block id `initialSection` names. */
internal fun sectionOfBlock(blockId: Int): DashboardSection = when (blockId) {
    DashboardBlockId.Servants -> DashboardSection.Servants
    DashboardBlockId.Items -> DashboardSection.Items
    DashboardBlockId.MysticCodes, DashboardBlockId.SupportFormation, DashboardBlockId.GrandFormation -> DashboardSection.Formations
    DashboardBlockId.CommandCodes, DashboardBlockId.ClassScore, DashboardBlockId.Summons -> DashboardSection.Collection
    DashboardBlockId.PresentBox -> DashboardSection.PresentBox
    else -> DashboardSection.Overview
}

/** Blocks that exist as their own group in a section; Overview blocks are not addressable. */
internal fun isBlockAddressable(blockId: Int) = sectionOfBlock(blockId) != DashboardSection.Overview

/** The small count shown on a list item, where one exists. */
internal fun sectionCount(section: DashboardSection, data: DashboardData): Int? = when (section) {
    DashboardSection.Servants -> data.servants?.size
    DashboardSection.PresentBox -> data.presentBox?.totalCount
    else -> null
}

private val FRUIT_ORDER = listOf(100L, 101L, 104L, 102L)
private val RESOURCE_ORDER = listOf(3L, 18L, 16L, 103L, SUMMON_TICKET_ID, STORM_POD_ID)

/** Overview's "fruit" and "resources" lists. Ids the account has no value for are left out, never shown as 0. */
internal data class OverviewResources(
    val fruits: List<Pair<Long, Long>>,
    val resources: List<Pair<Long, Long>>,
    val teapots: DashboardTeapots?,
)

internal fun overviewResources(data: DashboardData) = OverviewResources(
    fruits = FRUIT_ORDER.mapNotNull { id -> data.resources[id]?.let { id to it } },
    resources = RESOURCE_ORDER.mapNotNull { id -> data.resources[id]?.let { id to it } },
    teapots = data.teapots,
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
