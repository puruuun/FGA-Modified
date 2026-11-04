package io.github.fate_grand_automata.scripts.capture

import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class SingleUseCaTest {
    private val host = "yfgo-game.netmarble.com"
    private val now = Instant.parse("2026-11-04T00:00:00Z")
    private val issued = SingleUseCa.issue(host, now)
    private val ca = issued.caCert
    private val leaf = issued.chain[0]

    @Test
    fun leafChainsToCa() {
        assertEquals(listOf(leaf, ca), issued.chain)
        leaf.verify(ca.publicKey)
        ca.verify(ca.publicKey)
        assertEquals(ca.subjectX500Principal, leaf.issuerX500Principal)
        leaf.checkValidity(java.util.Date.from(now))
    }

    @Test
    fun leafHasSanAndExtensions() {
        val san = leaf.subjectAlternativeNames.map { it[0] as Int to it[1] as String }
        assertEquals(listOf(2 to host), san)
        assertTrue(leaf.subjectX500Principal.name.contains("CN=$host"))
        assertEquals(-1, leaf.basicConstraints)
        assertEquals(listOf("1.3.6.1.5.5.7.3.1"), leaf.extendedKeyUsage)
        assertTrue(leaf.keyUsage[0] && leaf.keyUsage[2] && !leaf.keyUsage[5])
        assertTrue(leaf.criticalExtensionOIDs.containsAll(listOf("2.5.29.19", "2.5.29.15")))
        assertNotNull(leaf.getExtensionValue("2.5.29.35"))
        assertNotNull(leaf.getExtensionValue("2.5.29.14"))
    }

    @Test
    fun caShapeAndNoNameConstraints() {
        assertEquals(0, ca.basicConstraints)
        assertTrue(ca.keyUsage[5] && ca.keyUsage[6] && !ca.keyUsage[0])
        assertNull(ca.getExtensionValue("2.5.29.30"))
        val dn = ca.subjectX500Principal.name
        assertTrue(dn.contains("CN=FGA single-use capture CA 2026-11-04"), dn)
        assertTrue(dn.contains("O=FGA-Modified gains tracker"), dn)
        assertTrue(ca.criticalExtensionOIDs.containsAll(listOf("2.5.29.19", "2.5.29.15")))
    }

    @Test
    fun validityIsAboutFiveYears() {
        val days = (leaf.notAfter.time - leaf.notBefore.time) / 86_400_000.0
        assertTrue(days in 365.0 * 5 + 1 - 2..365.0 * 5 + 3, "days=$days")
        assertEquals(now.minusSeconds(86_400).epochSecond, leaf.notBefore.time / 1000)
        assertEquals(leaf.notAfter, ca.notAfter)
    }

    @Test
    fun noCaPrivateKeyIsExposed() {
        val privateKeyFields = IssuedCerts::class.java.declaredFields.filter { PrivateKey::class.java.isAssignableFrom(it.type) }
        assertEquals(listOf("leafKey"), privateKeyFields.map { it.name })
        assertTrue(IssuedCerts::class.java.declaredFields.none { it.type == java.security.KeyPair::class.java })
        // the exposed key is the leaf's, not the CA's
        val sig = java.security.Signature.getInstance("SHA256withRSA")
        sig.initSign(issued.leafKey); sig.update(byteArrayOf(1))
        val s = sig.sign()
        val v = java.security.Signature.getInstance("SHA256withRSA")
        v.initVerify((leaf as X509Certificate).publicKey); v.update(byteArrayOf(1))
        assertTrue(v.verify(s))
    }
}