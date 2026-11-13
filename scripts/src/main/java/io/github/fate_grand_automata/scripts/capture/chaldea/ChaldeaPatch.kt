package io.github.fate_grand_automata.scripts.capture.chaldea

import io.github.fate_grand_automata.scripts.capture.AccountState
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** The two servers the Chaldea sync supports. */
enum class ChaldeaServer(val region: String, val label: String, val fallbackName: String) {
    Kr("kr", "KR", "KR account"),
    Jp("jp", "JP", "JP account"),
}

/** Atlas id -> collectionNo maps used to map CEs and command codes when building a [ChaldeaPatch]. */
data class ChaldeaCollectionNumbers(
    val equips: Map<Long, Long>,
    val commandCodes: Map<Long, Long>,
) {
    companion object {
        val EMPTY = ChaldeaCollectionNumbers(emptyMap(), emptyMap())
    }
}

/**
 * What FGA writes into one Chaldea user, built from the stored account tables (items, mystic codes, class boards,
 * gender, servant coins, craft essences, command codes). A craft essence's level and limit break are the highest over
 * its held copies in `userSvt` and the storage room table `userSvtStorage`. A null map means the table (or required collection number
 * list) was absent, so that Chaldea field is left alone. `itemsLeft` and servant growth are not touched.
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
    val svtCoins: Map<Long, Long>? = null,
    val craftEssences: Map<Long, CeStatus>? = null,
    val commandCodes: Map<Long, CcStatus>? = null,
    /** Distinct command-code ids skipped because their collectionNo was unknown (not serialized or hashed). */
    val unmapped: Int = 0,
) {
    data class Board(val unlockedSquares: List<Long>, val enhancedSquares: List<Long>)

    data class CeStatus(val status: Long, val lv: Long?, val limitCount: Long?)

    data class CcStatus(val status: Long, val count: Long)

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
        svtCoins?.let { put("svtCoins", longMap(it)) }
        craftEssences?.let { ces ->
            put("craftEssences", buildJsonObject {
                for ((colNo, ce) in ces.toSortedMap()) put(colNo.toString(), ceJson(ce))
            })
        }
        commandCodes?.let { ccs ->
            put("commandCodes", buildJsonObject {
                for ((colNo, cc) in ccs.toSortedMap()) put(colNo.toString(), ccJson(cc))
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

        internal fun ceJson(ce: CeStatus) = buildJsonObject {
            put("status", ce.status)
            ce.lv?.let { put("lv", it) }
            ce.limitCount?.let { put("limitCount", it) }
        }

        internal fun ccJson(cc: CcStatus) = buildJsonObject {
            put("status", cc.status)
            put("count", cc.count)
        }

        // Chaldea's own import order: userItem rows first, then the userGame currencies override.
        private val currencyItems = listOf("qp" to 1L, "stone" to 2L, "mana" to 3L, "rarePri" to 18L)

        /** Parses only the tables it needs, one at a time, and keeps nothing. */
        fun from(
            state: AccountState,
            server: ChaldeaServer,
            numbers: ChaldeaCollectionNumbers = ChaldeaCollectionNumbers.EMPTY,
        ): ChaldeaPatch {
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
            val svtCoins = rows(state, "userSvtCoin")?.let { rs ->
                val out = LinkedHashMap<Long, Long>()
                rs.forEach { row ->
                    val o = row as? JsonObject ?: return@forEach
                    val svtId = (o["svtId"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                    val num = (o["num"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                    out[svtId * 100L + 1L] = num
                }
                out
            }
            val svtCollectionRows = rows(state, "userSvtCollection")
            val hasSvtTables = svtCollectionRows != null ||
                rows(state, "userSvt") != null || rows(state, "userSvtStorage") != null
            val craftEssences = if (numbers.equips.isEmpty() || !hasSvtTables) {
                null
            } else {
                val statusByCol = LinkedHashMap<Long, Long>()
                svtCollectionRows?.forEach { row ->
                    val o = row as? JsonObject ?: return@forEach
                    val svtId = (o["svtId"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                    val colNo = numbers.equips[svtId] ?: return@forEach
                    val status = (o["status"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                    if (status != 1L && status != 2L) return@forEach
                    statusByCol[colNo] = maxOf(statusByCol[colNo] ?: 0L, status)
                }
                val maxLv = LinkedHashMap<Long, Long>()
                val maxLimit = LinkedHashMap<Long, Long>()
                val heldCols = LinkedHashSet<Long>()
                // Held copies live in userSvt and in the storage room (userSvtStorage); one table at a time.
                for (table in listOf("userSvt", "userSvtStorage")) {
                    rows(state, table)?.forEach { row ->
                        val o = row as? JsonObject ?: return@forEach
                        val svtId = (o["svtId"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                        val colNo = numbers.equips[svtId] ?: return@forEach
                        heldCols += colNo
                        (o["lv"] as? JsonPrimitive)?.longOrNull?.let { maxLv.merge(colNo, it, ::maxOf) }
                        (o["limitCount"] as? JsonPrimitive)?.longOrNull?.let { maxLimit.merge(colNo, it, ::maxOf) }
                    }
                }
                val out = LinkedHashMap<Long, CeStatus>()
                for (colNo in (statusByCol.keys + heldCols)) {
                    out[colNo] = CeStatus(statusByCol[colNo] ?: 2L, maxLv[colNo], maxLimit[colNo])
                }
                out
            }
            val unmappedIds = LinkedHashSet<Long>()
            val ccCollectionRows = rows(state, "userCommandCodeCollection")
            val ccRows = rows(state, "userCommandCode")
            val commandCodes = if (ccCollectionRows == null && ccRows == null) {
                null
            } else {
                val statusByCol = LinkedHashMap<Long, Long>()
                ccCollectionRows?.forEach { row ->
                    val o = row as? JsonObject ?: return@forEach
                    val ccId = (o["commandCodeId"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                    val status = (o["status"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                    if (status != 1L && status != 2L) return@forEach
                    val colNo = numbers.commandCodes[ccId]
                    if (colNo == null || colNo <= 0L) {
                        unmappedIds += ccId
                        return@forEach
                    }
                    statusByCol[colNo] = maxOf(statusByCol[colNo] ?: 0L, status)
                }
                val countByCol = LinkedHashMap<Long, Long>()
                ccRows?.forEach { row ->
                    val o = row as? JsonObject ?: return@forEach
                    val ccId = (o["commandCodeId"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                    val colNo = numbers.commandCodes[ccId]
                    if (colNo == null || colNo <= 0L) {
                        unmappedIds += ccId
                        return@forEach
                    }
                    countByCol[colNo] = (countByCol[colNo] ?: 0L) + 1L
                }
                if (numbers.commandCodes.isEmpty()) {
                    null
                } else {
                    val out = LinkedHashMap<Long, CcStatus>()
                    for (colNo in (statusByCol.keys + countByCol.keys)) {
                        out[colNo] = CcStatus(statusByCol[colNo] ?: 2L, countByCol[colNo] ?: 0L)
                    }
                    out
                }
            }
            return ChaldeaPatch(
                friendCode,
                server,
                nickname,
                gender?.let { it == 2L },
                items,
                mystic,
                boards,
                svtCoins,
                craftEssences,
                commandCodes,
                unmappedIds.size,
            )
        }

        /** Reads the text written by [toJson]; null when it has an unexpected shape. */
        fun fromJson(text: String): ChaldeaPatch? = try {
            val o = Json.parseToJsonElement(text) as? JsonObject
            val server = ChaldeaServer.entries.firstOrNull { it.region == (o?.get("server") as? JsonPrimitive)?.contentOrNull }
            if (o == null || server == null) null else {
                fun obj(name: String): JsonObject? = o[name]?.takeUnless { it is JsonNull }?.jsonObject
                fun longs(name: String): Map<Long, Long>? = obj(name)?.entries?.associate {
                    it.key.toLong() to (it.value as JsonPrimitive).long
                }
                ChaldeaPatch(
                    friendCode = (o["friendCode"] as? JsonPrimitive)?.contentOrNull,
                    server = server,
                    nickname = (o["nickname"] as? JsonPrimitive)?.contentOrNull,
                    isGirl = (o["isGirl"] as? JsonPrimitive)?.booleanOrNull,
                    items = longs("items"),
                    mysticCodes = longs("mysticCodes"),
                    classBoards = obj("classBoards")?.entries?.associate { (k, v) ->
                        val b = v.jsonObject
                        k.toLong() to Board(ids(b["unlockedSquares"]), ids(b["enhancedSquares"]))
                    },
                    svtCoins = longs("svtCoins"),
                    craftEssences = obj("craftEssences")?.entries?.associate { (k, v) ->
                        val c = v.jsonObject
                        k.toLong() to CeStatus(
                            status = (c["status"] as JsonPrimitive).long,
                            lv = (c["lv"] as? JsonPrimitive)?.longOrNull,
                            limitCount = (c["limitCount"] as? JsonPrimitive)?.longOrNull,
                        )
                    },
                    commandCodes = obj("commandCodes")?.entries?.associate { (k, v) ->
                        val c = v.jsonObject
                        k.toLong() to CcStatus(
                            status = (c["status"] as JsonPrimitive).long,
                            count = (c["count"] as JsonPrimitive).long,
                        )
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
