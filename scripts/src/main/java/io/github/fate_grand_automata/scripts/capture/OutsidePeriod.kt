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
) {
    val isEmpty get() = sources.all { it.delta.isEmpty } && fruitUsed.values.all { it == 0L }

    fun add(source: ChangeSource, delta: AccountDelta, now: Long): OutsidePeriod {
        if (delta.isEmpty) return this
        val previous = sources.find { it.source == source }
        fun fruitUsage(prior: Map<Long, Long>) = RunGains.FRUIT_IDS.associateWith {
            (prior[it] ?: 0L) + (-(delta.items[it] ?: 0L)).coerceAtLeast(0L)
        }
        return copy(
            endedAt = maxOf(endedAt, now),
            sources = sources.filterNot { it.source == source } +
                SourceDelta(source, (previous?.delta ?: AccountDelta()) + delta, fruitUsage(previous?.fruitUsed.orEmpty())),
            total = total + delta,
            // Count each observed decrease, so claiming fruit later cannot erase fruit already used.
            fruitUsed = fruitUsage(fruitUsed),
        )
    }
}
