package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.battlelog.BattleLogRepository
import io.github.fate_grand_automata.battlelog.battleLogFixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class OutsideLogStoreTest {
    @TempDir lateinit var directory: File

    @Test fun oldOutsideLogWithoutFriendPointsLoadsUnchanged() {
        val entry = outsideFixture()
        val legacy = Json.encodeToString(listOf(entry))
        assertFalse(legacy.contains("friendPoint"))
        File(directory, "outside-log.json").writeText(legacy)
        val store = OutsideLogStore(directory).apply { load() }
        assertEquals(listOf(entry), store.entries)
        assertEquals(0, store.entries.single().period.total.friendPoint)
        assertTrue(store.entries.single().period.sources.all { it.delta.friendPoint == 0L })
    }

    @Test fun oldOutsideLogWithoutScriptFlagKeepsEveryGroup() {
        val entry = outsideFixture()
        val legacy = Json.encodeToString(listOf(entry))
        assertFalse(legacy.contains("script"))
        File(directory, "outside-log.json").writeText(legacy)
        val loaded = OutsideLogStore(directory).apply { load() }.entries.single()
        assertEquals(entry, loaded)
        assertTrue(loaded.period.sources.none { it.source.script })
    }

    @Test fun loginGroupRoundTripsThroughTheStoreFile() {
        val period = io.github.fate_grand_automata.scripts.capture.OutsidePeriod("fictionalA", 100)
            .add(io.github.fate_grand_automata.scripts.capture.ChangeSource(io.github.fate_grand_automata.scripts.capture.ChangeSource.Group.Login),
                io.github.fate_grand_automata.scripts.capture.AccountDelta(friendPoint = 370), 200)
        val entry = OutsideLogEntry("login-entry", "fictionalA", "Sample", 100_000, 200_000, period)
        OutsideLogStore(directory).add(entry)
        assertTrue(File(directory, "outside-log.json").readText().contains("\"Login\""))
        assertEquals(listOf(entry), OutsideLogStore(directory).apply { load() }.entries)
    }

    @Test fun keepsNewest200AndReplayReplacesSameId() {
        File(directory, "outside-log.json").writeText(Json.encodeToString((1..200).map { outsideFixture("$it", it * 1000L) }))
        val store = OutsideLogStore(directory)
        val newest = outsideFixture("201", 201_000)
        store.add(newest)
        store.add(newest)
        assertEquals(200, store.entries.size)
        assertEquals("201", store.entries.first().id)
        assertEquals("2", store.entries.last().id)
        val reload = OutsideLogStore(directory).apply { load() }
        assertEquals(store.entries, reload.entries)
    }

    @Test fun deleteAndClearPersistAndHistoricalNamesStayFrozen() {
        val store = OutsideLogStore(directory)
        store.add(outsideFixture("a"))
        store.add(outsideFixture("b"))
        store.delete("a")
        val reload = OutsideLogStore(directory).apply { load() }
        assertEquals(listOf("b"), reload.entries.map { it.id })
        assertEquals("샘플 계정", reload.entries.single().accountName)
        store.clear()
        assertTrue(OutsideLogStore(directory).apply { load() }.entries.isEmpty())
    }

    @Test fun oldBattleJsonLoadsUnchangedAndMixedKindsSortByEndTime() = runBlocking {
        val before = battleLogFixture("before").copy(endedAt = 1000)
        val after = battleLogFixture("after").copy(endedAt = 3000)
        val legacy = Json.encodeToString(listOf(before, after))
        assertFalse(legacy.contains("gains"))
        File(directory, "battle-log.json").writeText(legacy)
        val store = OutsideLogStore(directory)
        store.add(outsideFixture("middle", 2000))
        val battles = BattleLogRepository(directory).entries.first()
        assertEquals(listOf("after", "outside:middle", "before"), mixedCaptureLogs(battles, store.entries).map { it.id })
        // The old file is migrated once into battle-log/ (issue #280) and then removed.
        assertFalse(File(directory, "battle-log.json").exists())
        assertTrue(File(directory, "battle-log/index.json").exists())
        store.clear()
        assertEquals(2, BattleLogRepository(directory).entries.first().size)
    }

    @Test fun failedWriteKeepsOriginalAndDoesNotPublishUnsavedState() {
        val store = OutsideLogStore(directory)
        store.add(outsideFixture())
        val original = File(directory, "outside-log.json").readText()
        File(directory, "outside-log.json.tmp").mkdir()
        assertFails { store.clear() }
        assertEquals(1, store.entries.size)
        assertEquals(original, File(directory, "outside-log.json").readText())
    }

    @Test fun readFailureDoesNotOverwriteUnreadHistory() {
        val target = File(directory, "outside-log.json").apply { mkdir() }
        val sentinel = File(target, "sentinel").apply { writeText("keep") }
        val store = OutsideLogStore(directory)
        assertFails { store.add(outsideFixture()) }
        assertEquals("keep", sentinel.readText())
    }

    @Test fun corruptJsonIsPreservedBeforeNewHistoryStarts() {
        File(directory, "outside-log.json").writeText("broken handwritten JSON")
        val store = OutsideLogStore(directory)
        store.add(outsideFixture())
        assertEquals(1, store.entries.size)
        assertEquals("broken handwritten JSON", directory.listFiles()!!.single { it.name.startsWith("outside-log.corrupt-") }.readText())
    }

    private fun futureGroupLog(): Pair<String, OutsideLogEntry> {
        val entry = outsideFixture()
        val written = Json.encodeToString(listOf(entry, outsideFixture("second", 1_793_000_000_000)))
        assertTrue(written.contains("\"group\":\"Summon\""))
        return written.replace("\"group\":\"Summon\"", "\"group\":\"FutureGroup\"") to entry
    }

    private fun corruptFiles(dir: File) = dir.listFiles { f -> f.name.startsWith("outside-log.corrupt-") }!!.toList()

    @Test fun unknownSourceGroupLoadsTheWholeLogAsOther() {
        val (text, original) = futureGroupLog()
        File(directory, "outside-log.json").writeText(text)
        val store = OutsideLogStore(directory).apply { load() }
        assertEquals(2, store.entries.size)
        assertTrue(corruptFiles(directory).isEmpty())
        val loaded = store.entries.first { it.id == original.id }
        assertEquals(original.period.total, loaded.period.total)
        val summon = original.period.sources.first { it.source.group == io.github.fate_grand_automata.scripts.capture.ChangeSource.Group.Summon }
        val read = loaded.period.sources.first { it.delta == summon.delta }
        assertEquals(io.github.fate_grand_automata.scripts.capture.ChangeSource.Group.Other, read.source.group)
        assertEquals(summon.source.path, read.source.path)
        assertEquals(summon.source.script, read.source.script)
        assertEquals(original.period.sources.map { it.delta }, loaded.period.sources.map { it.delta })
    }

    @Test fun cutOffOrWrongTypeLogIsStillMovedAside() {
        val (text, _) = futureGroupLog()
        listOf(text.dropLast(40), text.replace("\"group\":\"FutureGroup\"", "\"group\":5"),
            text.replace("\"group\":\"FutureGroup\"", "\"group\":null")).forEachIndexed { i, bad ->
            val dir = File(directory, "m$i").apply { mkdirs() }
            File(dir, "outside-log.json").writeText(bad)
            val store = OutsideLogStore(dir).apply { load() }
            assertTrue(store.entries.isEmpty())
            assertEquals(1, corruptFiles(dir).size)
        }
    }
}
