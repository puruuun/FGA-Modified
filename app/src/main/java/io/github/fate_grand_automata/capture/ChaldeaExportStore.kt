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
        data class Written(val name: String, val folder: String? = null) : Result {
            val path get() = folder?.let { "$it/$name" } ?: name
        }
        /** The capture folder was unusable: the UI opens the system save dialog for these bytes. */
        class NeedsSaveDialog(val name: String, val bytes: ByteArray) : Result
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
                saveExport(folder ?: CaptureFolders.open(context), id, bytes, LocalDateTime.now())
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
            is SaveOutcome.NeedsDialog -> Result.NeedsSaveDialog(outcome.name, outcome.bytes)
            is SaveOutcome.Saved -> {
                // Account files: only the newest per account stays. A failed cleanup undoes this export.
                val obsolete = ChaldeaFiles.toDelete(folder!!.names(), outcome.name)
                if (obsolete.all { folder.delete(it) }) Result.Written(outcome.name, outcome.folder)
                else { runCatching { folder.delete(outcome.name) }; Result.Failed }
            }
        }

    /** The system save dialog wrote the file: report the real name the user chose. */
    fun picked(context: Context, uri: android.net.Uri, request: Result.NeedsSaveDialog): Result =
        CaptureFileSaver.writePicked(context, uri, request.bytes)?.let { Result.Written(it) } ?: Result.Failed

    suspend fun deleteTrackerData(context: Context, folder: CaptureFolder? = null): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                CaptureTracker.delete()
                ownedExports(context, folder).let { (dir, names) -> names.forEach { check(dir!!.delete(it)) } }
            }.isSuccess
        }
    }

    suspend fun deleteAccount(context: Context, userId: String, folder: CaptureFolder? = null): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                CaptureTracker.delete(userId)
                ownedExports(context, folder).let { (dir, names) ->
                    names.filter { ChaldeaFiles.accountId(it) == userId }.forEach { check(dir!!.delete(it)) }
                }
            }.isSuccess
        }
    }

    /** An unset or unusable folder holds nothing of ours to delete. */
    private fun ownedExports(context: Context, folder: CaptureFolder?): Pair<CaptureFolder?, List<String>> {
        val dir = folder ?: CaptureFolders.open(context)
        return dir to (dir?.names().orEmpty().filter(ChaldeaFiles::isExport))
    }
}
