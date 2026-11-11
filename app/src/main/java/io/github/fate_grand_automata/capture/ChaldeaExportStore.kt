package io.github.fate_grand_automata.capture

import android.content.Context
import io.github.fate_grand_automata.scripts.capture.ChaldeaExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * Serializes exports and deletion from both screens. Files live in `<FGA folder>/capture/`.
 * No account contents enter logs. Older exports in Download/FGA are left alone.
 */
object ChaldeaExportStore {
    private val lock = Mutex()
    private const val MIME = "application/json"

    sealed interface Result {
        data object NoSnapshot : Result
        data object Failed : Result
        /** [folder] is the folder shown to the user, for example `FGA/capture`. */
        data class Written(val name: String, val folder: String? = null, val cleanupFailed: Boolean = false) : Result {
            val path get() = folder?.let { "$it/$name" } ?: name
        }
        /** The capture folder was unusable: the UI opens the system save dialog for these bytes. */
        class NeedsSaveDialog(val name: String, val bytes: ByteArray, val accountId: String) : Result
    }

    suspend fun export(
        context: Context,
        userId: String? = null,
        folder: CaptureFolder? = null,
    ): Result = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                CaptureTracker.init(context)
                val (id, state) = CaptureTracker.snapshot(userId) ?: return@withLock Result.NoSnapshot
                val bytes = java.io.ByteArrayOutputStream().also { out ->
                    out.bufferedWriter(Charsets.UTF_8).use { ChaldeaExport.write(state, it) }
                }.toByteArray()
                val destination = folder ?: CaptureFolders.open(context)
                when (val result = saveExport(destination, id, bytes, LocalDateTime.now())) {
                    is Result.Written -> {
                        val removed = runCatching { PickedChaldeaFiles(context).delete(id, keep = destination?.documentUri(result.name)) }.getOrDefault(false)
                        result.copy(cleanupFailed = result.cleanupFailed || !removed)
                    }
                    else -> result
                }
            } catch (_: Exception) {
                Result.Failed
            }
        }
    }

    /** Three-layer save, then keeps only the newest export per account. */
    internal fun saveExport(folder: CaptureFolder?, id: String, bytes: ByteArray, now: LocalDateTime): Result =
        when (val outcome = CaptureFileSaver.save(
            folder, ChaldeaFiles.name(id, now), ChaldeaFiles.fallbackName(id, now), MIME, bytes,
        )) {
            is SaveOutcome.NeedsDialog -> Result.NeedsSaveDialog(outcome.name, outcome.bytes, id)
            is SaveOutcome.Saved -> {
                // A saved fallback is useful even if the provider refuses to remove an older file.
                val removed = removeFolderExports(folder, id, outcome.name)
                Result.Written(outcome.name, outcome.folder, cleanupFailed = !removed)
            }
        }

    /** Track the chosen document before cleaning up earlier exports, under the same export/delete lock. */
    suspend fun picked(
        context: Context, uri: android.net.Uri?, request: Result.NeedsSaveDialog, folder: CaptureFolder? = null,
    ): Result? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (uri == null) return@withLock null
                try {
                    val saved = CaptureFileSaver.writePicked(context, uri, request.bytes) ?: return@withLock Result.Failed
                    val picked = PickedChaldeaFiles(context)
                    picked.remember(request.accountId, uri)
                    val documentsRemoved = picked.delete(request.accountId, keep = uri)
                    val folderRemoved = removeConfiguredFolderExports(context, folder, request.accountId, keepUri = uri)
                    Result.Written(saved.name, saved.folder, cleanupFailed = !documentsRemoved || !folderRemoved)
                } catch (_: Exception) { Result.Failed }
            }
        }

    suspend fun deleteTrackerData(context: Context, folder: CaptureFolder? = null): Boolean = delete(context, folder, null)

    suspend fun deleteAccount(context: Context, userId: String, folder: CaptureFolder? = null): Boolean = delete(context, folder, userId)

    private suspend fun delete(context: Context, folder: CaptureFolder?, id: String?): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            // Private deletion must happen even if the export permission is gone. Attempt every cleanup.
            val privateRemoved = runCatching { if (id == null) CaptureTracker.delete() else CaptureTracker.delete(id) }.isSuccess
            val folderRemoved = removeConfiguredFolderExports(context, folder, id)
            val pickedRemoved = runCatching { PickedChaldeaFiles(context).delete(id) }.getOrDefault(false)
            privateRemoved && folderRemoved && pickedRemoved
        }
    }

    private fun removeConfiguredFolderExports(
        context: Context, folder: CaptureFolder?, id: String?, keepUri: android.net.Uri? = null,
    ): Boolean = runCatching {
        val dir = folder ?: CaptureFolders.open(context)
        if (dir == null) return@runCatching !CaptureFolders.configured(context)
        removeFolderExports(dir, id, keepUri = keepUri)
    }.getOrDefault(false)

    private fun removeFolderExports(
        folder: CaptureFolder?, id: String?, keep: String? = null, keepUri: android.net.Uri? = null,
    ): Boolean = runCatching {
        checkNotNull(folder)
        val targets = folder.names().filter { it != keep && ChaldeaFiles.isExport(it) && (id == null || ChaldeaFiles.accountId(it) == id) &&
            (keepUri == null || !sameDocument(folder.documentUri(it), keepUri)) }
        // map before all: one refusal must not skip the other files.
        targets.map { runCatching { folder.delete(it) }.getOrDefault(false) }.all { it }
    }.getOrDefault(false)
}
