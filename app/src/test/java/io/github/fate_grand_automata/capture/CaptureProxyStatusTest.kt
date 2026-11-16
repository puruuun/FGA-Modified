package io.github.fate_grand_automata.capture

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureProxyStatusTest {
    private val rootRun = CaptureProxySnapshot(
        state = CaptureProxyState.Listening(8899),
        reading = true,
        lastGameHostConnectedAt = 1234L,
        upstreamFailureCount = 3,
        rootRulesActive = true,
        rootIpv4RuleCount = 1,
        rootIpv6RuleCount = 1,
        rootWatchdogActive = true,
        gameConnectionsSinceApply = 7,
        unexpectedConnectionCount = 2,
        nonGameUpstreamFailureCount = 4,
        rootCleanupRetrying = true,
        rootDraining = true,
        rootLastError = "auto retreat",
    )

    @Test
    fun rootServiceDestroyKeepsErrorAndCountersAndClearsLiveState() {
        val after = postDestroySnapshot(rootRun, ranAsRoot = true)

        assertEquals("auto retreat", after.rootLastError)
        assertEquals(2, after.unexpectedConnectionCount)
        assertEquals(4, after.nonGameUpstreamFailureCount)
        assertEquals(3, after.upstreamFailureCount)
        assertEquals(1234L, after.lastGameHostConnectedAt)
        assertFalse(after.reading)
        assertFalse(after.rootRulesActive)
        assertFalse(after.rootWatchdogActive)
        assertFalse(after.rootDraining)
        assertFalse(after.rootCleanupRetrying)
        assertEquals(0, after.rootIpv4RuleCount)
        assertEquals(CaptureProxyState.Idle, after.state)
    }

    @Test
    fun rootServiceDestroyKeepsThePortError() {
        val portError = rootRun.copy(state = CaptureProxyState.PortError(8899), reading = false)

        assertEquals(CaptureProxyState.PortError(8899), postDestroySnapshot(portError, ranAsRoot = true).state)
    }

    @Test
    fun externalProxyServiceDestroyStillResetsEverything() {
        assertEquals(CaptureProxySnapshot(), postDestroySnapshot(rootRun, ranAsRoot = false))
        assertNull(postDestroySnapshot(rootRun, ranAsRoot = false).rootLastError)
    }

    @Test
    fun rootShutdownIsSettledOnlyWhileDrainingWithRulesGoneAndReadingOff() {
        val draining = rootRun.copy(reading = false, rootRulesActive = false, rootCleanupRetrying = false)

        assertTrue(rootShutdownSettled(draining))
        assertFalse(rootShutdownSettled(draining.copy(rootRulesActive = true)))
        assertFalse(rootShutdownSettled(draining.copy(reading = true)))
        assertFalse(rootShutdownSettled(draining.copy(rootCleanupRetrying = true)))
        assertFalse(rootShutdownSettled(draining.copy(rootDraining = false)))
    }

    @Test
    fun rulesRemovedMarksReadingOffAndDraining() {
        CaptureProxyStatus.reset()
        CaptureProxyStatus.setRootApplied(8899, 1, 0, true, watchdogActive = true)
        CaptureProxyStatus.setRootRulesRemoved()

        val snap = CaptureProxyStatus.state.value
        assertFalse(snap.reading)
        assertFalse(snap.rootRulesActive)
        assertFalse(snap.rootWatchdogActive)
        assertTrue(snap.rootDraining)
        assertTrue(rootShutdownSettled(snap))
        CaptureProxyStatus.reset()
    }
}
