package io.github.fate_grand_automata.battlelog

import io.github.fate_grand_automata.scripts.capture.BattleGains
import io.github.fate_grand_automata.scripts.capture.GainedItem
import io.github.fate_grand_automata.scripts.capture.RunGains
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalSerializationApi::class)
class BattleLogStorageTest {
    @TempDir
    lateinit var directory: File

    private val legacy get() = File(directory, "battle-log.json")
    private val index get() = File(directory, "battle-log/index.json")
    private val details get() = File(directory, "battle-log/details")
    private fun detailFiles() = details.listFiles().orEmpty().map { it.name }.sorted()

    /** 12 item rows and 6 bond rows per battle, like the largest real runs. */
    private fun battle(time: Long, quest: Long? = 10001): BattleGains = BattleGains(
        time = time, questId = quest,
        items = (1..12).map { GainedItem(2, 6000L + it, it.toLong()) },
        qp = 1_000_000, masterExp = 12_345,
        bond = (1..6).associate { 100_000L + it to it * 100L },
    )

    private fun gains(count: Int, firstTime: Long = 1_000, quest: (Int) -> Long? = { 10001 }): RunGains =
        RunGains(battles = List(count) { battle(firstTime + it, quest(it)) }).finish(null).withRunCount(count)

    private fun entry(id: String, endedAt: Long, battles: Int = 3, quest: (Int) -> Long? = { 10001 }) =
        battleLogFixture(id).copy(endedAt = endedAt, gains = gains(battles, endedAt / 1000, quest))

    private fun writeLegacy(entries: List<BattleLogEntry>) = legacy.writeText(Json.encodeToString(entries))

    @Test
    fun addStoresOnlyTheSummaryInTheIndexAndTheBattlesInTheirOwnFile() = runBlocking {
        val original = entry("a", 10, battles = 4)
        val repository = BattleLogRepository(directory)
        repository.add(original)
        val summary = repository.entries.first().single()
        assertTrue(summary.gains!!.battles.isEmpty())
        assertEquals(4, summary.gains!!.capturedBattles)
        assertEquals(original.gains!!.items, summary.gains!!.items)
        assertEquals(original.gains!!.qp, summary.gains!!.qp)
        assertEquals(original.gains!!.missingBattles, summary.gains!!.missingBattles)
        assertEquals(1, detailFiles().size)
        assertFalse(index.readText().contains("\"battles\":[{"))
        assertEquals(original.gains!!.battles, repository.battles("a"))
        // Same data after a restart.
        assertEquals(summary, BattleLogRepository(directory).entries.first().single())
    }

    @Test
    fun breakdownReadsTheRightEntry() = runBlocking {
        val repository = BattleLogRepository(directory)
        val one = entry("one", 10, battles = 2) { 111L }
        val two = entry("two", 20, battles = 3) { 222L }
        repository.add(one); repository.add(two)
        assertEquals(one.gains!!.battles, repository.battles("one"))
        assertEquals(two.gains!!.battles, repository.battles("two"))
        assertTrue(repository.battles("missing")!!.isEmpty())
    }

    @Test
    fun entryWithoutGainsWritesNoDetail() = runBlocking {
        val repository = BattleLogRepository(directory)
        repository.add(battleLogFixture("plain"))
        assertTrue(detailFiles().isEmpty())
        assertEquals(listOf("plain"), repository.entries.first().map { it.id })
    }

    @Test
    fun dashboardQuestIdIsTheNewestBattlesQuest() = runBlocking {
        val repository = BattleLogRepository(directory)
        // Newest by time is the first row even though the list order is shuffled.
        val run = RunGains(battles = listOf(battle(500, 3L), battle(100, 1L), battle(300, 2L))).finish(null)
        val original = battleLogFixture("q").copy(gains = run)
        val expected = original.gains!!.battles.maxByOrNull { it.time }?.questId
        repository.add(original)
        assertEquals(3L, expected)
        assertEquals(expected, repository.entries.first().single().gains!!.lastQuestId)
        assertEquals(expected, BattleLogRepository(directory).entries.first().single().gains!!.lastQuestId)
    }

    @Test
    fun deleteClearAndTrimRemoveDetailFiles() = runBlocking {
        var limit = 10
        val repository = BattleLogRepository(directory, { limit })
        (1..5).forEach { repository.add(entry("e$it", it.toLong())) }
        assertEquals(5, detailFiles().size)
        repository.delete("e3")
        assertEquals(4, detailFiles().size)
        assertTrue(repository.battles("e3")!!.isEmpty())
        limit = 2
        repository.trim()
        assertEquals(listOf("e5", "e4"), repository.entries.first().map { it.id })
        assertEquals(2, detailFiles().size)
        repository.clear()
        assertTrue(detailFiles().isEmpty())
    }

