package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.AccountState
import io.github.fate_grand_automata.scripts.capture.OwnFormations
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.github.fate_grand_automata.util.grouped
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Only public Atlas metadata. Never includes account fields. */
@Serializable
data class AtlasBasic(
    val id: Long, val name: String? = null, val type: String? = null,
    val className: String? = null, val rarity: Long? = null, val collectionNo: Long? = null,
    val icon: String? = null,
)

/** Public Class Score summary cached per region. Never holds user state. */
@Serializable
data class ClassBoardSummary(
    val id: Long,
    val name: String,
    val icon: String? = null,
    val squareTotal: Int,
    val lockTotal: Int,
    val squareIds: Set<Long> = emptySet(),
    val lockIds: Set<Long> = emptySet(),
)

/** Public summon banner summary cached per region. Never holds user state. */
@Serializable
data class GachaSummary(
    val id: Long,
    val name: String,
    val type: String? = null,
    val openedAt: Long? = null,
    val closedAt: Long? = null,
)

/** "139/145" once a friend list was seen, the plain limit before that. */
internal fun friendsText(keep: String, count: Int?): String {
    val limit = keep.toLongOrNull()?.grouped() ?: keep
    return if (count == null) limit else "$count/$limit"
}

internal fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

/** Declaration order is the display order: the grail group leads (owner, 2026-11-22). */
enum class InventoryGroup { Grails, Materials, Gems, Pieces, Event, Coins, Other }
enum class ServantSort { Bond, Cost, Level }
enum class StoredKind { Servant, Equip, Other }

/** Atlas `type` values of the servants a player can own; everything else in `basic_servant` (enemies, CEs, ...) is not counted. */
internal val PLAYABLE_SERVANT_TYPES = setOf("normal", "heroine")

internal fun storedKind(id: Long?, names: GameNameMap): StoredKind = when {
    names.servantData[id]?.type in PLAYABLE_SERVANT_TYPES -> StoredKind.Servant
    id in names.equipData -> StoredKind.Equip
    else -> StoredKind.Other
}

/** Chaldea dream fires: KR "칼데아의 몽화", JP "カルデアの夢火" and "カルデアの大夢火". Matched by their Atlas item name. */
internal fun isDreamFire(name: String?) = name != null && ("몽화" in name || "夢火" in name)

internal fun inventoryGroup(id: Long, type: String?, name: String? = null): InventoryGroup = when {
    isDreamFire(name) -> InventoryGroup.Grails
    // Pure Prism (type purePri) and Beast's Footprint (type commandCardPrmUp) are matched by explicit id only.
    id == 46L || id == 2000L -> InventoryGroup.Materials
    // Atlas groups gems/Lore under skillLvUp and pieces/Grails under tdLvUp.
    type == "skillLvUp" && (id in 6001L..6008L || id in 6101L..6108L || id in 6201L..6208L) -> InventoryGroup.Gems
    type == "tdLvUp" && (id in 7001L..7008L || id in 7101L..7108L) -> InventoryGroup.Pieces
    (type == "skillLvUp" && id == 6999L) || (type == "tdLvUp" && id in setOf(7999L, 7998L)) -> InventoryGroup.Grails
    type == "skillLvUp" -> InventoryGroup.Materials
    type in setOf("eventItem", "eventPoint", "eventPassiveSkillGiven", "dice") -> InventoryGroup.Event
    else -> InventoryGroup.Other
}

/** Filled command code slots out of the unlocked ones; a locked slot is stored as -1. */
data class DashboardServantCodes(val filled: Int, val unlocked: Int)
/** Sum of the five card enhancement values and how many cards are enhanced. */
data class DashboardCardBoost(val sum: Long, val cards: Int)

