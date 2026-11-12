package io.github.fate_grand_automata.scripts.capture.chaldea

import io.github.fate_grand_automata.scripts.capture.AccountState
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** The two servers the Chaldea sync supports. */
enum class ChaldeaServer(val region: String, val label: String, val fallbackName: String) {
    Kr("kr", "KR", "KR account"),
    Jp("jp", "JP", "JP account"),
}

/**
 * What FGA writes into one Chaldea user, built from the stored account tables (stage 1: items, mystic codes, class
 * boards, gender). A null map means the table was not in the state, so that Chaldea field is left alone.
 * Servant coins and `itemsLeft` are deliberately not part of stage 1.
 */
data class ChaldeaPatch(
    /** Trimmed `userGame.friendCode`, or null when missing or blank (the account cannot be identified). */
    val friendCode: String?,
    val server: ChaldeaServer,
    val nickname: String?,
    /** `genderType == 2`; null when the gender is unknown. */
    val isGirl: Boolean?,
    val items: Map<Long, Long>?,
    val mysticCodes: Map<Long, Long>?,
    val classBoards: Map<Long, Board>?,
) {
    data class Board(val unlockedSquares: List<Long>, val enhancedSquares: List<Long>)

    /** Name of a Chaldea user created or taken over for this account. */
    val userName: String
        get() = nickname?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it (${server.label})" } ?: server.fallbackName

    /** Canonical compact JSON: fixed key order, numeric keys sorted. Equal patches have equal text. */
    fun toJson(): String = buildJsonObject {
        friendCode?.let { put("friendCode", it) }
        put("server", server.region)
        nickname?.let { put("nickname", it) }
        isGirl?.let { put("isGirl", it) }
        items?.let { put("items", longMap(it)) }
        mysticCodes?.let { put("mysticCodes", longMap(it)) }
        classBoards?.let { boards ->
            put("classBoards", buildJsonObject {
                for ((id, board) in boards.toSortedMap()) put(id.toString(), boardJson(board))
            })
        }
    }.toString()

    /** Stable SHA-256 of [toJson], used to skip syncs when nothing relevant changed. */
    fun hash(): String = sha256Hex(toJson().toByteArray(Charsets.UTF_8))

    private fun longMap(map: Map<Long, Long>) = buildJsonObject {
        for ((k, v) in map.toSortedMap()) put(k.toString(), v)
    }

    companion object {
        internal fun boardJson(board: Board) = buildJsonObject {
            put("unlockedSquares", JsonArray(board.unlockedSquares.map { JsonPrimitive(it) }))
            put("enhancedSquares", JsonArray(board.enhancedSquares.map { JsonPrimitive(it) }))
        }

        // Chaldea's own import order: userItem rows first, then the userGame currencies override.
        private val currencyItems = listOf("qp" to 1L, "stone" to 2L, "mana" to 3L, "rarePri" to 18L)

        /** Parses only the tables it needs, one at a time, and keeps nothing. */
        fun from(state: AccountState, server: ChaldeaServer): ChaldeaPatch {
            val game = (rows(state, "userGame") ?: rows(state, "tblUserGame"))?.firstOrNull { it is JsonObject } as? JsonObject
            val friendCode = game?.get("friendCode").text()?.trim()?.takeIf { it.isNotEmpty() }
            val nickname = game?.get("name").text()?.trim()?.takeIf { it.isNotEmpty() }
            val gender = (game?.get("genderType") as? JsonPrimitive)?.longOrNull
            val itemRows = rows(state, "userItem")
            val items = if (itemRows == null && game == null) null else {
                val out = LinkedHashMap<Long, Long>()
                itemRows?.forEach { row ->
                    val o = row as? JsonObject ?: return@forEach
                    val id = (o["itemId"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                    val num = (o["num"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                    out[id] = num
                }
                currencyItems.forEach { (field, id) ->
                    ((game?.get(field)) as? JsonPrimitive)?.longOrNull?.let { out[id] = it }
                }
                out
            }
            val mystic = rows(state, "userEquip")?.let { rs ->
                val out = LinkedHashMap<Long, Long>()
                rs.forEach { row ->
                    val o = row as? JsonObject ?: return@forEach
                    val id = (o["equipId"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                    val lv = (o["lv"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                    out[id] = lv
                }
                out
            }
            val boards = rows(state, "userClassBoardSquare")?.let { rs ->
                val out = LinkedHashMap<Long, Board>()
                rs.forEach { row ->
                    val o = row as? JsonObject ?: return@forEach
                    val id = (o["classBoardBaseId"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                    out[id] = Board(ids(o["classBoardUnlockSquareIds"]), ids(o["classBoardSquareIds"]))
                }
                out
            }
            return ChaldeaPatch(friendCode, server, nickname, gender?.let { it == 2L }, items, mystic, boards)
        }

        /** Reads the text written by [toJson]; null when it has an unexpected shape. */
        fun fromJson(text: String): ChaldeaPatch? = try {
            val o = Json.parseToJsonElement(text) as? JsonObject
            val server = ChaldeaServer.entries.firstOrNull { it.region == (o?.get("server") as? JsonPrimitive)?.contentOrNull }
            if (o == null || server == null) null else {
                fun longs(name: String): Map<Long, Long>? = (o[name] as? JsonObject)?.entries?.associate {
                    it.key.toLong() to (it.value as JsonPrimitive).long
                }
                ChaldeaPatch(
                    friendCode = (o["friendCode"] as? JsonPrimitive)?.contentOrNull,
                    server = server,
                    nickname = (o["nickname"] as? JsonPrimitive)?.contentOrNull,
                    isGirl = (o["isGirl"] as? JsonPrimitive)?.booleanOrNull,
                    items = longs("items"),
                    mysticCodes = longs("mysticCodes"),
                    classBoards = (o["classBoards"] as? JsonObject)?.entries?.associate { (k, v) ->
                        val b = v.jsonObject
                        k.toLong() to Board(ids(b["unlockedSquares"]), ids(b["enhancedSquares"]))
                    },
                )
            }
        } catch (e: Exception) {
            null
        }

        private fun rows(state: AccountState, table: String): JsonArray? =
            state.rawTables[table]?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() as? JsonArray }

        private fun ids(e: JsonElement?): List<Long> =
            (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }?.distinct().orEmpty()

        private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    }
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
