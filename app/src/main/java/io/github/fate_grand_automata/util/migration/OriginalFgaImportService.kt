package io.github.fate_grand_automata.util.migration

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.prefs.import
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

class OriginalFgaImportService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefsCore: PrefsCore,
    private val prefs: IPreferences
) {
    fun run(
        selection: ImportSelection,
        scripts: Uri?,
        images: Uri?,
        overwriteImages: Boolean,
        onProgress: (OriginalImportProgress) -> Unit
    ): OriginalImportReport {
        val store = object : ImportedScriptStore {
            override fun existing() = prefs.battleConfigs.map { it.export() }

            override fun add(values: Map<String, *>) {
                val id = UUID.randomUUID().toString()
                try {
                    val editor = prefsCore.forBattleConfig(id).sharedPrefs.edit()
                    editor.import(values)
                    check(editor.commit()) { "Could not save imported script" }
                    prefs.addBattleConfig(id)
                } catch (e: Exception) {
                    prefs.removeBattleConfig(id)
                    throw e
                }
            }
        }
        fun document(uri: Uri?) = uri?.let { DocumentFile.fromTreeUri(context, it) }?.let {
            SafImportDocument(it, context.contentResolver)
        }
        val destination = if (selection.images) {
            prefsCore.dirRoot.get().takeIf { it.isNotBlank() }?.let { document(Uri.parse(it)) }
        } else null
        return OriginalFgaImporter(store).run(
            selection, if (selection.scripts) document(scripts) else null,
            if (selection.images) document(images) else null, destination, overwriteImages, onProgress
        )
    }
}

private class SafImportDocument(
    private val file: DocumentFile,
    private val resolver: ContentResolver
) : ImportDocument {
    override val name get() = file.name ?: throw IOException("Unnamed document")
    // Different SAF tree grants can refer to the same file. URI equality alone is insufficient.
    override val identity get() = "${file.uri.authority}:${DocumentsContract.getDocumentId(file.uri)}"
    override val isDirectory get() = file.isDirectory
    override fun children(): List<ImportDocument> {
        check(file.canRead()) { "Cannot read directory" }
        return file.listFiles().map { SafImportDocument(it, resolver) }
    }
    override fun openInput() = resolver.openInputStream(file.uri) ?: throw IOException("Cannot read document")
    override fun openOutput() = resolver.openOutputStream(file.uri, "wt") ?: throw IOException("Cannot write document")
    override fun createDirectory(name: String) = SafImportDocument(
        file.createDirectory(name) ?: throw IOException("Cannot create directory"), resolver
    )
    override fun createFile(name: String): ImportDocument {
        val mime = when (name.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> "application/octet-stream"
        }
        return SafImportDocument(file.createFile(mime, name) ?: throw IOException("Cannot create document"), resolver)
    }
    override fun delete() { check(file.delete()) { "Cannot remove incomplete document" } }
}
