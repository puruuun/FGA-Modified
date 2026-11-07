package io.github.fate_grand_automata.capture

import java.io.File

/**
 * The removed main-screen event card cached these files. Deletes exactly them, once per start,
 * and nothing else in the names directory. Missing files and a missing directory are fine.
 */
internal fun deleteLegacyEventCache(namesDir: File) {
    if (!namesDir.isDirectory) return
    val exact = setOf(
        "basic_event.json", "event_bonuses.json", "event_banners.json",
        "basic_event.attempt", "event_bonuses.attempt",
    )
    namesDir.listFiles()?.forEach {
        if (it.isFile && (it.name in exact || (it.name.startsWith("event_banner_") && it.name.endsWith(".attempt")))) {
            runCatching { it.delete() }
        }
    }
}
