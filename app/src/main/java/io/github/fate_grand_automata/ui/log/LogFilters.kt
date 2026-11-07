package io.github.fate_grand_automata.ui.log

import io.github.fate_grand_automata.capture.CaptureLogItem
import io.github.fate_grand_automata.scripts.capture.RunGains
import java.util.Calendar
import java.util.TimeZone

internal enum class LogTimeFilter { Today, Week, Month, All, Custom }

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
