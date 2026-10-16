package io.github.fate_grand_automata.util.portraits

import kotlinx.serialization.json.*

enum class ServantNameTable(val file: String) {
    Base("mstSvt"), Ascension("mstSvtLimitAdd"), Hidden("mstSvtChange")
}

/** Official identity and display-name overrides, keyed by internal servant ID. */
object SupportServantNames {
    private val overrides = listOf("overWriteServantName", "overWriteServantBattleName",
        "overWriteServantShortName", "overwriteSvtDetailName")

    fun parse(text: String, table: ServantNameTable): Map<Int, Set<String>> {
        val rows = Json.parseToJsonElement(text) as? JsonArray ?: invalid()
        val names = linkedMapOf<Int, MutableSet<String>>()
        for (value in rows) {
            val row = value as? JsonObject ?: invalid()
            val id = (row[if (table == ServantNameTable.Base) "id" else "svtId"] as? JsonPrimitive)?.intOrNull ?: invalid()
            if (id <= 0) continue
            val fields = if (table == ServantNameTable.Ascension) {
                val script = row["script"] as? JsonObject
                overrides.map { script?.get(it) }
            } else listOf(row["name"], row["battleName"])
            for (field in fields) {
                if (field == null || field == JsonNull) continue
                val name = (field as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
                if (name.isNotBlank()) names.getOrPut(id) { linkedSetOf() }.add(name)
            }
        }
        return names
    }

    fun merge(parts: Iterable<Map<Int, Set<String>>>): Map<Int, Set<String>> {
        val names = linkedMapOf<Int, MutableSet<String>>()
        for (part in parts) for ((id, aliases) in part) names.getOrPut(id) { linkedSetOf() }.addAll(aliases)
        return names
    }

    fun encodeCache(names: Map<Int, Set<String>>): String = JsonObject(names.mapKeys { it.key.toString() }
        .mapValues { (_, values) -> JsonArray(values.map(::JsonPrimitive)) }).toString()

    fun parseCache(text: String): Map<Int, Set<String>> {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: invalid()
        return root.map { (key, value) ->
            val id = key.toIntOrNull()?.takeIf { it > 0 } ?: invalid()
            val values = value as? JsonArray ?: invalid()
            id to values.map { element ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: invalid()
            }.toSet()
        }.toMap()
    }

    private fun invalid(): Nothing = throw PortraitImportException(PortraitImportError.Metadata)
}
