package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.CapturedBattle
import io.github.fate_grand_automata.scripts.capture.ICaptureFeed
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.Duration

class CaptureBattleFeed @Inject constructor() : ICaptureFeed {
    internal var runningProvider: () -> Boolean = { CaptureTracker.running.value }
    internal var startingProvider: () -> Boolean = { CaptureTracker.starting }
    internal var tokenProvider: () -> Long = { CaptureTracker.battleInbox.token() }
    internal var awaitProvider: (Long, Duration) -> CapturedBattle? = { token, timeout ->
        CaptureTracker.battleInbox.await(token, timeout)
    }

    override fun isCollecting(): Boolean = try {
        captureSafely("feedIsCollecting", default = false) { runningProvider() }
    } catch (_: Throwable) {
        false
    }

    override fun isStarting(): Boolean = try {
        captureSafely("feedIsStarting", default = false) { startingProvider() }
    } catch (_: Throwable) {
        false
    }

    override fun battleToken(): Long = try {
        captureSafely("feedBattleToken", default = 0L) { tokenProvider() }
    } catch (_: Throwable) {
        0L
    }

    override fun awaitBattle(token: Long, timeout: Duration): CapturedBattle? = try {
        try {
            awaitProvider(token, timeout)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    } catch (t: Throwable) {
        if (t is InterruptedException) {
            Thread.currentThread().interrupt()
        } else {
            Timber.w("Capture: feedAwaitBattle failed: %s", t.javaClass.simpleName)
        }
        null
    }
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
