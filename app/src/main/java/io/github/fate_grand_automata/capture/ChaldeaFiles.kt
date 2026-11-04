package io.github.fate_grand_automata.capture

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Naming policy only. Callers must first restrict entries to files owned by this app. */
object ChaldeaFiles {
    private val pattern = Regex("chaldea-kr-\\d{8}-\\d{4}\\.json")
    private val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", java.util.Locale.ROOT)

    fun name(time: LocalDateTime): String = "chaldea-kr-${time.format(timestamp)}.json"

    fun isExport(name: String): Boolean = pattern.matches(name)

    /** Keep the freshly written name, even if the device clock moved backwards. */
    fun toDelete(names: List<String>, newest: String? = names.filter(::isExport).maxOrNull()): List<String> =
        names.filter { isExport(it) && it != newest }
}