data class DashboardServant(val id: Long, val atlas: AtlasBasic?, val bond: Long?, val friendship: Long?,
    val np: Long?, val skills: List<Long?>, val maxLevel: Long?, val level: Long? = null,
    val appends: List<Long?> = List(5) { null },
    val commandCodes: DashboardServantCodes? = null, val cardBoost: DashboardCardBoost? = null) {
    val name get() = atlas?.name ?: "#$id"
    // basic_servant has rarity but no cost. Mash always has zero party cost.
    val cost: Int? get() = if (id == 800100L) 0 else when (atlas?.rarity) {
        5L -> 16; 4L -> 12; 3L -> 7; 2L, 0L -> 4; 1L -> 3; else -> null
    }
}
/** Summed friendshipUpItem holdings; the icon comes from the held teapot with the largest count (ties: larger id). */
/** [iconId] is null when no teapot is known in the item master, which only happens with a total of 0. */
data class DashboardTeapots(val total: Long, val iconId: Long?)
internal const val SUMMON_TICKET_ID = 4001L
internal const val STORM_POD_ID = 49L
data class DashboardItem(val id: Long, val count: Long, val group: InventoryGroup)
data class DashboardEquip(val id: Long, val level: Long?, val equipped: Boolean)
data class DashboardAp(val current: Long, val max: Long, val recoverAt: Long)
internal fun dashboardAp(game: JsonObject?, now: Long): DashboardAp? {
    val max = game?.number("actMax")?.coerceAtLeast(0) ?: return null
    val recover = game.number("actRecoverAt") ?: return null
    val current = if (recover <= now) max else (max - ceil((recover.toDouble() - now.toDouble()) / 300).toLong()).coerceIn(0, max)
    return DashboardAp(current, max, recover)
}
data class DashboardStorage(val total: Int?, val servants: Int?, val equips: Int?, val others: Int?,
    val servantLimit: Long?, val equipLimit: Long?, val commandCodes: Int?, val secondStorage: Int?)
data class DashboardPresent(val giftType: Int, val objectId: Long, val count: Long)
data class DashboardPresentBox(val totalCount: Int, val entries: List<DashboardPresent>)
data class DashboardCommandCodes(val ownedIds: List<Long>, val total: Int?)
data class DashboardClassBoard(
    val id: Long,
    val name: String?,
    val icon: String?,
    val acquired: Int,
    val unlockedLocks: Int,
    val squareTotal: Int?,
    val lockTotal: Int?,
) {
    val isComplete: Boolean
        get() = squareTotal != null && lockTotal != null &&
            acquired == squareTotal && unlockedLocks == lockTotal
}

data class DashboardSummon(
    val id: Long,
    val name: String?,
    val type: String?,
    val openedAt: Long?,
    val closedAt: Long?,
    val count: Long,
)

data class DashboardSummonLog(
    val date: String,
    val gachaId: Long,
    val name: String?,
    val count: Int,
)

data class DashboardSummons(
    val friendPointTotal: Long?,
    val otherSummons: List<DashboardSummon>,
    val recentLog: List<DashboardSummonLog>,
)

/** Login counts as of the last login; each field is null when its table is missing. */
data class DashboardLogin(val total: Long?, val seq: Long?, val nextRewardDay: Long?) {
    /** Null unless both counts are known and the next milestone is still ahead. */
    val daysLeft: Long? get() = if (total != null && nextRewardDay != null && nextRewardDay > total) nextRewardDay - total else null
}

data class DashboardPartyCe(val svtId: Long, val level: Long?, val limitCount: Long?)

/** [svtId] is null for the support slot, which belongs to a friend and has no servant of the owner's. */
/** [ceMissing] is true when the slot names a CE the owned-Servant list does not hold, so "no CE" must not be shown. */
data class DashboardPartySlot(
    val position: Int, val svtId: Long?, val level: Long?, val np: Long?, val ces: List<DashboardPartyCe>, val ceMissing: Boolean = false,
) {
    val support get() = svtId == null
}

data class DashboardParty(val deckNo: Long, val name: String?, val slots: List<DashboardPartySlot>, val mysticCodeId: Long?)

/** A group-0 event point row the Overview may turn into a line; [updatedAt] is epoch seconds. */
data class DashboardEventPoint(val eventId: Long, val value: Long, val updatedAt: Long)