    @Test
    fun addingBeyondRetentionDropsTheOldestDetail() = runBlocking {
        val repository = BattleLogRepository(directory, { 2 })
        repository.add(entry("a", 1)); repository.add(entry("b", 2)); repository.add(entry("c", 3))
        assertEquals(listOf("c", "b"), repository.entries.first().map { it.id })
        assertEquals(2, detailFiles().size)
        repository.add(entry("ancient", 0))
        assertEquals(2, detailFiles().size)
    }

    @Test
    fun orphanDetailFilesAreSweptOnTheNextWrite() = runBlocking {
        val repository = BattleLogRepository(directory)
        repository.add(entry("a", 1))
        File(details, "stray.json").writeText("[]")
        repository.add(entry("b", 2))
        assertEquals(2, detailFiles().size)
        assertFalse(File(details, "stray.json").exists())
    }

    @Test
    fun migrationKeepsEveryTotalAndTheBattles() = runBlocking {
        val old = listOf(entry("a", 100, 5), entry("b", 200, 2), battleLogFixture("plain").copy(endedAt = 50))
        writeLegacy(old)
        val repository = BattleLogRepository(directory)
        val migrated = repository.entries.first()
        assertEquals(listOf("b", "a", "plain"), migrated.map { it.id })
        for (before in old) {
            val after = migrated.single { it.id == before.id }
            assertEquals(before.copy(gains = before.gains?.summarized()), after)
            before.gains?.let { g ->
                assertEquals(g.capturedBattles, after.gains!!.capturedBattles)
                assertEquals(g.items, after.gains!!.items)
                assertEquals(g.qp, after.gains!!.qp)
                assertEquals(g.masterExp, after.gains!!.masterExp)
                assertEquals(g.bond, after.gains!!.bond)
                assertEquals(g.fruitUsed, after.gains!!.fruitUsed)
                assertEquals(g.missingBattles, after.gains!!.missingBattles)
                assertEquals(g.battles, repository.battles(before.id))
            }
        }
        assertFalse(legacy.exists())
        assertTrue(index.exists())
        assertEquals(2, detailFiles().size)
    }

    @Test
    fun corruptOldFileIsBackedUpAndTheLogStartsEmpty() = runBlocking {
        legacy.writeText(Json.encodeToString(listOf(entry("a", 1))).dropLast(40) + " broken")
        val repository = BattleLogRepository(directory)
        assertTrue(repository.entries.first().isEmpty())
        assertFalse(legacy.exists())
        assertTrue(File(directory, "battle-log.corrupt.json").exists())
        assertTrue(detailFiles().isEmpty())
        repository.add(entry("b", 2))
        assertEquals(listOf("b"), BattleLogRepository(directory).entries.first().map { it.id })
    }

    @Test
    fun emptyOrMissingOldFileGivesAnEmptyLog() = runBlocking {
        assertTrue(BattleLogRepository(directory).entries.first().isEmpty())
        legacy.writeText("")
        assertTrue(BattleLogRepository(directory).entries.first().isEmpty())
        legacy.writeText("[]")
        assertTrue(BattleLogRepository(directory).entries.first().isEmpty())
        assertFalse(legacy.exists())
    }

    private class Failing(val real: BattleLogStorage) : BattleLogStorage by real {
        var failIndexWrite = false
        var failLoad = false
        var failDetailRead = false
        var failDetailWrite = false
        override fun writeIndex(entries: List<BattleLogEntry>) {
            if (failIndexWrite) throw OutOfMemoryError("test")
            real.writeIndex(entries)
        }
        override fun readIndex(): List<BattleLogEntry>? {
            if (failLoad) throw OutOfMemoryError("test")
            return real.readIndex()
        }
        override fun streamLegacy(onSummary: (BattleLogEntry) -> Unit) {
            if (failLoad) throw OutOfMemoryError("test")
            real.streamLegacy(onSummary)
        }
        override fun readDetail(id: String): List<BattleGains> {
            if (failDetailRead) throw OutOfMemoryError("test")
            return real.readDetail(id)
        }
        override fun writeDetail(id: String, battles: List<BattleGains>) {
            if (failDetailWrite) throw OutOfMemoryError("test")
            real.writeDetail(id, battles)
        }
    }

