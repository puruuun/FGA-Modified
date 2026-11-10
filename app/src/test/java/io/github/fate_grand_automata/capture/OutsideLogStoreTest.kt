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
}
