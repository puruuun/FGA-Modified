package io.github.fate_grand_automata.capture

import androidx.annotation.StringRes
import io.github.fate_grand_automata.R

/**
 * Pure helper for B5 automatic retreat in Root capture mode.
 *
 * Counts only game-host client TLS handshake failures and game-host upstream failures.
 * Non-game blind-tunnel failures are counted for status display only and never trigger retreat.
 *
 * Retreat triggers when:
 * - Within [WINDOW_MS] (30 s), [THRESHOLD] (5) game-host TLS handshake failures occur with 0 successes; or
 * - [THRESHOLD] (5) consecutive game-host upstream connection failures occur.
 */
internal class RootAutoRetreat(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    enum class Reason(@StringRes val messageRes: Int) {
        TlsHandshakeFailures(R.string.capture_root_retreat_reason_tls),
        UpstreamFailures(R.string.capture_root_retreat_reason_upstream),
    }

    private val handshakeFailureTimesMs = ArrayDeque<Long>()
    private var lastHandshakeSuccessMs: Long = Long.MIN_VALUE
    private var consecutiveGameUpstreamFailures: Int = 0
    private var nonGameFailureTotal: Int = 0

    @Synchronized
    fun reset() {
        handshakeFailureTimesMs.clear()
        lastHandshakeSuccessMs = Long.MIN_VALUE
        consecutiveGameUpstreamFailures = 0
        nonGameFailureTotal = 0
    }

    @Synchronized
    fun recordGameTlsSuccess(atMs: Long = nowMs()) {
        lastHandshakeSuccessMs = atMs
        handshakeFailureTimesMs.clear()
    }

    @Synchronized
    fun recordGameTlsFailure(atMs: Long = nowMs()): Reason? {
        val cutoff = atMs - WINDOW_MS
        while (handshakeFailureTimesMs.isNotEmpty() &&
            (handshakeFailureTimesMs.first() < cutoff || handshakeFailureTimesMs.first() <= lastHandshakeSuccessMs)
        ) {
            handshakeFailureTimesMs.removeFirst()
        }
        handshakeFailureTimesMs.addLast(atMs)
        return if (handshakeFailureTimesMs.size >= THRESHOLD) {
            Reason.TlsHandshakeFailures
        } else {
            null
        }
    }

    @Synchronized
    fun recordGameUpstreamSuccess() {
        consecutiveGameUpstreamFailures = 0
    }

    @Synchronized
    fun recordGameUpstreamFailure(): Reason? {
        consecutiveGameUpstreamFailures += 1
        return if (consecutiveGameUpstreamFailures >= THRESHOLD) {
            Reason.UpstreamFailures
        } else {
            null
        }
    }

    @Synchronized
    fun recordNonGameUpstreamFailure(): Int {
        nonGameFailureTotal += 1
        return nonGameFailureTotal
    }

    @Synchronized
    fun nonGameUpstreamFailureCount(): Int = nonGameFailureTotal

    @Synchronized
    fun onTlsFailure(host: String, gameHosts: Set<String>, atMs: Long = nowMs()): Reason? =
        if (host in gameHosts) recordGameTlsFailure(atMs) else null

    @Synchronized
    fun onTlsSuccess(host: String, gameHosts: Set<String>, atMs: Long = nowMs()) {
        if (host in gameHosts) recordGameTlsSuccess(atMs)
    }

    @Synchronized
    fun onUpstreamFailure(host: String, gameHosts: Set<String>): Reason? =
        if (host in gameHosts) {
            recordGameUpstreamFailure()
        } else {
            recordNonGameUpstreamFailure()
            null
        }

    @Synchronized
    fun onUpstreamSuccess(host: String, gameHosts: Set<String>) {
        if (host in gameHosts) recordGameUpstreamSuccess()
    }

    companion object {
        const val WINDOW_MS = 30_000L
        const val THRESHOLD = 5
    }
}
