package io.github.fate_grand_automata.ui.log

import io.github.fate_grand_automata.capture.CaptureLogItem
import io.github.fate_grand_automata.scripts.capture.RunGains
import io.github.fate_grand_automata.scripts.capture.ChangeSource
import io.github.fate_grand_automata.scripts.capture.SourceDelta
import java.util.Calendar
import java.util.TimeZone

internal enum class LogSourceFilter { Script, GiftBox, Shop, Summon, Lottery, Enhancement, ApRecovery, ManualQuest, Missions, Other }

internal fun ChangeSource.logSource(): LogSourceFilter =
    if (script) LogSourceFilter.Script else when (group) {
        ChangeSource.Group.GiftBox -> LogSourceFilter.GiftBox
        ChangeSource.Group.Shop -> LogSourceFilter.Shop
        ChangeSource.Group.Summon -> LogSourceFilter.Summon
        ChangeSource.Group.Lottery -> LogSourceFilter.Lottery
        ChangeSource.Group.Enhancement -> LogSourceFilter.Enhancement
        ChangeSource.Group.ApRecovery -> LogSourceFilter.ApRecovery
        ChangeSource.Group.ManualQuest -> LogSourceFilter.ManualQuest
        ChangeSource.Group.Missions -> LogSourceFilter.Missions
        ChangeSource.Group.Other -> LogSourceFilter.Other
    }

/** Stable partition: matching sources lead, with all other details still visible. */
internal fun orderedLogSources(sources: List<SourceDelta>, source: LogSourceFilter?): List<SourceDelta> =
    if (source == null) sources else sources.filter { it.source.logSource() == source } + sources.filter { it.source.logSource() != source }

internal enum class LogTimeFilter { Today, Week, Month, All, Custom }

private val CaptureLogItem.accountId: String? get() = battle?.accountId ?: outside?.accountId

/** Use the newest recorded name for each profile, falling back to its id for unnamed history. */
internal fun logProfiles(entries: List<CaptureLogItem>): Map<String, String> =
    entries.sortedByDescending { it.endedAt }.mapNotNull { entry ->
        entry.accountId?.let { id -> id to (entry.battle?.accountName ?: entry.outside?.accountName) }
    }.groupBy({ it.first }, { it.second }).mapValues { (id, names) ->
        names.firstOrNull { !it.isNullOrBlank() } ?: "#$id"
    }

/** Reserved profile choice for logs without an account id; real account ids are digits, so it cannot collide. */
internal const val NO_LOG_PROFILE = "!none"

internal fun hasLogWithoutProfile(entries: List<CaptureLogItem>): Boolean = entries.any { it.accountId == null }

internal fun availableLogProfile(profile: String?, profiles: Map<String, String>, hasNoProfileLog: Boolean = false): String? =
    profile?.takeIf { if (it == NO_LOG_PROFILE) hasNoProfileLog else it in profiles }

/** Material picker values are UTC dates, not instants in the user's zone. */
internal fun pickerDateInZone(date: Long, zone: TimeZone): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = date }
    return Calendar.getInstance(zone).apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** Stable resource keys keep items, battle drops and outside card counts separate. */
private fun CaptureLogItem.resourceChanges(): List<Map<String, Long>> = battle?.gains?.let { gains ->
    listOf(buildMap {
        gains.items.forEach { put("${it.type}:${it.objectId}", (get("${it.type}:${it.objectId}") ?: 0L) + it.gained) }
        gains.qp?.let { put("2:1", (get("2:1") ?: 0L) + it) }
        gains.fruitUsed.forEach { (id, used) -> if (used != null && used != 0L) putIfAbsent("2:$id", -used) }
        gains.itemsUsed.forEach { (id, used) -> if (used > 0L && id !in RunGains.FRUIT_IDS) putIfAbsent("2:$id", -used) }
    })
} ?: outside?.period?.let { period ->
    (outsideResources(period.total, period.fruitUsed) + period.sources.flatMap { outsideResources(it.delta, it.fruitUsed) })
        .map { mapOf("${it.type}:${it.id}" to (it.count ?: 0L)) }
}.orEmpty()

internal fun logResources(entries: List<CaptureLogItem>): Set<String> =
    entries.flatMap { entry -> entry.resourceChanges().flatMap { amounts -> amounts.filterValues { it != 0L }.keys } }.toSet()

/** Calendar days in the user's zone, including today; no clock or Android dependency in the predicate. */
internal fun filterCaptureLogs(
    entries: List<CaptureLogItem>, time: LogTimeFilter, resource: String?, now: Long,
    zone: TimeZone = TimeZone.getDefault(),
    customStart: Long? = null, customEnd: Long? = null,
    profile: String? = null, source: LogSourceFilter? = null,
): List<CaptureLogItem> {
    val days = when (time) { LogTimeFilter.Today -> 1L; LogTimeFilter.Week -> 7L; LogTimeFilter.Month -> 30L; else -> null }
    val midnight = Calendar.getInstance(zone).apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val start = if (time == LogTimeFilter.Custom) customStart?.let { pickerDateInZone(it, zone) }
        else days?.let { (midnight.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -(it - 1).toInt()) }.timeInMillis }
    val end = if (time == LogTimeFilter.Custom) customEnd?.let { date ->
        Calendar.getInstance(zone).apply { timeInMillis = pickerDateInZone(date, zone); add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
    } else (midnight.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
    return entries.filter { entry ->
        (source == null || (if (entry.battle != null) source == LogSourceFilter.Script else
            entry.outside?.period?.sources?.any { it.source.logSource() == source } == true)) &&
        (profile == null || (if (profile == NO_LOG_PROFILE) entry.accountId == null else entry.accountId == profile)) &&
            (start == null || end == null || entry.endedAt in start until end) &&
            (resource == null || entry.resourceChanges().any { val change = it[resource] ?: 0L; if (entry.battle != null) change != 0L else change > 0L })
    }
}

internal data class LogDeleteSelection(val active: Boolean = false, val selected: Set<String> = emptySet()) {
    fun enter() = LogDeleteSelection(active = true)
    fun toggle(id: String) = if (!active) this else copy(selected = if (id in selected) selected - id else selected + id)
    fun cancel() = LogDeleteSelection()
    fun targets(entries: List<CaptureLogItem>, all: Boolean = false): List<String> =
        if (!active) emptyList() else entries.map { it.id }.filter { all || it in selected }
}
