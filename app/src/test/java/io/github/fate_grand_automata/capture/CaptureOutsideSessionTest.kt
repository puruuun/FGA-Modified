package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.AccountSummary
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class CaptureOutsideSessionTest {
    @TempDir lateinit var directory: File
    private var time = 10L
    private lateinit var accounts: CaptureAccounts
    private lateinit var logs: OutsideLogStore
    private lateinit var session: CaptureOutsideSession

    @BeforeTest fun setup() {
        accounts = CaptureAccounts().apply {
            load(directory)
            apply("login/top", outsideLogin(), 1)
            save()
        }
        logs = OutsideLogStore(File(directory, "written"))
        session = CaptureOutsideSession(accounts, logs::add, { time }).apply { load(directory); setRunning(true) }
    }

    private fun feed(path: String = "item/recover", id: String = "fictionalA", num: Long = 8) {
        response(path, outsideFruit(id, num))
    }
    private fun response(path: String, body: JsonObject) {
        session.collect(path, assertNotNull(accounts.apply(path, body, time)))
    }
    private val pending get() = File(directory, "accounts/fictionalA/outside-period.json")

    @Test fun firstNonemptyDeltaOpensAndMultipleResponsesBecomeOneEntry() {
        feed()
        val live = session.openEntries.value.single()
        assertEquals(-2, live.period.total.items[100])
        assertEquals(10_000, live.endedAt)
        assertTrue(pending.isFile)
        assertTrue(logs.entries.isEmpty())
        time = 20
        feed(num = 7)
        val updated = session.openEntries.value.single()
        assertEquals(live.id, updated.id)
        assertEquals(-3, updated.period.total.items[100])
        assertEquals(-3, updated.period.sources.single().delta.items[100])
        assertEquals(20_000, updated.endedAt)
        time = 30
        session.setRunning(false)
        val entry = logs.entries.single()
        assertEquals(live.id, entry.id)
        assertTrue(session.openEntries.value.isEmpty())
        assertEquals(-3, entry.period.total.items[100])
        assertEquals(3, entry.period.fruitUsed[100])
        assertEquals(10_000, entry.startedAt)
        assertEquals(30_000, entry.endedAt)
        assertFalse(pending.exists())
        val reloaded = CaptureAccounts().apply { load(directory) }
        assertEquals(7, AccountSummary.from(reloaded.state!!, time).goldenApples)
    }

    @Test fun scriptStartClosesPeriodAndRunResponsesCannotFeedOutsideLog() {
        feed()
        time = 15
        session.scriptStart()
        feed(num = 4)
        session.setRunning(false)
        assertEquals(-2, logs.entries.single().period.total.items[100])
        assertEquals(15_000, logs.entries.single().endedAt)
        assertFalse(pending.exists())
    }

    @Test fun scriptEndStartsANewPeriodOnNextDelta() {
        session.scriptStart()
        feed(num = 6)
        session.scriptEnd()
        time = 20
        feed(num = 5)
        session.setRunning(false)
        assertEquals(-1, logs.entries.single().period.total.items[100])
        assertEquals(20_000, logs.entries.single().startedAt)
    }

    @Test fun loginOnlyResetsBaselineAndNeverCountsOfflineChanges() {
        response("login/top", outsideLogin(fruit = 50))
        assertFalse(pending.exists())
        feed(num = 49)
        response("login/top", outsideLogin(fruit = 100))
        feed(num = 99)
        session.setRunning(false)
        assertEquals(-2, logs.entries.single().period.total.items[100])
        assertTrue(logs.entries.single().period.sources.none { it.source.path == "login/top" })
    }

    @Test fun collectionOffDoesNotOpenPeriodAndRepeatedStopsWriteNothingExtra() {
        session.setRunning(false)
        feed(num = 9)
        assertFalse(pending.exists())
        session.setRunning(true)
        feed(num = 8)
        session.setRunning(false)
        session.setRunning(false)
        assertEquals(1, logs.entries.size)
    }

    @Test fun serviceStopClosesEvenWhenVpnKeepsRunningAndIsIdempotent() {
        feed()
        session.serviceStop()
        session.serviceStop()
        assertEquals(1, logs.entries.size)
        feed(num = 7)
        session.setRunning(false)
        assertEquals(2, logs.entries.size)
    }

    @Test fun switchingAccountClosesOldPeriodBeforeCountingNewOne() {
        feed()
        time = 20
        response("login/top", outsideLogin("fictionalB"))
        assertEquals("fictionalA", logs.entries.single().accountId)
        feed(id = "fictionalB", num = 9)
        time = 30
        feed(id = "fictionalA", num = 7)
        time = 40
        session.setRunning(false)
        assertEquals(listOf("fictionalA", "fictionalB", "fictionalA"), logs.entries.map { it.accountId })
        assertEquals(listOf(-1L, -1L, -2L), logs.entries.map { it.period.total.items[100] })
    }

    @Test fun unchangedResponseRoutedToOtherAccountAlsoClosesOldPeriod() {
        response("login/top", outsideLogin("fictionalB"))
        response("login/top", outsideLogin())
        feed()
        feed(id = "fictionalB", num = 10)
        assertEquals(1, logs.entries.size)
        assertFalse(pending.exists())
    }

    @Test fun emptyPeriodWritesNothingAtEveryClosingPoint() {
        feed(num = 10)
        assertTrue(session.openEntries.value.isEmpty())
        session.scriptStart()
        session.scriptEnd()
        session.serviceStop()
        session.setRunning(false)
        assertTrue(logs.entries.isEmpty())
        assertFalse(pending.exists())
    }

    @Test fun reloadKeepsOpenPeriodAndResourceBaselineEvenBeforeDebouncedAccountSave() {
        feed(num = 8) // Deliberately do not save CaptureAccounts: simulate a process kill.
        accounts = CaptureAccounts().apply { load(directory) }
        assertEquals(10, AccountSummary.from(accounts.state!!, time).goldenApples)
        session = CaptureOutsideSession(accounts, logs::add, { time }).apply { load(directory); setRunning(true) }
        assertEquals(8, AccountSummary.from(accounts.state!!, time).goldenApples)
        assertTrue(logs.entries.isEmpty())
        feed(num = 7)
        session.setRunning(false)
        assertEquals(-3, logs.entries.single().period.total.items[100])
    }

    @Test fun loginCheckpointSurvivesReloadWithoutReintroducingItsDelta() {
        feed()
        response("login/top", outsideLogin(fruit = 50))
        accounts = CaptureAccounts().apply { load(directory) }
        session = CaptureOutsideSession(accounts, logs::add, { time }).apply { load(directory); setRunning(true) }
        feed(num = 49)
        session.setRunning(false)
        assertEquals(-3, logs.entries.single().period.total.items[100])
    }

    @Test fun newlyDiscoveredAccountKeepsPeriodBeforeFirstScheduledSave() {
        response("login/top", outsideLogin("fictionalB"))
        feed(id = "fictionalB", num = 9)
        accounts = CaptureAccounts().apply { load(directory) }
        session = CaptureOutsideSession(accounts, logs::add, { time }).apply { load(directory); setRunning(true) }
        feed(id = "fictionalB", num = 8)
        session.setRunning(false)
        assertEquals("fictionalB", logs.entries.single().accountId)
        assertEquals(-2, logs.entries.single().period.total.items[100])
    }

    @Test fun accountDeletionAndClearDropOpenPeriodsButKeepWrittenHistory() {
        feed()
        session.serviceStop()
        feed(num = 7)
        session.delete("fictionalA")
        accounts.delete("fictionalA")
        session.setRunning(false)
        assertEquals(1, logs.entries.size)
        assertFalse(pending.exists())
        response("login/top", outsideLogin())
        session.setRunning(true)
        feed(num = 6)
        session.clear()
        accounts.clear()
        session.setRunning(false)
        assertEquals(1, logs.entries.size)
    }

    @Test fun historicalNameIsFrozenAtWriteTime() {
        feed()
        accounts.rename("fictionalA", "Before close")
        session.serviceStop()
        accounts.rename("fictionalA", "After close")
        assertEquals("Before close", logs.entries.single().accountName)
    }

    @Test fun failedCloseKeepsPendingAndRetryUsesSameEntryId() {
        feed()
        val saved = mutableListOf<OutsideLogEntry>()
        var fail = true
        val retry = CaptureOutsideSession(accounts, {
            saved += it
            if (fail) error("Synthetic write failure")
            logs.add(it)
        }, { time }).apply { load(directory); setRunning(true) }
        assertFails { retry.setRunning(false) }
        assertTrue(pending.exists())
        fail = false
        retry.setRunning(false)
        assertEquals(1, saved.map { it.id }.distinct().size)
        assertEquals(1, logs.entries.size)
        assertFalse(pending.exists())
    }
}
