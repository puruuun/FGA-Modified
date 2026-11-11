package io.github.fate_grand_automata.capture

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.github.fate_grand_automata.R
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the capture exports need from `<FGA folder>/capture/`. Kept small so tests can fake failures. */
interface CaptureFolder {
    /** Shown to the user, for example `FGA/capture`. */
    val label: String
    fun names(): List<String>
    fun delete(name: String): Boolean
    /** Creates an empty file and returns the name the provider really gave it, or null on refusal. */
    fun create(name: String, mime: String): String?
    fun open(name: String): OutputStream?
    fun rename(from: String, to: String): Boolean
    fun documentUri(name: String): Uri? = null
}

/** The capture subfolder of the SAF tree the user picked at onboarding. */
class DocumentCaptureFolder(
    private val dir: DocumentFile,
    private val openStream: (DocumentFile) -> OutputStream?,
    rootName: String?,
) : CaptureFolder {
    override val label = "${rootName ?: "FGA"}/${CaptureFolders.DIRECTORY}"
    override fun names(): List<String> {
        check(dir.canRead() && dir.canWrite()) { "Capture folder permission lost" }
        return dir.listFiles().filter { it.isFile }.mapNotNull { it.name }
    }
    override fun delete(name: String): Boolean {
        check(dir.canRead() && dir.canWrite()) { "Capture folder permission lost" }
        return dir.findFile(name)?.delete() ?: true
    }
    override fun create(name: String, mime: String) = dir.createFile(mime, name)?.name
    override fun documentUri(name: String) = dir.findFile(name)?.uri
    override fun open(name: String) = dir.findFile(name)?.let(openStream)
    override fun rename(from: String, to: String) = dir.findFile(from)?.let { it.renameTo(to) && it.name == to } ?: false
}

object CaptureFolders {
    const val DIRECTORY = "capture"

    fun configured(context: Context): Boolean = PrefsCore(
        PrefMaker(context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)), context,
    ).dirRoot.get().isNotBlank()

    /** Null when the FGA folder is unset, its permission was lost, or `capture` cannot be created. */
    fun open(context: Context): CaptureFolder? = runCatching {
        val prefs = PrefsCore(
            PrefMaker(context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)), context,
        )
        val uri = prefs.dirRoot.get().takeIf { it.isNotBlank() }?.toUri() ?: return null
        open(context, DocumentFile.fromTreeUri(context, uri))
    }.getOrNull()

    fun open(context: Context, root: DocumentFile?): CaptureFolder? = runCatching {
        if (root == null || !root.canWrite()) return null
        val dir = root.findFile(DIRECTORY)?.takeIf { it.isDirectory } ?: root.createDirectory(DIRECTORY) ?: return null
        DocumentCaptureFolder(dir, { context.contentResolver.openOutputStream(it.uri, "wt") }, root.name)
    }.getOrNull()
}

sealed interface SaveOutcome {
    /** Saved as [name]; [fallback] is true when the fixed name could not be used. */
    data class Saved(val name: String, val folder: String, val fallback: Boolean) : SaveOutcome {
        val path get() = "$folder/$name"
    }
    /** The folder was unusable. The caller opens the system save dialog for [name]. */
    data class NeedsDialog(val name: String, val mime: String, val bytes: ByteArray) : SaveOutcome
}

object CaptureFileSaver {
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT)

    /** `fga-capture-ca.crt` becomes `fga-capture-ca-20261111-0234.crt`. */
    fun timestamped(name: String, time: LocalDateTime): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        return "$base-${time.format(stamp)}$ext"
    }

    /**
     * Layer 1: the fixed name, replacing an existing file. Layer 2: one retry under [fallbackName].
     * Layer 3 is the caller's: [SaveOutcome.NeedsDialog]. The name really written is always checked.
     */
    fun save(
        folder: CaptureFolder?,
        fixedName: String,
        fallbackName: String,
        mime: String,
        bytes: ByteArray,
    ): SaveOutcome {
        if (folder != null) {
            val existing = runCatching { fixedName in folder.names() }.getOrNull()
            if (existing == true) {
                // Write the retry fully before touching the previous export. A failed write or a
                // cancelled layer-3 dialog must not destroy the file that was already there.
                if (fallbackName != fixedName && attempt(folder, fallbackName, mime, bytes)) {
                    val replaced = runCatching {
                        folder.delete(fixedName) && folder.rename(fallbackName, fixedName)
                    }.getOrDefault(false)
                    return SaveOutcome.Saved(if (replaced) fixedName else fallbackName, folder.label, fallback = !replaced)
                }
            } else if (existing == false) {
                for ((index, name) in listOf(fixedName, fallbackName).distinct().withIndex()) {
                    if (attempt(folder, name, mime, bytes)) return SaveOutcome.Saved(name, folder.label, fallback = index > 0)
                }
            }
        }
        return SaveOutcome.NeedsDialog(fixedName, mime, bytes)
    }

    private fun attempt(folder: CaptureFolder, name: String, mime: String, bytes: ByteArray): Boolean = runCatching {
        if (name in folder.names()) return false
        val actual = folder.create(name, mime) ?: return false
        if (actual != name) {
            // The provider renamed it (for example by appending an extension): do not leave that copy behind.
            folder.delete(actual)
            return false
        }
        val written = runCatching {
            (folder.open(name) ?: error("cannot open $name")).use { it.write(bytes) }
        }
        if (written.isFailure) {
            runCatching { folder.delete(name) }
            return false
        }
        true
    }.getOrDefault(false)

    data class Picked(val name: String, val folder: String) {
        val path get() = "$folder/$name"
    }

    /** Writes only after a non-null dialog result; cancellation has no filesystem effects. */
    fun writePicked(context: Context, uri: Uri, bytes: ByteArray): Picked? = runCatching {
        (context.contentResolver.openOutputStream(uri, "wt") ?: return null).use { it.write(bytes) }
        pickedLocation(context, uri)
    }.getOrNull()

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        val cursor = if (Build.VERSION.SDK_INT >= 26) {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), android.os.Bundle.EMPTY, null)
        } else context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) it.getString(0)?.takeIf(String::isNotBlank) else null
        }
    }.getOrNull()

    /** Providers can expose an ancestor path or a tree name; opaque document IDs are not folder names. */
    internal fun pickedLocation(context: Context, uri: Uri): Picked {
        val name = displayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val parent = if (Build.VERSION.SDK_INT >= 26) runCatching {
            val path = DocumentsContract.findDocumentPath(context.contentResolver, uri)?.path.orEmpty()
            path.dropLast(1).lastOrNull()?.let {
                val parentUri = if (DocumentsContract.isTreeUri(uri)) DocumentsContract.buildDocumentUriUsingTree(uri, it)
                    else DocumentsContract.buildDocumentUri(uri.authority!!, it)
                displayName(context, parentUri)
            }
        }.getOrNull() else null
        val tree = if (DocumentsContract.isTreeUri(uri)) runCatching {
            displayName(context, DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)))
        }.getOrNull() else null
        return Picked(name, parent ?: tree ?: context.getString(R.string.capture_selected_location))
    }
}
