package io.github.fate_grand_automata.ui.battle_config_list

/** A battle config as the folder grouping sees it. [folder] is "" when the config is not filed. */
data class FolderItem(val id: String, val name: String, val folder: String)

/** One folder with its configs, or one config that is in no folder. */
sealed class ConfigEntry {
    /** [key] is the normalised folder name (trimmed, lower case); [displayName] keeps the original spelling. */
    data class Folder(val key: String, val displayName: String, val items: List<FolderItem>) : ConfigEntry()
    data class Unfiled(val item: FolderItem) : ConfigEntry()
}

sealed class FolderNameResult {
    data class Valid(val name: String) : FolderNameResult()
    object Blank : FolderNameResult()
    object TooLong : FolderNameResult()
}

object ConfigFolders {
    const val MAX_NAME_LENGTH = 30

    fun key(folder: String) = folder.trim().lowercase()

    private val byName = compareBy<FolderItem, String>(String.CASE_INSENSITIVE_ORDER) { it.name }

    /**
     * Folders first, sorted by folder name; each holds its configs sorted by name.
     * Unfiled configs follow, sorted by name.
     */
    fun group(items: List<FolderItem>): List<ConfigEntry> {
        val sorted = items.sortedWith(byName)
        val folders = sorted
            .filter { key(it.folder).isNotEmpty() }
            .groupBy { key(it.folder) }
            .map { (key, members) ->
                // the first config in name order decides the displayed spelling
                ConfigEntry.Folder(key, members.first().folder.trim(), members)
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
        val unfiled = sorted
            .filter { key(it.folder).isEmpty() }
            .map { ConfigEntry.Unfiled(it) }
        return folders + unfiled
    }

    /** Validates a user-entered folder name; an existing folder with the same name (any case) wins. */
    fun normalizeName(input: String, existing: Collection<String>): FolderNameResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return FolderNameResult.Blank
        if (trimmed.length > MAX_NAME_LENGTH) return FolderNameResult.TooLong
        val match = existing.firstOrNull { key(it) == key(trimmed) }
        return FolderNameResult.Valid(match?.trim() ?: trimmed)
    }
}
