package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.CapturedBattle
import io.github.fate_grand_automata.scripts.capture.ICaptureFeed
import javax.inject.Inject
import kotlin.time.Duration

class CaptureBattleFeed @Inject constructor() : ICaptureFeed {
    override fun isCollecting() = CaptureTracker.running.value
    override fun isStarting() = CaptureTracker.starting
    override fun battleToken() = CaptureTracker.battleInbox.token()
    override fun awaitBattle(token: Long, timeout: Duration) = CaptureTracker.battleInbox.await(token, timeout)
}

/** Receives only this script account's results, under the tracker's routing lock.
 * Waiters use a separate lock so the proxy can deliver a response while a script waits.
 * Retains parsed values only, never response bodies or account identifiers.
 */
internal class CaptureBattleInbox {
    private val lock = Object()
    private var sequence = 0L
    private val results = ArrayDeque<Pair<Long, CapturedBattle?>>()

    fun token(): Long = synchronized(lock) { sequence }
    fun clear() = synchronized(lock) { results.clear() }
    fun add(battle: CapturedBattle?) = synchronized(lock) {
        results.addLast(++sequence to battle)
        while (results.size > 16) results.removeFirst()
        lock.notifyAll()
    }

    fun await(token: Long, timeout: Duration): CapturedBattle? = synchronized(lock) {
        val start = System.nanoTime()
        val budget = timeout.inWholeNanoseconds.coerceAtLeast(0)
        while (true) {
            results.firstOrNull { it.first > token }?.let { return@synchronized it.second }
            val remaining = budget - (System.nanoTime() - start)
            if (remaining <= 0) return@synchronized null
            lock.wait(remaining / 1_000_000, (remaining % 1_000_000).toInt())
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }
}
