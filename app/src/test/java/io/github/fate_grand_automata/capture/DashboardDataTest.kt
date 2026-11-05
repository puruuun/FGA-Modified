package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.AccountState
import kotlinx.serialization.json.*
import kotlin.test.*

internal fun dashboardState(vararg tables: Pair<String, String>) = AccountState(
    tables.associate { (name, body) -> name to Json.parseToJsonElement(body).jsonArray }, JsonArray(emptyList()), snapshotTime = 100, lastUpdateTime = 200)

internal fun dashboardNames() = GameNameMap(
    servantData = mapOf(10L to AtlasBasic(10, "Alpha", "normal", "saber", 5, 2),
        11L to AtlasBasic(11, "Beta", "heroine", "shielder", 3, 1), 12L to AtlasBasic(12, "EXP", "svtMaterial"),
        13L to AtlasBasic(13, "Gamma", "normal", "archer", 4, 3), 14L to AtlasBasic(14, "Delta", "normal", "lancer", 4, 4)),
    equipData = mapOf(20L to AtlasBasic(20, "CE", "servantEquip")))

class DashboardDataTest {
    @Test fun largeProjectionRunsOffCallerThreadAndCachesByStateVersion() = kotlinx.coroutines.runBlocking {
        val caller = Thread.currentThread()
        var calls = 0
        val cache = DashboardProjectionCache { state, names ->
            assertNotSame(caller, Thread.currentThread())
            calls++
            dashboardData(state,names)
        }
        val rows = (1..1500).joinToString(",","[","]") { """{"svtId":$it}""" }
        val state = dashboardState("userSvt" to rows)
        val names = dashboardNames()
        val first = cache.get(state,names)
        assertSame(first,cache.get(state,names)); assertEquals(1,calls)
        assertEquals(1500,first!!.storage!!.total)
        cache.get(state.copy(lastUpdateTime=201),names); assertEquals(2,calls)
        cache.get(state,names.copy()); assertEquals(3,calls)
        assertNull(cache.get(null,names))
    }
    @Test fun apMatchesAccountSummaryAndMissingFieldsStayAbsent() {
        listOf(900L, 1000L, 1001L, 1300L, 1301L, 50000L).forEach { recover ->
            val state = dashboardState("userGame" to """[{"actMax":144,"actRecoverAt":$recover}]""")
            val summary = io.github.fate_grand_automata.scripts.capture.AccountSummary.from(state, 1000)
            assertEquals(summary.ap, dashboardAp(state.tables["userGame"]!!.first().jsonObject,1000)!!.current)
        }
        assertNull(dashboardAp(null,1000))
        assertNull(dashboardAp(buildJsonObject { put("actMax",144) },1000))
    }
    @Test fun classifiesNormalHeroineEquipAndEverythingElse() {
        val names = dashboardNames()
        assertEquals(StoredKind.Servant, storedKind(10, names))
        assertEquals(StoredKind.Servant, storedKind(11, names))
        assertEquals(StoredKind.Equip, storedKind(20, names))
        assertEquals(StoredKind.Other, storedKind(12, names))
        assertEquals(StoredKind.Other, storedKind(999, names))
    }
    @Test fun storageCountsKeepPlainLimitsAndCountOptionalTables() {
        val data = dashboardData(dashboardState(
            "userGame" to """[{"svtKeep":100,"svtEquipKeep":50,"svtStorageAdjust":900,"svtEquipStorageAdjust":900}]""",
            "userSvt" to """[{"svtId":10},{"svtId":11},{"svtId":20},{"svtId":12},{"svtId":999}]""",
            "userCommandCode" to """[{"id":1},{"id":2}]""", "userSvtStorage" to """[{"id":3}]"""), dashboardNames())
        assertEquals(DashboardStorage(5, 2, 1, 2, 100, 50, 2, 1), data.storage)
    }
    @Test fun noAtlasShowsOnlyTotalWithoutGuessingCategories() {
        val storage = dashboardData(dashboardState("userSvt" to """[{"svtId":10},{"svtId":20}]"""), GameNameMap()).storage!!
        assertEquals(2, storage.total)
        assertNull(storage.servants); assertNull(storage.equips); assertNull(storage.others)
        assertNull(storage.commandCodes); assertNull(storage.secondStorage)
    }
    @Test fun ownedFilterIncludesExactlyStatusTwoAndSkipsMalformedIds() {
        val data = dashboardData(dashboardState("userSvtCollection" to """[
            {"svtId":10,"status":2},{"svtId":11,"status":1},{"svtId":12,"status":2},{"svtId":13},{"status":2},{"svtId":20,"status":2},{"svtId":999,"status":2}]
        """), dashboardNames())
        assertEquals(listOf(10L), data.servants!!.map { it.id })
    }
    @Test fun collectionKeepsOnlyNormalAndHeroineServants() {
        val data = dashboardData(dashboardState("userSvtCollection" to """[
            {"svtId":10,"status":2},{"svtId":11,"status":2},{"svtId":20,"status":2},{"svtId":12,"status":2},{"svtId":999,"status":2}]
        """), dashboardNames())
        assertEquals(listOf(10L, 11L), data.servants!!.map { it.id })
    }
    @Test fun servantSectionIsHiddenWithoutServantMetadata() {
        val data = dashboardData(dashboardState("userSvtCollection" to """[{"svtId":10,"status":2}]"""), GameNameMap())
        assertNull(data.servants)
    }
    @Test fun bondCountsSortAndPointsTieBreak() {
        val data = dashboardData(dashboardState("userSvtCollection" to """[
            {"svtId":10,"status":2,"friendshipRank":10,"friendship":300},
            {"svtId":11,"status":2,"friendshipRank":15,"friendship":100},
            {"svtId":13,"status":2,"friendshipRank":10,"friendship":400},
            {"svtId":14,"status":2,"friendshipRank":9}]
        """), dashboardNames())
        assertEquals(3, data.bondTen); assertEquals(1, data.bondFifteen)
        assertEquals(listOf(11L, 13L, 10L, 14L), data.servants!!.selectServants("", ServantSort.Bond, null).map { it.id })
    }
    @Test fun rarityCollectionSearchAndClassFilter() {
        val data = dashboardData(dashboardState("userSvtCollection" to """[{"svtId":10,"status":2},{"svtId":11,"status":2}]"""), dashboardNames()).servants!!
        assertEquals(listOf(10L,11L), data.selectServants("", ServantSort.Rarity, null).map { it.id })
        assertEquals(listOf(11L,10L), data.selectServants("", ServantSort.Collection, null).map { it.id })
        assertEquals(listOf(10L), data.selectServants("ALP", ServantSort.Bond, "saber").map { it.id })
        assertTrue(data.selectServants("Beta", ServantSort.Bond, "saber").isEmpty())
    }
    @Test fun preservesHighestCollectionStatsAndMissingStatsStayNull() {
        val servants = dashboardData(dashboardState("userSvtCollection" to """[
            {"svtId":10,"status":2,"treasureDeviceLv1":5,"skillLv1":10,"skillLv2":9,"skillLv3":8,"maxLv":120},
            {"svtId":11,"status":2}]
        """), dashboardNames()).servants!!
        assertEquals(5, servants.first().np); assertEquals(listOf(10L,9L,8L), servants.first().skills)
        assertEquals(120, servants.first().maxLevel)
        assertEquals("Beta", servants.last().name); assertNull(servants.last().bond)
        assertTrue(servants.last().skills.all { it == null })
    }
    @Test fun itemGroupsIncludeUnknownInOther() {
        assertEquals(InventoryGroup.Materials, inventoryGroup(6510, "skillLvUp"))
        listOf(6001L,6108L,6207L).forEach { assertEquals(InventoryGroup.Gems, inventoryGroup(it,"skillLvUp")) }
        assertEquals(InventoryGroup.Pieces, inventoryGroup(7001,"tdLvUp"))
        assertEquals(InventoryGroup.Pieces, inventoryGroup(7107,"tdLvUp"))
        assertEquals(InventoryGroup.Grails, inventoryGroup(6999,"skillLvUp"))
        assertEquals(InventoryGroup.Grails, inventoryGroup(7999,"tdLvUp"))
        assertEquals(InventoryGroup.Event, inventoryGroup(9000,"eventItem"))
        assertEquals(InventoryGroup.Other, inventoryGroup(6001,"futureType"))
        assertEquals(InventoryGroup.Other, inventoryGroup(6510,null))
    }
    @Test fun inventoryHidesZeroNegativeAndCurrenciesAlreadyShown() {
        val data = dashboardData(dashboardState("userGame" to """[{"qp":100,"stone":2,"mana":3,"rarePri":4}]""",
            "userItem" to """[{"itemId":1,"num":100},{"itemId":16,"num":1},{"itemId":100,"num":2},{"itemId":103,"num":3},
            {"itemId":5,"num":100},{"itemId":6,"num":1},{"itemId":7,"num":1},{"itemId":11,"num":1},
            {"itemId":6510,"num":2},{"itemId":6501,"num":0},{"itemId":6502,"num":-1},{"itemId":999,"num":1}]"""), GameNameMap())
        assertEquals(listOf(6510L,999L), data.inventory!!.map { it.id })
        assertEquals(setOf(1L,2L,3L,18L,16L,100L,103L), data.resources.keys)
    }
    @Test fun missingTablesAndFieldsNeverBecomeZero() {
        val data = dashboardData(dashboardState(), GameNameMap())
        assertTrue(data.profile.isEmpty()); assertTrue(data.resources.isEmpty())
        assertNull(data.storage); assertNull(data.servants); assertNull(data.inventory); assertNull(data.equips)
        assertNull(data.game); assertNull(data.freePaid)
    }
    @Test fun profileAllowlistNeverExposesBirthdayOrMessage() {
        val data = dashboardData(dashboardState("userGame" to """[{"name":"Master","lv":10,"birthday":"secret","message":"private","costMax":50}]""",
            "tblUserGame" to """[{"createdAt":5,"friendPoint":42,"message":"private"}]"""), GameNameMap())
        assertEquals(mapOf("name" to "Master","lv" to "10","costMax" to "50","createdAt" to "5","friendPoint" to "42"), data.profile)
    }
    @Test fun mysticCodesSortByLevelMarkEquippedAndPreserveMissingLevel() {
        val data = dashboardData(dashboardState("userGame" to """[{"userEquipId":2}]""",
            "userEquip" to """[{"equipId":1,"lv":3},{"equipId":2,"lv":10},{"equipId":3}]"""), GameNameMap())
        assertEquals(listOf(2L,1L,3L), data.equips!!.map { it.id })
        assertTrue(data.equips!!.first().equipped); assertNull(data.equips!!.last().level)
    }
}