/**
 * What the "in progress" block needs from the stored state; the master data it is joined with is loaded later.
 * [achievedMissions] is null when the account's mission table is missing, so a missing table is not read as 0 done.
 */
data class DashboardProgressInput(val snapshotTime: Long, val achievedMissions: Set<Long>?, val eventPoints: List<DashboardEventPoint>)

/** Version-specific projection built once on Dispatchers.Default, never during composition. */
data class DashboardData(
    val profile: Map<String, String>, val snapshotTime: Long, val lastUpdateTime: Long,
    val game: JsonObject?, val resources: Map<Long, Long>, val freePaid: Pair<Long, Long>?,
    val storage: DashboardStorage?, val servants: List<DashboardServant>?,
    val inventory: List<DashboardItem>?, val equips: List<DashboardEquip>?,
    val teapots: DashboardTeapots? = null,
    /** The owner's own support and Grand formations; null when the stored state has none or cannot be read. */
    val formations: OwnFormations? = null,
    val presentBox: DashboardPresentBox? = null,
    val commandCodes: DashboardCommandCodes? = null,
    val classBoards: List<DashboardClassBoard>? = null,
    val summons: DashboardSummons? = null,
    /** True when the account's item list was captured; only then is a missing resource a real 0. */
    val itemsCaptured: Boolean = false,
    /** The playable servants (collectionNo > 0) of the account's own server list; null when that list is not loaded. */
    val servantCatalog: List<AtlasBasic>? = null,
    val login: DashboardLogin? = null,
    /** Parties with at least one servant of the owner's, by party number. */
    val parties: List<DashboardParty> = emptyList(),
    val progressInput: DashboardProgressInput? = null,
    /**
     * True when the stored state has a `userQuest` table. Only its presence is checked here: the table changes after
     * every battle, and its rows are read by the quest records block alone, while that block is shown (#730).
     */
    val hasQuestRecords: Boolean = false,
) {
    val servantClasses = servants.orEmpty().mapNotNull { it.atlas?.className }.distinct().sorted()
}

/** Short-lived table cache for a single dashboard build so large tables are parsed at most once. */
internal class DashboardStateTables(val state: AccountState) {
    private val parsed = mutableMapOf<String, List<JsonObject>?>()
    fun rows(table: String): List<JsonObject>? =
        if (table in parsed) parsed[table]
        else state.tables[table]?.mapNotNull { it as? JsonObject }.also { parsed[table] = it }
    fun count(table: String): Int? =
        if (table in parsed) parsed[table]?.size else state.objectCount(table)
}

internal fun dashboardData(state: AccountState, names: GameNameMap): DashboardData =
    dashboardData(DashboardStateTables(state), names, TimeZone.getDefault())

internal fun dashboardData(state: AccountState, names: GameNameMap, timeZone: TimeZone): DashboardData =
    dashboardData(DashboardStateTables(state), names, timeZone)

