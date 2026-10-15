package io.github.fate_grand_automata.util.migration

import io.github.fate_grand_automata.prefs.BattleConfigFile
import io.github.fate_grand_automata.scripts.models.AutoSkillCommand
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException

enum class ImportSelection(val scripts: Boolean, val images: Boolean) {
    Scripts(true, false), Images(false, true), Both(true, true)
}

enum class ImportPart { Scripts, Images }
enum class ImportProblem { Folder, NoFiles, InvalidScript, UnsupportedCommand, ReadWrite, OverlappingFolders }
data class ImportIssue(val part: ImportPart, val path: String, val problem: ImportProblem)
data class ImportCounts(val imported: Int = 0, val skipped: Int = 0, val failed: Int = 0)
data class OriginalImportReport(
    val scripts: ImportCounts,
    val images: ImportCounts,
    val issues: List<ImportIssue>,
    val issueCount: Int
)
data class OriginalImportProgress(val part: ImportPart, val completed: Int, val total: Int)

/** Small filesystem boundary so SAF reads, merges and failures can be tested without Android. */
interface ImportDocument {
    val name: String
    val identity: String
    val isDirectory: Boolean
    fun children(): List<ImportDocument>
    fun openInput(): InputStream
    fun openOutput(): OutputStream
    fun createDirectory(name: String): ImportDocument
    fun createFile(name: String): ImportDocument
    fun delete()
}

interface ImportedScriptStore {
    fun existing(): List<Map<String, *>>
    /** Must not leave an empty script behind if saving fails. */
    fun add(values: Map<String, *>)
}

