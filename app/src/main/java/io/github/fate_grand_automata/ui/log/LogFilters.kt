package io.github.fate_grand_automata.ui.log

import io.github.fate_grand_automata.capture.CaptureLogItem
import java.util.Calendar
import java.util.TimeZone

internal enum class LogTimeFilter { Today, Week, Month, All }

/** Stable resource keys keep items, servants and CEs with overlapping ids separate. */
private fun CaptureLogItem.resourceChanges(): List<Map<String, Long>> = battle?.gains?.let { gains ->
    listOf(buildMap {
        gains.items.forEach { put("${it.type}:${it.objectId}", (get("${it.type}:${it.objectId}") ?: 0L) + it.gained) }
        gains.qp?.let { put("2:1", (get("2:1") ?: 0L) + it) }
        gains.fruitUsed.forEach { (id, used) -> if (used != null && used != 0L) putIfAbsent("2:$id", -used) }
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
): List<CaptureLogItem> {
    val days = when (time) { LogTimeFilter.Today -> 1L; LogTimeFilter.Week -> 7L; LogTimeFilter.Month -> 30L; LogTimeFilter.All -> null }
    val midnight = Calendar.getInstance(zone).apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val start = days?.let { (midnight.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -(it - 1).toInt()) }.timeInMillis }
    val end = (midnight.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
    return entries.filter { entry ->
        (start == null || entry.endedAt in start until end) &&
            (resource == null || entry.resourceChanges().any { (it[resource] ?: 0L) > 0 })
    }
}

internal data class LogDeleteSelection(val active: Boolean = false, val selected: Set<String> = emptySet()) {
    fun enter() = LogDeleteSelection(active = true)
    fun toggle(id: String) = if (!active) this else copy(selected = if (id in selected) selected - id else selected + id)
    fun cancel() = LogDeleteSelection()
    fun targets(entries: List<CaptureLogItem>, all: Boolean = false): List<String> =
        if (!active) emptyList() else entries.map { it.id }.filter { all || it in selected }
}