internal fun dashboardData(tables: DashboardStateTables, names: GameNameMap, timeZone: TimeZone = TimeZone.getDefault()): DashboardData {
    val state = tables.state
    fun rows(table: String) = tables.rows(table)
    val game = rows("userGame")?.firstOrNull()
    val tbl = rows("tblUserGame")?.firstOrNull()
    // Explicit allowlist: birthday/message and unrelated capture fields cannot reach the UI.
    val profile = buildMap {
        listOf("name", "lv", "exp", "friendCode", "costMax", "friendKeep").forEach { field ->
            game?.text(field)?.let { put(field, it) }
        }
        listOf("createdAt", "friendPoint").forEach { field -> tbl?.text(field)?.let { put(field, it) } }
    }
    val items = rows("userItem")?.mapNotNull { row ->
        val id = row.number("itemId") ?: return@mapNotNull null
        row.number("num")?.let { id to it }
    }?.toMap()
    val resources = buildMap {
        mapOf(1L to "qp", 2L to "stone", 3L to "mana", 18L to "rarePri").forEach { (id, key) -> game?.number(key)?.let { put(id, it) } }
        listOf(16L, 100L, 101L, 104L, 102L, 103L).forEach { id -> items?.get(id)?.let { put(id, it) } }
        // Always shown (0 without a row) once the userItem table exists; a missing table is not invented as 0.
        if (items != null) listOf(SUMMON_TICKET_ID, STORM_POD_ID).forEach { id -> put(id, items[id] ?: 0L) }
    }
    val teapotHeld = items?.filter { (id, count) -> count > 0 && names.itemData[id]?.type == "friendshipUpItem" }.orEmpty()
    val teapots = teapotHeld.entries.maxWithOrNull(compareBy<Map.Entry<Long, Long>>({ it.value }, { it.key }))
        ?.let { DashboardTeapots(teapotHeld.values.sum(), it.key) }
        // Without item metadata a held teapot cannot be told from other items, so a 0 would be invented.
        ?: items?.takeIf { names.itemData.isNotEmpty() }?.let { DashboardTeapots(0, names.itemData.values.filter { row -> row.type == "friendshipUpItem" }.minOfOrNull { row -> row.id }) }
    val teapotIds = teapotHeld.keys
    val classified = names.servantData.isNotEmpty() && names.equipData.isNotEmpty()
    val svts = rows("userSvt")
    val kinds = if (classified) svts?.groupingBy { storedKind(it.number("svtId"), names) }?.eachCount() else null
    val storage = DashboardStorage(svts?.size,
        kinds?.getOrDefault(StoredKind.Servant, 0), kinds?.getOrDefault(StoredKind.Equip, 0), kinds?.getOrDefault(StoredKind.Other, 0),
        game?.number("svtKeep"), game?.number("svtEquipKeep"), tables.count("userCommandCode"), tables.count("userSvtStorage"))
        .takeIf { svts != null || it.commandCodes != null || it.secondStorage != null }
    // The collection also holds CEs and other rows; without servant metadata they cannot be told apart, so the section is hidden.
    // Keep all per-copy stats together. When several copies exist, use the highest-level copy,
    // then NP and instance id as deterministic tie breakers.
    val owned = svts.orEmpty().groupBy { it.number("svtId") }.mapValues { (_, copies) ->
        copies.maxWith(compareBy<JsonObject>({ it.number("lv") ?: -1 }, { it.number("treasureDeviceLv1") ?: -1 }, { it.number("id") ?: -1 }))
    }
    val appendRows = rows("userSvtAppendPassiveSkillLv").orEmpty().groupBy { it.number("userSvtId") }
    val collectionRows = rows("userSvtCollection")
    val codeSlots = projectServantCodes(rows("userSvtCommandCode"))
    val cardBoosts = projectCardBoosts(rows("userSvtCommandCard"))
    val servants = collectionRows?.takeIf { names.servantData.isNotEmpty() }?.filter { it.number("status") == 2L }?.mapNotNull { row ->
        val id = row.number("svtId") ?: return@mapNotNull null
        if (names.servantData[id]?.type !in PLAYABLE_SERVANT_TYPES) return@mapNotNull null
        val copy = owned[id]
        val stats = copy ?: row
        val appends = MutableList<Long?>(5) { null }
        copy?.number("id")?.let { instance -> appendRows[instance].orEmpty().forEach { append ->
            val nums = (append["appendPassiveSkillNums"] as? JsonArray).orEmpty()
            val levels = (append["appendPassiveSkillLvs"] as? JsonArray).orEmpty()
            nums.forEachIndexed { index, num ->
                val slot = (num as? JsonPrimitive)?.intOrNull?.minus(100)
                if (slot != null && slot in 0..4) appends[slot] = (levels.getOrNull(index) as? JsonPrimitive)?.longOrNull
            }
        } }
        DashboardServant(id, names.servantData[id], row.number("friendshipRank"), row.number("friendship"),
            stats.number("treasureDeviceLv1"), (1..3).map { stats.number("skillLv$it") }, row.number("maxLv"),
            copy?.number("lv"), appends, codeSlots[id], cardBoosts[id])
    }
    val aliases = mapOf(5L to 1L, 6L to 2L, 7L to 2L, 11L to 16L)
    val userItems = items?.filter { (id, count) -> count > 0 && id !in resources && aliases[id] !in resources && id !in teapotIds }?.map { (id, count) ->
        DashboardItem(id, count, inventoryGroup(id, names.itemData[id]?.type, names.itemData[id]?.name))
    }
    val coins = rows("userSvtCoin")?.mapNotNull { row ->
        val svtId = row.number("svtId") ?: return@mapNotNull null
        row.number("num")?.let { svtId to it }
    }?.toMap()?.entries?.filter { it.value > 0 }
        ?.sortedWith(
            compareByDescending<Map.Entry<Long, Long>> { it.value }
                .thenBy(nullsLast()) { names.servantData[it.key]?.collectionNo }
                .thenBy { it.key }
        )?.map { (svtId, count) ->
            DashboardItem(id = svtId * 100 + 1, count = count, group = InventoryGroup.Coins)
        }
    val inventory = if (userItems == null && coins.isNullOrEmpty()) null else userItems.orEmpty() + coins.orEmpty()
    val equipRows = rows("userEquip")
    val equips = equipRows?.mapNotNull { row ->
        val id = row.number("equipId") ?: row.number("id") ?: return@mapNotNull null
        DashboardEquip(id, row.number("lv"), id == game?.number("userEquipId"))
    }?.sortedWith(compareByDescending<DashboardEquip> { it.level ?: -1L }.thenBy { it.id })
    val split = game?.number("freeStone")?.let { free -> game.number("chargeStone")?.let { free to it } }
    val presentBox = projectPresentBox(rows("userPresentBox"))
    val commandCodes = projectCommandCodes(rows("userCommandCodeCollection"), names)
    val classBoards = projectClassBoards(rows("userClassBoardSquare"), names)
    val summons = projectSummons(rows("userGacha"), rows("userGachaDrawLog"), names, timeZone)
    return DashboardData(profile, state.snapshotTime, state.lastUpdateTime, game, resources, split, storage, servants, inventory, equips, teapots,
        OwnFormations.from(state, game), presentBox, commandCodes, classBoards, summons,
        itemsCaptured = items != null,
        servantCatalog = names.accountServants.values
            .filter { it.type in PLAYABLE_SERVANT_TYPES && (it.collectionNo ?: 0L) > 0L }
            .takeIf { it.isNotEmpty() },
        login = projectLogin(rows("userLogin"), rows("mstTotalLogin")),
        parties = projectParties(rows("userDeck"), svts, rows("userEquip")),
        progressInput = projectProgressInput(state.snapshotTime, rows("userEventMission"), rows("userEventPoint")),
        hasQuestRecords = "userQuest" in state.rawTables)
}

