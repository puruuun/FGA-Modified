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
    fun leavingRootModeIsRefusedWhileTheCleanupRetries() {
        assertFalse(canLeaveRootMode(rootRun.copy(rootCleanupRetrying = true)))
        assertTrue(canLeaveRootMode(rootRun.copy(rootCleanupRetrying = false)))
    }

    @Test
    fun rootTransportIsGoneOnlyWhenServiceRulesAndRetryAreAllGone() {
        val gone = rootRun.copy(rootRulesActive = false, rootCleanupRetrying = false)

        assertTrue(rootTransportGone(gone, serviceAlive = false))
        assertFalse(rootTransportGone(gone, serviceAlive = true))
        assertFalse(rootTransportGone(gone.copy(rootRulesActive = true), serviceAlive = false))
        assertFalse(rootTransportGone(gone.copy(rootCleanupRetrying = true), serviceAlive = false))
    }

    @Test
    fun anOldServiceInstanceIsNotLiveOnceANewerOneClaimedTheGate() {
        val gate = InstanceGate()
        val oldRoot = Any()
        val newExternal = Any()
        gate.claim(oldRoot)
        assertTrue(gate.isLive(oldRoot))

        gate.claim(newExternal)

        // A callback posted by the destroyed root service no longer applies, and its release leaves the new one alone.
        assertFalse(gate.isLive(oldRoot))
        assertFalse(gate.release(oldRoot))
        assertTrue(gate.isLive(newExternal))
        assertTrue(gate.release(newExternal))
        assertFalse(gate.isLive(newExternal))
    }

    @Test
    fun rulesRemovedMarksReadingOffDrainingAndClearsTheRuleCounts() {
        CaptureProxyStatus.reset()
        CaptureProxyStatus.setRootApplied(8899, 2, 2, false, watchdogActive = true)
        CaptureProxyStatus.setRootRulesRemoved()

        val snap = CaptureProxyStatus.state.value
        assertFalse(snap.reading)
        assertFalse(snap.rootRulesActive)
        assertFalse(snap.rootWatchdogActive)
        assertTrue(snap.rootDraining)
        assertEquals(0, snap.rootIpv4RuleCount)
        assertEquals(0, snap.rootIpv6RuleCount)
        CaptureProxyStatus.reset()
    }

    @Test
    fun resetNonGameUpstreamFailuresZeroesOnlyThatCounterAndPreservesAllOtherFields() {
        CaptureProxyStatus.reset()
        try {
            CaptureProxyStatus.setRootApplied(8899, 2, 2, false, watchdogActive = true)
            CaptureProxyStatus.recordGameHostConnected(5678L)
            CaptureProxyStatus.incrementUpstreamFailure()
            CaptureProxyStatus.incrementUnexpectedConnection()
            CaptureProxyStatus.incrementUnexpectedConnection()
            repeat(4) { CaptureProxyStatus.incrementNonGameUpstreamFailure() }
            CaptureProxyStatus.setRootError("auto retreat")

            val before = CaptureProxyStatus.state.value
            assertEquals(4, before.nonGameUpstreamFailureCount)

            CaptureProxyStatus.resetNonGameUpstreamFailures()

            val after = CaptureProxyStatus.state.value
            assertEquals(before.copy(nonGameUpstreamFailureCount = 0), after)
            assertEquals(0, after.nonGameUpstreamFailureCount)
            assertEquals(2, after.unexpectedConnectionCount)
            assertEquals(1, after.gameConnectionsSinceApply)
            assertEquals(1, after.upstreamFailureCount)
            assertEquals(5678L, after.lastGameHostConnectedAt)
            assertEquals("auto retreat", after.rootLastError)
        } finally {
            CaptureProxyStatus.reset()
        }
    }
}
