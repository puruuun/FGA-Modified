package io.github.fate_grand_automata.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface CaptureProxyState {
    data object Idle : CaptureProxyState
    data class Listening(val port: Int) : CaptureProxyState
    data class PortError(val port: Int) : CaptureProxyState
}

data class CaptureProxySnapshot(
    val state: CaptureProxyState = CaptureProxyState.Idle,
    val reading: Boolean = false,
    val lastGameHostConnectedAt: Long? = null,
    val upstreamFailureCount: Int = 0,
    val rootRulesActive: Boolean = false,
    val rootIpv4RuleCount: Int = 0,
    val rootIpv6RuleCount: Int = 0,
    val rootIpv6Unsupported: Boolean = false,
    val rootWatchdogActive: Boolean = false,
    val gameConnectionsSinceApply: Int = 0,
    val unexpectedConnectionCount: Int = 0,
    val nonGameUpstreamFailureCount: Int = 0,
    val rootCleanupRetrying: Boolean = false,
    /** Root rules are gone and reading is off, but the old listener is still draining open connections. */
    val rootDraining: Boolean = false,
    val rootLastError: String? = null,
    val lastRefreshAtMs: Long? = null,
)

/**
 * Snapshot that stays visible after the proxy service stops (G1).
 *
 * In root mode the service stops itself right after reading is turned off, a root rule apply failed,
 * a port was in use or the auto-retreat fired. The status card must still tell the user what happened,
 * so the last error, the port error and the connection counters survive; only the live state
 * (rules, watchdog, reading, drain, listener) is cleared. External proxy mode keeps the plain reset.
 */
internal fun postDestroySnapshot(current: CaptureProxySnapshot, ranAsRoot: Boolean): CaptureProxySnapshot {
    if (!ranAsRoot) return CaptureProxySnapshot()
    return current.copy(
        state = if (current.state is CaptureProxyState.PortError) current.state else CaptureProxyState.Idle,
        reading = false,
        rootRulesActive = false,
        rootIpv4RuleCount = 0,
        rootIpv6RuleCount = 0,
        rootIpv6Unsupported = false,
        rootWatchdogActive = false,
        gameConnectionsSinceApply = 0,
        rootCleanupRetrying = false,
        rootDraining = false,
    )
}

/** H4: leaving root mode is refused while the rule cleanup is still retrying; the old mode stays. */
internal fun canLeaveRootMode(snap: CaptureProxySnapshot): Boolean = !snap.rootCleanupRetrying

/** H4: the root service is fully gone: service not alive, rules gone and no cleanup retry pending. */
internal fun rootTransportGone(snap: CaptureProxySnapshot, serviceAlive: Boolean): Boolean =
    !serviceAlive && !snap.rootRulesActive && !snap.rootCleanupRetrying

/**
 * H5: tells which service instance is the live one. Posts from an old instance (for example a destroyed root
 * service) check [isLive] on the main thread before they touch the global status or the tracker.
 */
internal class InstanceGate {
    @Volatile
    private var current: Any? = null

    fun claim(instance: Any) {
        current = instance
    }

    fun isLive(instance: Any): Boolean = current === instance

    /** Returns true when [instance] was the live one; a newer instance is left alone. */
    fun release(instance: Any): Boolean {
        if (current !== instance) return false
        current = null
        return true
    }
}

/** Single source of truth for [CaptureProxyService] runtime state and connection counters. */
object CaptureProxyStatus {
    private val _state = MutableStateFlow(CaptureProxySnapshot())
    val state: StateFlow<CaptureProxySnapshot> = _state.asStateFlow()

    fun reset() {
        _state.value = CaptureProxySnapshot()
    }

    fun onServiceDestroyed(ranAsRoot: Boolean) {
        _state.update { postDestroySnapshot(it, ranAsRoot) }
    }

    /** Rules are gone and reading is off; the listener drains in the background (G2). */
    fun setRootRulesRemoved() {
        _state.update {
            it.copy(
                reading = false,
                rootRulesActive = false,
                rootIpv4RuleCount = 0,
                rootIpv6RuleCount = 0,
                rootIpv6Unsupported = false,
                rootWatchdogActive = false,
                rootDraining = true,
                rootCleanupRetrying = false,
            )
        }
    }

    fun setListening(port: Int, reading: Boolean) {
        _state.update { it.copy(state = CaptureProxyState.Listening(port), reading = reading) }
    }

    fun setPortError(port: Int) {
        _state.update { it.copy(state = CaptureProxyState.PortError(port), reading = false) }
    }

    fun setReading(reading: Boolean) {
        _state.update { it.copy(reading = reading) }
    }

    fun recordGameHostConnected(timestampMs: Long) {
        _state.update {
            it.copy(
                lastGameHostConnectedAt = timestampMs,
                gameConnectionsSinceApply = if (it.rootRulesActive) it.gameConnectionsSinceApply + 1 else it.gameConnectionsSinceApply,
            )
        }
    }

    fun incrementUpstreamFailure() {
        _state.update { it.copy(upstreamFailureCount = it.upstreamFailureCount + 1) }
    }

    fun incrementUnexpectedConnection() {
        _state.update { it.copy(unexpectedConnectionCount = it.unexpectedConnectionCount + 1) }
    }

    fun incrementNonGameUpstreamFailure() {
        _state.update { it.copy(nonGameUpstreamFailureCount = it.nonGameUpstreamFailureCount + 1) }
    }

    fun setRootApplied(
        port: Int,
        ipv4Rules: Int,
        ipv6Rules: Int,
        ipv6Unsupported: Boolean,
        watchdogActive: Boolean,
        resetGameConnections: Boolean = false,
    ) {
        _state.update {
            it.copy(
                state = CaptureProxyState.Listening(port),
                reading = true,
                rootRulesActive = true,
                rootIpv4RuleCount = ipv4Rules,
                rootIpv6RuleCount = ipv6Rules,
                rootIpv6Unsupported = ipv6Unsupported,
                rootWatchdogActive = watchdogActive,
                gameConnectionsSinceApply = if (resetGameConnections) 0 else it.gameConnectionsSinceApply,
                rootCleanupRetrying = false,
                rootDraining = false,
                rootLastError = null,
                lastRefreshAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun setRootCleanedUp(preserveError: Boolean = true) {
        _state.update {
            it.copy(
                state = CaptureProxyState.Idle,
                reading = false,
                rootRulesActive = false,
                rootIpv4RuleCount = 0,
                rootIpv6RuleCount = 0,
                rootIpv6Unsupported = false,
                rootWatchdogActive = false,
                gameConnectionsSinceApply = 0,
                rootCleanupRetrying = false,
                rootDraining = false,
                rootLastError = if (preserveError) it.rootLastError else null,
            )
        }
    }

    fun setRootCleanupRetrying(retrying: Boolean, errorMessage: String? = null) {
        _state.update {
            it.copy(
                reading = if (retrying) false else it.reading,
                rootCleanupRetrying = retrying,
                rootLastError = errorMessage ?: it.rootLastError,
            )
        }
    }

    fun setRootError(errorMessage: String?) {
        _state.update { it.copy(rootLastError = errorMessage) }
    }

    fun touchRefresh(timestampMs: Long = System.currentTimeMillis()) {
        _state.update { it.copy(lastRefreshAtMs = timestampMs) }
    }
}