private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.longOrNull == 1L) } == true

internal fun projectServantCodes(rows: List<JsonObject>?): Map<Long, DashboardServantCodes> {
    val out = mutableMapOf<Long, DashboardServantCodes>()
    for (row in rows.orEmpty()) {
        val svtId = row.number("svtId") ?: continue
        val ids = (row["userCommandCodeIds"] as? JsonArray)?.map { (it as? JsonPrimitive)?.longOrNull } ?: continue
        val unlocked = ids.count { it != null && it != -1L }
        if (unlocked > 0) out[svtId] = DashboardServantCodes(ids.count { it != null && it > 0L }, unlocked)
    }
    return out
}

internal fun projectCardBoosts(rows: List<JsonObject>?): Map<Long, DashboardCardBoost> {
    val out = mutableMapOf<Long, DashboardCardBoost>()
    for (row in rows.orEmpty()) {
        val svtId = row.number("svtId") ?: continue
        val values = ((row["commandCardParam"] as? JsonArray) ?: continue).mapNotNull { (it as? JsonPrimitive)?.longOrNull }.filter { it > 0L }
        if (values.isNotEmpty()) out[svtId] = DashboardCardBoost(values.sum(), values.size)
    }
    return out
}

internal fun projectLogin(login: List<JsonObject>?, milestones: List<JsonObject>?): DashboardLogin? {
    val row = login?.firstOrNull()
    val total = row?.number("totalLoginCount")
    // `mstTotalLogin.script` is a string on KR and an object on JP and is never read.
    val days = milestones?.mapNotNull { it.number("day") }
    val next = days?.filter { total == null || it > total }?.minOrNull() ?: days?.maxOrNull()
    return DashboardLogin(total, row?.number("seqLoginCount"), next).takeIf { it.total != null || it.seq != null || it.nextRewardDay != null }
}

