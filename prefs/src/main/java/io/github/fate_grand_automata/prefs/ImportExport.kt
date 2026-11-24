package io.github.fate_grand_automata.prefs

import android.content.SharedPreferences
import io.github.fate_grand_automata.prefs.core.BattleConfigCore
import io.github.fate_grand_automata.prefs.core.PrefType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

fun SharedPreferences.Editor.import(map: Map<String, *>) {
    for ((key, value) in map) {
        when (value) {
            is String -> putString(key, value)
            is Int -> putInt(key, value)
            is Boolean -> putBoolean(key, value)
            is Collection<*> -> putStringSet(key, value.map { it.toString() }.toSet())
        }
    }
}

/**
 * The `.fga` file format: one battle config's whole [SharedPreferences] map as a flat JSON object.
 *
 * [PrefMaker][io.github.fate_grand_automata.prefs.core.PrefMaker] only ever stores strings, ints,
 * booleans and string sets, and those are the types [import] puts back, so those are the types
 * that survive a round trip. Other values are still written out, but importing skips them.
 */
object BattleConfigFile {
    private val json = Json

    /** The per-config support list filter switch of #685, retired by #695. */
    private val RETIRED_KEYS = setOf(io.github.fate_grand_automata.prefs.core.PrefsCore.LEGACY_SUPPORT_LIST_FILTER_KEY)

    fun encode(values: Map<String, *>): String {
        val entries = values.mapNotNull { (key, value) ->
            value.toJson()?.let { key to it }
        }

        return json.encodeToString(JsonObject.serializer(), JsonObject(entries.toMap()))
    }

    /**
     * Decodes a `.fga` JSON object and validates every known battle-config preference against
     * its declared type in [BattleConfigCore].
     *
     * @throws kotlinx.serialization.SerializationException if [text] is not a JSON object.
     * @throws IllegalArgumentException if any known preference has an incompatible value type.
     */
    fun decode(text: String): Map<String, Any> {
        val rawObject = json.parseToJsonElement(text).jsonObject
        val schema = BattleConfigCore.prefTypes
        val decoded = LinkedHashMap<String, Any>(rawObject.size)

        for ((key, element) in rawObject) {
            // Every `.fga` import (the config list and the original FGA folder) comes through here, so a key that no
            // version reads any more is dropped in this one place and does not make a re-import look different.
            if (key in RETIRED_KEYS) continue
            if (schema[key] == PrefType.StringSet && element is JsonArray) {
                if (!element.all { it is JsonPrimitive && it.isString }) {
                    throw IllegalArgumentException("Expected array of strings for '$key'")
                }
            }
            val value = element.toPrefValue() ?: continue
            decoded[key] = value
        }

        return validate(decoded, schema)
    }

    internal fun validate(
        values: Map<String, Any>,
        schema: Map<String, PrefType> = BattleConfigCore.prefTypes
    ): Map<String, Any> {
        val validated = LinkedHashMap<String, Any>(values.size)
        for ((key, value) in values) {
            val expectedType = schema[key]
            validated[key] = if (expectedType != null) {
                normalizeForType(key, value, expectedType)
            } else {
                value
            }
        }
        return validated
    }

    private fun normalizeForType(key: String, value: Any, expectedType: PrefType): Any =
        when (expectedType) {
            PrefType.String -> value as? String
                ?: throw IllegalArgumentException("Expected String for '$key', got ${value::class.simpleName}")

            PrefType.Boolean -> value as? Boolean
                ?: throw IllegalArgumentException("Expected Boolean for '$key', got ${value::class.simpleName}")

            PrefType.Int -> when (value) {
                is Int -> value
                is Long -> {
                    if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt()
                    else throw IllegalArgumentException("Expected Int in range for '$key', got Long $value")
                }

                else -> throw IllegalArgumentException("Expected Int for '$key', got ${value::class.simpleName}")
            }

            PrefType.StringSet -> {
                if (value is Collection<*> && value.all { it is String }) value
                else throw IllegalArgumentException("Expected StringSet for '$key', got ${value::class.simpleName}")
            }
        }

    private fun Any?.toJson(): JsonElement? = when (this) {
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Collection<*> -> JsonArray(map { JsonPrimitive(it.toString()) })
        else -> null
    }

    /*
     * JSON has one number type, so the whole-number cases have to be picked apart by hand:
     * reading them all back as doubles - which is what an untyped parse gives you - would make
     * every int-valued preference unimportable.
     */
    private fun JsonElement.toPrefValue(): Any? = when {
        this is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.content }
        this !is JsonPrimitive || this is JsonNull -> null
        isString -> content
        else -> booleanOrNull ?: intOrNull ?: longOrNull ?: doubleOrNull
    }
}
