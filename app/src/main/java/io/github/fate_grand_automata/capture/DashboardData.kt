package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.AccountState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only public Atlas metadata. Never includes account fields. */
@Serializable
data class AtlasBasic(
    val id: Long, val name: String? = null, val type: String? = null,
    val className: String? = null, val rarity: Long? = null, val collectionNo: Long? = null,
    val icon: String? = null,
)

internal fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

enum class InventoryGroup { Materials, Gems, Pieces, Grails, Event, Other }
enum class ServantSort { Bond, Rarity, Collection }
enum class StoredKind { Servant, Equip, Other }

internal fun storedKind(id: Long?, names: GameNameMap): StoredKind = when {
    names.servantData[id]?.type in setOf("normal", "heroine") -> StoredKind.Servant
    id in names.equipData -> StoredKind.Equip
    else -> StoredKind.Other
}

internal fun inventoryGroup(id: Long, type: String?): InventoryGroup = when {
    // Atlas groups gems/Lore under skillLvUp and pieces/Grails under tdLvUp.
    type == "skillLvUp" && (id in 6001L..6008L || id in 6101L..6108L || id in 6201L..6208L) -> InventoryGroup.Gems
    type == "tdLvUp" && (id in 7001L..7008L || id in 7101L..7108L) -> InventoryGroup.Pieces
    (type == "skillLvUp" && id == 6999L) || (type == "tdLvUp" && id in setOf(7999L, 7998L)) -> InventoryGroup.Grails
    type == "skillLvUp" -> InventoryGroup.Materials
    type in setOf("eventItem", "eventPoint", "eventPassiveSkillGiven", "dice") -> InventoryGroup.Event
    else -> InventoryGroup.Other
}

data class DashboardServant(val id: Long, val atlas: AtlasBasic?, val bond: Long?, val friendship: Long?,
    val np: Long?, val skills: List<Long?>, val maxLevel: Long?) {
    val name get() = atlas?.name ?: "#$id"
}
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

/** Version-specific projection built once on Dispatchers.Default, never during composition. */
data class DashboardData(
    val profile: Map<String, String>, val snapshotTime: Long, val lastUpdateTime: Long,
    val game: JsonObject?, val resources: Map<Long, Long>, val freePaid: Pair<Long, Long>?,
    val storage: DashboardStorage?, val servants: List<DashboardServant>?,
    val inventory: List<DashboardItem>?, val equips: List<DashboardEquip>?,
) {
    val bondTen = servants?.takeIf { rows -> rows.all { it.bond != null } }?.count { it.bond!! >= 10 }
    val bondFifteen = servants?.takeIf { rows -> rows.all { it.bond != null } }?.count { it.bond == 15L }
    val servantClasses = servants.orEmpty().mapNotNull { it.atlas?.className }.distinct().sorted()
}

internal fun dashboardData(state: AccountState, names: GameNameMap): DashboardData {
    fun rows(table: String) = state.tables[table]?.mapNotNull { it as? JsonObject }
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
    }
    val classified = names.servantData.isNotEmpty() && names.equipData.isNotEmpty()
    val svts = rows("userSvt")
    val kinds = if (classified) svts?.groupingBy { storedKind(it.number("svtId"), names) }?.eachCount() else null
    val storage = DashboardStorage(svts?.size,
        kinds?.getOrDefault(StoredKind.Servant, 0), kinds?.getOrDefault(StoredKind.Equip, 0), kinds?.getOrDefault(StoredKind.Other, 0),
        game?.number("svtKeep"), game?.number("svtEquipKeep"), rows("userCommandCode")?.size, rows("userSvtStorage")?.size)
        .takeIf { svts != null || it.commandCodes != null || it.secondStorage != null }
    val servants = rows("userSvtCollection")?.filter { it.number("status") == 2L }?.mapNotNull { row ->
        val id = row.number("svtId") ?: return@mapNotNull null
        DashboardServant(id, names.servantData[id], row.number("friendshipRank"), row.number("friendship"),
            row.number("treasureDeviceLv1"), (1..3).map { row.number("skillLv$it") }, row.number("maxLv"))
    }
    val aliases = mapOf(5L to 1L, 6L to 2L, 7L to 2L, 11L to 16L)
    val inventory = items?.filter { (id, count) -> count > 0 && id !in resources && aliases[id] !in resources }?.map { (id, count) ->
        DashboardItem(id, count, inventoryGroup(id, names.itemData[id]?.type))
    }
    val equips = rows("userEquip")?.mapNotNull { row ->
        val id = row.number("equipId") ?: row.number("id") ?: return@mapNotNull null
        DashboardEquip(id, row.number("lv"), id == game?.number("userEquipId"))
    }?.sortedWith(compareByDescending<DashboardEquip> { it.level ?: -1L }.thenBy { it.id })
    val split = game?.number("freeStone")?.let { free -> game.number("chargeStone")?.let { free to it } }
    return DashboardData(profile, state.snapshotTime, state.lastUpdateTime, game, resources, split, storage, servants, inventory, equips)
}

internal fun List<DashboardServant>.selectServants(query: String, sort: ServantSort, className: String?): List<DashboardServant> =
    filter { it.name.contains(query, ignoreCase = true) && (className == null || it.atlas?.className == className) }.sortedWith(
        when (sort) {
            ServantSort.Bond -> compareByDescending<DashboardServant> { it.bond ?: -1L }.thenByDescending { it.friendship ?: -1L }
            ServantSort.Rarity -> compareByDescending { it.atlas?.rarity ?: -1L }
            ServantSort.Collection -> compareBy { it.atlas?.collectionNo ?: Long.MAX_VALUE }
        }.thenBy { it.id })

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
}
