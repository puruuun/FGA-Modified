package io.github.fate_grand_automata.capture

import android.content.Context
import android.content.ContextWrapper
import android.content.ContentResolver
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.scripts.capture.CaptureNames
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class JpCertificateTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun subjectHashMatchesPinnedOpenSslFixture() {
        // Locally computed: openssl x509 -in subject-hash-old.pem -subject_hash_old -noout
        val cert = javaClass.getResourceAsStream("/capture/subject-hash-old.pem")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        assertEquals("bad93d86", CertStore.subjectHashOld(cert))
        assertTrue(CertStore.pem(cert).startsWith("-----BEGIN CERTIFICATE-----\n"))
    }

    @Test fun jpCertIsSeparateAndNeverExportsOrChangesKrFiles() {
        val dir = CertStore.directory(context)
        val krLeaf = File(dir, "leaf.p12").apply { writeText("unchanged KR leaf") }
        val krCa = File(dir, "ca.cer").apply { writeText("unchanged KR CA") }
        File(dir, "leaf-jp.p12").delete()
        File(dir, "ca-jp.cer").delete()
        assertFalse(CertStore.existsJp(context))
        val privateOnlyContext = object : ContextWrapper(context) {
            override fun getContentResolver(): ContentResolver = error("JP CA must never be exported")
        }
        CertStore.createJp(privateOnlyContext)
        assertTrue(CertStore.existsJp(context))
        assertEquals("unchanged KR leaf", krLeaf.readText())
        assertEquals("unchanged KR CA", krCa.readText())
        val ks = KeyStore.getInstance("PKCS12").apply {
            File(dir, "leaf-jp.p12").inputStream().use { load(it, "fga-capture".toCharArray()) }
        }
        val leaf = ks.getCertificate("leaf") as X509Certificate
        assertEquals(listOf(listOf(2, CaptureNames.JP_HOST)), leaf.subjectAlternativeNames.toList())
        leaf.verify(CertStore.jpCertificate(context).publicKey)
        assertEquals(1, ks.aliases().toList().size)
        assertNotNull(CertStore.jpServerContext(context))
        // The wrapper also fails this test on any attempt to access MediaStore.
        assertEquals(setOf("leaf.p12", "ca.cer", "leaf-jp.p12", "ca-jp.cer"), dir.list()!!.toSet())
    }
}
