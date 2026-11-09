package io.github.fate_grand_automata.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.battlelog.BattleLogRepository
import io.github.fate_grand_automata.battlelog.battleLogFixture
import io.github.fate_grand_automata.prefs.core.LogRetentionPrefsCore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class LogRetentionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun preferenceDefaultsTo200PersistsAndRejectsUnsupportedImportedValues() {
        val prefs = LogRetentionPrefsCore.fromContext(context)
        assertEquals(200, prefs.limit.get())
        prefs.limit.set(500)
        assertEquals(500, LogRetentionPrefsCore.fromContext(context).limit.get())
        assertEquals(listOf(50, 100, 200), LogRetentionPrefsCore.choices)
        assertEquals(200, LogRetentionPrefsCore.normalize(prefs.limit.get()))
        LogRetentionPrefsCore.choices.forEach { assertEquals(it, LogRetentionPrefsCore.normalize(it)) }
        assertEquals(200, LogRetentionPrefsCore.normalize(-1))
    }

    @Test fun confirmedLimitTrimsOldestOfBothKindsAndSubsequentAddsAndReloadsRespectIt() = runBlocking {
        val prefs = LogRetentionPrefsCore.fromContext(context)
        val directory = context.filesDir
        File(directory, "battle-log.json").writeText(Json.encodeToString((1..80).map { battleLogFixture("b$it").copy(endedAt = it.toLong()) }))
        File(directory, "outside-log.json").writeText(Json.encodeToString((1..80).map { outsideFixture("o$it", it.toLong()) }))
        val battle = BattleLogRepository(context)
        val outside = OutsideLogStore(directory, { prefs.limit.get() }).apply { load() }
        assertEquals(80, battle.entries.first().size)
        assertEquals(80, outside.entries.size)
        // Opening/changing a dialog draft never touches the pref or either store (covered by UI tests).
        assertEquals(200, prefs.limit.get())
        prefs.limit.setAndCommit(50) // Confirmed action.
        battle.trim()
        outside.trim()
        assertEquals((80 downTo 31).map { "b$it" }, battle.entries.first().map { it.id })
        assertEquals((80 downTo 31).map { "o$it" }, outside.entries.map { it.id })
        battle.add(battleLogFixture("b81").copy(endedAt = 81))
        outside.add(outsideFixture("o81", 81))
        assertEquals("b32", BattleLogRepository(context).entries.first().last().id)
        assertEquals("o32", OutsideLogStore(directory, { prefs.limit.get() }).apply { load() }.entries.last().id)
        assertEquals(50, battle.entries.first().size)
        assertEquals(50, outside.entries.size)
    }

    @Test fun saved500KeepsNewest200ForBothKindsOnLoadTrimAddAndReload() = runBlocking {
        val directory = context.filesDir
        val prefs = LogRetentionPrefsCore.fromContext(context)
        prefs.limit.set(500)
        val battles = (1..250).map { battleLogFixture("b$it").copy(endedAt = it.toLong()) }
        File(directory, "battle-log.json").writeText(Json.encodeToString(battles))
        File(directory, "outside-log.json").writeText(Json.encodeToString((1..250).map { outsideFixture("o$it", it.toLong()) }))
        val battle = BattleLogRepository(context)
        val outside = OutsideLogStore(directory, { LogRetentionPrefsCore.normalize(prefs.limit.get()) }).apply { load() }
        assertEquals((250 downTo 51).map { "b$it" }, battle.entries.first().map { it.id })
        assertEquals((250 downTo 51).map { "o$it" }, outside.entries.map { it.id })
        battle.trim()
        outside.trim()
        battle.add(battleLogFixture("b251").copy(endedAt = 251))
        outside.add(outsideFixture("o251", 251))
        assertEquals((251 downTo 52).map { "b$it" }, BattleLogRepository(context).entries.first().map { it.id })
        assertEquals((251 downTo 52).map { "o$it" }, OutsideLogStore(directory,
            { LogRetentionPrefsCore.normalize(prefs.limit.get()) }).apply { load() }.entries.map { it.id })
    }
}
