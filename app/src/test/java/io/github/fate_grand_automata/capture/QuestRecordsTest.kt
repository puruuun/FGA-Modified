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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.zip.GZIPOutputStream
import kotlin.system.measureTimeMillis
import kotlin.test.*

/** A hand-made export in the shape of Atlas's `nice_war.json`, with fields the summary must drop. */
internal const val NICE_WAR_FIXTURE = """[
 {"id":100,"name":"특이점F","longName":"특이점F\n염상오염도시 후유키","eventId":0,"eventName":"","age":"-","banner":null,"script":"x",
  "spots":[{"id":10001,"name":"미확인좌표 X-A","image":"https://example.invalid/spot.png","x":1,"quests":[
    {"id":1000000,"name":"프롤로그","type":"main","flags":["enableFollowQuest"],"recommendLv":"0","afterClear":"close",
     "phases":[1],"spotName":"미확인좌표 X-A","gifts":[{"id":1,"objectId":2}],"phaseScripts":[],"releaseConditions":[{"type":"x"}]},
    {"id":93000001,"name":"교회 터","type":"free","flags":[],"recommendLv":"90++","phases":[1,2,3],"spotName":"교회 터","consume":40}
  ]}]},
 {"id":1001,"name":"강화 퀘스트","longName":"강화 퀘스트","eventId":0,"eventName":"",
  "spots":[{"name":"강화 퀘스트","quests":[{"id":94002501,"name":"강화 퀘스트 스파르타쿠스","type":"event","flags":["noDisplayRemain"],"recommendLv":"60","spotName":"강화 퀘스트"}]}]},
 {"id":1002,"name":"요일 퀘스트","longName":"요일 퀘스트","eventId":0,"eventName":"",
  "spots":[{"name":"보물고","quests":[{"id":94006801,"name":"보물고의 문 초급","type":"event","flags":[],"recommendLv":"20","spotName":"보물고"}]}]},
 {"id":1003,"name":"막간의 이야기","longName":"막간의 이야기","eventId":0,"eventName":"",
  "spots":[{"name":"칼데아","quests":[{"id":91000101,"name":"막간: 별의 검","type":"friendship","flags":[],"recommendLv":"70","spotName":"칼데아"}]}]},
 {"id":9100,"name":"서유기","longName":"서유기\n이벤트 전쟁","eventId":80001,"eventName":"서유기 이벤트",
  "spots":[{"name":"오행산","quests":[
    {"id":94010101,"name":"레이드 보스","type":"event","flags":["raid"],"recommendLv":"90+","spotName":"오행산"},
    {"id":94010102,"name":"이벤트 스토리","type":"event","flags":["noBattle"],"recommendLv":"1","spotName":"오행산"},
    {"id":94010103,"name":"이벤트 프리","type":"event","flags":[],"recommendLv":"90","spotName":"오행산"},
    {"id":94010103,"name":"같은 퀘스트의 사본","type":"event","flags":[],"recommendLv":"90","spotName":"다른 곳"}
  ]}]}
]"""

private fun row(id: Long, clears: Long, challenges: Long = clears, last: Long = 1_700_000_000, phase: Long = 3) =
    """{"userId":"111","questId":$id,"questPhase":$phase,"clearNum":$clears,"challengeNum":$challenges,"lastStartedAt":$last,""" +
        """"createdAt":1600000000,"updatedAt":1700000001,"status":2,"expireAt":0,"isEternalOpen":false,"isNew":false}"""

private val ACCOUNT_TABLE = listOf(
    row(1000000, 1), row(93000001, 687, 690, last = 1_763_600_000), row(94002501, 1), row(94006801, 30, last = 1_763_000_000),
    row(91000101, 1), row(94010101, 120, last = 1_763_700_000), row(94010102, 1), row(94010103, 55), row(99999999, 4),
).joinToString(",", "[", "]")

