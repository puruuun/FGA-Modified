package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.prefs.core.CaptureConnectionMode
import io.github.fate_grand_automata.scripts.capture.CaptureNames
import kotlin.test.*

class CaptureRoutingTest {
    private val kr = CaptureVpnService.GAME_PACKAGE
    private val jp = CaptureVpnService.JP_GAME_PACKAGE

    @Test fun krOnly() = assertEquals(listOf(kr), allowedGamePackages(setOf(kr), true))
    @Test fun bothInstalledAndEnabled() = assertEquals(listOf(kr, jp), allowedGamePackages(setOf(kr, jp), true))
    @Test fun jpOnlyEnabled() = assertEquals(listOf(jp), allowedGamePackages(setOf(jp), true))
    @Test fun jpSwitchOff() {
        assertEquals(listOf(kr), allowedGamePackages(setOf(kr, jp), false))
        assertTrue(allowedGamePackages(setOf(jp), false).isEmpty())
    }
    @Test fun noInstalledGames() = assertTrue(allowedGamePackages(emptySet(), true).isEmpty())

    @Test fun eachHostUsesItsOwnContextAndOthersTunnel() {
        val krContext = Any()
        val jpContext = Any()
        val contexts = captureServerContexts(krContext, jpContext, true)
        assertSame(krContext, contexts[CaptureNames.GAME_HOST])
        assertSame(jpContext, contexts[CaptureNames.JP_HOST])
        assertNull(contexts["example.org"])
    }
    @Test fun disabledJpAndMissingCertificatesTunnel() {
        assertNull(captureServerContexts(Any(), Any(), false)[CaptureNames.JP_HOST])
        assertNull(captureServerContexts<Any>(null, Any(), true)[CaptureNames.GAME_HOST])
        assertNull(captureServerContexts<Any>(Any(), null, true)[CaptureNames.JP_HOST])
        assertTrue(captureServerContexts<Any>(null, null, true).isEmpty())
    }

    @Test fun jpOnlyNeedsNoKrCertificate() {
        assertTrue(CaptureReadiness(false, false, true, true, true).ready)
    }
    @Test fun krOnlyKeepsWorking() {
        assertTrue(CaptureReadiness(true, true, false, false, false).ready)
    }
    @Test fun eachJpRequirementAndAnInstalledKrGameAreRequired() {
        assertFalse(CaptureReadiness(false, true, false, true, true).ready)
        assertFalse(CaptureReadiness(false, false, true, false, true).ready)
        assertFalse(CaptureReadiness(false, false, true, true, false).ready)
        assertFalse(CaptureReadiness(true, false, true, true, false).ready)
    }

    @Test fun rootTargetUidsFollowsB3CertificateContextConditions() {
        assertEquals(
            listOf(10123),
            rootTargetUids(krUid = 10123, krCertificate = true, jpUid = null, jpEnabled = false, jpCertificate = false),
        )
        assertEquals(
            listOf(10456),
            rootTargetUids(krUid = null, krCertificate = false, jpUid = 10456, jpEnabled = true, jpCertificate = true),
        )
        assertEquals(
            listOf(10123, 10456),
            rootTargetUids(krUid = 10123, krCertificate = true, jpUid = 10456, jpEnabled = true, jpCertificate = true),
        )
        assertEquals(
            emptyList(),
            rootTargetUids(krUid = 10123, krCertificate = false, jpUid = 10456, jpEnabled = true, jpCertificate = false),
        )
        assertEquals(
            listOf(10123),
            rootTargetUids(krUid = 10123, krCertificate = true, jpUid = 10456, jpEnabled = false, jpCertificate = true),
        )
    }

    @Test fun rootModeEnumParseAndControllerDispatch() {
        assertEquals(CaptureConnectionMode.Root, CaptureConnectionMode.parse("Root"))
        assertEquals(CaptureConnectionMode.AdGuard, CaptureConnectionMode.parse("AdGuard"))
        assertEquals(CaptureConnectionMode.Vpn, CaptureConnectionMode.parse("Vpn"))
        assertEquals(CaptureConnectionMode.Vpn, CaptureConnectionMode.parse("Unknown"))
        assertEquals(CaptureConnectionMode.Vpn, CaptureConnectionMode.parse(null))

        assertEquals(CaptureServiceTarget.ProxyService, captureConsentMode(CaptureConnectionMode.Root))
        assertEquals(CaptureStartKind.StartProxyReading, captureStartDispatch(CaptureConnectionMode.Root))
        assertEquals(CaptureStopKind.StopProxyReading, captureStopDispatch(CaptureConnectionMode.Root))
        assertFalse(
            shouldEnsureCaptureProxy(
                supported = true,
                enabled = true,
                consentAgreed = true,
                mode = CaptureConnectionMode.Root,
            )
        )
        assertTrue(
            shouldEnsureCaptureProxy(
                supported = true,
                enabled = true,
                consentAgreed = true,
                mode = CaptureConnectionMode.AdGuard,
            )
        )
    }
}
