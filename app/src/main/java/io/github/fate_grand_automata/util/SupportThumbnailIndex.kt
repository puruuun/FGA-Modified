package io.github.fate_grand_automata.util

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.fate_grand_automata.SupportImageKind
import io.github.fate_grand_automata.util.portraits.ServantPortraitFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The first image of each support entry, found with one listing of the kind's folder and one listing per
 * entry that is asked for. A document tree query is slow, so a picker that listed the whole tree again
 * for every row would stall with a few hundred entries.
 *
 * It only reads: a missing folder gives no thumbnail and is never created. The entry folders are the same
 * ones [StorageProvider.readSupportImage] hands to the support search.
 */
class SupportThumbnailIndex(private val kindFolder: () -> DocumentFile?) {
    private val lock = Mutex()
    private var children: Map<String, DocumentFile>? = null
    private val resolved = ConcurrentHashMap<String, Found>()

    private class Found(val uri: Uri?)

    suspend fun thumbnail(name: String): Uri? = withContext(Dispatchers.IO) {
        resolved[name]?.let { return@withContext it.uri }
        val entry = lock.withLock {
            children ?: kindFolder()?.listFiles().orEmpty()
                .mapNotNull { file -> file.name?.let { it to file } }
                .toMap()
                .also { children = it }
        }.let { all ->
            // Same lookup as StorageProvider.readSupportImage: a legacy "No.444" request finds its renamed folder.
            all[name] ?: ServantPortraitFolder.resolve(name, all.filterValues { it.isDirectory }.keys.toList())?.let(all::get)
        }
        val uri = entry?.let(::firstImage)?.uri
        resolved[name] = Found(uri)
        uri
    }

    private fun firstImage(entry: DocumentFile): DocumentFile? {
        if (entry.isFile) return entry.takeIf { isImage(it) }
        return entry.listFiles()
            .filter { it.isFile && isImage(it) }
            .minByOrNull { it.name.orEmpty() }
    }

    private fun isImage(file: DocumentFile): Boolean {
        val name = file.name?.lowercase() ?: return false
        return !name.contains("nomedia") && (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp"))
    }

    companion object {
        fun folderName(kind: SupportImageKind) = when (kind) {
            SupportImageKind.Servant -> "servant"
            SupportImageKind.CE -> "ce"
            SupportImageKind.Friend -> "friend"
            SupportImageKind.NoblePhantasm -> "np"
        }
    }
}
