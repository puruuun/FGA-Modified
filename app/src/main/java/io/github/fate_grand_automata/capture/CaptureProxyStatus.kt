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
        _state.update { it.copy(lastGameHostConnectedAt = timestampMs) }
    }

    fun incrementUpstreamFailure() {
        _state.update { it.copy(upstreamFailureCount = it.upstreamFailureCount + 1) }
    }
}
