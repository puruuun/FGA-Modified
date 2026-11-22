package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.Serializable

@Serializable
data class SourceDelta(val source: ChangeSource, val delta: AccountDelta, val fruitUsed: Map<Long, Long> = emptyMap())

/** Unix seconds, like AccountState. A source list keeps JSON keys simple and unknown paths separate. */
@Serializable
data class OutsidePeriod(
    val accountId: String,
    val startedAt: Long,
    val endedAt: Long = startedAt,
    val sources: List<SourceDelta> = emptyList(),
    val total: AccountDelta = AccountDelta(),
    val fruitUsed: Map<Long, Long> = emptyMap(),
    /** One entry per recorded login grant, oldest first. Absent in files written before the breakdown existed. */
    val logins: List<LoginBreakdown> = emptyList(),
) {
    val isEmpty get() = sources.all { it.delta.isEmpty } && fruitUsed.values.all { it == 0L }

    /**
     * Stored groups this version does not know all read as Other, so two stored entries can share one source key.
     * Merging them on read keeps the keys unique; a period without duplicates is returned as is.
     */
    fun mergeDuplicateSources(): OutsidePeriod {
        if (sources.map { it.source }.toSet().size == sources.size) return this
        return copy(sources = sources.groupBy { it.source }.map { (source, same) ->
            same.singleOrNull() ?: SourceDelta(
                source,
                same.fold(AccountDelta()) { sum, it -> sum + it.delta },
                same.fold(emptyMap()) { sum, it ->
                    (sum.keys + it.fruitUsed.keys).associateWith { k -> (sum[k] ?: 0L) + (it.fruitUsed[k] ?: 0L) }
                },
            )
        })
    }

    fun withLogin(login: LoginBreakdown?) = if (login == null) this else copy(logins = logins + login)

    fun add(source: ChangeSource, delta: AccountDelta, now: Long): OutsidePeriod {
        if (delta.isEmpty) return this
        // Unknown stored groups all read as Other, so a loaded period can hold several entries under one key.
        val same = sources.filter { it.source == source }
        val previousDelta = same.fold(AccountDelta()) { sum, it -> sum + it.delta }
        val previousFruit = same.fold(emptyMap<Long, Long>()) { sum, it ->
            (sum.keys + it.fruitUsed.keys).associateWith { k -> (sum[k] ?: 0L) + (it.fruitUsed[k] ?: 0L) }
        }
        fun fruitUsage(prior: Map<Long, Long>) = RunGains.FRUIT_IDS.associateWith {
            (prior[it] ?: 0L) + (-(delta.items[it] ?: 0L)).coerceAtLeast(0L)
        }
        return copy(
            endedAt = maxOf(endedAt, now),
            sources = sources.filterNot { it.source == source } +
                SourceDelta(source, previousDelta + delta, fruitUsage(previousFruit)),
            total = total + delta,
            // Count each observed decrease, so claiming fruit later cannot erase fruit already used.
            fruitUsed = fruitUsage(fruitUsed),
        )
    }
}
