package io.github.fate_grand_automata.battlelog

import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BattleLogTest {
    @Test
    fun snapshotComputesMissingBattlesFromTheRecordedRunCount() {
        val original = battleLogFixture()
        val gains = io.github.fate_grand_automata.scripts.capture.RunGains.start(null)
            .add(io.github.fate_grand_automata.scripts.capture.BattleGains(100)).finish(null)
        val entry = BattleLogEntry.snapshot(
            AutoBattle.ExitException(AutoBattle.ExitReason.LimitRuns(8), original.exitState()),
            original.configName, original.reasonText, true, gains = gains,
        )
        assertEquals(7, entry.gains!!.missingBattles)
    }

    @Test
    fun oldJsonWithoutGainsStillLoads() {
        val json = Json.encodeToString(battleLogFixture())
        assertFalse(json.contains("gains"))
        assertNull(Json.decodeFromString<BattleLogEntry>(json).gains)
    }

    @Test
    fun gainsRoundTripIncludesPositiveMissingBattleCount() {
        val gains = io.github.fate_grand_automata.scripts.capture.RunGains.start(null)
            .add(io.github.fate_grand_automata.scripts.capture.BattleGains(100, qp = 1000)).finish(null).withRunCount(8)
        val entry = battleLogFixture().copy(gains = gains)
        val restored = Json.decodeFromString<BattleLogEntry>(Json.encodeToString(entry))
        assertEquals(gains, restored.gains)
        assertEquals(7, restored.gains!!.missingBattles)
    }

    @TempDir
    lateinit var directory: File

    @Test
    fun serializationRoundTripIncludesAllMaterialAndBondFields() {
        val entry = battleLogFixture(extras = true)
        assertEquals(entry, Json.decodeFromString<BattleLogEntry>(Json.encodeToString(entry)))
        val state = entry.exitState()
        assertEquals(600_123L, state.totalTime.inWholeMilliseconds)
        assertEquals(75_015L, state.averageTimePerRun.inWholeMilliseconds)
        assertEquals(12, state.materials.values.single())
        assertEquals(2, state.bond!!.slots.size)
        assertEquals(98_765L, state.bond!!.slots[1].total)
        assertTrue(state.bond!!.slots[1].maxObserved)
        assertEquals(1, state.bond!!.unmappedLevelUpRuns)
        assertEquals(2, state.bond!!.levelUpMissedRuns)
    }

    @Test
    fun absentOptionalSectionsRemainAbsent() {
        val entry = Json.decodeFromString<BattleLogEntry>(Json.encodeToString(battleLogFixture()))
        assertNull(entry.materials)
        assertNull(entry.bond)
        assertTrue(entry.exitState().materials.isEmpty())
        assertNull(entry.exitState().bond)
    }

    @Test
    fun removedMaterialAndUnknownReasonKeepRecordReadable() {
        val entry = battleLogFixture().copy(materials = mapOf("removed_material" to 7),
            reasonKey = "old_reason", reasonText = "Historical explanation")
        val restored = Json.decodeFromString<BattleLogEntry>(Json.encodeToString(entry))
        assertEquals("Historical explanation", restored.reasonText)
        assertTrue(restored.exitState().materials.isEmpty())
    }

    @Test
    fun addingKeepsNewest200AndPersistsThem() = runBlocking {
        val seed = (1..200).map { battleLogFixture("$it").copy(endedAt = it.toLong()) }
        File(directory, "battle-log.json").writeText(Json.encodeToString(seed))
        val repository = BattleLogRepository(directory)
        repository.add(battleLogFixture("newest").copy(endedAt = 201))
        val entries = repository.entries.first()
        assertEquals(200, entries.size)
        assertEquals("newest", entries.first().id)
        assertEquals("2", entries.last().id)
        assertEquals(entries, BattleLogRepository(directory).entries.first())
        repository.add(battleLogFixture("old").copy(endedAt = 0))
        assertEquals(entries, repository.entries.first())
    }

    @Test
    fun readingOversizedFileAlsoCapsAndSorts() = runBlocking {
        File(directory, "battle-log.json").writeText(Json.encodeToString(
            (1..205).map { battleLogFixture("$it").copy(endedAt = it.toLong()) }
        ))
        val entries = BattleLogRepository(directory).entries.first()
        assertEquals(200, entries.size)
        assertEquals("205", entries.first().id)
        assertEquals("6", entries.last().id)
    }

    @Test
    fun deleteAndClearSurviveReload() = runBlocking {
        val repository = BattleLogRepository(directory)
        repository.add(battleLogFixture("one"))
        repository.add(battleLogFixture("two").copy(endedAt = 1))
        repository.delete("missing")
        repository.delete("one")
        assertEquals(listOf("two"), BattleLogRepository(directory).entries.first().map { it.id })
        repository.clear()
        assertTrue(repository.entries.first().isEmpty())
        assertTrue(BattleLogRepository(directory).entries.first().isEmpty())
    }

    @Test
    fun concurrentMutationsDoNotLoseEntries() = runBlocking {
        val repository = BattleLogRepository(directory)
        (1..10).map { async { repository.add(battleLogFixture("$it")) } }.awaitAll()
        assertEquals((1..10).map { "$it" }.toSet(), repository.entries.first().map { it.id }.toSet())
    }

    @Test
    fun corruptFileIsEmptyAndRenamedBeforeNextSave() = runBlocking {
        val original = "{ broken JSON"
        File(directory, "battle-log.json").writeText(original)
        val repository = BattleLogRepository(directory)
        assertTrue(repository.entries.first().isEmpty())
        assertFalse(File(directory, "battle-log.json").exists())
        assertEquals(original, File(directory, "battle-log.corrupt.json").readText())
        repository.add(battleLogFixture())
        assertEquals(1, BattleLogRepository(directory).entries.first().size)
        assertEquals(original, File(directory, "battle-log.corrupt.json").readText())
    }

    @Test
    fun subsequentCorruptionDoesNotOverwritePreviousBackup() = runBlocking {
        File(directory, "battle-log.corrupt.json").writeText("first corrupt file")
        File(directory, "battle-log.json").writeText("second corrupt file")
        assertTrue(BattleLogRepository(directory).entries.first().isEmpty())
        assertEquals("first corrupt file", File(directory, "battle-log.corrupt.json").readText())
        assertEquals(2, directory.listFiles()!!.count { it.name.startsWith("battle-log.corrupt") })
    }

    @Test
    fun failedWriteDoesNotThrowOrPublishUnsavedState() = runBlocking {
        val repository = BattleLogRepository(directory)
        repository.add(battleLogFixture())
        val persisted = File(directory, "battle-log/index.json").readText()
        File(directory, "battle-log/index.json.tmp").mkdir()
        repository.clear()
        assertEquals(1, repository.entries.first().size)
        assertEquals(persisted, File(directory, "battle-log/index.json").readText())
    }

    @Test
    fun failedReadDoesNotOverwriteUnreadFile() = runBlocking {
        val file = File(directory, "battle-log.json")
        file.mkdir()
        val sentinel = File(file, "sentinel").apply { writeText("keep") }
        val repository = BattleLogRepository(directory)
        assertTrue(repository.entries.first().isEmpty())
        repository.add(battleLogFixture())
        assertEquals("keep", sentinel.readText())
        assertFalse(File(directory, "battle-log.corrupt.json").exists())
    }

    @Test
    fun onlyFinishedBattleAndWeeklyRouteWithCompletedRunsAreRecorded() {
        for (source in listOf(BattleLogSource.BattleExit, BattleLogSource.WeeklyRouteFinished)) {
            assertFalse(shouldRecordBattleLog(source, AutoBattle.ExitReason.Abort, 0))
            assertTrue(shouldRecordBattleLog(source, AutoBattle.ExitReason.Abort, 1))
            assertTrue(shouldRecordBattleLog(source, AutoBattle.ExitReason.LimitRuns(8), 8))
            assertFalse(shouldRecordBattleLog(source, AutoBattle.ExitReason.Paused, 8))
        }
    }

    @Test
    fun pausedStatusNeverRecordsEvenWithRunsAndNonPausedReason() {
        assertFalse(shouldRecordBattleLog(BattleLogSource.PausedStatus, AutoBattle.ExitReason.Paused, 8))
        assertFalse(shouldRecordBattleLog(BattleLogSource.PausedStatus, AutoBattle.ExitReason.LimitRuns(8), 8))
    }
}
