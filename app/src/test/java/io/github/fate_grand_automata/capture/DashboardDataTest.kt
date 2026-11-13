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
    @Test fun servantCopyProvidesLevelSkillsNpAndMatchingAppendInstance() {
        val result = dashboardData(dashboardState(
            "userSvtCollection" to """[{"svtId":10,"status":2,"maxLv":120,"skillLv1":1,"treasureDeviceLv1":1}]""",
            "userSvt" to """[{"id":101,"svtId":10,"lv":100,"treasureDeviceLv1":5,"skillLv1":10,"skillLv2":9,"skillLv3":8},
                {"id":102,"svtId":10,"lv":90,"treasureDeviceLv1":2,"skillLv1":2}]""",
            "userSvtAppendPassiveSkillLv" to """[{"userSvtId":101,"appendPassiveSkillNums":[104,100,103,102],"appendPassiveSkillLvs":[5,1,4,3]},
                {"userSvtId":102,"appendPassiveSkillNums":[101],"appendPassiveSkillLvs":[10]}]"""
        ), dashboardNames()).servants!!.single()
        assertEquals(100, result.level)
        assertEquals(5, result.np)
        assertEquals(listOf(10L, 9L, 8L), result.skills)
        assertEquals(listOf(1L, null, 3L, 4L, 5L), result.appends)
    }

    @Test fun allAppendNumsMapToFiveSlotsAndMalformedOrMissingLevelsStayLocked() {
        val result = dashboardData(dashboardState(
            "userSvtCollection" to """[{"svtId":10,"status":2},{"svtId":11,"status":2}]""",
            "userSvt" to """[{"id":101,"svtId":10,"lv":90},{"id":102,"svtId":11}]""",
            "userSvtAppendPassiveSkillLv" to """[{"userSvtId":101,"appendPassiveSkillNums":[100,101,102,103,104,999],"appendPassiveSkillLvs":[1,2,3,4,5,10]},
                {"userSvtId":102,"appendPassiveSkillNums":[100,101],"appendPassiveSkillLvs":[null]}]"""
        ), dashboardNames()).servants!!
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), result.first().appends)
        assertNull(result.last().level)
        assertEquals(List(5) { null }, result.last().appends)
    }

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
    @Test fun bondRankAndPointsTieBreak() {
        val data = dashboardData(dashboardState("userSvtCollection" to """[
            {"svtId":10,"status":2,"friendshipRank":10,"friendship":300},
            {"svtId":11,"status":2,"friendshipRank":15,"friendship":100},
            {"svtId":13,"status":2,"friendshipRank":10,"friendship":400},
            {"svtId":14,"status":2,"friendshipRank":9}]
        """), dashboardNames())
        assertEquals(listOf(11L, 13L, 10L, 14L), data.servants!!.selectServants(ServantSort.Bond, null).map { it.id })
    }
    @Test fun classFilterKeepsOnlySelectedClasses() {
        val data = dashboardData(dashboardState("userSvtCollection" to """[{"svtId":10,"status":2},{"svtId":11,"status":2}]"""), dashboardNames()).servants!!
        assertEquals(listOf(10L), data.selectServants(ServantSort.Bond, setOf("saber")).map { it.id })
        assertTrue(data.selectServants(ServantSort.Bond, setOf("beast")).isEmpty())
    }
    private fun servant(id: Long, rarity: Long?, bond: Long?, points: Long?, level: Long?) =
        DashboardServant(id, AtlasBasic(id, rarity = rarity), bond, points, null, emptyList(), level)

    @Test fun costTableIncludesZeroStarMashAndUnknownMetadata() {
        listOf(0L to 4, 1L to 3, 2L to 4, 3L to 7, 4L to 12, 5L to 16).forEach { (rarity, cost) ->
            assertEquals(cost, servant(1,rarity,null,null,null).cost)
        }
        assertEquals(0, servant(800100,5,null,null,null).cost)
        assertEquals(0, servant(800100,null,null,null,null).cost)
        assertNull(servant(1,null,null,null,null).cost)
        assertNull(servant(1,6,null,null,null).cost)
    }

    private val sortRows get() = listOf(
        servant(1,3,10,100,100), servant(2,5,10,100,80),
        servant(3,5,10,100,100), servant(4,5,10,100,100),
        servant(5,1,10,200,70), servant(6,0,11,0,1), servant(7,null,null,null,null))

    @Test fun bondSortUsesPointsThenCostLevelAndId() {
        assertEquals(listOf(6L,5L,3L,4L,2L,1L,7L), sortRows.reversed().selectServants(ServantSort.Bond,null).map { it.id })
    }
    @Test fun costSortUsesBondPointsLevelAndId() {
        assertEquals(listOf(3L,4L,2L,1L,6L,5L,7L), sortRows.reversed().selectServants(ServantSort.Cost,null).map { it.id })
        val tied = listOf(servant(1,5,10,100,1),servant(2,5,11,0,1),servant(3,5,10,200,1))
        assertEquals(listOf(2L,3L,1L), tied.selectServants(ServantSort.Cost,null).map { it.id })
    }
    @Test fun levelSortUsesBondPointsCostAndId() {
        assertEquals(listOf(3L,4L,1L,2L,5L,6L,7L), sortRows.reversed().selectServants(ServantSort.Level,null).map { it.id })
        val tied = listOf(servant(1,5,10,100,100),servant(2,1,11,0,100),servant(3,1,10,200,100))
        assertEquals(listOf(2L,3L,1L), tied.selectServants(ServantSort.Level,null).map { it.id })
        assertEquals(listOf(ServantSort.Bond,ServantSort.Cost,ServantSort.Level),ServantSort.entries)
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
        assertEquals(InventoryGroup.Other, inventoryGroup(1001,"svtCoin"))
        assertEquals(
            listOf(
                InventoryGroup.Materials,
                InventoryGroup.Gems,
                InventoryGroup.Pieces,
                InventoryGroup.Grails,
                InventoryGroup.Event,
                InventoryGroup.Coins,
                InventoryGroup.Other,
            ),
            InventoryGroup.entries,
        )
    }

    @Test fun coinsFromUserSvtCoinJoinInventoryAfterUserItemsAndSkipZeroOrMissingSvtId() {
        val names = dashboardNames().copy(itemData = mapOf(6510L to AtlasBasic(6510, "Gear", "skillLvUp")))
        val data = dashboardData(dashboardState(
            "userItem" to """[{"itemId":6510,"num":2}]""",
            "userSvtCoin" to """[{"svtId":10,"num":30},{"svtId":11,"num":0},{"svtId":12,"num":-5},{"num":15},{"svtId":"bad","num":10}]""",
        ), names)
        assertEquals(
            listOf(
                DashboardItem(6510L, 2L, InventoryGroup.Materials),
                DashboardItem(1001L, 30L, InventoryGroup.Coins),
            ),
            data.inventory,
        )
    }

    @Test fun coinsSortByCountDescThenCollectionNoUnknownLastThenSvtId() {
        val data = dashboardData(dashboardState(
            "userSvtCoin" to """[
                {"svtId":999,"num":100},
                {"svtId":10,"num":100},
                {"svtId":12,"num":100},
                {"svtId":13,"num":250},
                {"svtId":5,"num":100},
                {"svtId":11,"num":100}
            ]""",
        ), dashboardNames())
        assertEquals(
            listOf(1301L, 1101L, 1001L, 501L, 1201L, 99901L),
            data.inventory!!.map { it.id },
        )
        assertTrue(data.inventory.all { it.group == InventoryGroup.Coins })
    }

    @Test fun coinsWithoutUserItemFormInventoryAndZeroOnlyCoinsStayNull() {
        val names = dashboardNames().copy(itemData = mapOf(6510L to AtlasBasic(6510, "Gear", "skillLvUp")))
        val coinsOnly = dashboardData(dashboardState(
            "userSvtCoin" to """[{"svtId":10,"num":5}]""",
        ), names)
        assertEquals(listOf(DashboardItem(1001L, 5L, InventoryGroup.Coins)), coinsOnly.inventory)

        val zeroCoinsOnly = dashboardData(dashboardState(
            "userSvtCoin" to """[{"svtId":10,"num":0},{"num":3}]""",
        ), names)
        assertNull(zeroCoinsOnly.inventory)

        val noCoins = dashboardData(dashboardState(
            "userItem" to """[{"itemId":6510,"num":2}]""",
        ), names)
        assertEquals(listOf(DashboardItem(6510L, 2L, InventoryGroup.Materials)), noCoins.inventory)
    }
    @Test fun inventoryHidesZeroNegativeAndCurrenciesAlreadyShown() {
        val data = dashboardData(dashboardState("userGame" to """[{"qp":100,"stone":2,"mana":3,"rarePri":4}]""",
            "userItem" to """[{"itemId":1,"num":100},{"itemId":16,"num":1},{"itemId":100,"num":2},{"itemId":103,"num":3},
            {"itemId":5,"num":100},{"itemId":6,"num":1},{"itemId":7,"num":1},{"itemId":11,"num":1},
            {"itemId":6510,"num":2},{"itemId":6501,"num":0},{"itemId":6502,"num":-1},{"itemId":999,"num":1}]"""), GameNameMap())
        assertEquals(listOf(6510L,999L), data.inventory!!.map { it.id })
        assertEquals(setOf(1L,2L,3L,18L,16L,100L,103L,4001L,49L), data.resources.keys)
    }
    private fun teapotNames() = GameNameMap(itemData = mapOf(
        94065901L to AtlasBasic(94065901, "Tea A", "friendshipUpItem"), 94065902L to AtlasBasic(94065902, "Tea B", "friendshipUpItem"),
        94065903L to AtlasBasic(94065903, "Tea C", "friendshipUpItem"), 46L to AtlasBasic(46, "Pure Prism", "purePri")))
    @Test fun summonTicketsAndStormPodsAlwaysShownAfterOtherTiles() {
        val none = dashboardData(dashboardState("userGame" to """[{"qp":1}]""", "userItem" to """[{"itemId":100,"num":2}]"""), GameNameMap())
        assertEquals(listOf(1L,100L,4001L,49L), none.resources.keys.toList())
        assertEquals(0L, none.resources[4001L]); assertEquals(0L, none.resources[49L])
        val held = dashboardData(dashboardState("userItem" to """[{"itemId":49,"num":7},{"itemId":4001,"num":3}]"""), GameNameMap())
        assertEquals(listOf(4001L,49L), held.resources.keys.toList())
        assertEquals(3L, held.resources[4001L]); assertEquals(7L, held.resources[49L])
        assertTrue(held.inventory!!.isEmpty())
    }
    @Test fun teapotsSumAndUseIconOfLargestCountTiesToLargerId() {
        val items = """[{"itemId":94065901,"num":5},{"itemId":94065902,"num":9},{"itemId":94065903,"num":9},{"itemId":999,"num":1}]"""
        val data = dashboardData(dashboardState("userItem" to items), teapotNames())
        assertEquals(DashboardTeapots(23, 94065903L), data.teapots)
        assertEquals(listOf(999L), data.inventory!!.map { it.id })
        val single = dashboardData(dashboardState("userItem" to """[{"itemId":94065901,"num":5},{"itemId":94065902,"num":2}]"""), teapotNames())
        assertEquals(DashboardTeapots(7, 94065901L), single.teapots)
    }
    @Test fun teapotsHiddenAtZeroOrWithoutMetadata() {
        assertNull(dashboardData(dashboardState("userItem" to """[{"itemId":94065901,"num":0}]"""), teapotNames()).teapots)
        val noMeta = dashboardData(dashboardState("userItem" to """[{"itemId":94065901,"num":5}]"""), GameNameMap())
        assertNull(noMeta.teapots); assertEquals(listOf(94065901L), noMeta.inventory!!.map { it.id })
    }
    @Test fun pureAndFootprintAreMaterialsByExplicitId() {
        assertEquals(InventoryGroup.Materials, inventoryGroup(46, "purePri"))
        assertEquals(InventoryGroup.Materials, inventoryGroup(2000, "commandCardPrmUp"))
        assertEquals(InventoryGroup.Materials, inventoryGroup(46, null))
        assertEquals(InventoryGroup.Other, inventoryGroup(2001, "commandCardPrmUp"))
        assertEquals(InventoryGroup.Other, inventoryGroup(47, "purePri"))
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

    @Test fun largeStateDashboardParsesTablesOnceAndCountsStorageWithoutFullTrees() = kotlinx.coroutines.runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("dashboard-perf").toFile()
        try {
            val padding = "x".repeat(320)
            val rawTables = buildMap {
                put("userGame", """[{"userId":"7001","name":"Synthetic","lv":150,"actMax":144,"actRecoverAt":1800003600,"qp":900000000,"stone":120,"freeStone":100,"chargeStone":20,"mana":5000,"rarePri":25,"svtKeep":700,"svtEquipKeep":700}]""")
                put("tblUserGame", """[{"userId":"7001","friendPoint":250000,"createdAt":1500000000}]""")
                put("userItem", (1..180).joinToString(",", "[", "]") { """{"userId":"7001","itemId":${100 + it},"num":${it * 3},"pad":"$padding"}""" })
                put("userSvt", (1..600).joinToString(",", "[", "]") { """{"userId":"7001","id":${10_000 + it},"svtId":${if (it % 2 == 0) 10 else 30},"lv":90,"treasureDeviceLv1":5,"skillLv1":10,"skillLv2":10,"skillLv3":10,"pad":"$padding"}""" })
                put("userSvtCollection", (1..300).joinToString(",", "[", "]") { """{"userId":"7001","svtId":${if (it == 1) 10 else 100_000 + it},"status":2,"maxLv":90,"friendshipRank":10,"friendship":1000000,"pad":"$padding"}""" })
                put("userSvtStorage", (1..200).joinToString(",", "[", "]") { """{"userId":"7001","id":${20_000 + it},"svtId":10,"lv":1,"pad":"$padding"}""" })
                put("userEquip", (1..30).joinToString(",", "[", "]") { """{"userId":"7001","id":$it,"equipId":1,"lv":10}""" })
                put("userCommandCode", (1..50).joinToString(",", "[", "]") { """{"userId":"7001","id":$it,"commandCodeId":$it}""" })
                repeat(50) { table ->
                    put("other_$table", (1..30).joinToString(",", "[", "]") { """{"id":$it,"pad":"$padding"}""" })
                }
            }
            val state = AccountState(rawTables, "[]", snapshotTime = 1000, lastUpdateTime = 1000)
            assertEquals(200, state.objectCount("userSvtStorage"))
            assertEquals(600, state.objectCount("userSvt"))
            assertEquals(300, state.objectCount("userSvtCollection"))

            val cache = GameNames(dir, JpNamesFixture::download) { 1000 }
            // Warm the disk cache once, then measure warm dashboard loads.
            cache.dashboard(emptyList(), hasJpAccount = false)

            // Measure old path (separate table parses + readingstorage array as JsonObject list) vs new path.
            val oldStart = System.nanoTime()
            val oldAtlas = cache.dashboard(listOf(state), hasJpAccount = false)
            val oldData = dashboardData(state, oldAtlas)
            val oldMs = (System.nanoTime() - oldStart) / 1_000_000.0

            val t0 = System.nanoTime()
            val tables = DashboardStateTables(state)
            val atlas = cache.dashboardTables(listOf(tables), hasJpAccount = false)
            val t1 = System.nanoTime()
            val data = dashboardData(tables, atlas)
            val t2 = System.nanoTime()

            assertEquals(oldData, data)
            assertEquals(600, data.storage!!.total)
            assertEquals(200, data.storage!!.secondStorage)
            val namesMs = (t1 - t0) / 1_000_000.0
            val projectMs = (t2 - t1) / 1_000_000.0
            println("Dashboard large-state timing: separate=${"%.2f".format(oldMs)}ms, shared(names=${"%.2f".format(namesMs)}ms, projection=${"%.2f".format(projectMs)}ms)")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun presentBoxSumsOrdersCountsRowsAndHandlesEmptyOrMissing() {
        assertNull(dashboardData(dashboardState(), GameNameMap()).presentBox)
        val empty = dashboardData(dashboardState("userPresentBox" to "[]"), GameNameMap()).presentBox
        assertEquals(DashboardPresentBox(0, emptyList()), empty)

        val rows = """[
            {"presentId":1,"giftType":1,"objectId":9770400,"num":5,"message":"hidden"},
            {"presentId":2,"giftType":2,"objectId":6510,"num":2},
            {"presentId":3,"giftType":2,"objectId":6501,"num":5},
            {"presentId":4,"giftType":2,"objectId":6510,"num":3},
            {"presentId":5,"giftType":1,"objectId":9770400,"num":5},
            {"presentId":6,"giftType":11,"objectId":300,"num":1},
            {"presentId":7,"giftType":1,"objectId":10,"num":0},
            {"presentId":8,"giftType":2,"num":4},
            {"presentId":9,"objectId":6510,"num":4},
            {"presentId":10,"giftType":2,"objectId":6510,"num":-1}
        ]"""
        val box = dashboardData(dashboardState("userPresentBox" to rows), GameNameMap()).presentBox!!
        assertEquals(10, box.totalCount)
        assertEquals(
            listOf(
                DashboardPresent(giftType = 2, objectId = 6501L, count = 5L),
                DashboardPresent(giftType = 2, objectId = 6510L, count = 5L),
                DashboardPresent(giftType = 1, objectId = 9770400L, count = 10L),
                DashboardPresent(giftType = 11, objectId = 300L, count = 1L),
            ),
            box.entries,
        )
    }

    @Test fun commandCodesCountOwnedStatus2AndUseAtlasTotalOnlyWhenPresent() {
        assertNull(dashboardData(dashboardState(), GameNameMap()).commandCodes)
        val state = dashboardState(
            "userCommandCodeCollection" to """[
                {"commandCodeId":1001,"status":1},
                {"commandCodeId":1002,"status":2},
                {"commandCodeId":1003,"status":2},
                {"commandCodeId":1004,"status":0}
            ]"""
        )
        val withoutList = dashboardData(state, GameNameMap()).commandCodes!!
        assertEquals(DashboardCommandCodes(ownedIds = listOf(1002L, 1003L), total = null), withoutList)

        val withList = dashboardData(
            state,
            GameNameMap(commandCodeData = mapOf(1001L to AtlasBasic(1001, "A"), 1002L to AtlasBasic(1002, "B"), 1003L to AtlasBasic(1003, "C"), 1004L to AtlasBasic(1004, "D"))),
        ).commandCodes!!
        assertEquals(DashboardCommandCodes(ownedIds = listOf(1002L, 1003L), total = 4), withList)
    }
}
