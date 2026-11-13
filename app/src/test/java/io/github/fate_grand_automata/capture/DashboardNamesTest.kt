package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.capture.chaldea.ChaldeaSyncManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class DashboardNamesTest {
    @TempDir lateinit var directory: File
    private val body = """[{"id":10,"name":"Test","type":"normal","className":"saber","rarity":5,"collectionNo":1,"face":"https://static.atlasacademy.io/KR/Faces/fake.png","private":"not retained"}]"""

    @Test fun freshNiceItemAttemptCannotBlockOtherListsAndAllUseOwnMarkers() = runBlocking {
        File(directory,"nice_item.attempt").writeText("1000")
        File(directory,"item_icons.attempt").writeText("1000")
        val urls = mutableListOf<String>()
        val names = GameNames(directory, { urls += it; body }) { 1001 }.dashboard()
        listOf("basic_servant", "basic_equip", "basic_mystic_code").forEach { list ->
            assertTrue(urls.any { it.endsWith("/$list.json") }, list)
            assertTrue(File(directory,"$list.attempt").isFile, list)
        }
        assertEquals("1000",File(directory,"nice_item.attempt").readText())
        assertEquals("Test",names.servantData[10]?.name)
        assertEquals(5L,names.servantData[10]?.rarity)
    }
    @Test fun legacyNameAttemptDoesNotBlockFirstMetadataFetch() = runBlocking {
        listOf("nice_item","basic_servant","basic_equip","basic_mystic_code").forEach {
            File(directory,"$it.json").writeText("""{"10":"Old"}""")
            File(directory,"$it.attempt").writeText("1000")
        }
        File(directory,"item_icons.json").writeText("{}")
        val cache = GameNames(directory, { body }) { 1001 }.dashboard()
        assertEquals("normal", cache.servantData[10]?.type)
        assertEquals("https://static.atlasacademy.io/KR/Faces/fake.png",cache.servantData[10]?.icon)
        assertTrue(File(directory,"basic_servant_details.attempt").isFile)
    }
    @Test fun metadataCacheSurvivesNewInstanceWithoutDownloads() = runBlocking {
        val first = GameNames(directory, { body }) { 1001 }.dashboard()
        val second = GameNames(directory, { error("network forbidden") }) { 1002 }.dashboard()
        assertEquals(first,second)
        directory.listFiles()!!.filter { it.extension=="json" }.forEach { assertFalse(it.readText().contains("not retained")) }
    }
    @Test fun freshServantCacheWithUnknownIdsDoesNotDownloadButAnOldOneDoes() = runBlocking {
        GameNames(directory, { body }) { 1001 }.dashboard()
        val state = dashboardState("userSvtCollection" to """[{"svtId":999,"status":2}]""", "userSvt" to """[{"svtId":998}]""",
            "userEquip" to """[{"equipId":997}]""")
        GameNames(directory, { error("network forbidden") }) { 3_000_000 }.dashboard(listOf(state))
        val urls = mutableListOf<String>()
        GameNames(directory, { urls += it; body }) { 1001 + 7 * 86_400_000L }.dashboard(listOf(state))
        listOf("basic_servant", "basic_equip", "basic_mystic_code").forEach { list -> assertTrue(urls.any { it.endsWith("/$list.json") }, list) }
    }
    @Test fun failuresHaveIndependentPersistentDailyBackoff() = runBlocking {
        val calls = mutableListOf<String>()
        val download: (String)->String = { calls += it; error("offline") }
        GameNames(directory,download) { 1000 }.dashboard()
        val first = calls.toList()
        GameNames(directory,download) { 1001 }.dashboard()
        assertEquals(first,calls)
        GameNames(directory,download) { 86_401_001 }.dashboard()
        assertTrue(calls.size > first.size)
    }
    @Test fun mysticItemAndFaceUrlsUsePublicHttpsOnly() {
        val rows = GameNames.parseBasic("""[
            {"id":1,"name":"Mystic","item":{"male":"https://static.atlasacademy.io/KR/Items/fake.png"}},
            {"id":2,"face":"https://private.test/x"},
            {"id":3,"icon":"http://static.atlasacademy.io/x"},null,{}]""")
        assertEquals(3,rows.size)
        assertNotNull(rows[0].icon); assertNull(rows[1].icon); assertNull(rows[2].icon)
    }

    @Test fun coinIdsAreRequestedAsRequiredNiceItemIdsAndFallBackToJp() = runBlocking {
        val urls = mutableListOf<String>()
        val downloader: (String) -> String = { url ->
            urls += url
            when {
                url.endsWith("/KR/nice_item.json") ->
                    """[{"id":100,"name":"KR Item","type":"skillLvUp","icon":"https://static.atlasacademy.io/KR/Items/100.png"}]"""
                url.endsWith("/JP/nice_item.json") ->
                    """[{"id":100,"name":"JP Item","type":"skillLvUp"},{"id":1001,"name":"アルトリアのサーヴァントコイン","type":"svtCoin","icon":"https://static.atlasacademy.io/JP/Coins/10.png"}]"""
                else -> """[{"id":10,"name":"Test","type":"normal","className":"saber","rarity":5,"collectionNo":1}]"""
            }
        }
        val zeroOnly = dashboardState("userSvtCoin" to """[{"svtId":20,"num":0}]""")
        GameNames(directory, downloader) { 1000 }.dashboard(listOf(zeroOnly))
        assertFalse(urls.any { it.endsWith("/JP/nice_item.json") })

        urls.clear()
        val withCoin = dashboardState("userSvtCoin" to """[{"svtId":10,"num":30},{"svtId":20,"num":0}]""")
        val names = GameNames(directory, downloader) { 1001 }.dashboard(listOf(withCoin))
        assertTrue(urls.any { it.endsWith("/JP/nice_item.json") })
        assertEquals("アルトリアのサーヴァントコイン", names.item(1001))
        assertEquals("https://static.atlasacademy.io/JP/Coins/10.png", names.itemIcons[1001])
    }

    @Test fun commandCodeListDownloadsOnceWhenTableExistsAndSkipsWhenMissing() = runBlocking {
        val urls = mutableListOf<String>()
        val downloader: (String) -> String = { url ->
            urls += url
            when {
                url.endsWith("/KR/basic_command_code.json") ->
                    """[{"id":501,"name":"하얀 그릇","collectionNo":1,"icon":"https://static.atlasacademy.io/KR/CommandCodes/501.png"}]"""
                else -> """[{"id":10,"name":"Test","type":"normal","className":"saber","rarity":5,"collectionNo":1}]"""
            }
        }
        val withoutTable = dashboardState("userItem" to """[{"itemId":100,"num":1}]""")
        GameNames(directory, downloader) { 1000 }.dashboard(listOf(withoutTable))
        assertTrue(urls.none { "basic_command_code" in it })

        urls.clear()
        val withTable = dashboardState("userCommandCodeCollection" to """[{"commandCodeId":501,"status":2}]""")
        val first = GameNames(directory, downloader) { 1001 }.dashboard(listOf(withTable))
        assertEquals(1, urls.count { it.endsWith("/KR/basic_command_code.json") })
        assertTrue(urls.none { it.endsWith("/JP/basic_command_code.json") })
        assertEquals("하얀 그릇", first.commandCodeData[501]?.name)
        assertEquals("https://static.atlasacademy.io/KR/CommandCodes/501.png", first.commandCodeData[501]?.icon)

        urls.clear()
        val second = GameNames(directory, downloader) { 1002 }.dashboard(listOf(withTable))
        assertTrue(urls.none { "basic_command_code" in it })
        assertEquals(first.commandCodeData, second.commandCodeData)
    }

    @Test fun presentBoxWithUnknownGiftTypeOneDownloadsBasicSvtOnceAndResolvesNameAndIcon() = runBlocking {
        val urls = mutableListOf<String>()
        val downloader: (String) -> String = { url ->
            urls += url
            when {
                url.endsWith("/KR/basic_svt.json") ->
                    """[{"id":9770400,"name":"지혜의 맹화 ALL ★5","type":"svtMaterial","rarity":5,"face":"https://static.atlasacademy.io/KR/Faces/f_97704000.png"}]"""
                url.endsWith("/KR/basic_equip.json") ->
                    """[{"id":20,"name":"예장","type":"servantEquip"}]"""
                else -> """[{"id":10,"name":"서번트","type":"normal","className":"saber","rarity":5,"collectionNo":1}]"""
            }
        }
        val presentState = dashboardState(
            "userPresentBox" to """[{"giftType":1,"objectId":9770400,"num":5}]""",
        )
        val first = GameNames(directory, downloader) { 1000 }.dashboard(listOf(presentState))
        assertEquals(1, urls.count { it.endsWith("/KR/basic_svt.json") })
        assertTrue(urls.none { it.endsWith("/JP/basic_svt.json") })
        assertEquals("지혜의 맹화 ALL ★5", first.svtData[9770400]?.name)
        assertEquals("https://static.atlasacademy.io/KR/Faces/f_97704000.png", first.svtData[9770400]?.icon)

        urls.clear()
        val second = GameNames(directory, downloader) { 1001 }.dashboard(listOf(presentState))
        assertTrue(urls.none { "basic_svt" in it })
        assertEquals(first.svtData, second.svtData)
    }

    @Test fun presentBoxWithOnlyServantsCesOrNoPresentBoxDoesNotDownloadBasicSvt() = runBlocking {
        val urls = mutableListOf<String>()
        val downloader: (String) -> String = { url ->
            urls += url
            when {
                url.endsWith("/KR/basic_equip.json") ->
                    """[{"id":20,"name":"예장","type":"servantEquip"}]"""
                url.endsWith("/KR/nice_item.json") ->
                    """[{"id":100,"name":"사과","type":"apRecover"}]"""
                else -> """[{"id":10,"name":"서번트","type":"normal","className":"saber","rarity":5,"collectionNo":1}]"""
            }
        }
        val noPresentBox = dashboardState("userItem" to """[{"itemId":100,"num":1}]""")
        GameNames(directory, downloader) { 1000 }.dashboard(listOf(noPresentBox))
        assertTrue(urls.none { "basic_svt" in it })

        urls.clear()
        val emptyPresentBox = dashboardState("userPresentBox" to "[]")
        GameNames(directory, downloader) { 1001 }.dashboard(listOf(emptyPresentBox))
        assertTrue(urls.none { "basic_svt" in it })

        urls.clear()
        val knownGiftsOnly = dashboardState(
            "userPresentBox" to """[
                {"giftType":1,"objectId":10,"num":1},
                {"giftType":1,"objectId":20,"num":1},
                {"giftType":2,"objectId":100,"num":3},
                {"giftType":11,"objectId":501,"num":1}
            ]""",
        )
        GameNames(directory, downloader) { 1002 }.dashboard(listOf(knownGiftsOnly))
        assertTrue(urls.none { "basic_svt" in it })
    }

    @Test fun jpAccountDownloadsJpCommandCodesAndBasicSvtUnderSameConditionsAndKrWinsOnOverlap() = runBlocking {
        val urls = mutableListOf<String>()
        val downloader: (String) -> String = { url ->
            urls += url
            when {
                url.endsWith("/KR/basic_servant.json") -> """[{"id":10,"name":"KR 서번트","type":"normal"}]"""
                url.endsWith("/JP/basic_servant.json") -> """[{"id":705500,"name":"JP サーヴァント","type":"normal"}]"""
                url.endsWith("/KR/basic_equip.json") -> """[{"id":20,"name":"KR 예장","type":"servantEquip"}]"""
                url.endsWith("/JP/basic_equip.json") -> """[{"id":9400010,"name":"JP 礼装","type":"servantEquip"}]"""
                url.endsWith("/KR/basic_command_code.json") ->
                    """[{"id":501,"name":"KR 코드","collectionNo":1,"icon":"https://static.atlasacademy.io/KR/CommandCodes/501.png"}]"""
                url.endsWith("/JP/basic_command_code.json") ->
                    """[{"id":501,"name":"JP コード","collectionNo":1},{"id":599,"name":"JP 新コード","collectionNo":2,"icon":"https://static.atlasacademy.io/JP/CommandCodes/599.png"}]"""
                url.endsWith("/KR/basic_svt.json") ->
                    """[{"id":9770400,"name":"KR 맹화","type":"svtMaterial","face":"https://static.atlasacademy.io/KR/Faces/f_97704000.png"}]"""
                url.endsWith("/JP/basic_svt.json") ->
                    """[{"id":9770400,"name":"JP 叡智","type":"svtMaterial"},{"id":9770500,"name":"JP フォウ","type":"svtMaterial","face":"https://static.atlasacademy.io/JP/Faces/f_97705000.png"}]"""
                else -> """[{"id":100,"name":"과일"}]"""
            }
        }
        val knownJpOnly = dashboardState(
            "userPresentBox" to """[{"giftType":1,"objectId":705500,"num":1},{"giftType":1,"objectId":9400010,"num":1}]""",
        )
        GameNames(directory, downloader) { 1000 }.dashboard(listOf(knownJpOnly), hasJpAccount = true)
        assertTrue(urls.none { "basic_command_code" in it })
        assertTrue(urls.none { "basic_svt" in it })

        urls.clear()
        val withCardsAndCodes = dashboardState(
            "userCommandCodeCollection" to """[{"commandCodeId":501,"status":2},{"commandCodeId":599,"status":2}]""",
            "userPresentBox" to """[{"giftType":1,"objectId":9770500,"num":2}]""",
        )
        val first = GameNames(directory, downloader) { 1001 }.dashboard(listOf(withCardsAndCodes), hasJpAccount = true)
        assertEquals(1, urls.count { it.endsWith("/KR/basic_command_code.json") })
        assertEquals(1, urls.count { it.endsWith("/JP/basic_command_code.json") })
        assertEquals(1, urls.count { it.endsWith("/KR/basic_svt.json") })
        assertEquals(1, urls.count { it.endsWith("/JP/basic_svt.json") })
        assertEquals("KR 코드", first.commandCodeData[501]?.name)
        assertEquals("JP 新コード", first.commandCodeData[599]?.name)
        assertEquals("https://static.atlasacademy.io/JP/CommandCodes/599.png", first.commandCodeData[599]?.icon)
        assertEquals("KR 맹화", first.svtData[9770400]?.name)
        assertEquals("JP フォウ", first.svtData[9770500]?.name)
        assertEquals("https://static.atlasacademy.io/JP/Faces/f_97705000.png", first.svtData[9770500]?.icon)

        urls.clear()
        val second = GameNames(directory, downloader) { 1002 }.dashboard(listOf(withCardsAndCodes), hasJpAccount = true)
        assertTrue(urls.none { "basic_command_code" in it || "basic_svt" in it })
        assertEquals(first.commandCodeData, second.commandCodeData)
        assertEquals(first.svtData, second.svtData)
    }

    @Test fun dashboardCommandCodesAndSvtCardsFollowAtlasVersionHashes() = runBlocking {
        var time = 1_000L
        var krHash = "kr1"
        var jpHash = "jp1"
        val urls = mutableListOf<String>()
        val downloader: (String) -> String = { url ->
            urls += url
            when {
                url == AtlasVersions.INFO_URL -> """{"KR":{"hash":"$krHash","timestamp":1},"JP":{"hash":"$jpHash","timestamp":2}}"""
                url.endsWith("/KR/basic_command_code.json") -> """[{"id":501,"name":"KR $krHash"}]"""
                url.endsWith("/JP/basic_command_code.json") -> """[{"id":599,"name":"JP $jpHash"}]"""
                url.endsWith("/KR/basic_svt.json") -> """[{"id":9770400,"name":"KR SVT $krHash"}]"""
                url.endsWith("/JP/basic_svt.json") -> """[{"id":9770500,"name":"JP SVT $jpHash"}]"""
                else -> """[{"id":10,"name":"서번트","type":"normal"}]"""
            }
        }
        val state = dashboardState(
            "userCommandCodeCollection" to """[{"commandCodeId":599,"status":2}]""",
            "userPresentBox" to """[{"giftType":1,"objectId":9770500,"num":1}]""",
        )
        val first = GameNames(directory, downloader) { time }.dashboard(listOf(state), hasJpAccount = true)
        assertEquals("KR kr1", first.commandCodeData[501]?.name)
        assertEquals("JP jp1", first.commandCodeData[599]?.name)
        assertEquals("KR SVT kr1", first.svtData[9770400]?.name)
        assertEquals("JP SVT jp1", first.svtData[9770500]?.name)
        assertEquals("kr1", File(directory, "basic_command_code_details.hash").readText())
        assertEquals("jp1", File(directory, "jp_basic_command_code_details.hash").readText())
        assertEquals("kr1", File(directory, "basic_svt_details.hash").readText())
        assertEquals("jp1", File(directory, "jp_basic_svt_details.hash").readText())

        // Same hashes 2 days later -> only /info is fetched, no list downloads.
        urls.clear()
        time += 2 * 86_400_000L
        GameNames(directory, downloader) { time }.dashboard(listOf(state), hasJpAccount = true)
        assertEquals(listOf(AtlasVersions.INFO_URL), urls)

        // Hash changes after 1 hour -> refreshed.
        urls.clear()
        time += AtlasVersions.CHECK_INTERVAL_MS
        krHash = "kr2"
        jpHash = "jp2"
        val updated = GameNames(directory, downloader) { time }.dashboard(listOf(state), hasJpAccount = true)
        assertEquals("KR kr2", updated.commandCodeData[501]?.name)
        assertEquals("JP jp2", updated.commandCodeData[599]?.name)
        assertEquals("KR SVT kr2", updated.svtData[9770400]?.name)
        assertEquals("JP SVT jp2", updated.svtData[9770500]?.name)
    }

    @Test fun summarizeClassBoardsExcludesBlankCountsStartAndSeparatesLocks() {
        val raw = """[
            {
                "id": 1,
                "name": "세이버 클래스 스코어",
                "icon": "https://static.atlasacademy.io/KR/ClassIcons/board_1.png",
                "extraField": "ignored",
                "squares": [
                    {"id": 1001, "flags": ["start"], "skill": {"id": 99}},
                    {"id": 1002, "flags": []},
                    {"id": 1003, "flags": ["blank"]},
                    {"id": 1004, "flags": [], "lock": {"items": [{"item": {"id": 6501}, "amount": 1}]}},
                    {"id": 1005, "flags": ["blank"], "lock": {"items": []}}
                ]
            },
            {
                "id": 2,
                "name": "",
                "icon": "http://untrusted.example.com/icon.png",
                "squares": [
                    {"id": 2001, "flags": ["start"]}
                ]
            }
        ]"""
        val summaries = GameNames.summarizeClassBoards(raw)
        assertEquals(2, summaries.size)
        assertEquals(
            ClassBoardSummary(
                id = 1L,
                name = "세이버 클래스 스코어",
                icon = "https://static.atlasacademy.io/KR/ClassIcons/board_1.png",
                squareTotal = 2,
                lockTotal = 1,
                squareIds = setOf(1001L, 1002L),
                lockIds = setOf(1004L),
            ),
            summaries[0],
        )
        assertEquals(
            ClassBoardSummary(
                id = 2L,
                name = "#2",
                icon = null,
                squareTotal = 1,
                lockTotal = 0,
                squareIds = setOf(2001L),
                lockIds = emptySet(),
            ),
            summaries[1],
        )
    }

    @Test fun summarizeGachasKeepsFriendPointStoneChargeStoneAndFallbackName() {
        val raw = """[
            {"id": 1, "name": "프렌드 포인트 소환", "type": "friendPoint", "openedAt": 1600000000, "closedAt": 4102444799, "extra": 123},
            {"id": 200, "name": "신규 픽업", "type": "stone", "openedAt": 1700000000, "closedAt": 1700864000},
            {"id": 300, "name": "유료 확정 소환", "type": "chargeStone", "openedAt": 1704067200, "closedAt": 1704931200},
            {"id": 400, "name": "", "type": "stone"}
        ]"""
        val summaries = GameNames.summarizeGachas(raw)
        assertEquals(
            listOf(
                GachaSummary(1L, "프렌드 포인트 소환", "friendPoint", 1600000000L, 4102444799L),
                GachaSummary(200L, "신규 픽업", "stone", 1700000000L, 1700864000L),
                GachaSummary(300L, "유료 확정 소환", "chargeStone", 1704067200L, 1704931200L),
                GachaSummary(400L, "#400", "stone", null, null),
            ),
            summaries,
        )
    }

    @Test fun dashboardSummariesDownloadByRegionFollowHashesAndReadFromDiskWithoutInMemoryCache() = runBlocking {
        var time = 1_000L
        var krHash = "kr1"
        var jpHash = "jp1"
        val urls = mutableListOf<String>()
        val downloader: (String) -> String = { url ->
            urls += url
            when {
                url == AtlasVersions.INFO_URL -> """{"KR":{"hash":"$krHash","timestamp":1},"JP":{"hash":"$jpHash","timestamp":2}}"""
                url.endsWith("/KR/nice_class_board.json") -> """[{"id":1,"name":"KR 보드 $krHash","squares":[{"id":10,"flags":["start"]}]}]"""
                url.endsWith("/JP/nice_class_board.json") -> """[{"id":1,"name":"JP ボード $jpHash","squares":[{"id":10,"flags":["start"]}]}]"""
                url.endsWith("/KR/nice_gacha.json") -> """[{"id":1,"name":"KR FP $krHash","type":"friendPoint"}]"""
                url.endsWith("/JP/nice_gacha.json") -> """[{"id":1,"name":"JP FP $jpHash","type":"friendPoint"}]"""
                else -> """[{"id":10,"name":"서번트","type":"normal"}]"""
            }
        }
        val state = dashboardState(
            "userClassBoardSquare" to """[{"classBoardBaseId":1,"classBoardSquareIds":[10],"classBoardUnlockSquareIds":[]}]""",
            "userGacha" to """[{"gachaId":1,"num":5}]""",
        )

        // KR account fetches only KR summaries and never downloads JP exports.
        val gameNames = GameNames(directory, downloader) { time }
        val krFirst = gameNames.dashboard(listOf(state), hasJpAccount = false)
        assertEquals(1, urls.count { it.endsWith("/KR/nice_class_board.json") })
        assertEquals(1, urls.count { it.endsWith("/KR/nice_gacha.json") })
        assertTrue(urls.none { "/JP/nice_class_board.json" in it || "/JP/nice_gacha.json" in it })
        assertEquals("KR 보드 kr1", krFirst.classBoards[1]?.name)
        assertEquals("KR FP kr1", krFirst.gachas[1]?.name)
        assertEquals("kr1", File(directory, "nice_class_board_summary.hash").readText())
        assertEquals("kr1", File(directory, "nice_gacha_summary.hash").readText())
        assertFalse(File(directory, "jp_nice_class_board_summary.json").exists())

        // Same instance reads summaries from disk when reloaded (not pinned in an in-memory cache).
        val editedBoard = File(directory, "nice_class_board_summary.json").readText().replace("KR 보드 kr1", "디스크 수정 보드")
        File(directory, "nice_class_board_summary.json").writeText(editedBoard)
        urls.clear()
        time += 2 * 86_400_000L
        val krFromDisk = gameNames.dashboard(listOf(state), hasJpAccount = false)
        assertEquals(listOf(AtlasVersions.INFO_URL), urls)
        assertEquals("디스크 수정 보드", krFromDisk.classBoards[1]?.name)

        // Opening JP account downloads JP summaries under jp_ prefixes; hash change refreshes after cooldown.
        urls.clear()
        val jpFirst = gameNames.dashboard(listOf(state), hasJpAccount = true)
        assertEquals(1, urls.count { it.endsWith("/JP/nice_class_board.json") })
        assertEquals(1, urls.count { it.endsWith("/JP/nice_gacha.json") })
        assertEquals("JP ボード jp1", jpFirst.classBoards[1]?.name)
        assertEquals("jp1", File(directory, "jp_nice_class_board_summary.hash").readText())

        urls.clear()
        time += AtlasVersions.CHECK_INTERVAL_MS
        krHash = "kr2"
        val krUpdated = gameNames.dashboard(listOf(state), hasJpAccount = false)
        assertEquals("KR 보드 kr2", krUpdated.classBoards[1]?.name)
        assertEquals("KR FP kr2", krUpdated.gachas[1]?.name)
    }

    @Test fun dashboardSummariesSkipDownloadWhileScriptRunsAndRetryAfterStop() = runBlocking {
        val urls = mutableListOf<String>()
        val downloader: (String) -> String = { url ->
            urls += url
            when {
                url.endsWith("/KR/nice_class_board.json") -> """[{"id":1,"name":"세이버","squares":[{"id":1,"flags":["start"]}]}]"""
                url.endsWith("/KR/nice_gacha.json") -> """[{"id":1,"name":"프포","type":"friendPoint"}]"""
                else -> """[{"id":10,"name":"서번트","type":"normal"}]"""
            }
        }
        val state = dashboardState(
            "userClassBoardSquare" to """[{"classBoardBaseId":1,"classBoardSquareIds":[1],"classBoardUnlockSquareIds":[]}]""",
            "userGacha" to """[{"gachaId":1,"num":5}]""",
        )
        try {
            ChaldeaSyncManager.onScriptStart()
            val duringRun = GameNames(directory, downloader) { 1_000L }.dashboard(listOf(state))
            assertTrue(urls.isEmpty(), "No network requests while script is running, saw: $urls")
            assertTrue(duringRun.classBoards.isEmpty())
            assertTrue(duringRun.gachas.isEmpty())
            assertFalse(File(directory, "nice_class_board_summary.attempt").exists())

            ChaldeaSyncManager.onScriptEnd()
            val afterRun = GameNames(directory, downloader) { 1_001L }.dashboard(listOf(state))
            assertTrue(urls.any { it.endsWith("/KR/nice_class_board.json") })
            assertTrue(urls.any { it.endsWith("/KR/nice_gacha.json") })
            assertEquals("세이버", afterRun.classBoards[1]?.name)
            assertEquals("프포", afterRun.gachas[1]?.name)
        } finally {
            ChaldeaSyncManager.resetForTest()
        }
    }
}
