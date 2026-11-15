package io.github.fate_grand_automata.capture

/**
 * Lets an action run at most once per [minIntervalMs]. The clock is injectable for tests.
 */
class EnsureThrottle(
    private val minIntervalMs: Long = 30_000L,
    private val clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private var lastMs: Long? = null

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clockMs()
        val last = lastMs
        if (last != null && now - last in 0 until minIntervalMs) return false
        lastMs = now
        return true
    }
}
