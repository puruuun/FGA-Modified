package io.github.fate_grand_automata.scripts.capture.chaldea

import io.github.fate_grand_automata.scripts.capture.AccountState
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** The two servers the Chaldea sync supports. */
enum class ChaldeaServer(val region: String, val label: String, val fallbackName: String) {
    Kr("kr", "KR", "KR account"),
    Jp("jp", "JP", "JP account"),
}

/** Atlas id -> collectionNo maps used to map CEs, command codes, and servants when building a [ChaldeaPatch]. */
data class ChaldeaCollectionNumbers(
    val equips: Map<Long, Long>,
    val commandCodes: Map<Long, Long>,
    val servants: Map<Long, Long> = emptyMap(),
) {
    companion object {
        val EMPTY = ChaldeaCollectionNumbers(emptyMap(), emptyMap(), emptyMap())
    }
}

/**
 * What FGA writes into one Chaldea user, built from the stored account tables (items, mystic codes, class boards,
 * gender, servant coins, craft essences, command codes, servant growth). A craft essence's level and limit break are
 * the highest over its held copies in `userSvt` and the storage room table `userSvtStorage`. A null map means the
 * table (or required collection number list) was absent, so that Chaldea field is left alone. `itemsLeft` and plans
 * are not touched.
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
    val servants: Map<Long, SvtStatus>? = null,
) {
    data class Board(val unlockedSquares: List<Long>, val enhancedSquares: List<Long>)

    data class CeStatus(val status: Long, val lv: Long?, val limitCount: Long?)

    data class CcStatus(val status: Long, val count: Long)

    /**
     * Per-servant growth patch. Every field is nullable so "null = do not touch" during the per-field merge in
     * [ChaldeaUserdataEditor]. `fouHp`/`fouAtk` hold Chaldea's Fou 4 values (matching its JSON keys).
     */
    data class SvtStatus(
        val favorite: Boolean? = null,
        val ascension: Long? = null,
        val skills: List<Long>? = null,
        val appendSkills: List<Long>? = null,
        val costumes: Map<Long, Long>? = null,
        val grail: Long? = null,
        val fouHp5: Long? = null,
        val fouAtk5: Long? = null,
        val fouHp: Long? = null,
        val fouAtk: Long? = null,
        val fouHp3: Long? = null,
        val fouAtk3: Long? = null,
        val bondLimit: Long? = null,
        val npLv: Long? = null,
        val bond: Long? = null,
        val equipCmdCodes: List<Long?>? = null,
        val cmdCardStrengthen: List<Long>? = null,
        val grandSvt: Boolean? = null,
    )

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
        servants?.let { svts ->
            put("servants", buildJsonObject {
                for ((colNo, svt) in svts.toSortedMap()) put(colNo.toString(), svtJson(svt))
            })
        }
    }.toString()

    /** Stable SHA-256 of [toJson], used to skip syncs when nothing relevant changed. */
    fun hash(): String = sha256Hex(toJson().toByteArray(Charsets.UTF_8))

    private fun longMap(map: Map<Long, Long>) = buildJsonObject {
        for ((k, v) in map.toSortedMap()) put(k.toString(), v)
    }

    companion object {
        /** Binder parcel size guard: refuse patches whose JSON exceeds 350,000 characters (~700 KB UTF-16). */
        const val MAX_JSON_CHARS = 350_000

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

        internal fun svtJson(s: SvtStatus) = buildJsonObject {
            s.appendSkills?.let { list -> put("appendSkills", JsonArray(list.map(::JsonPrimitive))) }
            s.ascension?.let { put("ascension", it) }
            s.bond?.let { put("bond", it) }
            s.bondLimit?.let { put("bondLimit", it) }
            s.cmdCardStrengthen?.let { list -> put("cmdCardStrengthen", JsonArray(list.map(::JsonPrimitive))) }
            s.costumes?.let { map ->
                put("costumes", buildJsonObject {
                    for ((k, v) in map.toSortedMap()) put(k.toString(), v)
                })
            }
            s.equipCmdCodes?.let { list ->
                put("equipCmdCodes", JsonArray(list.map { id -> id?.let(::JsonPrimitive) ?: JsonNull }))
            }
            s.favorite?.let { put("favorite", it) }
            s.fouAtk?.let { put("fouAtk", it) }
            s.fouAtk3?.let { put("fouAtk3", it) }
            s.fouAtk5?.let { put("fouAtk5", it) }
            s.fouHp?.let { put("fouHp", it) }
            s.fouHp3?.let { put("fouHp3", it) }
            s.fouHp5?.let { put("fouHp5", it) }
            s.grail?.let { put("grail", it) }
            s.grandSvt?.let { put("grandSvt", it) }
            s.npLv?.let { put("npLv", it) }
            s.skills?.let { list -> put("skills", JsonArray(list.map(::JsonPrimitive))) }
        }

        private class SvtColRow(
            val friendshipRank: Long,
            val friendshipExceedCount: Long,
            val costumeIds: List<Long>,
        )

        private class RawSvtCopy(
            val id: Long,
            val lv: Long,
            val skillLv1: Long,
            val skillLv2: Long,
            val skillLv3: Long,
            val createdAt: Long,
            val status: Long,
            val isLock: Long?,
            val treasureDeviceLv1: Long,
            val limitCount: Long,
            val exceedCount: Long,
            val adjustHp: Long,
            val adjustAtk: Long,
        ) {
            val skillSum: Long get() = skillLv1 + skillLv2 + skillLv3
        }

        private val svtCopyComparator = compareByDescending<RawSvtCopy> { it.lv }
            .thenByDescending { it.skillSum }
            .thenBy { it.createdAt }
            .thenByDescending { it.id }

        // Chaldea's own import order: userItem rows first, then the userGame currencies override.
        private val currencyItems = listOf("qp" to 1L, "stone" to 2L, "mana" to 3L, "rarePri" to 18L)

        /** Parses only the tables it needs, one at a time, and keeps nothing. */
        fun from(
            state: AccountState,
            server: ChaldeaServer,
            numbers: ChaldeaCollectionNumbers = ChaldeaCollectionNumbers.EMPTY,
            gameData: ChaldeaGameData? = null,
        ): ChaldeaPatch = fromWithParser(state, server, numbers, gameData)

        internal fun fromWithParser(
            state: AccountState,
            server: ChaldeaServer,
            numbers: ChaldeaCollectionNumbers = ChaldeaCollectionNumbers.EMPTY,
            gameData: ChaldeaGameData? = null,
            parseJson: (String) -> JsonElement? = { runCatching { Json.parseToJsonElement(it) }.getOrNull() },
        ): ChaldeaPatch {
            fun rows(table: String): JsonArray? =
                state.rawTables[table]?.let { parseJson(it) as? JsonArray }

            val game = (rows("userGame") ?: rows("tblUserGame"))?.firstOrNull { it is JsonObject } as? JsonObject
            val friendCode = game?.get("friendCode").text()?.trim()?.takeIf { it.isNotEmpty() }
            val nickname = game?.get("name").text()?.trim()?.takeIf { it.isNotEmpty() }
            val gender = (game?.get("genderType") as? JsonPrimitive)?.longOrNull
            val itemRows = rows("userItem")
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
            val mystic = rows("userEquip")?.let { rs ->
                val out = LinkedHashMap<Long, Long>()
                rs.forEach { row ->
                    val o = row as? JsonObject ?: return@forEach
                    val id = (o["equipId"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                    val lv = (o["lv"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                    out[id] = lv
                }
                out
            }
            val boards = rows("userClassBoardSquare")?.let { rs ->
                val out = LinkedHashMap<Long, Board>()
                rs.forEach { row ->
                    val o = row as? JsonObject ?: return@forEach
                    val id = (o["classBoardBaseId"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                    out[id] = Board(ids(o["classBoardUnlockSquareIds"]), ids(o["classBoardSquareIds"]))
                }
                out
            }
            val svtCoins = rows("userSvtCoin")?.let { rs ->
                val out = LinkedHashMap<Long, Long>()
                rs.forEach { row ->
                    val o = row as? JsonObject ?: return@forEach
                    val svtId = (o["svtId"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                    val num = (o["num"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                    out[svtId * 100L + 1L] = num
                }
                out
            }

            // Parse each big table (userSvtCollection, userSvt, userSvtStorage) at most once and extract only compact data.
            val partnerIds = gameData?.transforms?.values?.toSet().orEmpty()
            val ceStatusByCol = LinkedHashMap<Long, Long>()
            val ceColMaxLv = LinkedHashMap<Long, Long>()
            val ceColMaxLimit = LinkedHashMap<Long, Long>()
            val svtCollection = LinkedHashMap<Long, SvtColRow>()
            var hasSvtCollection = false
            rows("userSvtCollection")?.let { rs ->
                hasSvtCollection = true
                rs.forEach { row ->
                    val o = row as? JsonObject ?: return@forEach
                    val svtId = (o["svtId"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                    val equipCol = numbers.equips[svtId]
                    if (equipCol != null) {
                        val status = (o["status"] as? JsonPrimitive)?.longOrNull
                        if (status == 1L || status == 2L) {
                            ceStatusByCol[equipCol] = maxOf(ceStatusByCol[equipCol] ?: 0L, status)
                            if (status == 2L) {
                                (o["maxLv"] as? JsonPrimitive)?.longOrNull?.let { ceColMaxLv.merge(equipCol, it, ::maxOf) }
                                (o["maxLimitCount"] as? JsonPrimitive)?.longOrNull?.let { ceColMaxLimit.merge(equipCol, it, ::maxOf) }
                            }
                        }
                    }
                    if (numbers.servants.isNotEmpty() && (svtId in numbers.servants || svtId in partnerIds)) {
                        svtCollection[svtId] = SvtColRow(
                            friendshipRank = (o["friendshipRank"] as? JsonPrimitive)?.longOrNull ?: 0L,
                            friendshipExceedCount = (o["friendshipExceedCount"] as? JsonPrimitive)?.longOrNull ?: 0L,
                            costumeIds = ids(o["costumeIds"]),
                        )
                    }
                }
            }

            val ceHeldMaxLv = LinkedHashMap<Long, Long>()
            val ceHeldMaxLimit = LinkedHashMap<Long, Long>()
            val heldCeCols = LinkedHashSet<Long>()
            val bestSvtCopy = LinkedHashMap<Long, RawSvtCopy>()
            val collectServantCopies = numbers.servants.isNotEmpty() && hasSvtCollection
            var hasCardTables = false
            for (table in listOf("userSvt", "userSvtStorage")) {
                rows(table)?.let { rs ->
                    hasCardTables = true
                    rs.forEach { row ->
                        val o = row as? JsonObject ?: return@forEach
                        val svtId = (o["svtId"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                        val equipCol = numbers.equips[svtId]
                        if (equipCol != null) {
                            heldCeCols += equipCol
                            (o["lv"] as? JsonPrimitive)?.longOrNull?.let { ceHeldMaxLv.merge(equipCol, it, ::maxOf) }
                            (o["limitCount"] as? JsonPrimitive)?.longOrNull?.let { ceHeldMaxLimit.merge(equipCol, it, ::maxOf) }
                        }
                        if (collectServantCopies && svtId in numbers.servants) {
                            val candidate = RawSvtCopy(
                                id = (o["id"] as? JsonPrimitive)?.longOrNull ?: 0L,
                                lv = (o["lv"] as? JsonPrimitive)?.longOrNull ?: 0L,
                                skillLv1 = (o["skillLv1"] as? JsonPrimitive)?.longOrNull ?: 0L,
                                skillLv2 = (o["skillLv2"] as? JsonPrimitive)?.longOrNull ?: 0L,
                                skillLv3 = (o["skillLv3"] as? JsonPrimitive)?.longOrNull ?: 0L,
                                createdAt = (o["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
                                status = (o["status"] as? JsonPrimitive)?.longOrNull ?: 0L,
                                isLock = (o["isLock"] as? JsonPrimitive)?.longOrNull,
                                treasureDeviceLv1 = (o["treasureDeviceLv1"] as? JsonPrimitive)?.longOrNull ?: 1L,
                                limitCount = (o["limitCount"] as? JsonPrimitive)?.longOrNull ?: 0L,
                                exceedCount = (o["exceedCount"] as? JsonPrimitive)?.longOrNull ?: 0L,
                                adjustHp = (o["adjustHp"] as? JsonPrimitive)?.longOrNull ?: 0L,
                                adjustAtk = (o["adjustAtk"] as? JsonPrimitive)?.longOrNull ?: 0L,
                            )
                            val prev = bestSvtCopy[svtId]
                            if (prev == null || svtCopyComparator.compare(candidate, prev) < 0) {
                                bestSvtCopy[svtId] = candidate
                            }
                        }
                    }
                }
            }

            val hasSvtTables = hasSvtCollection || hasCardTables
            val craftEssences = if (numbers.equips.isEmpty() || !hasSvtTables) {
                null
            } else {
                val out = LinkedHashMap<Long, CeStatus>()
                for (colNo in (ceStatusByCol.keys + heldCeCols)) {
                    val status = ceStatusByCol[colNo] ?: 2L
                    val lv = if (colNo in heldCeCols) ceHeldMaxLv[colNo] else if (status == 2L) ceColMaxLv[colNo] else null
                    val limit = if (colNo in heldCeCols) ceHeldMaxLimit[colNo] else if (status == 2L) ceColMaxLimit[colNo] else null
                    out[colNo] = CeStatus(status, lv, limit)
                }
                out
            }

            val unmappedIds = LinkedHashSet<Long>()
            val ccCollectionRows = rows("userCommandCodeCollection")
            val userCcIdToCommandCodeId = LinkedHashMap<Long, Long>()
            val ccCountByCol = LinkedHashMap<Long, Long>()
            var hasUserCommandCode = false
            rows("userCommandCode")?.let { rs ->
                hasUserCommandCode = true
                rs.forEach { row ->
                    val o = row as? JsonObject ?: return@forEach
                    (o["id"] as? JsonPrimitive)?.longOrNull?.let { userCcId ->
                        (o["commandCodeId"] as? JsonPrimitive)?.longOrNull?.let { ccId ->
                            userCcIdToCommandCodeId[userCcId] = ccId
                        }
                    }
                    val ccId = (o["commandCodeId"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                    val colNo = numbers.commandCodes[ccId]
                    if (colNo == null || colNo <= 0L) {
                        unmappedIds += ccId
                        return@forEach
                    }
                    ccCountByCol[colNo] = (ccCountByCol[colNo] ?: 0L) + 1L
                }
            }
            val commandCodes = if (ccCollectionRows == null && !hasUserCommandCode) {
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
                if (numbers.commandCodes.isEmpty()) {
                    null
                } else {
                    val out = LinkedHashMap<Long, CcStatus>()
                    for (colNo in (statusByCol.keys + ccCountByCol.keys)) {
                        out[colNo] = CcStatus(statusByCol[colNo] ?: 2L, ccCountByCol[colNo] ?: 0L)
                    }
                    out
                }
            }

            val servants = if (!collectServantCopies || !hasCardTables) {
                null
            } else {
                val appendUnlocksBySvt = LinkedHashMap<Long, Set<Long>>()
                val hasAppendUnlock = rows("userSvtAppendPassiveSkill")?.let { rs ->
                    rs.forEach { row ->
                        val o = row as? JsonObject ?: return@forEach
                        val svtId = (o["svtId"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                        val nums = ids(o["unlockNums"])
                        if (nums.isNotEmpty()) {
                            appendUnlocksBySvt[svtId] = (appendUnlocksBySvt[svtId].orEmpty() + nums)
                        }
                    }
                    true
                } ?: false
                val appendLvByUserSvtId = LinkedHashMap<Long, Map<Long, Long>>()
                val hasAppendLv = rows("userSvtAppendPassiveSkillLv")?.let { rs ->
                    rs.forEach { row ->
                        val o = row as? JsonObject ?: return@forEach
                        val userSvtId = (o["userSvtId"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                        val nums = o["appendPassiveSkillNums"] as? JsonArray ?: return@forEach
                        val lvs = o["appendPassiveSkillLvs"] as? JsonArray ?: return@forEach
                        val map = LinkedHashMap<Long, Long>()
                        val count = minOf(nums.size, lvs.size)
                        for (i in 0 until count) {
                            val num = (nums[i] as? JsonPrimitive)?.longOrNull ?: continue
                            val lv = (lvs[i] as? JsonPrimitive)?.longOrNull ?: continue
                            map[num] = lv
                        }
                        appendLvByUserSvtId[userSvtId] = map
                    }
                    true
                } ?: false
                val grandUserSvtIds: Set<Long>? = rows("userSvtGrand")?.let { rs ->
                    val set = HashSet<Long>()
                    rs.forEach { row ->
                        val o = row as? JsonObject ?: return@forEach
                        (o["userSvtId"] as? JsonPrimitive)?.longOrNull?.let { set += it }
                    }
                    set
                }
                val equipCmdCodesByCol = LinkedHashMap<Long, List<Long?>>()
                if (hasUserCommandCode && numbers.commandCodes.isNotEmpty()) {
                    rows("userSvtCommandCode")?.forEach { row ->
                        val o = row as? JsonObject ?: return@forEach
                        val svtId = (o["svtId"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                        val colNo = numbers.servants[svtId] ?: return@forEach
                        val arr = o["userCommandCodeIds"] as? JsonArray ?: return@forEach
                        equipCmdCodesByCol[colNo] = List(5) { idx ->
                            val userCcId = (arr.getOrNull(idx) as? JsonPrimitive)?.longOrNull
                            if (userCcId == null || userCcId <= 0L) {
                                null
                            } else {
                                userCcIdToCommandCodeId[userCcId]?.let { ccId ->
                                    numbers.commandCodes[ccId]?.takeIf { it > 0L }
                                }
                            }
                        }
                    }
                }
                val cmdCardByCol = LinkedHashMap<Long, List<Long>>()
                rows("userSvtCommandCard")?.forEach { row ->
                    val o = row as? JsonObject ?: return@forEach
                    val svtId = (o["svtId"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                    val colNo = numbers.servants[svtId] ?: return@forEach
                    val arr = o["commandCardParam"] as? JsonArray ?: return@forEach
                    cmdCardByCol[colNo] = List(5) { idx ->
                        val raw = (arr.getOrNull(idx) as? JsonPrimitive)?.longOrNull ?: 0L
                        (raw / 20L).coerceIn(0L, 25L)
                    }
                }

                val growthByCol = LinkedHashMap<Long, SvtStatus>()
                for ((svtId, a) in bestSvtCopy) {
                    val colNo = numbers.servants[svtId] ?: continue
                    val withdrawn = (a.status and 4L) != 0L
                    val locked = if (a.isLock != null) a.isLock == 1L else (a.status and 1L) != 0L
                    if (withdrawn || !locked) continue
                    val c = svtCollection[svtId] ?: continue
                    val bond = c.friendshipRank.coerceIn(0L, 16L)
                    val baseBondLimit = if (svtId == 800100L) 5L else 10L
                    val bondLimit = (baseBondLimit + c.friendshipExceedCount).coerceIn(maxOf(bond, 10L), 16L)
                    val appendSkills = if (!hasAppendUnlock && !hasAppendLv) {
                        null
                    } else {
                        val levels = LongArray(5)
                        for (num in appendUnlocksBySvt[svtId].orEmpty()) {
                            val idx = (num - 100L).toInt()
                            if (idx in 0..4) levels[idx] = 1L
                        }
                        for ((num, lv) in appendLvByUserSvtId[a.id].orEmpty()) {
                            val idx = (num - 100L).toInt()
                            if (idx in 0..4) levels[idx] = lv
                        }
                        levels.map { it.coerceIn(0L, 10L) }
                    }
                    val grandSvt = grandUserSvtIds?.let { a.id in it }
                    val costumes = gameData?.let { gd ->
                        val mapped = LinkedHashMap<Long, Long>()
                        val partnerSvtId = gd.transforms[svtId]
                        if (partnerSvtId != null && partnerSvtId != svtId) {
                            val partnerCol = svtCollection[partnerSvtId]
                            val partnerMap = gd.costumes[partnerSvtId]
                            if (partnerCol != null && partnerMap != null) {
                                for (cid in partnerCol.costumeIds) {
                                    partnerMap[cid]?.let { bcId -> mapped[bcId] = 1L }
                                }
                            }
                        }
                        val ownMap = gd.costumes[svtId]
                        if (ownMap != null) {
                            for (cid in c.costumeIds) {
                                ownMap[cid]?.let { bcId -> mapped[bcId] = 1L }
                            }
                        }
                        mapped
                    }
                    growthByCol[colNo] = SvtStatus(
                        favorite = true,
                        ascension = a.limitCount.coerceIn(0L, 4L),
                        skills = listOf(
                            a.skillLv1.coerceIn(1L, 10L),
                            a.skillLv2.coerceIn(1L, 10L),
                            a.skillLv3.coerceIn(1L, 10L),
                        ),
                        appendSkills = appendSkills,
                        costumes = costumes,
                        grail = a.exceedCount.coerceAtLeast(0L),
                        fouHp5 = ((a.adjustHp - 200L) / 10L).coerceIn(0L, 10L),
                        fouAtk5 = ((a.adjustAtk - 200L) / 10L).coerceIn(0L, 10L),
                        fouHp = ((a.adjustHp - 100L) / 2L).coerceIn(0L, 50L),
                        fouAtk = ((a.adjustAtk - 100L) / 2L).coerceIn(0L, 50L),
                        fouHp3 = minOf(20L, a.adjustHp / 5L),
                        fouAtk3 = minOf(20L, a.adjustAtk / 5L),
                        bondLimit = bondLimit,
                        npLv = a.treasureDeviceLv1,
                        bond = bond,
                        grandSvt = grandSvt,
                    )
                }

                val out = LinkedHashMap<Long, SvtStatus>()
                for (colNo in (growthByCol.keys + equipCmdCodesByCol.keys + cmdCardByCol.keys)) {
                    val base = growthByCol[colNo] ?: SvtStatus()
                    out[colNo] = base.copy(
                        equipCmdCodes = equipCmdCodesByCol[colNo] ?: base.equipCmdCodes,
                        cmdCardStrengthen = cmdCardByCol[colNo] ?: base.cmdCardStrengthen,
                    )
                }
                out
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
                servants,
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
                    servants = obj("servants")?.entries?.associate { (k, v) ->
                        k.toLong() to svtStatusFromJson(v.jsonObject)
                    },
                )
            }
        } catch (e: Exception) {
            null
        }

        private fun svtStatusFromJson(s: JsonObject): SvtStatus {
            fun boolOrNull(key: String): Boolean? = s[key]?.takeUnless { it is JsonNull }?.let {
                (it as JsonPrimitive).booleanOrNull ?: throw IllegalArgumentException("Invalid boolean for $key")
            }
            fun longOrNull(key: String): Long? = s[key]?.takeUnless { it is JsonNull }?.let {
                (it as JsonPrimitive).long
            }
            fun longListOrNull(key: String): List<Long>? = s[key]?.takeUnless { it is JsonNull }?.let { el ->
                el.jsonArray.map { (it as JsonPrimitive).long }
            }
            fun nullableLongListOrNull(key: String): List<Long?>? = s[key]?.takeUnless { it is JsonNull }?.let { el ->
                el.jsonArray.map { item -> if (item is JsonNull) null else (item as JsonPrimitive).long }
            }
            fun costumesOrNull(): Map<Long, Long>? = s["costumes"]?.takeUnless { it is JsonNull }?.let { el ->
                el.jsonObject.entries.associate { it.key.toLong() to (it.value as JsonPrimitive).long }
            }
            return SvtStatus(
                favorite = boolOrNull("favorite"),
                ascension = longOrNull("ascension"),
                skills = longListOrNull("skills"),
                appendSkills = longListOrNull("appendSkills"),
                costumes = costumesOrNull(),
                grail = longOrNull("grail"),
                fouHp5 = longOrNull("fouHp5"),
                fouAtk5 = longOrNull("fouAtk5"),
                fouHp = longOrNull("fouHp"),
                fouAtk = longOrNull("fouAtk"),
                fouHp3 = longOrNull("fouHp3"),
                fouAtk3 = longOrNull("fouAtk3"),
                bondLimit = longOrNull("bondLimit"),
                npLv = longOrNull("npLv"),
                bond = longOrNull("bond"),
                equipCmdCodes = nullableLongListOrNull("equipCmdCodes"),
                cmdCardStrengthen = longListOrNull("cmdCardStrengthen"),
                grandSvt = boolOrNull("grandSvt"),
            )
        }

        private fun ids(e: JsonElement?): List<Long> =
            (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }?.distinct().orEmpty()

        private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    }
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
