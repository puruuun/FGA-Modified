package io.github.fate_grand_automata.util.portraits

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.fate_grand_automata.util.StorageProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

data class PortraitImportProgress(val name: String, val completed: Int, val total: Int)
data class PortraitImportReport(val name: String, val folder: String, val saved: Int, val failed: Int, val kind: SupportCatalogKind)

class ServantPortraitImporter @Inject constructor(
    private val storage: StorageProvider,
    private val atlas: AtlasSupportRepository
) {
    private val lookup = SupportImageLookup(atlas)

    suspend fun search(kind: SupportCatalogKind, query: String, language: String, region: String) =
        lookup.search(kind, query, language, region)

    suspend fun run(entry: SupportCatalogEntry, region: String,
                    onProgress: (PortraitImportProgress) -> Unit): PortraitImportReport = withContext(Dispatchers.IO) {
        val portraits = lookup.portraits(entry, region)
        val folder = try {
            storage.createNoMediaFile()
            storage.prepareSupportPortraitFolder(entry.kind.storage, entry.number, entry.name)
        } catch (e: Exception) { throw PortraitImportException(PortraitImportError.Storage) }
        var saved = 0
        var failed = 0
        onProgress(PortraitImportProgress(entry.name, 0, portraits.size))
        portraits.forEachIndexed { index, portrait ->
            coroutineContext.ensureActive()
            try {
                val bytes = atlas.image(portrait.url)
                val png = if (entry.kind == SupportCatalogKind.CE) SupportCETemplate.create(bytes) else SupportPortraitTemplate.create(bytes)
                coroutineContext.ensureActive()
                try {
                    storage.writeSupportImage(entry.kind.storage, "$folder/${portrait.key}.png").use { it.write(png) }
                } catch (e: Exception) { throw PortraitImportException(PortraitImportError.Storage) }
                saved++
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                failed++
                Timber.w(e, "Support image import failed for %s", portrait.key)
            }
            onProgress(PortraitImportProgress(entry.name, index + 1, portraits.size))
        }
        PortraitImportReport(entry.name, folder, saved, failed, entry.kind)
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

/** Same comparison scale as the existing normal and Grand CE support strips. */
object SupportCECrop {
    const val SOURCE_WIDTH = 150
    const val SOURCE_HEIGHT = 68
    const val RESIZED_WIDTH = 158
    const val RESIZED_HEIGHT = 72
    const val X = 3
    const val Y = 14
    const val WIDTH = 142
    const val HEIGHT = 25
}

object SupportCETemplate {
    fun create(png: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
        if (bounds.outWidth != SupportCECrop.SOURCE_WIDTH || bounds.outHeight != SupportCECrop.SOURCE_HEIGHT)
            throw PortraitImportException(PortraitImportError.Image)
        val source = BitmapFactory.decodeByteArray(png, 0, png.size)
            ?: throw PortraitImportException(PortraitImportError.Image)
        try {
            val scaled = Bitmap.createScaledBitmap(source, SupportCECrop.RESIZED_WIDTH, SupportCECrop.RESIZED_HEIGHT, true)
            try {
                val cropped = Bitmap.createBitmap(scaled, SupportCECrop.X, SupportCECrop.Y, SupportCECrop.WIDTH, SupportCECrop.HEIGHT)
                try {
                    return ByteArrayOutputStream().use { stream ->
                        if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw PortraitImportException(PortraitImportError.Image)
                        stream.toByteArray()
                    }
                } finally { cropped.recycle() }
            } finally { scaled.recycle() }
        } finally { source.recycle() }
    }
}