class QuestRecordsTest {
    @TempDir lateinit var directory: File
    private val wars by lazy { GameNames.summarizeWars(NICE_WAR_FIXTURE) }
    private val records by lazy { questRecords(readUserQuest(ACCOUNT_TABLE), wars) }
    private fun ids(filter: QuestFilter, query: String = "", sort: QuestSort = QuestSort.Clears) =
        questView(records, filter, query, sort).rows.map { it.id }

    @Test fun theSummaryKeepsOnlyNamesTypesFlagsAndLevels() {
        val byId = wars.associateBy { it.id }
        assertEquals("특이점F 염상오염도시 후유키", byId.getValue(100).longName)
        assertEquals(null, byId.getValue(100).eventName)
        assertEquals("서유기 이벤트", byId.getValue(9100).eventName)
        val raid = byId.getValue(9100).quests.first { it.id == 94010101L }
        assertEquals(QuestNameSummary(94010101, "레이드 보스", "오행산", "event", "90+", raid = true, noBattle = false), raid)
        assertTrue(byId.getValue(9100).quests.first { it.id == 94010102L }.noBattle)
        // A quest listed twice in one war keeps its first entry.
        assertEquals(listOf("오행산"), byId.getValue(9100).quests.filter { it.id == 94010103L }.map { it.spot })
        val encoded = Json.encodeToString(wars)
        for (dropped in listOf("gifts", "phases", "releaseConditions", "banner", "image", "consume", "afterClear")) {
            assertFalse(dropped in encoded, dropped)
        }
    }

    @Test fun anExportWithoutQuestsIsRejected() {
        assertFails { GameNames.summarizeWars("""[{"id":1,"name":"x","spots":[]}]""") }
        assertFails { GameNames.summarizeWars(NICE_WAR_FIXTURE.dropLast(40)) }
    }

    @Test fun theReaderKeepsTheFourFieldsAndAcceptsQuotedNumbers() {
        val read = readUserQuest("""[{"questId":"5","questPhase":"2","clearNum":"7","challengeNum":9,"lastStartedAt":"100","other":{"a":[1]}},{"questId":0}]""")
        assertTrue(read.complete)
        assertEquals(listOf(UserQuestRow(5, 2, 7, 9, 100)), read.rows)
    }

    @Test fun aDamagedTableKeepsTheRowsBeforeTheDamage() {
        val read = readUserQuest("[" + row(1, 2) + "," + row(2, 3) + ",{\"questId\":3,\"clearNum\":" )
        assertFalse(read.complete)
        assertEquals(listOf(1L, 2L), read.rows.map { it.questId })
    }

