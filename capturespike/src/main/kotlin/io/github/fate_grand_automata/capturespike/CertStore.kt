package io.github.fate_grand_automata.capturespike

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

object CertStore {
    const val GAME_HOST = "yfgo-game.netmarble.com"
    private const val FILE = "leaf.p12"
    private val PASSWORD = "spike".toCharArray()
    private const val DOWNLOAD_NAME = "fga-capture-ca.cer"

    fun leafExists(ctx: Context) = File(ctx.filesDir, FILE).isFile

    /** Generates CA + leaf, stores leaf key and chain privately, writes the CA cert to Download. */
    fun create(ctx: Context) {
        val issued = SingleUseCa.issue(GAME_HOST)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("leaf", issued.leafKey, PASSWORD, issued.chain.toTypedArray())
        File(ctx.filesDir, FILE).outputStream().use { ks.store(it, PASSWORD) }

        val der = issued.caCert.encoded
        SpikeLog.log("CA sha256=" + MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) })
        writeToDownloads(ctx, der)
    }

    private fun writeToDownloads(ctx: Context, der: ByteArray) {
        val resolver = ctx.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        // Remove an earlier copy of ours so the name stays fga-capture-ca.cer.
        try {
            resolver.delete(collection, "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(DOWNLOAD_NAME))
        } catch (e: Exception) {
            SpikeLog.log("old CA file not removed: $e")
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, DOWNLOAD_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/x-x509-ca-cert")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        resolver.openOutputStream(uri)!!.use { it.write(der) }
        SpikeLog.log("CA written to Download/$DOWNLOAD_NAME ($uri). Install: 설정 → 보안 및 개인정보 → 기타 보안 설정 → 인증서 설치 → CA 인증서")
    }

    fun serverContext(ctx: Context): SSLContext {
        val ks = KeyStore.getInstance("PKCS12")
        File(ctx.filesDir, FILE).inputStream().use { ks.load(it, PASSWORD) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, PASSWORD)
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }
}