    @Test
    fun interruptedMigrationKeepsTheOldFileAndTheNextLoadCompletes() = runBlocking {
        val old = listOf(entry("a", 1), entry("b", 2))
        writeLegacy(old)
        val storage = Failing(FileBattleLogStorage(directory)).apply { failIndexWrite = true }
        val repository = BattleLogRepository(storage)
        assertTrue(repository.entries.first().isEmpty())
        assertTrue(legacy.exists())
        assertFalse(index.exists())
        // Writes must not clobber the unread log while the migration cannot finish.
        repository.add(entry("c", 3))
        assertTrue(legacy.exists())
        storage.failIndexWrite = false
        repository.add(entry("c", 3))
        assertEquals(listOf("c", "b", "a"), repository.entries.first().map { it.id })
        assertFalse(legacy.exists())
        assertEquals(3, detailFiles().size)
    }

    @Test
    fun oldFileLeftBehindAfterAWrittenIndexIsRemovedWithoutReimporting() = runBlocking {
        BattleLogRepository(directory).add(entry("new", 5))
        writeLegacy(listOf(entry("old", 1)))
        assertEquals(listOf("new"), BattleLogRepository(directory).entries.first().map { it.id })
        assertFalse(legacy.exists())
    }

    @Test
    fun anErrorFromStorageNeverPropagatesAndTheLastGoodStateStays() = runBlocking {
        val storage = Failing(FileBattleLogStorage(directory))
        val repository = BattleLogRepository(storage)
        storage.failLoad = true
        assertTrue(repository.entries.first().isEmpty())
        repository.add(entry("x", 1)) // load fails: nothing thrown, nothing written
        assertFalse(index.exists())
        storage.failLoad = false
        repository.add(entry("a", 1))
        assertEquals(listOf("a"), repository.entries.first().map { it.id })

        storage.failIndexWrite = true
        repository.add(entry("b", 2))
        repository.delete("a"); repository.clear(); repository.trim()
        assertEquals(listOf("a"), repository.entries.first().map { it.id })
        storage.failIndexWrite = false

        storage.failDetailWrite = true
        repository.add(entry("c", 3))
        assertEquals(listOf("a"), repository.entries.first().map { it.id })
        storage.failDetailWrite = false

        storage.failDetailRead = true
        assertNull(repository.battles("a"))
        storage.failDetailRead = false
        assertNotNull(repository.battles("a"))
        repository.add(entry("d", 4))
        assertEquals(listOf("d", "a"), repository.entries.first().map { it.id })
    }

    @Test
    fun largeLogStaysFarBelowTheTestHeap() = runBlocking {
        val entries = 500
        val battles = 300
        legacy.outputStream().buffered().use { out ->
            out.write('['.code)
            for (n in 1..entries) {
                if (n > 1) out.write(','.code)
                Json.encodeToStream(entry("e$n", n * 1_000L, battles), out)
            }
            out.write(']'.code)
        }
        val oldBytes = legacy.length()
        gc()
        val before = usedHeap()
        val migrateStart = System.nanoTime()
        val repository = BattleLogRepository(directory, { 500 })
        val loaded = repository.entries.first()
        val migrateMs = (System.nanoTime() - migrateStart) / 1_000_000
        gc()
        val retained = usedHeap() - before
        assertEquals(entries, loaded.size)
        assertEquals(battles, loaded.first().gains!!.capturedBattles)

        val addStart = System.nanoTime()
        repository.add(entry("fresh", 9_999_999L, battles))
        val addMs = (System.nanoTime() - addStart) / 1_000_000
        gc()
        val retainedAfterAdd = usedHeap() - before
        println("BATTLE-LOG-MEASURE oldFile=${oldBytes / 1024}KB index=${index.length() / 1024}KB " +
            "details=${details.listFiles()!!.size} retainedAfterLoad=${retained / 1024}KB retainedAfterAdd=${retainedAfterAdd / 1024}KB " +
            "migrate=${migrateMs}ms addOne=${addMs}ms maxHeap=${Runtime.getRuntime().maxMemory() / (1024 * 1024)}MB")
        assertTrue(retainedAfterAdd < 64 * 1024 * 1024, "retained $retainedAfterAdd")
        assertEquals(battles, repository.battles("fresh")!!.size)
    }

    private fun gc() { repeat(3) { System.gc() }; Thread.sleep(50) }
    private fun usedHeap() = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
}
