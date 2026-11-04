package io.github.fate_grand_automata.capture

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import io.github.fate_grand_automata.scripts.capture.ChaldeaExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/** Serializes exports and deletion from both screens. No account contents enter logs. */
object ChaldeaExportStore {
    private val lock = Mutex()
    private val path = "${Environment.DIRECTORY_DOWNLOADS}/FGA/"

    sealed interface Result {
        data object NoSnapshot : Result
        data object Failed : Result
        data class Written(val name: String) : Result
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun export(context: Context): Result = withContext(Dispatchers.IO) {
        lock.withLock {
            val resolver = context.contentResolver
            var created: Uri? = null
            try {
                CaptureTracker.init(context)
                val state = CaptureTracker.state.value ?: return@withLock Result.NoSnapshot
                val name = ChaldeaFiles.name(LocalDateTime.now())
                val body = ChaldeaExport.body(state)
                val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, path)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }))
                created = uri
                checkNotNull(resolver.openOutputStream(uri, "w")).bufferedWriter(Charsets.UTF_8).use { it.write(body) }
                // Delete previous entries only after the new body was fully written. Restore the
                // requested name before publishing, since a same-minute insert may get a suffix.
                val old = ownedExports(context).filter { ContentUris.parseId(it.uri) != ContentUris.parseId(uri) }
                val obsoleteNames = ChaldeaFiles.toDelete(old.map { it.name }, name).toSet()
                old.filter { it.name in obsoleteNames || it.name == name }.forEach {
                    check(resolver.delete(it.uri, null, null) == 1)
                }
                check(resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null) == 1)
                checkNotNull(resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)).use {
                    check(it.moveToFirst() && it.getString(0) == name)
                }
                Result.Written(name)
            } catch (_: Exception) {
                created?.let { runCatching { resolver.delete(it, null, null) } }
                Result.Failed
            }
        }
    }

    suspend fun deleteTrackerData(context: Context): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                CaptureTracker.delete()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ownedExports(context).forEach { check(context.contentResolver.delete(it.uri, null, null) == 1) }
                }
            }.isSuccess
        }
    }

    private data class Entry(val uri: Uri, val name: String)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun ownedExports(context: Context): List<Entry> {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.OWNER_PACKAGE_NAME} = ?"
        return buildList {
            checkNotNull(context.contentResolver.query(collection, columns, selection, arrayOf(path, context.packageName), null)).use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    if (ChaldeaFiles.isExport(name)) add(Entry(ContentUris.withAppendedId(collection, cursor.getLong(0)), name))
                }
            }
        }
    }
}
