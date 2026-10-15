package io.github.fate_grand_automata.util.portraits

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.fate_grand_automata.SupportImageKind
import io.github.fate_grand_automata.util.StorageProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

data class PortraitImportProgress(val name: String, val completed: Int, val total: Int)
data class PortraitImportReport(val name: String, val folder: String, val saved: Int, val failed: Int)

class ServantPortraitImporter @Inject constructor(private val storage: StorageProvider) {
    suspend fun run(number: Int, onProgress: (PortraitImportProgress) -> Unit): PortraitImportReport =
        withContext(Dispatchers.IO) {
            if (number !in 1..9999) throw PortraitImportException(PortraitImportError.Number)
            val text = download("https://api.atlasacademy.io/nice/JP/servant/$number", 2_097_152, metadata = true)
                .toString(Charsets.UTF_8)
            val metadata = try {
                ServantPortraitMetadata.parse(text, number)
            } catch (e: PortraitImportException) {
                throw e
            } catch (e: Exception) {
                throw PortraitImportException(PortraitImportError.Metadata)
            }
            val folder = try {
                storage.createNoMediaFile()
                storage.prepareServantPortraitFolder(metadata)
            } catch (e: Exception) {
                throw PortraitImportException(PortraitImportError.Storage)
            }
            var saved = 0
            var failed = 0
            onProgress(PortraitImportProgress(metadata.name, 0, metadata.portraits.size))
            metadata.portraits.forEachIndexed { index, portrait ->
                coroutineContext.ensureActive()
                try {
                    val png = SupportPortraitTemplate.create(download(portrait.url, 1_048_576))
                    coroutineContext.ensureActive()
                    try {
                        storage.writeSupportImage(SupportImageKind.Servant, "$folder/${portrait.key}.png").use {
                            it.write(png)
                        }
                    } catch (e: Exception) {
                        throw PortraitImportException(PortraitImportError.Storage)
                    }
                    saved++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    coroutineContext.ensureActive()
                    // An unsuccessful picture must not be reported as a successful import.
                    failed++
                    Timber.w(e, "Support portrait import failed for %s", portrait.key)
                }
                onProgress(PortraitImportProgress(metadata.name, index + 1, metadata.portraits.size))
            }
            PortraitImportReport(metadata.name, folder, saved, failed)
        }

    private suspend fun download(url: String, maxBytes: Int, metadata: Boolean = false): ByteArray {
        coroutineContext.ensureActive()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "FGA-beta-support-portraits")
            connection.setRequestProperty("Accept", if (metadata) "application/json" else "image/png")
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_FOUND -> throw PortraitImportException(
                    if (metadata) PortraitImportError.NotFound else PortraitImportError.Image
                )
                else -> throw PortraitImportException(PortraitImportError.Network)
            }
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maxBytes) throw PortraitImportException(
                        if (metadata) PortraitImportError.Metadata else PortraitImportError.Image
                    )
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (e: IOException) {
            throw PortraitImportException(PortraitImportError.Network)
        } finally {
            connection.disconnect()
        }
    }
}

object SupportPortraitTemplate {
    fun create(png: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
        if (bounds.outWidth != SupportPortraitCrop.SOURCE_SIZE || bounds.outHeight != SupportPortraitCrop.SOURCE_SIZE) {
            throw PortraitImportException(PortraitImportError.Image)
        }
        val source = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: throw PortraitImportException(PortraitImportError.Image)
        try {
            val scaled = Bitmap.createScaledBitmap(source, SupportPortraitCrop.RESIZED_SIZE, SupportPortraitCrop.RESIZED_SIZE, true)
            try {
                val cropped = Bitmap.createBitmap(scaled, SupportPortraitCrop.X, SupportPortraitCrop.Y, SupportPortraitCrop.WIDTH, SupportPortraitCrop.HEIGHT)
                try {
                    // Keep colour here: FGA's normal PNG loader performs the OpenCV grayscale conversion.
                    return ByteArrayOutputStream().use { stream ->
                        if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw PortraitImportException(PortraitImportError.Image)
                        stream.toByteArray()
                    }
                } finally { cropped.recycle() }
            } finally { scaled.recycle() }
        } finally { source.recycle() }
    }
}
