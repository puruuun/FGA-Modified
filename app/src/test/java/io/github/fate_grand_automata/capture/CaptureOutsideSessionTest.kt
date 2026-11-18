package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.AccountSummary
import io.github.fate_grand_automata.scripts.capture.AccountDelta
import io.github.fate_grand_automata.scripts.capture.ChangeSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
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
            apply("https://yfgo-game.netmarble.com/login/top", outsideLogin(), 1)
            save()
        }
        logs = OutsideLogStore(File(directory, "written"))
        session = CaptureOutsideSession(accounts, logs::add, { time }).apply { load(directory); setRunning(true) }
    }

    private fun feed(path: String = "item/recover", id: String = "fictionalA", num: Long = 8) {
        response(path, outsideFruit(id, num))
    }
    private fun response(path: String, body: JsonObject) {
        val url = if (path.startsWith("https://")) path else "https://yfgo-game.netmarble.com/$path"
        session.collect(url, assertNotNull(accounts.apply(url, body, time)))
    }
    private val pending get() = File(directory, "accounts/fictionalA/outside-period.json")

    @Test fun oldPendingWithoutFriendPointsResumesWithZeroAndCountsNextChange() {
        feed()
        val oldPeriod = Json.parseToJsonElement(pending.readText()).jsonObject.getValue("period").toString()
        assertFalse(oldPeriod.contains("friendPoint"))
        accounts = CaptureAccounts().apply { load(directory) }
        session = CaptureOutsideSession(accounts, logs::add, { time }).apply { load(directory); setRunning(true) }
        assertEquals(AccountDelta(items = mapOf(100L to -2L)), session.openEntries.value.single().period.total)
        response("gacha/draw", outsideFriendPointSummon(includeCards = false))
        session.setRunning(false)
        assertEquals(AccountDelta(items = mapOf(100L to -2L), friendPoint = -200), logs.entries.single().period.total)
    }

    @Test fun friendPointOnlySummonOpensAndExtendsPeriodAndResumesWithoutDoubleCounting() {
        val target = "https://yfgo-game.netmarble.com/gacha/draw"
        response(target, outsideFriendPointSummon(includeCards = false))
        val opened = session.openEntries.value.single()
        assertEquals(AccountDelta(friendPoint = -200), opened.period.total)
        assertEquals(ChangeSource(ChangeSource.Group.Summon), opened.period.sources.single().source)
        accounts = CaptureAccounts().apply { load(directory) }
        session = CaptureOutsideSession(accounts, logs::add, { time }).apply { load(directory); setRunning(true) }
        response(target, outsideFriendPointSummon(includeCards = false))
        time = 20
        response(target, outsideFriendPointSummon(friendPoint = 9600, includeCards = false))
        val extended = session.openEntries.value.single()
        assertEquals(opened.id, extended.id)
        assertEquals(20_000, extended.endedAt)
        session.setRunning(false)
        assertEquals(AccountDelta(friendPoint = -400), logs.entries.single().period.total)
        assertEquals(AccountDelta(friendPoint = -400), logs.entries.single().period.sources.single().delta)
    }

    @Test fun krFriendPointSummonCountsServantAndCeUnderSummonSource() {
        response("login/top", outsideLogin(svts = """[{"userId":"fictionalA","id":1001,"svtId":100100,"lv":1}]"""))
        val target = "https://yfgo-game.netmarble.com/gacha/draw"
        response(target, outsideFriendPointSummon())
        response(target, outsideFriendPointSummon())
        session.setRunning(false)
        val period = logs.entries.single().period
        val expected = AccountDelta(svts = mapOf(100100L to 1L, 9700010L to 1L), friendPoint = -200)
        assertEquals(expected, period.total)
        assertEquals(ChangeSource(ChangeSource.Group.Summon), period.sources.single().source)
        assertEquals(expected, period.sources.single().delta)
    }

    @Test fun friendPointRewardKeepsItsOwnResponseSource() {
        val reward = JsonObject(outsideFriendPointSummon(friendPoint = 10200, includeCards = false).filterKeys { it != "response" })
        response("home/top", reward)
        val period = session.openEntries.value.single().period
        assertEquals(AccountDelta(friendPoint = 200), period.total)
        assertEquals(ChangeSource.of("home/top"), period.sources.single().source)
        assertEquals(period.total, period.sources.single().delta)
    }

    private fun cardResponse(svts: String, storage: String = "[]", codes: String = "[]") = Json.parseToJsonElement("""
        {"cache":{"replaced":{"userSvt":$svts,"userSvtStorage":$storage,"userCommandCode":$codes}}}
    """).jsonObject

    @Test fun cardOnlyEnhancementAndShopChangesPersistAndResumeWithoutDoubleCounting() {
        response("present/receive", cardResponse("""[{"id":1,"svtId":10},{"id":2,"svtId":10},{"id":3,"svtId":20}]""",
            codes = """[{"id":1,"commandCodeId":30}]"""))
        accounts.save()
        session.delete("fictionalA")
        time++
        response("svtCombine/execute", cardResponse("""[{"id":3,"svtId":20}]""", codes = """[{"id":1,"commandCodeId":30}]"""))
        time++
        response("shop/sell", cardResponse("[]"))
        accounts = CaptureAccounts().apply { load(directory) }
        session = CaptureOutsideSession(accounts, logs::add, { time }).apply { load(directory); setRunning(true) }
        response("shop/sell", cardResponse("[]"))
        session.setRunning(false)
        val period = logs.entries.single().period
        assertEquals(AccountDelta(svts = mapOf(10L to -2L, 20L to -1L), commandCodes = mapOf(30L to -1L)), period.total)
        assertEquals(mapOf(10L to -2L), period.sources.single { it.source.group == ChangeSource.Group.Enhancement }.delta.svts)
        assertEquals(mapOf(20L to -1L), period.sources.single { it.source.group == ChangeSource.Group.Shop }.delta.svts)
    }

    @Test fun storageMoveCheckpointSurvivesProcessRestart() {
        response("present/receive", cardResponse("""[{"id":1,"svtId":10}]"""))
        time++
        response("card/move", cardResponse("[]", storage = """[{"id":1,"svtId":10}]"""))
        accounts = CaptureAccounts().apply { load(directory) }
        session = CaptureOutsideSession(accounts, logs::add, { time }).apply { load(directory); setRunning(true) }
        response("shop/sell", cardResponse("[]"))
        session.setRunning(false)
        val period = logs.entries.single().period
        assertTrue(period.total.isEmpty)
        assertEquals(mapOf(10L to -1L), period.sources.single { it.source.group == ChangeSource.Group.Shop }.delta.svts)
    }

    @Test fun oldPendingCheckpointKeepsCardTablesFromSavedAccount() {
        response("present/receive", cardResponse("""[{"id":1,"svtId":10}]"""))
        accounts.save()
        feed()
        val saved = Json.parseToJsonElement(pending.readText()).jsonObject
        val old = saved.toMutableMap()
        old["resourceBaseline"] = JsonObject(saved.getValue("resourceBaseline").jsonObject.filterKeys {
            it in setOf("userGame", "tblUserGame", "userItem")
        })
        pending.writeText(JsonObject(old).toString())
        accounts = CaptureAccounts().apply { load(directory) }
        session = CaptureOutsideSession(accounts, logs::add, { time }).apply { load(directory); setRunning(true) }
        response("shop/sell", cardResponse("[]"))
        assertEquals(mapOf(10L to -1L), session.openEntries.value.single().period.sources.single {
            it.source.group == ChangeSource.Group.Shop
        }.delta.svts)
    }

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

    @Test fun nonBattleScriptMarksOnlyItsChangesWithoutClosingOrPausingPeriod() {
        response("gacha/draw", outsideFriendPointSummon(9800, includeCards = false))
        val id = session.openEntries.value.single().id
        session.scriptStart(battle = false)
        response("gacha/draw", outsideFriendPointSummon(9600, includeCards = false))
        session.scriptEnd()
        response("gacha/draw", outsideFriendPointSummon(9400, includeCards = false))
        val entry = session.openEntries.value.single()
        assertEquals(id, entry.id)
        assertTrue(logs.entries.isEmpty())
        assertEquals(-600L, entry.period.total.friendPoint)
        assertEquals(-200L, entry.period.sources.single { it.source.script }.delta.friendPoint)
        assertEquals(-400L, entry.period.sources.single { !it.source.script }.delta.friendPoint)
        session.setRunning(false)
        assertEquals(entry.period, logs.entries.single().period)
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

    private val loginSource = ChangeSource(ChangeSource.Group.Login)

    @Test fun loginGrantOpensPeriodUnderLoginSourceAndStaysInTheTotals() {
        response("login/top", outsideLogin(grant = 370))
        val entry = session.openEntries.value.single()
        assertEquals(AccountDelta(friendPoint = 370), entry.period.total)
        assertEquals(loginSource, entry.period.sources.single().source)
        session.setRunning(false)
        assertEquals(AccountDelta(friendPoint = 370), logs.entries.single().period.sources.single().delta)
    }

    @Test fun loginWithoutGrantRecordsNothingButStillCheckpoints() {
        feed()
        response("login/top", outsideLogin(fruit = 50, grant = 0))
        assertEquals(listOf(100L to -2L), session.openEntries.value.single().period.total.items.toList())
        feed(num = 49)
        session.setRunning(false)
        val period = logs.entries.single().period
        assertEquals(-3, period.total.items[100])
        assertTrue(period.sources.none { it.source.group == ChangeSource.Group.Login })
    }

    @Test fun loginGrantIsIgnoredWhenNotRunning() {
        session.setRunning(false)
        response("login/top", outsideLogin(grant = 370))
        assertTrue(session.openEntries.value.isEmpty())
        assertFalse(pending.exists())
    }

    @Test fun loginGrantDuringBattleScriptOnTheSameServerIsDropped() {
        session.scriptStart(battle = true, server = "KR")
        response("login/top", outsideLogin(grant = 370))
        session.scriptEnd()
        session.setRunning(false)
        assertTrue(logs.entries.isEmpty())
    }

    @Test fun failedLoginDoesNotRecordTheGrant() {
        response("login/top", outsideLogin(grant = 370, resCode = "01"))
        assertTrue(session.openEntries.value.isEmpty())
    }

    @Test fun loginThatDoesNotReplaceTheSnapshotDoesNotRepeatThePreviousGrant() {
        response("login/top", outsideLogin(grant = 370))
        // Without its own userGame the login leaves the stored snapshot, and its old login response, in place.
        response("login/top", Json.parseToJsonElement("""{"response":[{"nid":"login","resCode":"00","success":{"addFriendPoint":500}}],"cache":{"replaced":{}}}""").jsonObject)
        assertEquals(370, session.openEntries.value.single().period.total.friendPoint)
    }

    @Test fun failedLoginWithCacheDoesNotRepeatThePreviousGrant() {
        response("login/top", outsideLogin(grant = 370))
        session.setRunning(false)
        session.setRunning(true)
        val failedWithTime = """{"response":[{"nid":"login","resCode":"01"}],"cache":{"serverTime":12345}}"""
        response("login/top", Json.parseToJsonElement(failedWithTime).jsonObject)
        val failedWithTable = """{"response":[{"nid":"login","resCode":"01"}],"cache":{"updated":{"userItem":[{"userId":"fictionalA","itemId":100,"num":10}]}}}"""
        response("login/top", Json.parseToJsonElement(failedWithTable).jsonObject)
        assertTrue(session.openEntries.value.isEmpty())
        session.setRunning(false)
        assertEquals(1, logs.entries.size)
    }

    @Test fun twoSuccessfulLoginsEachRecordTheirOwnGrant() {
        response("login/top", outsideLogin(grant = 370))
        response("login/top", outsideLogin(grant = 50))
        assertEquals(420, session.openEntries.value.single().period.total.friendPoint)
    }

    @Test fun zeroGrantLoginWithNoOpenPeriodCreatesNothing() {
        response("login/top", outsideLogin(grant = 0))
        assertTrue(session.openEntries.value.isEmpty())
        assertFalse(pending.exists())
    }

    @Test fun loginGrantThenOrdinaryFriendPointChangeIsNotCountedTwice() {
        response("login/top", outsideLogin(grant = 370))
        response("gacha/draw", outsideFriendPointSummon(friendPoint = 9800, includeCards = false))
        session.setRunning(false)
        val period = logs.entries.single().period
        assertEquals(170, period.total.friendPoint)
        assertEquals(setOf(370L, -200L), period.sources.map { it.delta.friendPoint }.toSet())
    }

    @Test fun loginGrantsOfTwoServersKeepSeparatePeriods() {
        response("login/top", outsideLogin(grant = 370))
        response("https://game.fate-go.jp/login/top", outsideLogin("fictionalJp", grant = 50))
        val byAccount = session.openEntries.value.associateBy { it.accountId }
        assertEquals(370, byAccount.getValue("fictionalA").period.total.friendPoint)
        assertEquals(50, byAccount.getValue("fictionalJp").period.total.friendPoint)
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

    @Test fun otherServerResponseDoesNotCloseOpenPeriodAndScriptAppliesOnlyToItsServer() {
        val jpLogin = "https://game.fate-go.jp/login/top"
        val jpItem = "https://game.fate-go.jp/item/recover"
        val krItem = "https://yfgo-game.netmarble.com/item/recover"
        response(jpLogin, outsideLogin("fictionalJp"))
        // Open both a KR period and a JP period simultaneously.
        response(krItem, outsideFruit("fictionalA", 8))
        response(jpItem, outsideFruit("fictionalJp", 7))
        assertEquals(setOf("fictionalA", "fictionalJp"), session.openEntries.value.map { it.accountId }.toSet())
        assertTrue(logs.entries.isEmpty())

        // Non-battle KR script marks only KR changes as script-made, leaving JP changes unmarked and open.
        session.scriptStart(battle = false, server = "KR")
        response(krItem, outsideFruit("fictionalA", 6))
        response(jpItem, outsideFruit("fictionalJp", 5))
        session.scriptEnd()
        val byAccount = session.openEntries.value.associateBy { it.accountId }
        assertTrue(byAccount.getValue("fictionalA").period.sources.any { it.source.script })
        assertTrue(byAccount.getValue("fictionalJp").period.sources.none { it.source.script })

        // Starting a KR battle script closes only the KR period; JP stays open and continues recording manual changes.
        time = 30
        session.scriptStart(battle = true, server = "KR")
        assertEquals(listOf("fictionalA"), logs.entries.map { it.accountId })
        assertEquals(listOf("fictionalJp"), session.openEntries.value.map { it.accountId })
        response(krItem, outsideFruit("fictionalA", 4)) // paused on KR
        response(jpItem, outsideFruit("fictionalJp", 3)) // still recorded on JP
        assertEquals(listOf("fictionalJp"), session.openEntries.value.map { it.accountId })
        assertEquals(-7L, session.openEntries.value.single().period.total.items[100])
        session.scriptEnd()

        // Switching accounts within JP closes the old JP period.
        time = 40
        response(jpLogin, outsideLogin("fictionalJp2"))
        assertEquals(listOf("fictionalJp", "fictionalA"), logs.entries.map { it.accountId })
    }

    @Test fun corruptOutsideLogIsPreservedAndAtomicReplaceOverwritesExistingFile() {
        val logDir = File(directory, "outside-store-replace").apply { mkdirs() }
        val logFile = File(logDir, "outside-log.json").apply { writeText("{corrupt") }
        val store = OutsideLogStore(logDir)
        store.load()
        assertTrue(store.entries.isEmpty())
        assertFalse(logFile.exists())
        assertTrue(logDir.listFiles().orEmpty().any { it.name.startsWith("outside-log.corrupt-") })

        val period = io.github.fate_grand_automata.scripts.capture.OutsidePeriod("acc1", 10L)
            .add(ChangeSource.of("item/recover"), AccountDelta(items = mapOf(100L to -1L)), 11L)
        store.add(OutsideLogEntry("e1", "acc1", "Master", 10_000L, 11_000L, period))
        store.add(OutsideLogEntry("e2", "acc1", "Master", 12_000L, 13_000L, period))
        assertEquals(listOf("e2", "e1"), OutsideLogStore(logDir).apply { load() }.entries.map { it.id })
        store.clear()
        assertTrue(OutsideLogStore(logDir).apply { load() }.entries.isEmpty())
    }

    @Test fun pendingPeriodWithUnknownSourceGroupResumesAsOther() {
        feed()
        assertTrue(pending.readText().contains("\"ApRecovery\""))
        pending.writeText(pending.readText().replace("\"ApRecovery\"", "\"FutureGroup\""))
        accounts = CaptureAccounts().apply { load(directory) }
        session = CaptureOutsideSession(accounts, logs::add, { time }).apply { load(directory); setRunning(true) }
        val period = session.openEntries.value.single().period
        assertEquals(AccountDelta(items = mapOf(100L to -2L)), period.total)
        assertEquals(ChangeSource.Group.Other, period.sources.single().source.group)
        assertEquals(AccountDelta(items = mapOf(100L to -2L)), period.sources.single().delta)
    }

    @Test fun pendingPeriodWithNonStringGroupIsStillSkipped() {
        feed()
        pending.writeText(pending.readText().replace("\"ApRecovery\"", "7"))
        accounts = CaptureAccounts().apply { load(directory) }
        session = CaptureOutsideSession(accounts, logs::add, { time }).apply { load(directory); setRunning(true) }
        assertTrue(session.openEntries.value.isEmpty())
    }

    @Test fun pendingPeriodWithTwoUnknownGroupsResumesAsOneOtherEntry() {
        feed()
        val root = Json.parseToJsonElement(pending.readText()).jsonObject
        val period = root.getValue("period").jsonObject
        val first = period.getValue("sources").jsonArray.single().jsonObject
        fun named(group: String, qp: Long) = JsonObject(first + mapOf(
            "source" to JsonObject(mapOf("group" to JsonPrimitive(group))),
            "delta" to JsonObject(mapOf("qp" to JsonPrimitive(qp))),
            "fruitUsed" to JsonObject(emptyMap())))
        val sources = JsonArray(listOf(named("FutureA", 4), named("FutureB", 9)))
        pending.writeText(JsonObject(root + ("period" to JsonObject(period + ("sources" to sources)))).toString())
        accounts = CaptureAccounts().apply { load(directory) }
        session = CaptureOutsideSession(accounts, logs::add, { time }).apply { load(directory); setRunning(true) }
        val loaded = session.openEntries.value.single().period.sources
        assertEquals(1, loaded.size)
        assertEquals(ChangeSource.Group.Other, loaded.single().source.group)
        assertEquals(13, loaded.single().delta.qp)
    }
}
