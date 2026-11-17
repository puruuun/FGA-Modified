package io.github.fate_grand_automata.capture

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Naming policy only. Callers must first restrict entries to files owned by this app. */
object ChaldeaFiles {
    private val pattern = Regex("chaldea-(?:account|kr)-([a-zA-Z0-9_-]+)-(\\d{8}-\\d{4}(?:-\\d{2})?)\\.json")
    private val legacy = Regex("chaldea-kr-\\d{8}-\\d{4}\\.json")

    fun name(userId: String, timeMillis: Long): String =
        "chaldea-account-$userId-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date(timeMillis))}.json"

    /** Retry name when the fixed [name] cannot be written: the same pattern with the second added. */
    fun fallbackName(userId: String, timeMillis: Long): String =
        "chaldea-account-$userId-${SimpleDateFormat("yyyyMMdd-HHmm-ss", Locale.ROOT).format(Date(timeMillis))}.json"

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
