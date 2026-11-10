package io.github.fate_grand_automata.capture

import android.content.Context
import android.net.Uri
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
}

/** The capture subfolder of the SAF tree the user picked at onboarding. */
class DocumentCaptureFolder(
    private val dir: DocumentFile,
    private val openStream: (DocumentFile) -> OutputStream?,
    rootName: String?,
) : CaptureFolder {
    override val label = "${rootName ?: "FGA"}/${CaptureFolders.DIRECTORY}"
    override fun names() = dir.listFiles().filter { it.isFile }.mapNotNull { it.name }
    override fun delete(name: String) = dir.findFile(name)?.delete() ?: true
    override fun create(name: String, mime: String) = dir.createFile(mime, name)?.name
    override fun open(name: String) = dir.findFile(name)?.let(openStream)
}

object CaptureFolders {
    const val DIRECTORY = "capture"

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
            for ((index, name) in listOf(fixedName, fallbackName).distinct().withIndex()) {
                if (attempt(folder, name, mime, bytes)) return SaveOutcome.Saved(name, folder.label, fallback = index > 0)
            }
        }
        return SaveOutcome.NeedsDialog(fixedName, mime, bytes)
    }

    private fun attempt(folder: CaptureFolder, name: String, mime: String, bytes: ByteArray): Boolean = runCatching {
        if (name in folder.names() && !folder.delete(name)) return false
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

    /** Writes the bytes to a file the user picked in the system save dialog. Returns its real name. */
    fun writePicked(context: Context, uri: Uri, bytes: ByteArray): String? = runCatching {
        (context.contentResolver.openOutputStream(uri, "wt") ?: return null).use { it.write(bytes) }
        DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment
    }.getOrNull()
}
