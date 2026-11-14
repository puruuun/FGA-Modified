package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.prefs.core.CaptureConnectionMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureProxyLogicTest {
    @Test
    fun captureControllerDispatchesByConnectionMode() {
        assertEquals(CaptureServiceTarget.VpnService, captureConsentMode(CaptureConnectionMode.Vpn))
        assertEquals(CaptureServiceTarget.ProxyService, captureConsentMode(CaptureConnectionMode.AdGuard))

        assertEquals(CaptureStartKind.StartVpn, captureStartDispatch(CaptureConnectionMode.Vpn))
        assertEquals(CaptureStartKind.StartProxyReading, captureStartDispatch(CaptureConnectionMode.AdGuard))

        assertEquals(CaptureStopKind.StopVpn, captureStopDispatch(CaptureConnectionMode.Vpn))
        assertEquals(CaptureStopKind.StopProxyReading, captureStopDispatch(CaptureConnectionMode.AdGuard))
    }

    @Test
    fun shouldEnsureCaptureProxyRequiresAllFourConditions() {
        assertTrue(
            shouldEnsureCaptureProxy(
                supported = true,
                enabled = true,
                consentAgreed = true,
                mode = CaptureConnectionMode.AdGuard,
            )
        )
        assertFalse(shouldEnsureCaptureProxy(supported = false, enabled = true, consentAgreed = true, mode = CaptureConnectionMode.AdGuard))
        assertFalse(shouldEnsureCaptureProxy(supported = true, enabled = false, consentAgreed = true, mode = CaptureConnectionMode.AdGuard))
        assertFalse(shouldEnsureCaptureProxy(supported = true, enabled = true, consentAgreed = false, mode = CaptureConnectionMode.AdGuard))
        assertFalse(shouldEnsureCaptureProxy(supported = true, enabled = true, consentAgreed = true, mode = CaptureConnectionMode.Vpn))
    }

    @Test
    fun proxyServerHostPortAndIpLiteralDetection() {
        assertEquals("::1" to 443, ProxyServer.parseHostPort("[::1]:443", stripBrackets = true))
        assertEquals("2001:db8::1" to 8443, ProxyServer.parseHostPort("[2001:db8::1]:8443", stripBrackets = true))
        assertEquals("game.fate-go.jp" to 443, ProxyServer.parseHostPort("game.fate-go.jp:443", stripBrackets = true))

        assertTrue(ProxyServer.isIpLiteral("127.0.0.1"))
        assertTrue(ProxyServer.isIpLiteral("::1"))
        assertTrue(ProxyServer.isIpLiteral("2001:db8::1"))
        assertFalse(ProxyServer.isIpLiteral("game.fate-go.jp"))
        assertFalse(ProxyServer.isIpLiteral("999.0.0.1"))
        assertFalse(ProxyServer.isIpLiteral(""))
    }

    @Test
    fun captureProxyStatusTracksAndResetsState() {
        CaptureProxyStatus.reset()
        assertEquals(CaptureProxyState.Idle, CaptureProxyStatus.state.value.state)
        assertFalse(CaptureProxyStatus.state.value.reading)

        CaptureProxyStatus.setListening(8899, reading = false)
        assertEquals(CaptureProxyState.Listening(8899), CaptureProxyStatus.state.value.state)
        assertFalse(CaptureProxyStatus.state.value.reading)

        CaptureProxyStatus.setReading(true)
        assertTrue(CaptureProxyStatus.state.value.reading)

        CaptureProxyStatus.recordGameHostConnected(123456L)
        CaptureProxyStatus.incrementUpstreamFailure()
        assertEquals(123456L, CaptureProxyStatus.state.value.lastGameHostConnectedAt)
        assertEquals(1, CaptureProxyStatus.state.value.upstreamFailureCount)

        CaptureProxyStatus.setPortError(8899)
        assertEquals(CaptureProxyState.PortError(8899), CaptureProxyStatus.state.value.state)
        assertFalse(CaptureProxyStatus.state.value.reading)

        CaptureProxyStatus.reset()
        assertEquals(CaptureProxyState.Idle, CaptureProxyStatus.state.value.state)
        assertEquals(null, CaptureProxyStatus.state.value.lastGameHostConnectedAt)
        assertEquals(0, CaptureProxyStatus.state.value.upstreamFailureCount)
    }
}
