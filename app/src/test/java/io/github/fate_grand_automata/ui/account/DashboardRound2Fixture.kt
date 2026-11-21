package io.github.fate_grand_automata.ui.account

import io.github.fate_grand_automata.capture.*
import java.io.File

/**
 * An account with servants of every rarity and several classes, so the Overview and Servants numbers mean something.
 * Servant ids 1..70 form the account server's list; the account owns ids that are not a multiple of three up to 60.
 * Icons are placeholders and item names are "Item #id" on purpose.
 */
internal class Round2Fixture(val withCatalog: Boolean = true) {
    private val base = DashboardUiFixture()
    private val classes = listOf("saber", "archer", "lancer", "rider", "caster", "assassin", "berserker")
    private fun rarity(id: Long) = when { id <= 12 -> 5L; id <= 28 -> 4L; id <= 44 -> 3L; id <= 54 -> 2L; else -> 1L }
    private fun className(id: Long) = classes[(id % 7).toInt()]
    fun owns(id: Long) = id <= 60 && id % 3 != 0L
    fun np(id: Long) = 1 + id % 5

    val catalog: Map<Long, AtlasBasic> = (1L..70L).associateWith { id ->
        AtlasBasic(id, "샘플 서번트 $id", "normal", className(id), rarity(id), id, "https://static.atlasacademy.io/test-only/servant/$id.png")
    } + (900L to AtlasBasic(900, "Enemy", "enemy", "saber", 5, 0))

    val names: GameNameMap = base.names.copy(
        servants = catalog.mapValues { it.value.name ?: "" },
        servantData = catalog,
        accountServants = if (withCatalog) catalog else emptyMap(), // AFTER-ONLY
        itemData = base.names.itemData + (94065901L to AtlasBasic(94065901, "Item #94065901", "friendshipUpItem")) +
            (94000001L to AtlasBasic(94000001, "칼데아의 몽화", "skillLvUp")) + (7999L to AtlasBasic(7999, "성배", "tdLvUp")),
    )

    init {
        val source = File(base.context.filesDir, "capture/icons/6510.png")
        catalog.keys.forEach { id ->
            source.copyTo(File(base.context.filesDir, "capture/icons/servants/$id.png").apply { parentFile!!.mkdirs() }, overwrite = true)
        }
    }

    val state = dashboardState(
        "userGame" to """[{"name":"샘플 마스터","lv":160,"exp":1234567,"friendCode":"123456789","costMax":114,"friendKeep":100,
            "actMax":144,"actRecoverAt":1999999999,"carryOverActPoint":12,"stone":123,"freeStone":100,"chargeStone":23,
            "qp":123456789,"mana":500,"rarePri":20,"svtKeep":900,"svtEquipKeep":700,"commandSpellRecoverAt":1999999999}]""",
        "tblUserGame" to """[{"createdAt":1700000000,"friendPoint":123456}]""",
        "userItem" to """[{"itemId":16,"num":6},{"itemId":100,"num":50},{"itemId":101,"num":23},{"itemId":104,"num":10},
            {"itemId":102,"num":9},{"itemId":103,"num":8},{"itemId":6510,"num":60},{"itemId":6001,"num":40},{"itemId":94000001,"num":3},{"itemId":7999,"num":5}]""",
        "userSvt" to (1L..70L).filter(::owns).joinToString(",", "[", "]") { """{"id":$it,"svtId":$it,"lv":${40 + it},"treasureDeviceLv1":${np(it)},"skillLv1":10,"skillLv2":${1 + it % 10},"skillLv3":${1 + it % 7}}""" },
        "userSvtCollection" to (1L..70L).filter(::owns).joinToString(",", "[", "]") {
            """{"svtId":$it,"status":2,"friendshipRank":${5 + it % 11},"friendship":${10000 - it},"treasureDeviceLv1":${np(it)},"skillLv1":10,"skillLv2":${1 + it % 10},"skillLv3":${1 + it % 7},"maxLv":${80 + rarity(it) * 4}}"""
        },
    )

    val data: DashboardData = dashboardData(state, names)

    /** Every section, with the formations of [case]; the same layout the #594 shots used. */
    fun allSections(case: FormationCase): DashboardData = data.copy(
        formations = case.data.formations,
        equips = listOf(DashboardEquip(1, 10, true)),
        presentBox = DashboardPresentBox(totalCount = 3, entries = listOf(DashboardPresent(2, 6510L, 4L))),
        commandCodes = DashboardCommandCodes(ownedIds = listOf(501L), total = 3),
    )

    fun uiNames(case: FormationCase): GameNameMap = names.copy(servants = names.servants + case.names.servants, equips = case.names.equips)
}