    @Test fun theReaderStreamsRowsInsteadOfParsingTheWholeTable() {
        val text = (1..7000).joinToString(",", "[", "]") { row(90_000_000L + it, it.toLong()) }
        var consumed = 0L
        val stream = object : InputStream() {
            val inner = text.byteInputStream()
            override fun read(): Int = inner.read().also { if (it >= 0) consumed++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) consumed += it }
        }
        val first = readUserQuestRows(stream).first()
        assertEquals(90_000_001L, first.questId)
        val total = text.toByteArray().size
        assertTrue(consumed < total / 10, "read $consumed of $total bytes for the first row")
    }

    @Test fun storyQuestsAreListedAndCountedUnderAll() {
        // 1000000 is a main story quest, 94010102 an event quest without a battle.
        assertTrue(1000000L in ids(QuestFilter.All))
        assertTrue(94010102L in ids(QuestFilter.All))
        assertTrue(94010102L in ids(QuestFilter.Event))
        val all = questView(records, QuestFilter.All, "", QuestSort.Clears)
        assertEquals(1L + 687 + 1 + 30 + 1 + 120 + 1 + 55 + 4, all.totalClears)
        assertEquals(9, all.clearedQuests)
        assertEquals(records.size, all.rows.size)
    }

    @Test fun eachChipIsDecidedFromThePublicData() {
        assertEquals(listOf(93000001L), ids(QuestFilter.Free))
        assertEquals(setOf(94010101L, 94010102L, 94010103L), ids(QuestFilter.Event).toSet())
        assertEquals(listOf(QuestFilter.All, QuestFilter.Free, QuestFilter.Event, QuestFilter.Daily, QuestFilter.Raid), QuestFilter.entries)
        // Interlude (war 1003) and rank-up (war 1001) quests have no chip of their own any more.
        assertEquals(emptySet(), records.single { it.id == 91000101L }.kinds)
        assertEquals(emptySet(), records.single { it.id == 94002501L }.kinds)
        assertEquals(listOf(94006801L), ids(QuestFilter.Daily))
        assertEquals(listOf(94010101L), ids(QuestFilter.Raid))
    }

    @Test fun aQuestWithoutANameShowsItsNumberOnlyUnderAllAndStaysSearchable() {
        val unknown = records.single { it.id == 99999999L }
        assertEquals(null, unknown.name)
        assertEquals(null, unknown.war)
        assertTrue(99999999L in ids(QuestFilter.All))
        for (filter in QuestFilter.entries - QuestFilter.All) assertFalse(99999999L in ids(filter), filter.name)
        assertEquals(listOf(99999999L), ids(QuestFilter.All, "#99999999"))
        assertEquals(listOf(99999999L), ids(QuestFilter.All, "9999999"))
    }

    @Test fun searchFindsQuestSpotWarAndEventNames() {
        assertEquals(listOf(93000001L), ids(QuestFilter.All, "교회"))
        assertEquals(setOf(94010101L, 94010102L, 94010103L), ids(QuestFilter.All, "오행산").toSet())
        assertEquals(setOf(1000000L, 93000001L), ids(QuestFilter.All, "후유키").toSet())
        assertEquals(setOf(94010101L, 94010102L, 94010103L), ids(QuestFilter.All, "서유기 이벤트").toSet())
        assertEquals(listOf(94010101L), ids(QuestFilter.Raid, " 오행산 "))
        assertEquals(emptyList(), ids(QuestFilter.Free, "오행산"))
    }

    @Test fun rowsCarryTheWarOrEventNameAndTheSpot() {
        val free = records.single { it.id == 93000001L }
        assertEquals("특이점F 염상오염도시 후유키", free.war)
        assertEquals("교회 터", free.spot)
        assertEquals(690, free.challenges)
        assertEquals(1_763_600_000, free.lastStartedAt)
        assertEquals("서유기 이벤트", records.single { it.id == 94010101L }.war)
    }

    @Test fun sortingByClearsLastRunAndName() {
        assertEquals(listOf(93000001L, 94010101L, 94010103L, 94006801L), ids(QuestFilter.All).take(4))
        assertEquals(listOf(94010101L, 93000001L, 94006801L), ids(QuestFilter.All, sort = QuestSort.LastRun).take(3))
        val byName = questView(records, QuestFilter.All, "", QuestSort.Name).rows
        assertEquals(99999999L, byName.last().id)
        assertEquals(byName.dropLast(1).map { it.name }, byName.dropLast(1).map { it.name }.sortedBy { it })
    }

    @Test fun oneRowPerQuestKeepsTheFurthestPhaseAndDoesNotAddClearsTwice() {
        val read = readUserQuest("[" + row(93000001, 5, phase = 2) + "," + row(93000001, 9, phase = 3) + "," + row(93000001, 1, phase = 1) + "]")
        val single = questRecords(read, wars).single()
        assertEquals(9, single.clears)
    }

    @Test fun sevenThousandRowsAreReadAndProjectedQuickly() {
        val table = (1..7000).joinToString(",", "[", "]") { row(if (it % 7 == 0) 93000001L + it else 90_000_000L + it, (it % 500).toLong(), last = 1_700_000_000L + it) }
        lateinit var view: QuestView
        // Warm-up so that class loading is not measured.
        questView(questRecords(readUserQuest(table), wars), QuestFilter.All, "", QuestSort.Clears)
        val readMs = measureTimeMillis { readUserQuest(table) }
        val read = readUserQuest(table)
        val projectMs = measureTimeMillis { view = questView(questRecords(read, wars), QuestFilter.All, "#9000", QuestSort.Name) }
        println("quest records: read 7,000 rows in $readMs ms, projected and searched in $projectMs ms")
        assertTrue(view.rows.isNotEmpty())
        assertTrue(readMs + projectMs < 2_000, "read $readMs ms + project $projectMs ms")
    }

    @Test fun theDashboardProjectionNeverParsesTheQuestTable() {
        // Not JSON: a parse of userQuest anywhere in dashboardData would throw.
        val state = AccountState(mapOf("userGame" to """[{"name":"x","lv":1}]""", "userQuest" to "not json {"), "[]", null, 100, 200)
        val data = dashboardData(state, GameNameMap())
        assertTrue(data.hasQuestRecords)
        assertFalse(dashboardData(AccountState(mapOf("userGame" to "[]"), "[]", null, 100, 200), GameNameMap()).hasQuestRecords)
    }

    @Test fun aGzipBodyIsUnzipped() {
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(NICE_WAR_FIXTURE.toByteArray()) } }.toByteArray()
        assertEquals(NICE_WAR_FIXTURE, decodedBody(zipped.inputStream(), "gzip").readBytes().decodeToString())
        assertEquals("plain", decodedBody("plain".byteInputStream(), null).readBytes().decodeToString())
    }

    // --- The name list cache ---

    private fun names(script: () -> Boolean = { false }, time: () -> Long = { 1000L }, download: (String) -> String) =
        GameNames(directory, download, time, script)

    @Test fun theListOfTheAccountsOwnServerIsUsedAndNeverTheOther() = runBlocking<Unit> {
        val urls = mutableListOf<String>()
        val cache = names { url -> if (url == AtlasVersions.INFO_URL) error("offline"); urls += url; NICE_WAR_FIXTURE }
        assertTrue(cache.questWars("JP", allowDownload = true).isNotEmpty())
        assertEquals(listOf("https://api.atlasacademy.io/export/JP/nice_war.json"), urls)
        assertTrue(File(directory, "jp_nice_war_summary.json").isFile)
        // A KR account with only the JP list cached gets no names.
        assertEquals(emptyList(), cache.questWars("KR", allowDownload = false))
        assertEquals(emptyList(), cache.questWars("NA", allowDownload = true))
    }

    @Test fun nothingIsDownloadedWhileAScriptRuns() = runBlocking<Unit> {
        var calls = 0
        val cache = names(script = { true }) { calls++; NICE_WAR_FIXTURE }
        assertEquals(emptyList(), cache.questWars("KR", allowDownload = true, force = true))
        assertEquals(0, calls)
    }

    @Test fun aDamagedDownloadLeavesTheOldSummaryInPlace() = runBlocking<Unit> {
        var body = NICE_WAR_FIXTURE
        var time = 1000L
        val cache = names(time = { time }) { url -> if (url == AtlasVersions.INFO_URL) error("offline"); body }
        val first = cache.questWars("KR", allowDownload = true)
        val file = File(directory, "nice_war_summary.json")
        val saved = file.readText()
        body = NICE_WAR_FIXTURE.substring(0, NICE_WAR_FIXTURE.length / 2)
        time += 8 * 86_400_000L
        assertEquals(first, cache.questWars("KR", allowDownload = true))
        assertEquals(saved, file.readText())
    }

    @Test fun aRetrySkipsTheWaitBetweenFailedAttempts() = runBlocking<Unit> {
        var calls = 0
        val cache = names { url -> if (url == AtlasVersions.INFO_URL) error("offline"); calls++; error("offline") }
        assertEquals(emptyList(), cache.questWars("KR", allowDownload = true))
        assertEquals(emptyList(), cache.questWars("KR", allowDownload = true))
        assertEquals(1, calls)
        cache.questWars("KR", allowDownload = true, force = true)
        assertEquals(2, calls)
    }

    // --- The loader ---

    private class Harness(scriptInitially: Boolean = false, private val cachedWars: List<QuestWarSummary>, private val fetchedWars: List<QuestWarSummary>) {
        val keys = MutableStateFlow(RecordsKey(1, "KR"))
        val running = MutableStateFlow(scriptInitially)
        val retries = MutableStateFlow(0)
        var reads = 0
        val calls: MutableList<Pair<Boolean, Boolean>> = Collections.synchronizedList(mutableListOf())
        val states: MutableList<QuestRecordsState> = Collections.synchronizedList(mutableListOf())
        /** When set, the volatile script flag; otherwise it follows [running], as both do in the app. */
        @Volatile var flag: Boolean? = null
        val loader = RecordsLoader(
            readTable = { reads++; ACCOUNT_TABLE },
            parse = ::readUserQuest,
            names = { _, allow, force -> calls += allow to force; if (allow && !running.value) fetchedWars else cachedWars },
            hasNames = { it.isNotEmpty() },
            project = ::questRecords,
            scriptRunning = running,
            isScriptRunning = { flag ?: running.value },
        )
        val scope = CoroutineScope(Dispatchers.Default)
        fun start() { scope.launch { loader.load("u", keys, retries).collect { states += it } } }
        suspend fun until(what: String, check: (QuestRecordsState) -> Boolean) = withTimeout(5_000) {
            while (states.lastOrNull()?.let(check) != true) delay(10)
        }.also { assertTrue(states.last().let(check), what) }
    }

    @Test fun nothingIsRebuiltWhileAScriptRunsAndTheBuildFollowsItsEnd() = runBlocking<Unit> {
        val h = Harness(cachedWars = wars, fetchedWars = wars)
        try {
            h.start()
            h.until("names") { it.names == RecordNameStatus.Ready && it.records != null }
            assertEquals(1, h.reads)
            h.running.value = true
            h.keys.value = RecordsKey(2, "KR")
            h.until("paused") { it.paused }
            delay(100)
            assertEquals(1, h.reads)
            h.running.value = false
            h.until("rebuilt") { !it.paused && it.records != null }
            assertEquals(2, h.reads)
        } finally { h.scope.cancel() }
    }

    @Test fun anAccountUpdateBeforeTheScriptFlowIsNotRebuiltWhenTheFlagSaysRunning() = runBlocking<Unit> {
        val h = Harness(cachedWars = wars, fetchedWars = wars)
        try {
            h.start()
            h.until("names") { it.names == RecordNameStatus.Ready && it.records != null }
            val before = h.states.size
            // The script has started (the flag is set first) but the flow has not delivered it yet; then the account changes.
            h.flag = true
            h.keys.value = RecordsKey(2, "KR")
            withTimeout(5_000) { while (h.states.size == before) delay(10) }
            assertTrue(h.states.last().paused)
            assertEquals(1, h.reads)
            assertFalse(h.running.value)
        } finally { h.scope.cancel() }
    }

    @Test fun noDownloadIsAskedForWhileAScriptRuns() = runBlocking<Unit> {
        val h = Harness(scriptInitially = true, cachedWars = emptyList(), fetchedWars = wars)
        try {
            h.start()
            h.until("waiting") { it.names == RecordNameStatus.Waiting }
            assertTrue(h.states.last().records!!.all { it.name == null })
            assertEquals(listOf(false to false), h.calls.toList())
            h.running.value = false
            h.until("names after the script") { it.names == RecordNameStatus.Ready }
            assertTrue(true to false in h.calls)
            assertEquals(1, h.reads)
            assertEquals("교회 터", h.states.last().records!!.single { it.id == 93000001L }.name)
        } finally { h.scope.cancel() }
    }

    @Test fun aFailedDownloadShowsNumbersAndTheRetryForcesANewAttempt() = runBlocking<Unit> {
        val h = Harness(cachedWars = emptyList(), fetchedWars = emptyList())
        try {
            h.start()
            h.until("missing") { it.names == RecordNameStatus.Missing }
            assertTrue(h.states.last().records!!.isNotEmpty())
            h.retries.value = 1
            withTimeout(5_000) { while (true to true !in h.calls) delay(10) }
            assertEquals(1, h.reads)
        } finally { h.scope.cancel() }
    }
}
