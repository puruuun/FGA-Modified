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
    val rootLastError: String? = null,
    val lastRefreshAtMs: Long? = null,
)

/** Single source of truth for [CaptureProxyService] runtime state and connection counters. */
object CaptureProxyStatus {
    private val _state = MutableStateFlow(CaptureProxySnapshot())
    val state: StateFlow<CaptureProxySnapshot> = _state.asStateFlow()

    fun reset() {
        _state.value = CaptureProxySnapshot()
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
                rootLastError = if (preserveError) it.rootLastError else null,
            )
        }
    }

    fun setRootCleanupRetrying(retrying: Boolean, errorMessage: String? = null) {
        _state.update {
            it.copy(
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
