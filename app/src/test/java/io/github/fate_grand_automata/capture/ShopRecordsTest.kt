package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.AccountState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.InputStream
import java.util.Collections
import kotlin.system.measureTimeMillis
import kotlin.test.*

/** Hand-made exports in the shape of Atlas's `nice_shop.json` and `nice_event.json`, with fields the summaries must drop. */
internal const val NICE_SHOP_FIXTURE = """[
 {"id":1,"baseShopId":1,"shopType":"none","eventId":0,"name":"친구 포인트 소환","detail":"x","payType":"friendPoint",
  "cost":{"item":{"id":4,"name":"친구 포인트","icon":"https://static.atlasacademy.io/KR/Items/12.png","uses":[]},"amount":200},
  "consumes":[],"gifts":[],"releaseConditions":[{"type":"questClear"}],"limitNum":0,"openedAt":1436194800,"closedAt":1893423600},
 {"id":11000101,"shopType":"mana","eventId":0,"name":"성배","cost":{"item":{"id":3,"name":"마나 프리즘"},"amount":300},"limitNum":1,"openedAt":1700000000,"closedAt":1800000000},
 {"id":11000102,"shopType":"mana","eventId":0,"name":"QP 100만","cost":{"item":{"id":3,"name":"마나 프리즘"},"amount":20},"limitNum":5,"openedAt":1700000000,"closedAt":1800000000},
 {"id":12000101,"shopType":"rarePri","eventId":0,"name":"레어 교환권","cost":{"item":{"id":18,"name":"레어 프리즘"},"amount":10},"limitNum":3,"openedAt":1700000000,"closedAt":1800000000},
 {"id":13000101,"shopType":"purePri","eventId":0,"name":"퓨어 교환권","cost":{"item":{"id":46,"name":"퓨어 프리즘"},"amount":5},"limitNum":2,"openedAt":1700000000,"closedAt":1800000000},
 {"id":400013,"shopType":"svtAnonymous","eventId":0,"name":"드레이크","cost":{"item":{"id":17,"name":"무기명 영기"},"amount":10},"limitNum":0,"openedAt":1436194800,"closedAt":1893423600}
]"""

internal const val NICE_EVENT_FIXTURE = """[
 {"id":80001,"name":"서유기 \n이벤트","startedAt":1500000000,"endedAt":1600000000,"missions":[{"id":1,"conds":[{"x":1}]}],"rewards":[],
  "shop":[
   {"id":80001001,"eventId":80001,"name":"금각","cost":{"item":{"id":94000001,"name":"복숭아"},"amount":50},"limitNum":1,"openedAt":1500000000,"closedAt":1600000000,"gifts":[{"id":1}]},
   {"id":80001002,"eventId":80001,"name":"종화","cost":{"item":{"id":94000001,"name":"복숭아"},"amount":5},"limitNum":3,"openedAt":1500000000,"closedAt":1600000000},
   {"id":80001003,"eventId":80001,"name":"QP","cost":{"item":{"id":94000001,"name":"복숭아"},"amount":1},"limitNum":0,"openedAt":1500000000,"closedAt":1600000000}
  ]},
 {"id":80002,"name":"상점 없는 이벤트","startedAt":1500000000,"endedAt":1600000000,"shop":[]},
 {"id":80003,"name":"열린 이벤트","startedAt":1700000000,"endedAt":1790000000,
  "shop":[
   {"id":80003001,"eventId":80003,"name":"예장","cost":{"item":{"id":94000002,"name":"메달"},"amount":100},"limitNum":2,"openedAt":1700000000,"closedAt":1800000000},
   {"id":80003002,"eventId":80003,"name":"성배","cost":{"item":{"id":94000002,"name":"메달"},"amount":400},"limitNum":1,"openedAt":1700000000,"closedAt":1800000000}
  ]}
]"""

private fun shopRow(id: Long, num: Long, reset: Long = 0, at: Long = 1_750_000_000) =
    """{"userId":"111","shopId":$id,"num":$num,"flag":0,"resetNum":$reset,"updatedAt":$at}"""

