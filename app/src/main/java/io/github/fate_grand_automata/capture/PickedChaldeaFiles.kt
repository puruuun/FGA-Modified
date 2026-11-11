package io.github.fate_grand_automata.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import java.io.FileNotFoundException

/** Single-document and tree-document grants may refer to the very same file. */
internal fun sameDocument(first: Uri?, second: Uri?): Boolean {
    if (first == null || second == null) return false
    if (first == second) return true
    return first.authority == second.authority && runCatching {
        DocumentsContract.getDocumentId(first) == DocumentsContract.getDocumentId(second)
    }.getOrDefault(false)
}

/** Document grants outlive the account data, so failed deletions remain available for a retry. */
internal class PickedChaldeaFiles(
    private val context: Context,
    private val remove: (Uri) -> Boolean = { DocumentsContract.deleteDocument(context.contentResolver, it) },
) {
    private val prefs = context.getSharedPreferences("capture-picked-exports", Context.MODE_PRIVATE)

    fun remember(accountId: String, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        // Providers may grant only one mode or no persistable permission at all.
        for (mode in listOf(flags, Intent.FLAG_GRANT_WRITE_URI_PERMISSION, Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
            if (runCatching { context.contentResolver.takePersistableUriPermission(uri, mode) }.isSuccess) break
        }
        val value = uri.toString()
        val edit = prefs.edit()
        // Reusing the same document for another account transfers its ownership to the new contents.
        prefs.all.keys.forEach { id ->
            edit.putStringSet(id, entries(id).filterNot { sameDocument(Uri.parse(it), uri) }.toSet())
        }
        edit.putStringSet(accountId, entries(accountId).filterNot { sameDocument(Uri.parse(it), uri) }.toSet() + value)
        check(edit.commit()) { "Could not track exported document" }
    }

    private fun entries(id: String) = prefs.getStringSet(id, emptySet()).orEmpty().toSet()

    /** A refused delete can also mean the user already removed the document in another app. */
    private fun missing(uri: Uri, deleteReportedMissing: Boolean): Boolean = try {
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val cursor = if (Build.VERSION.SDK_INT >= 26) {
            context.contentResolver.query(uri, columns, Bundle.EMPTY, null)
        } else context.contentResolver.query(uri, columns, null, null, null)
        // DocumentsProvider turns queryDocument's FileNotFoundException into a null cursor.
        cursor?.use { !it.moveToFirst() } ?: deleteReportedMissing
    } catch (_: FileNotFoundException) {
        true
    } catch (_: Exception) {
        // Permission loss and provider errors do not prove the file is gone.
        false
    }

    fun delete(accountId: String? = null, keep: Uri? = null): Boolean {
        var success = true
        for (id in prefs.all.keys.filter { accountId == null || it == accountId }) {
            val remaining = entries(id).toMutableSet()
            for (value in remaining.toList().filter { !sameDocument(Uri.parse(it), keep) }) {
                val uri = Uri.parse(value)
                val removed = runCatching { remove(uri) }
                if (removed.getOrDefault(false) || missing(uri, removed.exceptionOrNull() is FileNotFoundException)) remaining.remove(value)
                else success = false
            }
            if (!prefs.edit().putStringSet(id, remaining).commit()) success = false
        }
        return success
    }
}