class OriginalFgaImporter(private val scripts: ImportedScriptStore) {
    fun run(
        selection: ImportSelection,
        scriptSource: ImportDocument?,
        imageSource: ImportDocument?,
        destination: ImportDocument?,
        overwriteImages: Boolean = false,
        onProgress: (OriginalImportProgress) -> Unit = {}
    ): OriginalImportReport {
        var scriptCounts = ImportCounts()
        var imageCounts = ImportCounts()
        val issues = mutableListOf<ImportIssue>()
        var issueCount = 0
        fun issue(part: ImportPart, path: String, problem: ImportProblem) {
            issueCount++
            if (issues.size < 20) issues += ImportIssue(part, path, problem)
        }

        if (selection.scripts) {
            val files = try {
                requireNotNull(scriptSource).also { require(it.isDirectory) }
                collect(scriptSource) { it.endsWith(".fga", ignoreCase = true) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                issue(ImportPart.Scripts, "", ImportProblem.Folder)
                scriptCounts = scriptCounts.copy(failed = 1)
                emptyList()
            }
            if (files.isEmpty() && scriptCounts.failed == 0) issue(ImportPart.Scripts, "", ImportProblem.NoFiles)
            val known = scripts.existing().map { comparable(it) }.toMutableSet()
            files.forEachIndexed { index, (path, file) ->
                onProgress(OriginalImportProgress(ImportPart.Scripts, index, files.size))
                var problem = ImportProblem.ReadWrite
                try {
                    val text = file.openInput().use { it.readBounded(MAX_SCRIPT_BYTES).toString(Charsets.UTF_8) }
                    problem = ImportProblem.InvalidScript
                    val values = BattleConfigFile.decode(text.removePrefix("\uFEFF"))
                    require(values["autoskill_name"] is String || values["autoskill_cmd"] is String)
                    require("autoskill_name" !in values || values["autoskill_name"] is String)
                    require("autoskill_cmd" !in values || values["autoskill_cmd"] is String)
                    // SharedPreferences cannot store arbitrary JSON numbers or nested objects.
                    require(values.values.all { it is String || it is Int || it is Boolean || it is Collection<*> })
                    for (key in listOf("support_pref_servant_list", "support_pref_ce_list", "support_friend_names_list")) {
                        require(key !in values || values[key] is Collection<*>)
                    }
                    problem = ImportProblem.UnsupportedCommand
                    (values["autoskill_cmd"] as? String)?.let { AutoSkillCommand.parse(it) }
                    val comparable = comparable(values)
                    if (comparable in known) {
                        scriptCounts = scriptCounts.copy(skipped = scriptCounts.skipped + 1)
                    } else {
                        problem = ImportProblem.ReadWrite
                        scripts.add(values)
                        known += comparable
                        scriptCounts = scriptCounts.copy(imported = scriptCounts.imported + 1)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    issue(ImportPart.Scripts, path, problem)
                    scriptCounts = scriptCounts.copy(failed = scriptCounts.failed + 1)
                }
            }
            onProgress(OriginalImportProgress(ImportPart.Scripts, files.size, files.size))
        }

        if (selection.images) {
            var imageRoot: ImportDocument? = null
            val sourceDirectories = mutableSetOf<String>()
            val files = try {
                requireNotNull(imageSource).also { require(it.isDirectory) }
                val children = imageSource.children()
                imageRoot = children.firstOrNull { it.name == "support" && it.isDirectory } ?: imageSource
                val categories = imageRoot.children().filter { it.isDirectory && it.name in IMAGE_KINDS }
                require(categories.isNotEmpty())
                sourceDirectories += imageRoot.identity
                categories.flatMap { category -> collect(category, category.name, { sourceDirectories += it.identity }) { name ->
                    name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
                } }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                issue(ImportPart.Images, "", ImportProblem.Folder)
                imageCounts = imageCounts.copy(failed = 1)
                emptyList()
            }
            if (files.isEmpty() && imageCounts.failed == 0) issue(ImportPart.Images, "", ImportProblem.NoFiles)
            if (files.isNotEmpty()) {
                try {
                    requireNotNull(destination).also { require(it.isDirectory) }
                    // Source equal to destination is safe (a no-op). A nested destination would
                    // otherwise copy the application's images back into its own source tree.
                    // Track visited directory identities too: SAF document IDs can be opaque.
                    if (destination.identity in sourceDirectories || destination.identity.startsWith(imageRoot!!.identity + "/")) {
                        issue(ImportPart.Images, "", ImportProblem.OverlappingFolders)
                        imageCounts = imageCounts.copy(failed = files.size)
                    } else {
                        val root = destination.children().firstOrNull { it.name == "support" }
                            ?: destination.createDirectory("support")
                        require(root.isDirectory)
                        val childrenCache = mutableMapOf<String, MutableMap<String, ImportDocument>>()
                        fun childrenOf(dir: ImportDocument) = childrenCache.getOrPut(dir.identity) {
                            dir.children().associateBy { it.name }.toMutableMap()
                        }
                        files.forEachIndexed { index, (path, source) ->
                            onProgress(OriginalImportProgress(ImportPart.Images, index, files.size))
                            try {
                                var dir = root
                                val parts = path.split('/')
                                for (name in parts.dropLast(1)) {
                                    val next = childrenOf(dir)[name] ?: dir.createDirectory(name).also {
                                        if (it.name != name) {
                                            runCatching { it.delete() }
                                            error("Storage provider changed the directory name")
                                        }
                                        childrenOf(dir)[name] = it
                                    }
                                    require(next.isDirectory)
                                    dir = next
                                }
                                val name = parts.last()
                                val existing = childrenOf(dir)[name]
                                require(existing == null || !existing.isDirectory)
                                if (existing != null && (existing.identity == source.identity || !overwriteImages)) {
                                    imageCounts = imageCounts.copy(skipped = imageCounts.skipped + 1)
                                } else {
                                    val bytes = source.openInput().use { it.readBounded(MAX_IMAGE_BYTES) }
                                    val backup = existing?.also { require(!it.isDirectory) }?.openInput()?.use {
                                        it.readBounded(MAX_IMAGE_BYTES)
                                    }
                                    if (backup != null && bytes.contentEquals(backup)) {
                                        imageCounts = imageCounts.copy(skipped = imageCounts.skipped + 1)
                                    } else {
                                        val target = existing ?: dir.createFile(name)
                                        try {
                                            check(target.name == name) { "Storage provider changed the image name" }
                                            target.openOutput().use { it.write(bytes) }
                                        } catch (e: Exception) {
                                            // Restore the old image on a failed overwrite; delete a failed new file.
                                            runCatching {
                                                if (backup == null) target.delete()
                                                else target.openOutput().use { it.write(backup) }
                                            }
                                            throw e
                                        }
                                        childrenOf(dir)[name] = target
                                        imageCounts = imageCounts.copy(imported = imageCounts.imported + 1)
                                    }
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                issue(ImportPart.Images, path, ImportProblem.ReadWrite)
                                imageCounts = imageCounts.copy(failed = imageCounts.failed + 1)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    issue(ImportPart.Images, "", ImportProblem.Folder)
                    imageCounts = imageCounts.copy(failed = files.size)
                }
            }
            onProgress(OriginalImportProgress(ImportPart.Images, files.size, files.size))
        }
        return OriginalImportReport(scriptCounts, imageCounts, issues, issueCount)
    }

    private fun comparable(values: Map<String, *>): Map<String, *> = values.mapValues { (_, value) ->
        if (value is Collection<*>) value.toSet() else value
    }

    private fun collect(
        root: ImportDocument,
        prefix: String = "",
        onDirectory: (ImportDocument) -> Unit = {},
        matches: (String) -> Boolean
    ): List<Pair<String, ImportDocument>> {
        val result = mutableListOf<Pair<String, ImportDocument>>()
        val visited = mutableSetOf<String>()
        fun visit(dir: ImportDocument, path: String, depth: Int) {
            require(depth <= 20 && visited.size < 10_000)
            require(visited.add(dir.identity))
            onDirectory(dir)
            for (child in dir.children().sortedBy { it.name }) {
                require(child.name.isNotBlank() && child.name !in listOf(".", "..") && '/' !in child.name && '\\' !in child.name)
                val childPath = if (path.isEmpty()) child.name else "$path/${child.name}"
                if (child.isDirectory) visit(child, childPath, depth + 1)
                else if (matches(child.name)) {
                    require(result.size < 20_000)
                    result += childPath to child
                }
            }
        }
        visit(root, prefix, 0)
        return result
    }

    private fun InputStream.readBounded(limit: Int): ByteArray {
        val bytes = readNBytesCompat(limit + 1)
        require(bytes.size <= limit)
        return bytes
    }

    // InputStream.readNBytes is not available on all supported Android versions.
    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() < limit) {
            val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) break
            if (count == 0) { val byte = read(); if (byte < 0) break; output.write(byte) }
            else output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        private val IMAGE_KINDS = setOf("servant", "ce", "friend")
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp")
        private const val MAX_SCRIPT_BYTES = 4 * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
    }
}
