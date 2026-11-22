package io.github.fate_grand_automata.util

import androidx.documentfile.provider.DocumentFile
import timber.log.Timber

/**
 * Deletes the entries called [names] from [folder], a kind folder of the user's support images
 * (`support/servant` or `support/ce`), and returns the names that are really gone.
 *
 * Only direct children of [folder] are matched, by exact name, so nothing outside it is ever touched.
 * A name that is missing or that fails to delete is left out of the result: the caller keeps those in the
 * stored selection.
 */
internal fun deleteSupportEntries(
    folder: DocumentFile,
    names: Set<String>,
    delete: (DocumentFile) -> Boolean = DocumentFile::delete
): Set<String> {
    // One listing of the folder: a lookup per name would make the document provider list it once per name.
    val children = folder.listFiles().mapNotNull { file -> file.name?.let { it to file } }.toMap()
    val removed = mutableSetOf<String>()
    for (name in names) {
        val entry = children[name] ?: continue
        val done = try {
            delete(entry)
        } catch (e: Exception) {
            Timber.e(e, "Could not delete support entry $name")
            false
        }
        if (done) removed += name
    }
    return removed
}