/** `svtId` inside `deckInfo` is always 0, so servants and CEs are found through `userSvtId` and `userSvtEquipIds`. */
internal fun projectParties(decks: List<JsonObject>?, svts: List<JsonObject>?, equips: List<JsonObject>?): List<DashboardParty> {
    if (decks == null || svts == null) return emptyList()
    val svtById = svts.mapNotNull { row -> row.number("id")?.let { it to row } }.toMap()
    val equipById = equips.orEmpty().mapNotNull { row -> row.number("id")?.let { it to row } }.toMap()
    return decks.mapNotNull { deck ->
        val deckNo = deck.number("deckNo") ?: return@mapNotNull null
        val info = deck["deckInfo"] as? JsonObject ?: return@mapNotNull null
        val slots = ((info["svts"] as? JsonArray).orEmpty()).mapIndexedNotNull { index, element ->
            val slot = element as? JsonObject ?: return@mapIndexedNotNull null
            val position = slot.number("initPos")?.toInt() ?: (index + 1)
            if (slot.bool("isFollowerSvt")) return@mapIndexedNotNull DashboardPartySlot(position, null, null, null, emptyList())
            val own = slot.number("userSvtId")?.takeIf { it > 0L }?.let(svtById::get) ?: return@mapIndexedNotNull null
            val ceIds = ((slot["userSvtEquipIds"] as? JsonArray).orEmpty()).mapNotNull { (it as? JsonPrimitive)?.longOrNull?.takeIf { id -> id > 0L } }
            val ces = ceIds.mapNotNull { id ->
                val ce = svtById[id] ?: return@mapNotNull null
                ce.number("svtId")?.let { DashboardPartyCe(it, ce.number("lv"), ce.number("limitCount")) }
            }
            own.number("svtId")?.let { DashboardPartySlot(position, it, own.number("lv"), own.number("treasureDeviceLv1"), ces, ceMissing = ces.size < ceIds.size) }
        }.sortedBy { it.position }
        if (slots.none { !it.support }) return@mapNotNull null
        val mystic = info.number("userEquipId")?.let(equipById::get)?.number("equipId")
        DashboardParty(deckNo, deck.text("name")?.trim()?.takeIf { it.isNotEmpty() }, slots, mystic)
    }.sortedBy { it.deckNo }
}

private const val EVENT_POINT_WINDOW_SECONDS = 30L * 24 * 3600

internal fun projectProgressInput(snapshotTime: Long, missions: List<JsonObject>?, points: List<JsonObject>?): DashboardProgressInput? {
    if (missions == null && points == null) return null
    // 4 = achieved, 5 = reward received; a mission without a row is not done.
    val achieved = missions?.mapNotNull { row ->
        row.number("missionId")?.takeIf { row.number("missionProgressType") in setOf(4L, 5L) }
    }?.toSet()
    val events = points.orEmpty().mapNotNull { row ->
        if (row.number("groupId") != 0L) return@mapNotNull null
        val updated = row.number("updatedAt") ?: return@mapNotNull null
        if (updated < snapshotTime - EVENT_POINT_WINDOW_SECONDS) return@mapNotNull null
        DashboardEventPoint(row.number("eventId") ?: return@mapNotNull null, row.number("value") ?: return@mapNotNull null, updated)
    }.sortedByDescending { it.updatedAt }
    return DashboardProgressInput(snapshotTime, achieved, events)
}

