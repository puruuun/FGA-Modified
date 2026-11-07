package io.github.fate_grand_automata.capture

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
}
