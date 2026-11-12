package io.github.fate_grand_automata.runner

import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.entrypoints.AutoLottery
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

class ScriptManagerExitTest {
    @Test
    fun cleanupRunsWhenOnScriptEndThrowsOutOfMemoryError() {
        val calls = mutableListOf<String>()
        var cleanupRan = false

        performScriptExitCaptureAndCleanup(
            e = AutoLottery.ExitException(AutoLottery.ExitReason.RanOutOfCurrency),
            onScriptEnd = {
                calls.add("onScriptEnd")
                throw OutOfMemoryError("synthetic OOM during onScriptEnd")
            },
            discardRun = {
                calls.add("discardRun")
            },
            cleanup = {
                calls.add("cleanup")
                cleanupRan = true
            }
        )

        assertTrue(cleanupRan)
        assertEquals(listOf("onScriptEnd", "discardRun", "cleanup"), calls)
    }

    @Test
    fun cleanupRunsWhenDiscardRunThrowsException() {
        val calls = mutableListOf<String>()
        var cleanupRan = false

        performScriptExitCaptureAndCleanup(
            e = AutoLottery.ExitException(AutoLottery.ExitReason.RanOutOfCurrency),
            onScriptEnd = {
                calls.add("onScriptEnd")
            },
            discardRun = {
                calls.add("discardRun")
                throw RuntimeException("synthetic failure during discardRun")
            },
            cleanup = {
                calls.add("cleanup")
                cleanupRan = true
            }
        )

        assertTrue(cleanupRan)
        assertEquals(listOf("onScriptEnd", "discardRun", "cleanup"), calls)
    }

    @Test
    fun battleExitDoesNotCallDiscardRunAndRunsCleanup() {
        val calls = mutableListOf<String>()

        val exitException = AutoBattle.ExitException(
            reason = AutoBattle.ExitReason.Abort,
            state = AutoBattle.ExitState(
                timesRan = 1,
                runLimit = null,
                timesRefilled = 0,
                refillLimit = 0,
                ceDropCount = 0,
                materials = emptyMap(),
                totalTime = Duration.ZERO,
                averageTimePerRun = Duration.ZERO,
                minTurnsPerRun = 1,
                averageTurnsPerRun = 1.0,
                maxTurnsPerRun = 1,
            )
        )

        performScriptExitCaptureAndCleanup(
            e = exitException,
            onScriptEnd = { calls.add("onScriptEnd") },
            discardRun = { calls.add("discardRun") },
            cleanup = { calls.add("cleanup") }
        )

        assertEquals(listOf("onScriptEnd", "cleanup"), calls)
    }
}