internal val SHOP_TABLE = listOf(
    shopRow(11000101, 1, at = 1_750_000_100), shopRow(11000102, 2, at = 1_750_000_200), shopRow(12000101, 3, reset = 2),
    shopRow(13000101, 1), shopRow(400013, 4, at = 1_600_000_000), shopRow(80001001, 1, at = 1_550_000_000), shopRow(80001002, 2, at = 1_550_000_100),
    shopRow(80001003, 10, at = 1_550_000_200), shopRow(80003001, 1, at = 1_750_000_300), shopRow(99999999, 2, at = 1_400_000_000),
).joinToString(",", "[", "]")

internal fun shopFixtureNames() = ShopNames(GameNames.summarizeShops(NICE_SHOP_FIXTURE), GameNames.summarizeEventShops(NICE_EVENT_FIXTURE))

class ShopRecordsTest {
    @TempDir lateinit var directory: File
    private val records by lazy { shopRecords(readUserShop(SHOP_TABLE), shopFixtureNames()) }
    private val now = 1_750_000_000L
    private fun ids(filter: ShopFilter, query: String = "", sort: ShopSort = ShopSort.LastPurchase, buyable: Boolean = false) =
        shopView(records, filter, query, sort, buyable, now).map {
            when (it) { is ShopRow.Item -> it.item.id; is ShopRow.Event -> it.event.id }
        }

    @Test fun theSummariesKeepOnlyNamePriceLimitAndPeriod() {
        val shops = GameNames.summarizeShops(NICE_SHOP_FIXTURE).associateBy { it.id }
        assertEquals(ShopSummary(11000101, "성배", 3, "마나 프리즘", 300, 1, 1700000000, 1800000000), shops[11000101])
        assertEquals(0, shops.getValue(400013).limit)
        val events = GameNames.summarizeEventShops(NICE_EVENT_FIXTURE)
        assertEquals(listOf(80001L, 80003L), events.map { it.id })
        assertEquals("서유기 이벤트", events.first().name)
        assertEquals(3, events.first().shops.size)
        val encoded = Json.encodeToString(shops.values.toList()) + Json.encodeToString(events)
        for (dropped in listOf("gifts", "releaseConditions", "missions", "icon", "shopType", "detail", "payType")) assertFalse(dropped in encoded, dropped)
    }

    @Test fun anEmptyOrTruncatedExportIsRejected() {
        assertFails { GameNames.summarizeShops("[]") }
        assertFails { GameNames.summarizeEventShops("""[{"id":1,"name":"x","shop":[]}]""") }
        assertFails { GameNames.summarizeEventShops(NICE_EVENT_FIXTURE.dropLast(30)) }
    }

    @Test fun theReaderKeepsTheFourFieldsAndAcceptsQuotedNumbers() {
        val read = readUserShop("""[{"shopId":"5","num":"2","resetNum":1,"updatedAt":"9","flag":0,"userId":"1"},{"shopId":0}]""")
        assertTrue(read.complete)
        assertEquals(listOf(UserShopRow(5, 2, 1, 9)), read.rows)
    }

