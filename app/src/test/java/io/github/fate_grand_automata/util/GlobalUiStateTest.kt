package io.github.fate_grand_automata.util

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import io.github.fate_grand_automata.accessibility.TapperService
import io.github.fate_grand_automata.runner.ScriptRunnerService
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class GlobalUiStateTest {
    @Test
    fun `reading state created in global snapshot after snapshot was taken throws`() {
        val snapshot = Snapshot.takeSnapshot()
        try {
            val state = mutableStateOf(false)
            assertFailsWith<IllegalStateException> {
                snapshot.enter { state.value }
            }
        } finally {
            snapshot.dispose()
        }
    }

    @Test
    fun `initGlobalUiState creates service states readable from later snapshots`() {
        initGlobalUiState()
        val snapshot = Snapshot.takeSnapshot()
        try {
            val (scriptStarted, tapperStarted) = snapshot.enter {
                ScriptRunnerService.serviceStarted.value to TapperService.serviceStarted.value
            }
            assertFalse(scriptStarted)
            assertFalse(tapperStarted)
        } finally {
            snapshot.dispose()
        }
    }
}
