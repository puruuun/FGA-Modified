package io.github.fate_grand_automata.capture

import android.content.Context
import io.github.fate_grand_automata.scripts.capture.CaptureNames
import io.github.fate_grand_automata.scripts.capture.SingleUseCa
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Holds the leaf key and the public CA certificate under `filesDir/capture/` (app-private). The exported copy goes to `<FGA folder>/capture/`. The CA private key is
 * never stored: [SingleUseCa] signs one leaf and drops it.
 */
object CertStore {
    const val EXPORT_NAME = "fga-capture-ca.crt"
    const val EXPORT_MIME = "application/x-x509-ca-cert"
    private const val LEAF = "leaf.p12"
    private const val CA = "ca.cer"
    // The file lives in app-private storage; the password only keeps the keystore format happy.
    private val PASSWORD = "fga-capture".toCharArray()

    fun directory(ctx: Context) = File(ctx.filesDir, "capture").apply { mkdirs() }

    @Synchronized
    fun exists(ctx: Context) = pairFiles(ctx).recover()

    fun existsJp(ctx: Context) = File(directory(ctx), "leaf-jp.p12").isFile && File(directory(ctx), "ca-jp.cer").isFile

    @Synchronized
    fun ensureJp(ctx: Context) { if (!existsJp(ctx)) createJp(ctx) }

    /** Separate from KR; this CA stays private and is installed only through root. */
    @Synchronized
    fun createJp(ctx: Context) {
        val issued = SingleUseCa.issue(CaptureNames.JP_HOST)
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry("leaf", issued.leafKey, PASSWORD, issued.chain.toTypedArray())
        val dir = directory(ctx)
        File(dir, "leaf-jp.p12").outputStream().use { ks.store(it, PASSWORD) }
        File(dir, "ca-jp.cer").writeBytes(issued.caCert.encoded)
    }

    fun jpCertificate(ctx: Context): X509Certificate = File(directory(ctx), "ca-jp.cer").inputStream().use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }

    fun jpServerContext(ctx: Context): SSLContext {
        val ks = KeyStore.getInstance("PKCS12")
        File(directory(ctx), "leaf-jp.p12").inputStream().use { ks.load(it, PASSWORD) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, PASSWORD) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    /** OpenSSL -subject_hash_old: MD5 of the original DER name, first uint32 little endian. */
    fun subjectHashOld(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("MD5").digest(cert.subjectX500Principal.encoded)
        return digest.take(4).reversed().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun pem(cert: X509Certificate): String = "-----BEGIN CERTIFICATE-----\n" +
        Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(cert.encoded) + "\n-----END CERTIFICATE-----\n"

    /** A new CA and leaf held in memory. Nothing replaces the stored ones until [commit]. */
    class Pending(internal val leaf: ByteArray, val caDer: ByteArray)

    fun prepare(): Pending {
        val issued = SingleUseCa.issue(CaptureNames.GAME_HOST)
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry("leaf", issued.leafKey, PASSWORD, issued.chain.toTypedArray())
        val leaf = java.io.ByteArrayOutputStream().also { ks.store(it, PASSWORD) }.toByteArray()
        return Pending(leaf, issued.caCert.encoded)
    }

    private fun pairFiles(ctx: Context) = CertificatePairFiles(directory(ctx), ::validPair)

    private fun validPair(leaf: File, ca: File): Boolean = runCatching {
        val ks = KeyStore.getInstance("PKCS12")
        leaf.inputStream().use { ks.load(it, PASSWORD) }
        val cert = ca.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
        val chain = ks.getCertificateChain("leaf")
        check(chain.size >= 2 && chain.last().encoded.contentEquals(cert.encoded))
        (chain.first() as X509Certificate).verify(cert.publicKey)
        true
    }.getOrDefault(false)

    /** Backups stay complete until both same-directory temporary files have been renamed. */
    @Synchronized
    fun commit(ctx: Context, pending: Pending) = pairFiles(ctx).commit(pending.leaf, pending.caDer)

    /** A recovered, matching CA/leaf pair only; never expose half a transaction. */
    @Synchronized
    fun caBytes(ctx: Context): ByteArray? =
        if (pairFiles(ctx).recover()) File(directory(ctx), CA).readBytes() else null

    /** Saves the CA to `<FGA folder>/capture/fga-capture-ca.crt` (three layers, see [CaptureFileSaver]). */
    fun save(folder: CaptureFolder?, der: ByteArray, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): SaveOutcome =
        CaptureFileSaver.save(
            folder, EXPORT_NAME, CaptureFileSaver.timestamped(EXPORT_NAME, now), EXPORT_MIME, der,
        )

    /** Saves the existing CA again. The CA itself is never touched. Null when there is none. */
    fun export(ctx: Context, folder: CaptureFolder? = CaptureFolders.open(ctx)): SaveOutcome? =
        caBytes(ctx)?.let { save(folder, it) }

    /**
     * Makes a new CA and leaf and saves the CA. The stored pair is replaced only once the export
     * succeeded; for [SaveOutcome.NeedsDialog] the caller commits [pending] after the user saved the file.
     */
    fun createAndExport(ctx: Context, folder: CaptureFolder? = CaptureFolders.open(ctx)): Pair<Pending, SaveOutcome> {
        val pending = prepare()
        val outcome = save(folder, pending.caDer)
        if (outcome is SaveOutcome.Saved) commit(ctx, pending)
        return pending to outcome
    }

    private fun ownCertificate(ctx: Context): X509Certificate? = runCatching {
        (caBytes(ctx) ?: return null).inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    }.getOrNull()

    /** True when a user-installed CA in `AndroidCAStore` equals ours. */
    fun isInstalled(ctx: Context): Boolean {
        val ours = ownCertificate(ctx) ?: return false
        return runCatching {
            val store = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
            store.aliases().asSequence().filter { it.startsWith("user:") }
                .any { store.getCertificate(it)?.encoded?.contentEquals(ours.encoded) == true }
        }.getOrDefault(false)
    }

    @Synchronized
    fun serverContext(ctx: Context): SSLContext {
        check(pairFiles(ctx).recover()) { "No complete certificate pair" }
        val ks = KeyStore.getInstance("PKCS12")
        File(directory(ctx), LEAF).inputStream().use { ks.load(it, PASSWORD) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, PASSWORD)
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }
}