    @Test fun theReaderStreamsRows() {
        val text = (1..4687).joinToString(",", "[", "]") { shopRow(80_000_000L + it, 1) }
        var consumed = 0L
        val stream = object : InputStream() {
            val inner = text.byteInputStream()
            override fun read(): Int = inner.read().also { if (it >= 0) consumed++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) consumed += it }
        }
        assertEquals(80_000_001L, readTableRows(stream, UserShopRow.serializer()).first().shopId)
        assertTrue(consumed < text.length / 10, "read $consumed of ${text.length}")
    }

    @Test fun prismTotalsArePurchasesTimesPriceAndLeaveResetItemsOut() {
        val byItem = records.prisms.associateBy { it.itemId }
        assertEquals(PrismTotal(MANA_PRISM, 1 * 300L + 2 * 20L, 0), byItem[MANA_PRISM])
        assertEquals(PrismTotal(RARE_PRISM, 0, 1), byItem[RARE_PRISM])
        assertEquals(PrismTotal(PURE_PRISM, 5, 0), byItem[PURE_PRISM])
    }

    @Test fun withoutTheShopListThereAreNoPrismTotals() {
        assertEquals(emptyList(), shopRecords(readUserShop(SHOP_TABLE), ShopNames(events = shopFixtureNames().events)).prisms)
    }

    @Test fun eachChipIsDecidedByThePriceItemOrTheEvent() {
        assertEquals(listOf(80003L, 80001L), ids(ShopFilter.Event))
        assertEquals(listOf(11000102L, 11000101L), ids(ShopFilter.Mana))
        assertEquals(listOf(12000101L), ids(ShopFilter.Rare))
        assertEquals(listOf(13000101L), ids(ShopFilter.Pure))
        assertEquals(listOf(400013L, 99999999L), ids(ShopFilter.Other))
    }

    @Test fun anEventRowCountsItsLimitedItemsAndThoseBoughtToTheLimit() {
        val event = records.events.single { it.id == 80001L }
        assertEquals(2, event.limited)
        assertEquals(1, event.completed)
        assertEquals(listOf(80001001L, 80001002L, 80001003L), event.items.map { it.id }.sorted())
        assertEquals(1_600_000_000L, event.shopClosedAt)
        val open = records.events.single { it.id == 80003L }
        assertEquals(2, open.limited)
        assertEquals(0, open.completed)
    }

    @Test fun doneIsAPurchaseTotalAtTheLimitAndZeroMeansNoLimit() {
        val all = records.items + records.events.flatMap { it.items }
        assertTrue(all.single { it.id == 11000101L }.complete)
        assertFalse(all.single { it.id == 11000102L }.complete)
        val unlimited = all.single { it.id == 80001003L }
        assertEquals(0L, unlimited.limit)
        assertFalse(unlimited.complete)
        assertNull(all.single { it.id == 99999999L }.limit)
    }

    @Test fun buyableOnlyKeepsOpenShopsBelowTheirLimit() {
        assertEquals(listOf(80003L), ids(ShopFilter.Event, buyable = true))
        val row = shopView(records, ShopFilter.Event, "", ShopSort.LastPurchase, true, now).single() as ShopRow.Event
        assertEquals(listOf(80003001L), row.items.map { it.id })
        assertEquals(listOf(11000102L), ids(ShopFilter.Mana, buyable = true))
        // The unlimited anonymous exchange stays; the unknown item cannot be judged and is left out.
        assertEquals(listOf(400013L), ids(ShopFilter.Other, buyable = true))
    }

    @Test fun anUnknownItemShowsItsNumberUnderOtherAndIsSearchable() {
        val unknown = records.items.single { it.id == 99999999L }
        assertNull(unknown.name)
        assertEquals(0, unknown.costItem)
        assertEquals(listOf(99999999L), ids(ShopFilter.Other, "#99999999"))
        assertEquals(listOf(99999999L), ids(ShopFilter.Other, "9999999"))
    }

    @Test fun searchFindsItemAndEventNames() {
        assertEquals(listOf(11000101L), ids(ShopFilter.Mana, "성배"))
        assertEquals(listOf(80001L), ids(ShopFilter.Event, "서유기"))
        val byEvent = shopView(records, ShopFilter.Event, "서유기", ShopSort.LastPurchase, false, now).single() as ShopRow.Event
        assertEquals(3, byEvent.items.size)
        val byItem = shopView(records, ShopFilter.Event, "금각", ShopSort.LastPurchase, false, now).single() as ShopRow.Event
        assertEquals(listOf(80001001L), byItem.items.map { it.id })
        assertEquals(emptyList(), ids(ShopFilter.Event, "없는 이름"))
    }

    @Test fun sortingByLastPurchaseAmountAndName() {
        assertEquals(listOf(400013L, 99999999L), ids(ShopFilter.Other, sort = ShopSort.LastPurchase))
        assertEquals(listOf(80001L, 80003L), ids(ShopFilter.Event, sort = ShopSort.Amount))
        assertEquals(listOf(400013L, 99999999L), ids(ShopFilter.Other, sort = ShopSort.Name))
        assertEquals(listOf(11000102L, 11000101L), ids(ShopFilter.Mana, sort = ShopSort.Name))
    }

    @Test fun aRepeatedRowKeepsTheLatestTotal() {
        val read = readUserShop("[" + shopRow(11000102, 1, at = 1) + "," + shopRow(11000102, 4, at = 2) + "]")
        assertEquals(4, shopRecords(read, shopFixtureNames()).items.single().num)
    }

    @Test fun theNumbersOfALargeAccountAreProjectedQuickly() {
        val table = (1..4687).joinToString(",", "[", "]") { shopRow(80_000_000L + it, (it % 5).toLong(), at = 1_600_000_000L + it) }
        val names = shopFixtureNames()
        shopView(shopRecords(readUserShop(table), names), ShopFilter.Other, "", ShopSort.Name, false, now)
        lateinit var rows: List<ShopRow>
        val ms = measureTimeMillis { rows = shopView(shopRecords(readUserShop(table), names), ShopFilter.Other, "#8000", ShopSort.Name, true, now) }
        println("shop records: read and projected 4,687 rows in $ms ms")
        assertTrue(ms < 2_000, "$ms ms")
        assertTrue(rows.isEmpty())
    }

    @Test fun theDashboardProjectionNeverParsesTheShopTable() {
        val state = AccountState(mapOf("userGame" to """[{"name":"x","lv":1}]""", "userShop" to "not json {"), "[]", null, 100, 200)
        assertTrue(dashboardData(state, GameNameMap()).hasShopRecords)
    }

    // --- The name lists ---

    private fun names(script: () -> Boolean = { false }, time: () -> Long = { 1000L }, download: (String) -> String) =
        GameNames(directory, download, time, script)

    private fun fixtureFor(url: String) = when {
        url == AtlasVersions.INFO_URL -> error("offline")
        url.endsWith("/nice_shop.json") -> NICE_SHOP_FIXTURE
        url.endsWith("/nice_event.json") -> NICE_EVENT_FIXTURE
        else -> error(url)
    }

    @Test fun theListsOfTheAccountsOwnServerAreUsedAndNeverTheOther() = runBlocking<Unit> {
        val urls = mutableListOf<String>()
        val cache = names { url -> fixtureFor(url).also { urls += url } }
        assertTrue(cache.shopNames("JP", allowDownload = true).complete)
        assertEquals(listOf("https://api.atlasacademy.io/export/JP/nice_shop.json", "https://api.atlasacademy.io/export/JP/nice_event.json"), urls)
        assertTrue(File(directory, "jp_nice_shop_summary.json").isFile)
        assertTrue(File(directory, "jp_nice_event_summary.json").isFile)
        assertEquals(ShopNames(), cache.shopNames("KR", allowDownload = false))
        assertEquals(ShopNames(), cache.shopNames("NA", allowDownload = true))
    }

    @Test fun nothingIsDownloadedWhileAScriptRuns() = runBlocking<Unit> {
        var calls = 0
        val cache = names(script = { true }) { calls++; fixtureFor(it) }
        assertEquals(ShopNames(), cache.shopNames("KR", allowDownload = true, force = true))
        assertEquals(0, calls)
    }

    @Test fun aDamagedDownloadLeavesTheOldSummaryInPlace() = runBlocking<Unit> {
        var damaged = false
        var time = 1000L
        val cache = names(time = { time }) { url ->
            val body = fixtureFor(url)
            if (damaged && url.endsWith("/nice_event.json")) body.substring(0, body.length / 2) else body
        }
        val first = cache.shopNames("KR", allowDownload = true)
        val file = File(directory, "nice_event_summary.json")
        val saved = file.readText()
        damaged = true
        time += 8 * 86_400_000L
        assertEquals(first, cache.shopNames("KR", allowDownload = true))
        assertEquals(saved, file.readText())
    }

    @Test fun aMissingEventListMeansNumbersAndAStatusOfMissing() = runBlocking<Unit> {
        val keys = MutableStateFlow(RecordsKey(1, "KR"))
        val states = Collections.synchronizedList(mutableListOf<ShopRecordsState>())
        val partial = ShopNames(GameNames.summarizeShops(NICE_SHOP_FIXTURE), emptyList())
        val loader = RecordsLoader(
            readTable = { SHOP_TABLE }, parse = ::readUserShop,
            names = { _, _, _ -> partial }, hasNames = { it.complete }, project = ::shopRecords,
            scriptRunning = MutableStateFlow(false), isScriptRunning = { false },
        )
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            scope.launch { loader.load("u", keys, MutableStateFlow(0)).collect { states += it } }
            withTimeout(5_000) { while (states.lastOrNull()?.names != RecordNameStatus.Missing) delay(10) }
            val shown = states.last().records!!
            assertTrue(shown.events.isEmpty())
            assertEquals("성배", shown.items.single { it.id == 11000101L }.name)
            assertNull(shown.items.single { it.id == 80001001L }.name)
        } finally { scope.cancel() }
    }
}
