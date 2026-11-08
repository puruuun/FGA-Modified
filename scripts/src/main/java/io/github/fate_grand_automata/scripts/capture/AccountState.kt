package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer

/**
 * Immutable account cache. Times are Unix seconds; no requests, headers or response history.
 *
 * Issue #258: the resident form is compact. Each table is kept as its JSON text and parsed only when a feature
 * reads it, because a parsed JsonElement tree costs about ten times the size of its text. The text is always
 * produced by [JsonElement.toString], so it is canonical: equal tables have equal text, and the Chaldea export and
 * the saved file embed it verbatim and stay byte-identical to the tree-based versions.
 */
class AccountState(
    val rawTables: Map<String, String>,
    val rawLoginResponse: String,
    val serverTime: JsonElement? = null,
    val snapshotTime: Long,
    val lastUpdateTime: Long,
) {
    constructor(
        tables: Map<String, JsonArray>,
        loginResponse: JsonArray,
        serverTime: JsonElement? = null,
        snapshotTime: Long,
        lastUpdateTime: Long,
    ) : this(tables.mapValues { it.value.toString() }, loginResponse.toString(), serverTime, snapshotTime, lastUpdateTime)

    /** Parsed on every read: do not hold the result. */
    val tables: Map<String, JsonArray> get() = TablesView(rawTables)
    val loginResponse: JsonArray get() = parseArray(rawLoginResponse)

    fun copy(
        tables: Map<String, JsonArray>? = null,
        loginResponse: JsonArray? = null,
        serverTime: JsonElement? = this.serverTime,
        snapshotTime: Long = this.snapshotTime,
        lastUpdateTime: Long = this.lastUpdateTime,
    ) = AccountState(
        tables?.mapValues { it.value.toString() } ?: rawTables,
        loginResponse?.toString() ?: rawLoginResponse, serverTime, snapshotTime, lastUpdateTime,
    )

    fun withRawTables(rawTables: Map<String, String>, lastUpdateTime: Long = this.lastUpdateTime) =
        AccountState(rawTables, rawLoginResponse, serverTime, snapshotTime, lastUpdateTime)

    override fun equals(other: Any?) = other is AccountState && rawTables == other.rawTables &&
        rawLoginResponse == other.rawLoginResponse && serverTime == other.serverTime &&
        snapshotTime == other.snapshotTime && lastUpdateTime == other.lastUpdateTime

    override fun hashCode() = listOf(rawTables, rawLoginResponse, serverTime, snapshotTime, lastUpdateTime).hashCode()

    override fun toString() = "AccountState(tables=${rawTables.keys}, snapshotTime=$snapshotTime, lastUpdateTime=$lastUpdateTime)"

    /** The saved file format: `{"tables":{..},"loginResponse":[..],"serverTime":..,"snapshotTime":..,"lastUpdateTime":..}`. */
    fun writeTo(out: Writer) {
        out.write("{\"tables\":{")
        var first = true
        for ((name, rows) in rawTables) {
            if (!first) out.write(",")
            first = false
            out.write(JsonPrimitive(name).toString())
            out.write(":")
            out.write(rows)
        }
        out.write("},\"loginResponse\":")
        out.write(rawLoginResponse)
        serverTime?.let { out.write(",\"serverTime\":"); out.write(it.toString()) }
        out.write(",\"snapshotTime\":$snapshotTime,\"lastUpdateTime\":$lastUpdateTime}")
    }

    fun writeTo(out: OutputStream) {
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writeTo(writer)
        writer.flush()
    }

    fun toJson(): String = java.io.StringWriter().also { writeTo(it) }.toString()

    /** A read-only Map over the tables; every get parses the text again. */
    private class TablesView(private val raw: Map<String, String>) : AbstractMap<String, JsonArray>() {
        override val size get() = raw.size
        override fun containsKey(key: String) = raw.containsKey(key)
        override fun get(key: String): JsonArray? = raw[key]?.let(::parseArray)
        override val keys: Set<String> get() = raw.keys
        override val entries: Set<Map.Entry<String, JsonArray>>
            get() = object : AbstractSet<Map.Entry<String, JsonArray>>() {
                override val size get() = raw.size
                override fun iterator() = raw.entries.asSequence()
                    .map<Map.Entry<String, String>, Map.Entry<String, JsonArray>> { java.util.AbstractMap.SimpleImmutableEntry(it.key, parseArray(it.value)) }
                    .iterator()
            }
    }

    /** Rows of one cache section, already reduced to what [reduceCache] needs. */
    class Sections(
        val replaced: Map<String, String> = emptyMap(),
        val updated: Map<String, JsonArray> = emptyMap(),
        val deleted: Map<String, JsonArray> = emptyMap(),
        val serverTime: JsonElement? = null,
    )

    /** Result of reading a login/top response as a stream. */
    class LoginRead(
        /** Only what [AccountRouting] reads: the own userGame rows of `cache.replaced`. */
        val routing: JsonObject,
        /** True for a successful login with its own userGame: the result does not depend on the previous state. */
        val replacesAccount: Boolean,
        /** Applies the response to the previous state exactly as [reduce] would. */
        val reducer: (previous: AccountState?, now: Long) -> AccountState?,
    )

    companion object {
        /** Other players' rows. They live in the friend roster instead, never in the account state. */
        val OTHER_PLAYER_TABLES = setOf("otherUserGame", "userFollower", "userRecommendFollower")

        private val json = Json

        private fun parseArray(text: String): JsonArray = json.parseToJsonElement(text) as JsonArray

        /** Older saved states may still hold other players' tables; they are dropped on load. */
        fun fromJson(value: String): AccountState = read(StateSerializer(), value)

        @OptIn(ExperimentalSerializationApi::class)
        fun fromStream(input: InputStream): AccountState = read(StateSerializer(), input)

        /** True when the saved file still holds other players' tables, so that the caller rewrites it. */
        @OptIn(ExperimentalSerializationApi::class)
        fun fromStreamChecked(input: InputStream): Pair<AccountState, Boolean> {
            val serializer = StateSerializer()
            val state = read(serializer, input)
            return state to serializer.droppedOtherTables
        }

        private fun read(serializer: StateSerializer, value: String): AccountState = json.decodeFromString(serializer, value)

        @OptIn(ExperimentalSerializationApi::class)
        private fun read(serializer: StateSerializer, input: InputStream): AccountState = json.decodeFromStream(serializer, input)

        /** Before a successful login snapshot, deltas cannot form an account. */
        fun reduce(previous: AccountState?, target: String, body: JsonObject, now: Long): AccountState? {
            val kind = CaptureNames.classify(target)
            if (kind == CaptureNames.ApiPath.Account) return previous
            val cache = body["cache"] as? JsonObject ?: return previous
            val response = body["response"] as? JsonArray
            val replaced = (cache["replaced"] as? JsonObject).sectionTables()
            val updated = (cache["updated"] as? JsonObject).sectionArrays()
            val deleted = (cache["deleted"] as? JsonObject).sectionArrays()
            val time = cache["serverTime"]?.takeUnless { it == JsonNull }
            return reduceCache(previous, kind, response?.toString(), Sections(replaced.mapValues { it.value.toString() }, updated, deleted, time), now)
        }

        private fun JsonObject?.sectionTables(): Map<String, JsonArray> =
            this?.entries?.filter { (table, rows) -> rows is JsonArray && table !in OTHER_PLAYER_TABLES }
                ?.associate { it.key to it.value as JsonArray }.orEmpty()

        private fun JsonObject?.sectionArrays() = sectionTables()

        /**
         * Reads a login/top response without a String or a second tree of the whole body (issue #258). Only one
         * table is a tree at a time; the others are kept as text. [reduce] and the returned reducer give equal states.
         */
        @OptIn(ExperimentalSerializationApi::class)
        fun readLogin(input: InputStream, target: String): LoginRead {
            val wire = json.decodeFromStream(LoginWire.serializer, input)
            val kind = CaptureNames.classify(target)
            val cache = wire.cache
            val userGame = cache?.replaced?.get("userGame")?.let(::parseArray)
            val routing = if (cache == null) JsonObject(emptyMap()) else JsonObject(mapOf("cache" to JsonObject(buildMap {
                if (userGame != null) put("replaced", JsonObject(mapOf("userGame" to userGame)))
            })))
            val sections = Sections(cache?.replaced.orEmpty(), cache?.updated.orEmpty(), cache?.deleted.orEmpty(),
                cache?.serverTime?.takeUnless { it == JsonNull })
            val replaces = kind == CaptureNames.ApiPath.Login && cache != null && isLoginSuccess(wire.response) &&
                userGame?.any { it is JsonObject } == true
            return LoginRead(routing, replaces) { previous, now ->
                if (kind == CaptureNames.ApiPath.Account || cache == null) previous
                else reduceCache(previous, kind, wire.response?.toString(), sections, now)
            }
        }

        private fun isLoginSuccess(response: JsonArray?) = response?.any {
            val row = it as? JsonObject
            row?.get("nid")?.jsonPrimitive?.contentOrNull == "login" &&
                row["resCode"]?.jsonPrimitive?.contentOrNull == "00"
        } == true

        /** The part of [reduce] that does not depend on how the body was parsed. */
        private fun reduceCache(previous: AccountState?, kind: CaptureNames.ApiPath, response: String?, sections: Sections, now: Long): AccountState? {
            val responseArray = response?.let(::parseArray)
            val login = kind == CaptureNames.ApiPath.Login && isLoginSuccess(responseArray)
            // A login without its own userGame is not a new account snapshot.
            if (login && sections.replaced["userGame"]?.let(::parseArray)?.any { it is JsonObject } != true) return previous
            val base = if (login) AccountState(emptyMap<String, String>(), response!!, snapshotTime = now, lastUpdateTime = now)
                else previous ?: return null
            val tables = LinkedHashMap(base.rawTables)
            sections.replaced.forEach { (table, rows) -> if (table !in OTHER_PLAYER_TABLES) tables[table] = rows }
            sections.updated.forEach { (table, rows) ->
                if (table in OTHER_PLAYER_TABLES) return@forEach
                val existing = tables[table]
                val current = existing?.let(::parseArray)?.toMutableList() ?: mutableListOf()
                var changed = existing == null
                for (row in rows) {
                    val key = rowKey(table, row) ?: continue
                    val index = current.indexOfFirst { rowKey(table, it) == key }
                    if (index < 0) { current += row; changed = true }
                    else if (current[index] != row) { current[index] = row; changed = true }
                }
                if (changed) tables[table] = JsonArray(current).toString()
            }
            sections.deleted.forEach { (table, rows) ->
                if (table in OTHER_PLAYER_TABLES) return@forEach
                val existing = tables[table] ?: return@forEach
                val keys = rows.mapNotNull { rowKey(table, it) }.toSet()
                if (keys.isEmpty()) return@forEach
                val rest = parseArray(existing)
                val kept = rest.filterNot { row -> rowKey(table, row) in keys }
                if (kept.size != rest.size) tables[table] = JsonArray(kept).toString()
            }
            val time = sections.serverTime ?: base.serverTime
            if (!login && tables == base.rawTables && time == base.serverTime) return base
            return AccountState(tables, base.rawLoginResponse, time, base.snapshotTime, now)
        }

        /** Composite fallback is sorted and excludes userId so table-wide updates remain stable. */
        fun rowKey(table: String, row: JsonElement): List<Pair<String, String>>? {
            val obj = row as? JsonObject ?: return null
            val fields = when (table) {
                "userGame", "tblUserGame" -> listOf("userId")
                "userItem" -> listOf("itemId")
                "userSvtCollection", "userSvtCoin", "userSvtVoicePlayed" -> listOf("svtId")
                "userSvtAppendPassiveSkillLv" -> listOf("userSvtId")
                "userQuest", "userQuestInfo" -> listOf("questId")
                "userEvent" -> listOf("eventId")
                "userCommandCodeCollection" -> listOf("commandCodeId")
                else -> if ("id" in obj) listOf("id")
                    else obj.keys.filter { it.endsWith("Id") && it != "userId" }.sorted()
            }
            if (fields.isEmpty()) return null
            return fields.map { field ->
                field to ((obj[field] as? JsonPrimitive)?.contentOrNull ?: return null)
            }
        }

        // ---- streaming readers -------------------------------------------------------------------------------

        private val tableMapDescriptor = MapSerializer(String.serializer(), JsonElement.serializer()).descriptor

        /** One row at a time, so a table that is skipped never exists as a whole tree. */
        private object DiscardRow : KSerializer<Unit> {
            override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
            override fun deserialize(decoder: Decoder) { (decoder as JsonDecoder).decodeJsonElement() }
            override fun serialize(encoder: Encoder, value: Unit) = throw UnsupportedOperationException()
        }

        private val discardRows = ListSerializer(DiscardRow)

        /**
         * A JSON object of tables as table name to JSON text. Only one table is a tree at a time. Other players'
         * tables are read row by row and dropped. Non-array values are ignored, like [reduce] does.
         */
        private class TextTables(private val onDropped: () -> Unit = {}, private val strict: Boolean = false) : KSerializer<Map<String, String>> {
            override val descriptor: SerialDescriptor = tableMapDescriptor
            override fun serialize(encoder: Encoder, value: Map<String, String>) = throw UnsupportedOperationException()
            override fun deserialize(decoder: Decoder): Map<String, String> {
                val out = LinkedHashMap<String, String>()
                val c = decoder.beginStructure(descriptor)
                while (true) {
                    val keyIndex = c.decodeElementIndex(descriptor)
                    if (keyIndex == CompositeDecoder.DECODE_DONE) break
                    val key = c.decodeSerializableElement(descriptor, keyIndex, String.serializer())
                    val valueIndex = c.decodeElementIndex(descriptor)
                    if (key in OTHER_PLAYER_TABLES) {
                        c.decodeSerializableElement(descriptor, valueIndex, discardRows)
                        onDropped()
                        continue
                    }
                    val element = c.decodeSerializableElement(descriptor, valueIndex, JsonElement.serializer())
                    if (element is JsonArray) out[key] = element.toString()
                    else check(!strict) { "table $key is not an array" }
                }
                c.endStructure(descriptor)
                return out
            }
        }

        private class ArrayTables : KSerializer<Map<String, JsonArray>> {
            private val inner = TextTables()
            override val descriptor: SerialDescriptor = tableMapDescriptor
            override fun serialize(encoder: Encoder, value: Map<String, JsonArray>) = throw UnsupportedOperationException()
            override fun deserialize(decoder: Decoder): Map<String, JsonArray> =
                inner.deserialize(decoder).mapValues { parseArray(it.value) }
        }

        private class RawElement : KSerializer<String> {
            override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
            override fun serialize(encoder: Encoder, value: String) = throw UnsupportedOperationException()
            override fun deserialize(decoder: Decoder): String = (decoder as JsonDecoder).decodeJsonElement().toString()
        }

        private class StateSerializer : KSerializer<AccountState> {
            var droppedOtherTables = false
                private set
            override val descriptor: SerialDescriptor = buildClassSerialDescriptor("AccountState") {
                element("tables", tableMapDescriptor)
                element("loginResponse", JsonElement.serializer().descriptor)
                element("serverTime", JsonElement.serializer().descriptor, isOptional = true)
                element("snapshotTime", Long.serializer().descriptor)
                element("lastUpdateTime", Long.serializer().descriptor)
            }
            override fun serialize(encoder: Encoder, value: AccountState) = throw UnsupportedOperationException()
            override fun deserialize(decoder: Decoder): AccountState {
                var tables: Map<String, String>? = null
                var login: String? = null
                var serverTime: JsonElement? = null
                var snapshot: Long? = null
                var updated: Long? = null
                val c = decoder.beginStructure(descriptor)
                while (true) {
                    when (val index = c.decodeElementIndex(descriptor)) {
                        CompositeDecoder.DECODE_DONE -> break
                        0 -> tables = c.decodeSerializableElement(descriptor, 0, TextTables({ droppedOtherTables = true }, strict = true))
                        1 -> login = c.decodeSerializableElement(descriptor, 1, RawElement())
                        2 -> serverTime = c.decodeNullableSerializableElement(descriptor, 2, JsonElement.serializer())
                        3 -> snapshot = c.decodeLongElement(descriptor, 3)
                        4 -> updated = c.decodeLongElement(descriptor, 4)
                        else -> error("Unexpected index $index")
                    }
                }
                c.endStructure(descriptor)
                checkNotNull(login) { "loginResponse missing" }
                return AccountState(
                    checkNotNull(tables) { "tables missing" }, login, serverTime,
                    checkNotNull(snapshot) { "snapshotTime missing" }, checkNotNull(updated) { "lastUpdateTime missing" },
                )
            }
        }

        private class LoginCache(
            val replaced: Map<String, String>?, val updated: Map<String, JsonArray>?, val deleted: Map<String, JsonArray>?,
            val serverTime: JsonElement?,
        )

        private class LoginWire(val response: JsonArray?, val cache: LoginCache?) {
            companion object {
                val serializer: KSerializer<LoginWire> = object : KSerializer<LoginWire> {
                    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("LoginTop") {
                        element("response", JsonElement.serializer().descriptor, isOptional = true)
                        element("cache", JsonElement.serializer().descriptor, isOptional = true)
                    }
                    override fun serialize(encoder: Encoder, value: LoginWire) = throw UnsupportedOperationException()
                    override fun deserialize(decoder: Decoder): LoginWire {
                        var response: JsonArray? = null
                        var cache: LoginCache? = null
                        val c = decoder.beginStructure(descriptor)
                        while (true) {
                            when (c.decodeElementIndex(descriptor)) {
                                CompositeDecoder.DECODE_DONE -> break
                                0 -> response = c.decodeNullableSerializableElement(descriptor, 0, JsonElement.serializer()) as? JsonArray
                                1 -> cache = c.decodeNullableSerializableElement(descriptor, 1, CacheSerializer)
                                else -> error("Unexpected index")
                            }
                        }
                        c.endStructure(descriptor)
                        return LoginWire(response, cache)
                    }
                }
            }
        }

        private object CacheSerializer : KSerializer<LoginCache> {
            override val descriptor: SerialDescriptor = buildClassSerialDescriptor("LoginCache") {
                element("replaced", tableMapDescriptor, isOptional = true)
                element("updated", tableMapDescriptor, isOptional = true)
                element("deleted", tableMapDescriptor, isOptional = true)
                element("serverTime", JsonElement.serializer().descriptor, isOptional = true)
            }
            override fun serialize(encoder: Encoder, value: LoginCache) = throw UnsupportedOperationException()
            override fun deserialize(decoder: Decoder): LoginCache {
                var replaced: Map<String, String>? = null
                var updated: Map<String, JsonArray>? = null
                var deleted: Map<String, JsonArray>? = null
                var serverTime: JsonElement? = null
                val c = decoder.beginStructure(descriptor)
                while (true) {
                    when (c.decodeElementIndex(descriptor)) {
                        CompositeDecoder.DECODE_DONE -> break
                        0 -> replaced = c.decodeNullableSerializableElement(descriptor, 0, TextTables())
                        1 -> updated = c.decodeNullableSerializableElement(descriptor, 1, ArrayTables())
                        2 -> deleted = c.decodeNullableSerializableElement(descriptor, 2, ArrayTables())
                        3 -> serverTime = c.decodeNullableSerializableElement(descriptor, 3, JsonElement.serializer())
                        else -> error("Unexpected index")
                    }
                }
                c.endStructure(descriptor)
                return LoginCache(replaced, updated, deleted, serverTime)
            }
        }
    }
}
