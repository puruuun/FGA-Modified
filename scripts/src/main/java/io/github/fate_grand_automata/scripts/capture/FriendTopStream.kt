package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import java.io.InputStream

/**
 * Bounded-memory reader for the JP friend/top response (issue #254). The decoded body is tens of MB, so it is
 * never turned into a String or a JsonElement tree. Only the slices the roster and the account routing read are
 * kept; every other value is skipped by the streaming decoder. The result is a small JsonObject with the same
 * shape as the full body for those slices, so FriendRoster and AccountRouting behave exactly as before.
 */
object FriendTopStream {
    private val OWN_TABLES = listOf(
        "userGame", "userItem", "userSvt", "userSvtCollection", "userQuest", "userEquip",
        "userEvent", "userCommandCode",
    )

    /** Reads one row at a time and keeps only its direct userId (null if the row has none). */
    private object UserIdOnly : KSerializer<String?> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UserIdOnly", PrimitiveKind.STRING)
        override fun deserialize(decoder: Decoder): String? {
            val element = (decoder as JsonDecoder).decodeJsonElement()
            return ((element as? JsonObject)?.get("userId") as? JsonPrimitive)?.contentOrNull
        }
        override fun serialize(encoder: Encoder, value: String?) = throw UnsupportedOperationException()
    }

    private val ids = ListSerializer(UserIdOnly)

    @Serializable
    private class Section(
        val tblFriend: List<JsonElement>? = null,
        val otherUserGame: List<JsonElement>? = null,
        @Serializable(with = IdsSerializer::class) val userGame: List<String?>? = null,
        @Serializable(with = IdsSerializer::class) val userItem: List<String?>? = null,
        @Serializable(with = IdsSerializer::class) val userSvt: List<String?>? = null,
        @Serializable(with = IdsSerializer::class) val userSvtCollection: List<String?>? = null,
        @Serializable(with = IdsSerializer::class) val userQuest: List<String?>? = null,
        @Serializable(with = IdsSerializer::class) val userEquip: List<String?>? = null,
        @Serializable(with = IdsSerializer::class) val userEvent: List<String?>? = null,
        @Serializable(with = IdsSerializer::class) val userCommandCode: List<String?>? = null,
    ) {
        fun own(table: String): List<String?>? = when (table) {
            "userGame" -> userGame
            "userItem" -> userItem
            "userSvt" -> userSvt
            "userSvtCollection" -> userSvtCollection
            "userQuest" -> userQuest
            "userEquip" -> userEquip
            "userEvent" -> userEvent
            else -> userCommandCode
        }
    }

    private object IdsSerializer : KSerializer<List<String?>> {
        override val descriptor: SerialDescriptor = ids.descriptor
        override fun deserialize(decoder: Decoder): List<String?> = ids.deserialize(decoder)
        override fun serialize(encoder: Encoder, value: List<String?>) = throw UnsupportedOperationException()
    }

    @Serializable
    private class Cache(
        val replaced: Section? = null,
        val updated: Section? = null,
        val deleted: Section? = null,
        val serverTime: JsonPrimitive? = null,
    )

    @Serializable
    private class Slice(val cache: Cache? = null)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Returns the reduced body, or null when the stream has no object at the top. Malformed JSON, or a slice with
     * an unexpected type, throws; the caller drops the response.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun parse(input: InputStream): JsonObject {
        val cache = json.decodeFromStream<Slice>(input).cache ?: return JsonObject(emptyMap())
        fun section(name: String, value: Section?, keepFriends: Boolean): Pair<String, JsonElement>? {
            value ?: return null
            val fields = LinkedHashMap<String, JsonElement>()
            if (keepFriends) value.tblFriend?.let { fields["tblFriend"] = JsonArray(it) }
            if (name == "updated") value.otherUserGame?.let { fields["otherUserGame"] = JsonArray(it) }
            for (table in OWN_TABLES) {
                val rows = value.own(table)?.filterNotNull()?.filter { it.isNotBlank() }?.distinct() ?: continue
                fields[table] = JsonArray(rows.map { JsonObject(mapOf("userId" to JsonPrimitive(it))) })
            }
            return name to JsonObject(fields)
        }
        val out = LinkedHashMap<String, JsonElement>()
        listOf(
            section("replaced", cache.replaced, true),
            section("updated", cache.updated, true),
            section("deleted", cache.deleted, false),
        ).filterNotNull().forEach { out[it.first] = it.second }
        cache.serverTime?.let { out["serverTime"] = it }
        return JsonObject(mapOf("cache" to JsonObject(out)))
    }
}
