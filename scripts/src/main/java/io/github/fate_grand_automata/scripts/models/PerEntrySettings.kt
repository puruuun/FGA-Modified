package io.github.fate_grand_automata.scripts.models

import io.github.fate_grand_automata.scripts.enums.CardPriorityStyle

/**
 * Per wave or turn entry card priority settings (style and servant priority switch).
 * An empty stored list means "not set per entry": every entry then uses the old single value.
 * A list shorter than the entries repeats its last value.
 */
object PerEntrySettings {
    private const val separator = ","

    /** The value for [index]: the last value repeats past the end, [default] is used for an empty list. */
    fun <T> at(values: List<T>, index: Int, default: T): T =
        if (values.isEmpty()) default else values[index.coerceIn(values.indices)]

    /** The stored per-entry values, or the old single [legacy] value when nothing is stored. */
    fun <T> withLegacy(values: List<T>, legacy: T): List<T> = values.ifEmpty { listOf(legacy) }

    fun parseStyles(text: String): List<CardPriorityStyle> =
        if (text.isBlank()) emptyList()
        else text.split(separator).map { part ->
            CardPriorityStyle.entries.firstOrNull { it.name == part.trim() } ?: CardPriorityStyle.Affinity
        }

    fun serializeStyles(styles: List<CardPriorityStyle>) = styles.joinToString(separator) { it.name }

    fun parseFlags(text: String): List<Boolean> =
        if (text.isBlank()) emptyList()
        else text.split(separator).map { it.trim().equals("true", ignoreCase = true) }

    fun serializeFlags(flags: List<Boolean>) = flags.joinToString(separator) { it.toString() }
}