private fun projectPresentBox(rows: List<JsonObject>?): DashboardPresentBox? {
    if (rows == null) return null
    val summed = linkedMapOf<Pair<Int, Long>, Long>()
    for (row in rows) {
        val giftType = row.number("giftType")?.toInt() ?: continue
        val objectId = row.number("objectId") ?: continue
        val num = row.number("num")?.takeIf { it > 0L } ?: continue
        val key = giftType to objectId
        summed[key] = (summed[key] ?: 0L) + num
    }
    val entries = summed.entries
        .map { (key, count) -> DashboardPresent(key.first, key.second, count) }
        .sortedWith(
            compareBy<DashboardPresent> { it.giftType != 2 }
                .thenByDescending { it.count }
                .thenBy { it.objectId }
                .thenBy { it.giftType }
        )
    return DashboardPresentBox(rows.size, entries)
}

private fun projectCommandCodes(rows: List<JsonObject>?, names: GameNameMap): DashboardCommandCodes? {
    if (rows == null) return null
    val ownedIds = rows
        .filter { it.number("status") == 2L }
        .mapNotNull { it.number("commandCodeId") }
        .distinct()
        .sortedWith(
            compareBy<Long, Long?>(nullsLast()) { names.commandCodeData[it]?.collectionNo?.takeIf { no -> no > 0L } }
                .thenBy { it }
        )
    val total = names.commandCodeData.size.takeIf { it > 0 }
    return DashboardCommandCodes(ownedIds, total)
}

private fun projectClassBoards(rows: List<JsonObject>?, names: GameNameMap): List<DashboardClassBoard>? {
    if (rows == null) return null
    val userIds = linkedSetOf<Long>()
    val userSquaresByBoard = linkedMapOf<Long, LinkedHashSet<Long>>()
    val userLocksByBoard = linkedMapOf<Long, LinkedHashSet<Long>>()
    for (row in rows) {
        val id = row.number("classBoardBaseId") ?: continue
        userIds.add(id)
        val squares = userSquaresByBoard.getOrPut(id) { linkedSetOf() }
        (row["classBoardSquareIds"] as? JsonArray).orEmpty().mapNotNullTo(squares) { (it as? JsonPrimitive)?.longOrNull }
        val locks = userLocksByBoard.getOrPut(id) { linkedSetOf() }
        (row["classBoardUnlockSquareIds"] as? JsonArray).orEmpty().mapNotNullTo(locks) { (it as? JsonPrimitive)?.longOrNull }
    }
    val summaries = names.classBoards
    val allIds = (summaries.keys + userIds).sorted()
    return allIds.map { id ->
        val summary = summaries[id]
        val userSquares = userSquaresByBoard[id].orEmpty()
        val userLocks = userLocksByBoard[id].orEmpty()
        if (summary != null) {
            DashboardClassBoard(
                id = id,
                name = summary.name,
                icon = summary.icon,
                acquired = userSquares.count { it in summary.squareIds },
                unlockedLocks = userLocks.count { it in summary.lockIds },
                squareTotal = summary.squareTotal,
                lockTotal = summary.lockTotal,
            )
        } else {
            DashboardClassBoard(
                id = id,
                name = null,
                icon = null,
                acquired = userSquares.size,
                unlockedLocks = userLocks.size,
                squareTotal = null,
                lockTotal = null,
            )
        }
    }
}

internal fun formatEpochDate(seconds: Long, timeZone: TimeZone = TimeZone.getDefault()): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { this.timeZone = timeZone }.format(Date(seconds * 1000L))

private data class SummonLogAccumulator(
    val date: String,
    val gachaId: Long,
    var count: Int = 0,
    var latestDrawAt: Long = Long.MIN_VALUE,
)

