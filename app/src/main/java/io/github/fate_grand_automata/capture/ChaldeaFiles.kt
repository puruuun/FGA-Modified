package io.github.fate_grand_automata.capture

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Naming policy only. Callers must first restrict entries to files owned by this app. */
object ChaldeaFiles {
    private val pattern = Regex("chaldea-(?:account|kr)-([a-zA-Z0-9_-]+)-(\\d{8}-\\d{4}(?:-\\d{2})?)\\.json")
    private val legacy = Regex("chaldea-kr-\\d{8}-\\d{4}\\.json")
    private val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", java.util.Locale.ROOT)

    fun name(userId: String, time: LocalDateTime): String = "chaldea-account-$userId-${time.format(timestamp)}.json"

    private val secondStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm-ss", java.util.Locale.ROOT)

    /** Retry name when the fixed [name] cannot be written: the same pattern with the second added. */
    fun fallbackName(userId: String, time: LocalDateTime): String = "chaldea-account-$userId-${time.format(secondStamp)}.json"

    private fun stamp(name: String): String = pattern.matchEntire(name)?.groupValues?.get(2) ?: ""

    fun accountId(name: String): String? = pattern.matchEntire(name)?.groupValues?.get(1)
    fun isExport(name: String): Boolean = pattern.matches(name) || legacy.matches(name)

    /** Keep the freshly written name, even if the device clock moved backwards. */
    fun toDelete(names: List<String>, newest: String? = null): List<String> {
        val keep = names.filter { accountId(it) != null }.groupBy { accountId(it) }
            .mapValues { (id, files) -> newest?.takeIf { accountId(it) == id }
                ?: files.maxByOrNull { stamp(it) } }
        return names.filter { legacy.matches(it) || (accountId(it) != null && it != keep[accountId(it)]) }
    }
}
