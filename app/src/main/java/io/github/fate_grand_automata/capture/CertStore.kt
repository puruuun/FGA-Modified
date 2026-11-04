package io.github.fate_grand_automata.capture

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import io.github.fate_grand_automata.scripts.capture.CaptureNames
import io.github.fate_grand_automata.scripts.capture.SingleUseCa
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Holds the leaf key and the public CA certificate under `filesDir/capture/`. The CA private key is
 * never stored: [SingleUseCa] signs one leaf and drops it.
 */
object CertStore {
    const val DOWNLOAD_NAME = "fga-capture-ca.cer"
    private const val LEAF = "leaf.p12"
    private const val CA = "ca.cer"
    // The file lives in app-private storage; the password only keeps the keystore format happy.
    private val PASSWORD = "fga-capture".toCharArray()

    fun directory(ctx: Context) = File(ctx.filesDir, "capture").apply { mkdirs() }

    fun exists(ctx: Context) = File(directory(ctx), LEAF).isFile && File(directory(ctx), CA).isFile

    /** Makes a new CA and leaf, replacing any earlier ones, and writes the CA to Download. */
    fun create(ctx: Context) {
        val issued = SingleUseCa.issue(CaptureNames.GAME_HOST)
        val dir = directory(ctx)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("leaf", issued.leafKey, PASSWORD, issued.chain.toTypedArray())
        File(dir, LEAF).outputStream().use { ks.store(it, PASSWORD) }
        File(dir, CA).writeBytes(issued.caCert.encoded)
        export(ctx)
    }

    /** Writes the CA certificate to `Download/fga-capture-ca.cer`. */
    fun export(ctx: Context) {
        val der = File(directory(ctx), CA).readBytes()
        val resolver = ctx.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        runCatching {
            resolver.delete(collection, "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(DOWNLOAD_NAME))
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, DOWNLOAD_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/x-x509-ca-cert")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        resolver.openOutputStream(uri)!!.use { it.write(der) }
    }

    private fun ownCertificate(ctx: Context): X509Certificate? = runCatching {
        File(directory(ctx), CA).inputStream().use {
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

    fun serverContext(ctx: Context): SSLContext {
        val ks = KeyStore.getInstance("PKCS12")
        File(directory(ctx), LEAF).inputStream().use { ks.load(it, PASSWORD) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, PASSWORD)
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }
}