private fun projectSummons(
    gachaRows: List<JsonObject>?,
    drawLogRows: List<JsonObject>?,
    names: GameNameMap,
    timeZone: TimeZone,
): DashboardSummons? {
    if (gachaRows == null && drawLogRows == null) return null
    val summaries = names.gachas
    val hasSummary = summaries.isNotEmpty()
    fun isFriendPoint(id: Long) = if (hasSummary) summaries[id]?.type == "friendPoint" else id == 1L

    var friendPointTotal: Long? = null
    val otherSummons = mutableListOf<DashboardSummon>()
    if (gachaRows != null) {
        val countsByGacha = linkedMapOf<Long, Long>()
        for (row in gachaRows) {
            val id = row.number("gachaId") ?: continue
            val num = row.number("num")?.takeIf { it >= 0L } ?: continue
            countsByGacha[id] = (countsByGacha[id] ?: 0L) + num
        }
        var fpTotal = 0L
        for ((id, count) in countsByGacha) {
            if (isFriendPoint(id)) {
                fpTotal += count
            } else if (count > 0L) {
                val summary = summaries[id]
                otherSummons += DashboardSummon(
                    id = id,
                    name = summary?.name,
                    type = summary?.type,
                    openedAt = summary?.openedAt,
                    closedAt = summary?.closedAt,
                    count = count,
                )
            }
        }
        friendPointTotal = fpTotal
        otherSummons.sortWith(
            compareByDescending<DashboardSummon> { it.count }
                .thenByDescending { it.openedAt ?: Long.MIN_VALUE }
                .thenBy { it.id }
        )
    }

    val recentLog = if (drawLogRows == null) {
        emptyList()
    } else {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { this.timeZone = timeZone }
        val grouped = linkedMapOf<Pair<String, Long>, SummonLogAccumulator>()
        for (row in drawLogRows) {
            val gachaId = row.number("gachaId") ?: continue
            val drawAt = row.number("drawAt") ?: continue
            val date = formatter.format(Date(drawAt * 1000L))
            val acc = grouped.getOrPut(date to gachaId) { SummonLogAccumulator(date, gachaId) }
            acc.count += 1
            if (drawAt > acc.latestDrawAt) acc.latestDrawAt = drawAt
        }
        grouped.values
            .sortedWith(
                compareByDescending<SummonLogAccumulator> { it.date }
                    .thenByDescending { it.latestDrawAt }
                    .thenBy { it.gachaId }
            )
            .map { acc ->
                DashboardSummonLog(
                    date = acc.date,
                    gachaId = acc.gachaId,
                    name = summaries[acc.gachaId]?.name,
                    count = acc.count,
                )
            }
    }
    return DashboardSummons(friendPointTotal, otherSummons, recentLog)
}

internal fun List<DashboardServant>.selectServants(sort: ServantSort, classes: Set<String>?): List<DashboardServant> {
    val bond = compareByDescending<DashboardServant> { it.bond ?: -1L }.thenByDescending { it.friendship ?: -1L }
    val cost = compareByDescending<DashboardServant> { it.cost ?: -1 }
    val level = compareByDescending<DashboardServant> { it.maxLevel ?: -1L }
    val order = when (sort) {
        ServantSort.Bond -> bond.then(cost).then(level)
        ServantSort.Cost -> cost.then(bond).then(level)
        ServantSort.Level -> level.then(bond).then(cost)
    }.thenBy { it.id }
    return filter { classes == null || it.atlas?.className in classes }.sortedWith(order)
}

/** One projection per immutable state/Atlas version. Owned by a single account flow. */
internal class DashboardProjectionCache(private val project: (AccountState, GameNameMap) -> DashboardData = ::dashboardData) {
    private var previous: AccountState? = null
    private var previousNames: GameNameMap? = null
    private var projection: DashboardData? = null
    suspend fun get(state: AccountState?, names: GameNameMap): DashboardData? = withContext(Dispatchers.Default) {
        if (previous !== state || previousNames !== names) {
            projection = state?.let { project(it, names) }
            previous = state
            previousNames = names
        }
        projection
    }
    suspend fun getTables(tables: DashboardStateTables?, names: GameNameMap): DashboardData? = withContext(Dispatchers.Default) {
        val state = tables?.state
        if (previous !== state || previousNames !== names) {
            projection = tables?.let { dashboardData(it, names) }
            previous = state
            previousNames = names
        }
        projection
    }
}
