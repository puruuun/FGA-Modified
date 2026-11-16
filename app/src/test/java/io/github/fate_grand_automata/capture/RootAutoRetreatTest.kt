package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.CaptureNames
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RootAutoRetreatTest {
    private val gameHosts = CaptureNames.HOST_SERVERS.keys

    @Test
    fun fiveGameTlsHandshakeFailuresWithin30SecondsWithZeroSuccessesTriggersRetreat() {
        var now = 1_000L
        val retreat = RootAutoRetreat { now }

        repeat(4) { i ->
            now = 1_000L + i * 5_000L
            assertNull(retreat.onTlsFailure(CaptureNames.GAME_HOST, gameHosts))
        }
        now = 25_000L
        assertEquals(
            RootAutoRetreat.Reason.TlsHandshakeFailures,
            retreat.onTlsFailure(CaptureNames.GAME_HOST, gameHosts),
        )
    }

    @Test
    fun tlsSuccessResetsFailureWindowSoSubsequentFailuresBelowFiveDoNotRetreat() {
        var now = 1_000L
        val retreat = RootAutoRetreat { now }

        repeat(4) { i ->
            now = 1_000L + i * 2_000L
            assertNull(retreat.recordGameTlsFailure())
        }
        now = 10_000L
        retreat.recordGameTlsSuccess()

        repeat(4) { i ->
            now = 12_000L + i * 2_000L
            assertNull(retreat.recordGameTlsFailure())
        }
    }

    @Test
    fun tlsFailuresOlderThan30SecondsExpireWithoutRetreat() {
        var now = 0L
        val retreat = RootAutoRetreat { now }

        repeat(4) { i ->
            now = i * 5_000L
            assertNull(retreat.recordGameTlsFailure())
        }
        // Advance past 30 s from the first failure (at 0 ms)
        now = 31_000L
        assertNull(retreat.recordGameTlsFailure())
    }

    @Test
    fun fiveConsecutiveGameUpstreamFailuresTriggersRetreatAndSuccessResetsStreak() {
        val retreat = RootAutoRetreat()

        repeat(4) {
            assertNull(retreat.onUpstreamFailure(CaptureNames.JP_HOST, gameHosts))
        }
        retreat.onUpstreamSuccess(CaptureNames.JP_HOST, gameHosts)

        repeat(4) {
            assertNull(retreat.onUpstreamFailure(CaptureNames.JP_HOST, gameHosts))
        }
        assertEquals(
            RootAutoRetreat.Reason.UpstreamFailures,
            retreat.onUpstreamFailure(CaptureNames.JP_HOST, gameHosts),
        )
    }

    @Test
    fun nonGameBlindTunnelFailuresAreCountedOnlyAndNeverTriggerRetreat() {
        val retreat = RootAutoRetreat()

        repeat(20) {
            assertNull(retreat.onUpstreamFailure("login.netmarble.com", gameHosts))
            assertNull(retreat.onTlsFailure("webview.fate-go.jp", gameHosts))
        }
        assertEquals(20, retreat.nonGameUpstreamFailureCount())
    }
}
