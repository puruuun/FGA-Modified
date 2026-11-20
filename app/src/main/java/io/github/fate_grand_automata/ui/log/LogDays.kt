package io.github.fate_grand_automata.ui.log

import io.github.fate_grand_automata.capture.CaptureLogItem
import io.github.fate_grand_automata.scripts.capture.GameDay
import io.github.fate_grand_automata.scripts.capture.gameDay
import java.util.TimeZone

internal data class LogDayAccount(
    /** Null groups the records saved without an account id ("No profile"). */
    val accountId: String?,
    val name: String?,
    val server: String?,
    /** Outside changes plus battle drops, keyed like [resourceChanges] ("type:id"). Zero sums are dropped. */
    val totals: Map<String, Long>,
    val outside: List<CaptureLogItem>,
    val battles: List<CaptureLogItem>,
    val inProgress: Boolean = false,
)

/** One left-list row of the day view: a game day seen for a single account. */
internal data class LogDayRow(val day: LogDay, val account: LogDayAccount) {
    val key get() = logDayRowKey(day.key, account.accountId)
}

/** What a day row lists on its second line: one battle entry for script and hand-played battles alike, then the other sources. */
internal sealed interface DaySource {
    data object Battle : DaySource
    data class Other(val source: LogSourceFilter) : DaySource
}

/**
 * Sources of one account's day in order of first appearance. A script battle record and a hand-played quest
 * ([LogSourceFilter.ManualQuest]) are the same entry for the row, so "battle" appears once and without a count.
 */
internal fun daySources(account: LogDayAccount): List<DaySource> = buildList {
    val events = account.battles.map { it.startedAt() to listOf<DaySource>(DaySource.Battle) } +
        account.outside.map { record ->
            record.startedAt() to record.outside!!.period.sources.map { delta ->
                delta.source.logSource().let { if (it == LogSourceFilter.ManualQuest) DaySource.Battle else DaySource.Other(it) }
            }
        }
    events.sortedBy { it.first }.forEach { (_, sources) -> sources.forEach { if (it !in this) add(it) } }
}

internal fun logDayRowKey(dayKey: String, accountId: String?) = "$dayKey/${accountId?.let { "a:$it" } ?: "none"}"

/** Newest day first; inside a day the accounts keep the order [groupLogDays] gave them, "No profile" last. */
internal fun logDayRows(days: List<LogDay>): List<LogDayRow> = days.flatMap { day -> day.accounts.map { LogDayRow(day, it) } }

internal data class LogDay(
    val day: GameDay,
    val accounts: List<LogDayAccount>,
    /** Battle records of the day, newest start first. */
    val battles: List<CaptureLogItem>,
    val outside: List<CaptureLogItem>,
    val inProgress: Boolean,
) {
    val key get() = day.key()
}

internal fun GameDay.key() = "$year-$month-$day"

/**
 * A record belongs to the game day it started on. A battle record has no start field, so it is derived from the
 * run's elapsed time; an outside record's own end is the time it was closed, which says nothing about when the
 * changes happened.
 */
internal fun CaptureLogItem.startedAt(): Long = battle?.let { if (it.totalTime > 0) it.endedAt - it.totalTime else it.endedAt }
    ?: outside!!.startedAt

/**
 * Per-record change used for the day totals. [resourceChanges] cannot be reused for outside records: it lists the
 * total and every source for the filter, which would count each change twice here. FGA's own screen-read
 * materials are not part of it (owner decision, 2026-11-20).
 */
internal fun CaptureLogItem.totalChanges(): Map<String, Long> = battle?.let { resourceChanges().singleOrNull().orEmpty() }
    ?: outside!!.period.let { period ->
        buildMap<String, Long> {
            outsideResources(period.total, period.fruitUsed).forEach {
                val key = "${it.type}:${it.id}"
                put(key, (get(key) ?: 0L) + (it.count ?: 0L))
            }
        }
    }

/** Call with the already filtered list; the filters run first so the day cards agree with the record list. */
internal fun groupLogDays(
    items: List<CaptureLogItem>,
    profiles: Map<String, String> = logProfiles(items),
    servers: Map<String, String> = emptyMap(),
): List<LogDay> = items.distinctBy { it.id }.groupBy { gameDay(it.startedAt()) }.map { (day, records) ->
    val accounts = records.groupBy { it.accountId }.map { (id, own) ->
        val totals = LinkedHashMap<String, Long>()
        own.forEach { record -> record.totalChanges().forEach { (key, count) -> totals[key] = (totals[key] ?: 0L) + count } }
        LogDayAccount(
            accountId = id,
            name = id?.let { profiles[it] },
            server = id?.let { servers[it] },
            totals = totals.filterValues { it != 0L },
            outside = own.filter { it.outside != null }.sortedByDescending { it.startedAt() },
            battles = own.filter { it.battle != null }.sortedByDescending { it.startedAt() },
            inProgress = own.any { it.inProgress },
        )
    }.sortedWith(compareBy<LogDayAccount> { it.accountId == null }.thenBy { it.name.orEmpty() }.thenBy { it.accountId.orEmpty() })
    LogDay(
        day = day,
        accounts = accounts,
        battles = records.filter { it.battle != null }.sortedByDescending { it.startedAt() },
        outside = records.filter { it.outside != null }.sortedByDescending { it.startedAt() },
        inProgress = records.any { it.inProgress },
    )
}.sortedByDescending { it.day }

/** The day boundary is fixed to Korea/Japan time, so a device in another zone gets a note on the day header. */
internal fun deviceZoneDiffers(now: Long, zone: TimeZone = TimeZone.getDefault()): Boolean =
    zone.getOffset(now) != 9 * 60 * 60 * 1000

internal fun resourceKeyParts(key: String): Pair<Int, Long> = key.substringBefore(':').toInt() to key.substringAfter(':').toLong()
