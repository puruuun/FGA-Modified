package io.github.fate_grand_automata.scripts.capture.chaldea

import io.github.fate_grand_automata.scripts.capture.AccountState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

private const val PKG = "cc.narumi.chaldea"
private const val MIN = 60_000L

/** Scripted outside world: Chaldea state, the service and the accounts. Entirely synthetic. */
@OptIn(ExperimentalCoroutinesApi::class)
private class Fx(val ts: TestScope, val tmp: File) {
    var enabled = true
    var closeMinutes = 30
    var shizuku = ShizukuState.Ready
    var root = RootState.Unchecked
    var openThrows = false
    var clockOffset = 0L
    var wall = 1000L

    // Scripted service
    var stateFn: (now: Long, call: Int) -> ChaldeaRunState = { _, _ -> ChaldeaRunState.NotRunning }
    var stateCalls = 0
    var base = "base-bytes".toByteArray()
    var bak: ByteArray? = null
    var loads = 0
    val restoredBundles = mutableListOf<ByteArray>()
    var baseSource: ChaldeaBaseSource = ChaldeaBaseSource.UserdataJson
    var prepareCode = ChaldeaSyncCode.Ok
    var backupMatches = true
    var commitDelay = 0L
    var commitResult: (Int) -> ChaldeaSyncResult = { n ->
        ChaldeaSyncResult(ChaldeaSyncCode.Ok, packageName = PKG, size = 5, sha256 = sha256Hex("written-$n".toByteArray()), itemCount = 2)
    }
    var restoreCode = ChaldeaSyncCode.Ok
    var gameDataFn: (String?) -> ChaldeaGameDataResult = { ChaldeaGameDataResult.Error(ChaldeaSyncCode.NotFound.name) }
    var gameDataCalls = 0
    val log = mutableListOf<String>()
    val openTimes = mutableListOf<Long>()
    val commits = mutableListOf<Pair<ChaldeaPatch, Boolean>>()
    var opened = 0
    var closed = 0
    var restores = 0

    val loaded = mutableMapOf<String, ChaldeaAccountSource.Loaded>()
    val current = mutableMapOf<ChaldeaServer, String>()
    var numbers = ChaldeaCollectionNumbers.EMPTY
    var collectionNumberCalls = 0

    val store = ChaldeaSyncStore(File(tmp, "sync"))
    val backups = ChaldeaBackupStore(File(tmp, "bk"))

    fun now() = ts.testScheduler.currentTime

    inner class Session : ChaldeaSyncSession {
        override suspend fun state(): ChaldeaStateResult {
            log += "state"
            return ChaldeaStateResult(stateFn(now(), stateCalls++), PKG)
        }

        override suspend fun forceStop(): ChaldeaStateResult {
            log += "forceStop"
            stateFn = { _, _ -> ChaldeaRunState.NotRunning }
            return ChaldeaStateResult(ChaldeaRunState.NotRunning, PKG)
        }

        override suspend fun prepare(sink: OutputStream): ChaldeaSyncSession.PrepareOutcome {
            log += "prepare"
            val bundle = ChaldeaBackupBundle.encode(base, bak)
            sink.write(bundle)
            sink.flush()
            val sha = sha256Hex(base)
            val bundleSha = sha256Hex(bundle)
            val result = ChaldeaSyncResult(
                prepareCode, packageName = PKG, base = baseSource, size = base.size.toLong(), sha256 = sha,
                bundleSize = bundle.size.toLong(), bundleSha256 = bundleSha,
            )
            return ChaldeaSyncSession.PrepareOutcome(result, ChaldeaBackupReceiver.Received(bundle.size.toLong(), bundleSha), backupMatches)
        }

        override suspend fun commit(
            patch: ChaldeaPatch,
            expectedSha256: String,
            allowCreate: Boolean,
            expectedPackage: String,
            expectedBundleSha256: String,
        ): ChaldeaSyncResult {
            log += "commit"
            commits += patch to allowCreate
            if (commitDelay > 0) delay(commitDelay)
            return commitResult(commits.size).also { if (it.ok) base = "written-${commits.size}".toByteArray() }
        }

        override suspend fun restore(backup: File, sha256: String, packageName: String): ChaldeaSyncResult {
            log += "restore"
            restores++
            restoredBundles += backup.readBytes()
            return ChaldeaSyncResult(restoreCode, packageName = packageName)
        }

        override suspend fun gameData(knownVersion: String?): ChaldeaGameDataResult {
            log += "gameData"
            gameDataCalls++
            return gameDataFn(knownVersion)
        }

        override fun close() {
            closed++
        }
    }

    val env = object : ChaldeaSyncEnv {
        override fun enabled() = enabled
        override fun closeMinutes() = closeMinutes
        override fun access() = ChaldeaAccess(root, shizuku)
        override suspend fun openSession(): ChaldeaSyncSession {
            if (openThrows) {
                openThrows = false
                error("boom")
            }
            opened++
            openTimes += now()
            return Session()
        }
    }

    val source = object : ChaldeaAccountSource {
        override fun load(userId: String): ChaldeaAccountSource.Loaded? {
            loads++
            return loaded[userId]
        }
        override fun currentIds() = current.toMap()
        override fun collectionNumbers(): ChaldeaCollectionNumbers {
            collectionNumberCalls++
            return numbers
        }
    }

    val sch = ChaldeaSyncScheduler(
        ts.backgroundScope, env, source, store, backups,
        clock = { ts.testScheduler.currentTime + clockOffset }, wallClock = { wall++ },
    )

    fun account(id: String, code: String? = "111", num: Long = 1, server: ChaldeaServer = ChaldeaServer.Kr, current: Boolean = true) {
        val game = if (code == null) """[{"name":"N"}]""" else """[{"friendCode":"$code","name":"N","genderType":1}]"""
        loaded[id] = ChaldeaAccountSource.Loaded(
            server,
            AccountState(mapOf("userGame" to game, "userItem" to """[{"itemId":100,"num":$num}]"""), "[]", null, 1L, 2L),
        )
        if (current) this.current[server] = id
    }

    fun hash(id: String) = loaded.getValue(id).let { ChaldeaPatch.from(it.state, it.server).hash() }

    suspend fun start() {
        sch.onSettingsChanged()
        ts.runCurrent()
    }

    suspend fun advance(ms: Long) {
        ts.advanceTimeBy(ms)
        ts.runCurrent()
    }

    fun count(s: String) = log.count { it == s }

    val kr get() = sch.serverResults.value[ChaldeaServer.Kr]
    val jp get() = sch.serverResults.value[ChaldeaServer.Jp]
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChaldeaSyncSchedulerTest {
    private lateinit var tmp: File

    @BeforeTest fun setUp() {
        tmp = Files.createTempDirectory("chaldea-sched").toFile()
    }

    @AfterTest fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun fx(block: suspend Fx.() -> Unit) = runTest {
        Fx(this, tmp).block()
    }

    // ---- quiet timer, script hold ----

    @Test fun quietTimerRestartsOnEachSave() = fx {
        account("a")
        start()
        sch.onAccountSaved(listOf("a"))
        advance(59_000)
        sch.onAccountSaved(listOf("a")) // restarts the minute
        advance(59_000)
        assertEquals(0, opened, "no attempt 118 s in, the timer restarted at 59 s")
        advance(2_000)
        assertEquals(1, opened)
        assertEquals(1, commits.size)
    }

    @Test fun noAttemptWhileScriptRunsThenAttemptAfterGrace() = fx {
        account("a")
        start()
        sch.onScriptStart()
        advance(10)
        sch.onAccountSaved(listOf("a"))
        advance(10 * MIN)
        assertEquals(0, opened)
        assertEquals(ChaldeaSyncStatus.WaitingScript, sch.status.value)
        sch.onScriptEnd()
        advance(14_000)
        assertEquals(0, opened, "still in the 15 s grace")
        advance(2_000)
        assertEquals(1, commits.size)
    }

    @Test fun scriptStartDuringGraceCancelsTheAttempt() = fx {
        account("a")
        start()
        sch.onScriptStart()
        sch.onAccountSaved(listOf("a"))
        advance(100)
        sch.onScriptEnd()
        advance(10_000)
        sch.onScriptStart()
        advance(5 * MIN)
        assertEquals(0, opened)
        assertEquals(0, commits.size)
    }

    @Test fun startDuringPollingCancelsAndUnbinds() = fx {
        account("a")
        stateFn = { _, _ -> ChaldeaRunState.Visible }
        start()
        sch.onAccountSaved(listOf("a"))
        advance(MIN + 100)
        assertEquals(1, opened)
        sch.onScriptStart()
        advance(30 * MIN)
        assertEquals(1, opened, "no poll after the script started")
        assertEquals(opened, closed)
    }

    @Test fun anAttemptInsideCommitFinishes() = fx {
        account("a")
        commitDelay = 5_000
        start()
        sch.onAccountSaved(listOf("a"))
        advance(MIN + 1_000)
        assertEquals(1, commits.size, "commit is running")
        sch.onScriptStart()
        advance(10_000)
        assertEquals(hash("a"), store.read().syncedPatchHash["a"])
        assertIs<ChaldeaSyncStatus.Synced>(kr)
        assertEquals(opened, closed)
    }

    @Test fun scriptFlagIsABooleanNotACounter() = fx {
        account("a")
        start()
        sch.onScriptStart()
        sch.onScriptStart()
        sch.onAccountSaved(listOf("a"))
        advance(100)
        sch.onScriptEnd() // one end is enough after two starts
        advance(20_000)
        assertEquals(1, commits.size)
    }

    @Test fun scriptHoldExpiresAfterSixHours() = fx {
        account("a")
        start()
        sch.onScriptStart() // the end hook never comes
        advance(6 * 3_600_000L + MIN)
        sch.onAccountSaved(listOf("a"))
        advance(MIN + 1_000)
        assertEquals(1, commits.size)
    }

    // ---- patch hash, unknown, visible, background ----

    @Test fun samePatchHashMeansNoSessionAndNoClose() = fx {
        account("a")
        store.update { it.copy(syncedPatchHash = mapOf("a" to hash("a"))) }
        stateFn = { _, _ -> ChaldeaRunState.Background }
        start()
        sch.onAccountSaved(listOf("a"))
        advance(90 * MIN)
        assertEquals(0, opened)
        assertEquals(0, count("forceStop"))
        assertTrue(store.read().pending.isEmpty())
    }

    @Test fun changedPatchIsWrittenAndRecordsTheHash() = fx {
        account("a", num = 1)
        start()
        sch.onAccountSaved(listOf("a"))
        advance(MIN + 1_000)
        val first = store.read().syncedPatchHash["a"]
        assertEquals(hash("a"), first)
        account("a", num = 2)
        sch.onAccountSaved(listOf("a"))
        advance(MIN + 1_000)
        assertEquals(2, commits.size)
        assertNotEquals(first, store.read().syncedPatchHash["a"])
    }

    @Test fun unknownStateNeverClosesNorWrites() = fx {
        account("a")
        stateFn = { _, _ -> ChaldeaRunState.Unknown }
        start()
        sch.onAccountSaved(listOf("a"))
        advance(3 * 3_600_000L)
        assertEquals(0, count("forceStop"))
        assertEquals(0, count("prepare"))
        assertEquals(0, count("commit"))
        assertEquals(ChaldeaSyncStatus.StateUnknown, sch.status.value)
        assertEquals(opened, closed)
    }

    @Test fun visibleChaldeaWaitsAndNeverCloses() = fx {
        account("a")
        stateFn = { _, _ -> ChaldeaRunState.Visible }
        start()
        sch.onAccountSaved(listOf("a"))
        advance(3 * 3_600_000L)
        assertEquals(0, count("forceStop"))
        assertEquals(0, count("commit"))
        assertEquals(ChaldeaSyncStatus.WaitingVisible, sch.status.value)
        assertTrue("a" in store.read().pending)
    }

    @Test fun backgroundForThirtyMinutesClosesAfterARecheckAndThenWrites() = fx {
        account("a")
        stateFn = { _, _ -> ChaldeaRunState.Background }
        start()
        sch.onAccountSaved(listOf("a")) // first background observation at 1 min
        advance(30 * MIN)
        assertEquals(0, count("forceStop"), "29 minutes of background")
        advance(MIN + 100)
        assertEquals(1, count("forceStop"))
        val order = log.filter { it in setOf("forceStop", "prepare", "commit") }
        assertEquals(listOf("forceStop", "prepare", "commit"), order)
        // The state was read again right before the close.
        val closeIndex = log.indexOf("forceStop")
        assertEquals("state", log[closeIndex - 1])
        assertEquals("state", log[closeIndex - 2])
    }

    @Test fun recheckThatFindsChaldeaVisibleDoesNotClose() = fx {
        account("a")
        var calls = 0
        stateFn = { now, _ ->
            if (now < 31 * MIN) ChaldeaRunState.Background
            else if (calls++ == 0) ChaldeaRunState.Background else ChaldeaRunState.Visible
        }
        start()
        sch.onAccountSaved(listOf("a"))
        advance(40 * MIN)
        assertEquals(0, count("forceStop"))
        assertEquals(0, count("commit"))
    }

    @Test fun neverOptionNeverClosesInTheBackground() = fx {
        account("a")
        closeMinutes = 0
        stateFn = { _, _ -> ChaldeaRunState.Background }
        start()
        sch.onAccountSaved(listOf("a"))
        advance(5 * 3_600_000L)
        assertEquals(0, count("forceStop"))
        assertEquals(0, count("commit"))
    }

    @Test fun chaldeaNotRunningIsWrittenWithoutClosing() = fx {
        account("a")
        start()
        sch.onAccountSaved(listOf("a"))
        advance(MIN + 100)
        assertEquals(0, count("forceStop"))
        assertEquals(1, count("commit"))
    }

    @Test fun gapBetweenObservationsRestartsTheBackgroundCount() = fx {
        account("a")
        stateFn = { _, _ -> ChaldeaRunState.Background }
        start()
        sch.onAccountSaved(listOf("a"))
        advance(20 * MIN)
        clockOffset += 60 * MIN // the process was frozen: the next observation is an hour late
        advance(MIN)
        advance(29 * MIN) // 50 minutes of virtual time; 30 minutes after the restart is at 51
        assertEquals(0, count("forceStop"), "a long gap must not count as background time")
        advance(2 * MIN)
        assertEquals(1, count("forceStop"))
    }

    @Test fun pollingEveryMinuteForTenMinutesThenEveryFive() = fx {
        account("a")
        stateFn = { _, _ -> ChaldeaRunState.Visible }
        start()
        sch.onAccountSaved(listOf("a"))
        advance(45 * MIN)
        val diffs = openTimes.zipWithNext { a, b -> b - a }
        assertTrue(diffs.size > 12)
        assertTrue(diffs.take(10).all { it == MIN }, "fast polls: $diffs")
        assertTrue(diffs.drop(10).all { it == 5 * MIN }, "slow polls: $diffs")
        assertEquals(opened, closed, "unbound after every poll")
    }

    // ---- switches, Shizuku, deletion ----

    @Test fun switchOffMidWaitCancelsAndUnbinds() = fx {
        account("a")
        stateFn = { _, _ -> ChaldeaRunState.Visible }
        start()
        sch.onAccountSaved(listOf("a"))
        advance(3 * MIN)
        val before = opened
        enabled = false
        sch.onSettingsChanged()
        advance(60 * MIN)
        assertEquals(before, opened)
        assertEquals(opened, closed)
        assertEquals(ChaldeaSyncStatus.Idle, sch.status.value)
    }

    @Test fun switchOffMeansNoCallAtAll() = fx {
        account("a")
        enabled = false
        start()
        sch.onAccountSaved(listOf("a"))
        sch.onScriptStart()
        sch.onScriptEnd()
        sch.onForeground()
        advance(10 * MIN)
        assertEquals(0, opened)
        assertEquals(0, stateCalls)
        assertTrue(store.read().pending.isEmpty())
    }

    @Test fun shizukuNotRunningKeepsPendingAndWritesWhenItArrives() = fx {
        account("a")
        shizuku = ShizukuState.NotRunning
        start()
        sch.onAccountSaved(listOf("a"))
        advance(10 * MIN)
        assertEquals(0, opened)
        assertEquals(ChaldeaSyncStatus.ShizukuUnavailable(ShizukuState.NotRunning), sch.status.value)
        assertTrue("a" in store.read().pending)
        shizuku = ShizukuState.Ready
        sch.onAccessChanged()
        advance(1_000)
        assertEquals(1, commits.size)
    }

    @Test fun syncRunsInRootModeWhileShizukuIsNotInstalled() = fx {
        account("a")
        shizuku = ShizukuState.NotInstalled
        root = RootState.Granted
        start()
        sch.onAccountSaved(listOf("a"))
        advance(2 * MIN)
        assertEquals(1, commits.size)
        assertTrue(kr is ChaldeaSyncStatus.Synced)
    }

    @Test fun fallsBackToShizukuWhenRootIsUnavailable() = fx {
        account("a")
        shizuku = ShizukuState.Ready
        root = RootState.Unavailable
        start()
        sch.onAccountSaved(listOf("a"))
        advance(2 * MIN)
        assertEquals(1, commits.size)
        assertTrue(kr is ChaldeaSyncStatus.Synced)
    }

    @Test fun neitherRootNorShizukuKeepsPendingAndReportsShizukuState() = fx {
        account("a")
        shizuku = ShizukuState.NotInstalled
        root = RootState.Unavailable
        start()
        sch.onAccountSaved(listOf("a"))
        advance(10 * MIN)
        assertEquals(0, opened)
        assertEquals(ChaldeaSyncStatus.ShizukuUnavailable(ShizukuState.NotInstalled), sch.status.value)
        root = RootState.Granted
        sch.onAccessChanged()
        advance(1_000)
        assertEquals(1, commits.size, "a root state change wakes the scheduler like a Shizuku change")
    }

    @Test fun ownerChangedIsAPersistentFailure() = fx {
        account("a")
        commitResult = { ChaldeaSyncResult(ChaldeaSyncCode.OwnerChanged, packageName = PKG) }
        start()
        sch.onAccountSaved(listOf("a"))
        advance(2 * MIN)
        assertEquals(ChaldeaSyncStatus.OwnerChanged, kr)
        assertTrue(ChaldeaSyncStatus.OwnerChanged.persistent)
    }

    @Test fun deletingAnAccountRemovesItFromPending() = fx {
        account("a")
        start()
        sch.onAccountSaved(listOf("a"))
        advance(10_000)
        sch.onAccountDeleted("a")
        advance(10 * MIN)
        assertEquals(0, opened)
        assertFalse("a" in store.read().pending)
    }

    @Test fun pendingAndBackgroundSinceSurviveARestart() = fx {
        account("a")
        stateFn = { _, _ -> ChaldeaRunState.Background }
        start()
        sch.onAccountSaved(listOf("a"))
        advance(5 * MIN)
        val saved = store.read()
        assertTrue("a" in saved.pending)
        assertNotNull(saved.backgroundSince)
        // A new scheduler on the same files is a restarted process.
        val second = ChaldeaSyncScheduler(
            ts.backgroundScope, env, source, store, backups,
            clock = { ts.testScheduler.currentTime + clockOffset }, wallClock = { wall++ },
        )
        val before = opened
        second.onSettingsChanged()
        ts.runCurrent()
        advance(2 * MIN)
        assertTrue(opened > before, "the restored pending set is attempted")
    }

    @Test fun chaldeaStartedDuringTheWriteKeepsThePending() = fx {
        account("a")
        var afterCommit = false
        commitResult = { n ->
            afterCommit = true
            ChaldeaSyncResult(ChaldeaSyncCode.Ok, packageName = PKG, size = 5, sha256 = sha256Hex("w$n".toByteArray()))
        }
        stateFn = { _, _ -> if (afterCommit) ChaldeaRunState.Visible else ChaldeaRunState.NotRunning }
        start()
        sch.onAccountSaved(listOf("a"))
        advance(MIN + 1_000)
        assertEquals(1, commits.size)
        assertNull(store.read().syncedPatchHash["a"])
        assertTrue("a" in store.read().pending)
    }

    @Test fun twoAccountsAreWrittenInOneSession() = fx {
        account("kr", code = "111", server = ChaldeaServer.Kr)
        account("jp", code = "222", server = ChaldeaServer.Jp)
        start()
        sch.onAccountSaved(listOf("kr", "jp"))
        advance(MIN + 1_000)
        assertEquals(2, commits.size)
        assertEquals(1, opened)
        assertEquals(setOf(ChaldeaServer.Kr, ChaldeaServer.Jp), commits.map { it.first.server }.toSet())
    }

    // ---- account identification ----

    @Test fun missingFriendCodeWritesNothing() = fx {
        account("a", code = null)
        start()
        sch.onAccountSaved(listOf("a"))
        advance(MIN + 1_000)
        assertEquals(0, count("commit"))
        assertEquals(ChaldeaSyncStatus.AccountNotIdentified, kr)
        assertFalse("a" in store.read().pending)
    }

    @Test fun onlyTheCurrentAccountMayCreateAUser() = fx {
        account("old", code = "111", current = false)
        account("cur", code = "222", current = true)
        start()
        sch.onAccountSaved(listOf("old", "cur"))
        advance(MIN + 1_000)
        val byCode = commits.associate { it.first.friendCode to it.second }
        assertEquals(false, byCode["111"])
        assertEquals(true, byCode["222"])
    }

    @Test fun refusalWithoutMatchingUserDropsThePendingQuietly() = fx {
        account("old", code = "111", current = false)
        commitResult = { ChaldeaSyncResult(ChaldeaSyncCode.NoMatchingUser, packageName = PKG) }
        start()
        sch.onAccountSaved(listOf("old"))
        advance(MIN + 1_000)
        assertFalse("old" in store.read().pending)
        assertEquals(ChaldeaSyncStatus.Idle, sch.status.value)
        assertNull(store.read().syncedPatchHash["old"])
    }

    // ---- backups ----

    @Test fun firstOriginalAndNewestThreeAreKeptAndSameHashIsSkipped() = fx {
        account("a", num = 1)
        start()
        for (n in 1..6) {
            account("a", num = n.toLong())
            base = "orig-$n".toByteArray()
            sch.onAccountSaved(listOf("a"))
            advance(MIN + 1_000)
        }
        assertEquals(6, commits.size)
        val first = backups.firstOriginal(PKG)
        assertEquals("orig-1", File(first, "userdata.json").readText(), "the first original is never replaced")
        val rotating = backups.rotating(PKG)
        assertEquals(3, rotating.size)
        assertEquals(listOf("orig-6", "orig-5", "orig-4"), rotating.map { File(it, "userdata.json").readText() })
        // The base now equals what FGA wrote last: no new rotating backup, so a user's own data is not buried.
        account("a", num = 99)
        sch.onAccountSaved(listOf("a"))
        advance(MIN + 1_000)
        assertEquals(7, commits.size)
        assertEquals(listOf("orig-6", "orig-5", "orig-4"), backups.rotating(PKG).map { File(it, "userdata.json").readText() })
    }

    @Test fun backupThatDoesNotMatchMeansNoCommit() = fx {
        account("a")
        backupMatches = false
        start()
        sch.onAccountSaved(listOf("a"))
        advance(10 * MIN)
        assertEquals(0, count("commit"))
        assertEquals(ChaldeaSyncStatus.BackupFailed, kr)
        assertTrue("a" in store.read().pending)
        assertNull(store.readInflight())
    }

    @Test fun backupFolderThatCannotBeWrittenMeansNoCommit() = fx {
        account("a")
        File(tmp, "bk").writeText("a file where the folder should be")
        start()
        sch.onAccountSaved(listOf("a"))
        advance(10 * MIN)
        assertEquals(0, count("commit"))
        assertEquals(ChaldeaSyncStatus.BackupFailed, kr)
        assertTrue("a" in store.read().pending)
    }

    @Test fun prepareFailureMeansNoWrite() = fx {
        account("a")
        prepareCode = ChaldeaSyncCode.NotFound
        start()
        sch.onAccountSaved(listOf("a"))
        advance(10 * MIN)
        assertEquals(0, count("commit"))
        assertEquals(ChaldeaSyncStatus.FileNotFound, kr)
    }

    @Test fun inflightIsPersistedBeforeCommitAndRemovedAfterSuccess() = fx {
        account("a")
        var seen: ChaldeaSyncStore.Inflight? = null
        var backedUp: String? = null
        commitResult = { n ->
            seen = store.readInflight()
            backedUp = seen?.let { File(it.backupPath, "userdata.json").readText() }
            ChaldeaSyncResult(ChaldeaSyncCode.Ok, packageName = PKG, size = 5, sha256 = sha256Hex("w$n".toByteArray()))
        }
        start()
        sch.onAccountSaved(listOf("a"))
        advance(MIN + 1_000)
        assertNotNull(seen)
        assertEquals(sha256Hex("base-bytes".toByteArray()), seen!!.baseSha256)
        assertEquals("base-bytes", backedUp, "the backup exists before the commit")
        assertNull(store.readInflight())
    }

    @Test fun failedAndRestoredIsReported() = fx {
        account("a")
        commitResult = { ChaldeaSyncResult(ChaldeaSyncCode.Restored, cause = ChaldeaSyncCode.VerifyFailed, packageName = PKG) }
        start()
        sch.onAccountSaved(listOf("a"))
        advance(MIN + 1_000)
        assertEquals(ChaldeaSyncStatus.FailedRestored, kr)
        assertNull(store.read().syncedPatchHash["a"])
        assertNull(store.readInflight())
    }

    @Test fun failedRestoreKeepsTheInflightRecordAndNamesTheBackup() = fx {
        account("a")
        commitResult = { ChaldeaSyncResult(ChaldeaSyncCode.RestoreFailed, cause = ChaldeaSyncCode.VerifyFailed, packageName = PKG) }
        start()
        sch.onAccountSaved(listOf("a"))
        advance(MIN + 1_000)
        val s = kr
        assertIs<ChaldeaSyncStatus.RestoreFailed>(s)
        assertTrue(s.backupPath.contains(PKG))
        assertNotNull(store.readInflight())
    }

    // ---- in-flight recovery ----

    private fun Fx.inflight(json: ByteArray = "orig".toByteArray(), bakBytes: ByteArray? = null): File {
        val dir = backups.inflightDir(PKG)
        dir.mkdirs()
        File(dir, "userdata.json").writeBytes(json)
        bakBytes?.let { File(dir, "userdata.json.bak").writeBytes(it) }
        val bundle = ChaldeaBackupBundle.encode(json, bakBytes)
        store.writeInflight(ChaldeaSyncStore.Inflight(PKG, dir.path, sha256Hex(json), bundle.size.toLong(), sha256Hex(bundle), 1))
        return dir
    }

    @Test fun interruptedWriteWithDamagedFileIsRestoredFromTheBackup() = fx {
        inflight()
        prepareCode = ChaldeaSyncCode.InvalidJson
        start()
        advance(1_000)
        assertEquals(1, restores)
        assertEquals(ChaldeaSyncStatus.FailedRestored, sch.status.value)
        assertNull(store.readInflight())
    }

    @Test fun interruptedWriteWithBakAsBaseIsRestored() = fx {
        inflight()
        baseSource = ChaldeaBaseSource.Bak // userdata.json is broken, .bak still valid
        start()
        advance(1_000)
        assertEquals(1, restores)
    }

    @Test fun interruptedWriteThatLeftAValidFileIsNotRestored() = fx {
        inflight()
        start()
        advance(1_000)
        assertEquals(0, restores)
        assertNull(store.readInflight())
    }

    @Test fun failedRestoreIsReportedWithTheBackupPath() = fx {
        val f = inflight()
        prepareCode = ChaldeaSyncCode.InvalidJson
        restoreCode = ChaldeaSyncCode.RestoreFailed
        start()
        advance(1_000)
        assertEquals(ChaldeaSyncStatus.RestoreFailed(f.path), sch.status.value)
        assertNotNull(store.readInflight())
    }

    @Test fun recoveryWaitsWhileChaldeaRuns() = fx {
        inflight()
        stateFn = { _, _ -> ChaldeaRunState.Visible }
        start()
        advance(30 * MIN)
        assertEquals(0, restores)
        assertEquals(0, count("prepare"))
        assertNotNull(store.readInflight())
    }

    // ---- robustness ----

    @Test fun anExceptionInAnAttemptNeverEscapesAndTheNextAttemptRuns() = fx {
        account("a")
        openThrows = true
        start()
        sch.onAccountSaved(listOf("a"))
        advance(MIN + 1_000)
        assertEquals(0, commits.size)
        sch.onAccountSaved(listOf("a"))
        advance(MIN + 1_000)
        assertEquals(1, commits.size)
    }

    @Test fun hooksNeverBlockEvenWhenTheEventConsumerIsStuck() {
        val stuck = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        stuck.execute { gate.await() } // the only thread of the scope is busy
        try {
            val scope = CoroutineScope(SupervisorJob() + stuck.asCoroutineDispatcher())
            val f = Fx(TestScope(), tmp)
            val sch = ChaldeaSyncScheduler(scope, f.env, f.source, f.store, f.backups, clock = { 0 })
            val done = CountDownLatch(1)
            val lock = Any()
            val t = Thread {
                synchronized(lock) { // stands in for the CaptureTracker lock held by save()
                    sch.onSettingsChanged()
                    sch.onAccountSaved(listOf("a"))
                    sch.onAccountDeleted("a")
                    sch.onScriptStart()
                    sch.onScriptEnd()
                    sch.onForeground()
                    sch.onAccessChanged()
                }
                done.countDown()
            }
            t.start()
            assertTrue(done.await(2, TimeUnit.SECONDS), "a hook blocked")
        } finally {
            gate.countDown()
            stuck.shutdownNow()
        }
    }

    // ---- sync now ----

    @Test fun syncNowIgnoresTheHashAndWritesNow() = fx {
        account("a")
        store.update { it.copy(syncedPatchHash = mapOf("a" to hash("a"))) }
        val outcome = sch.syncNow(null)
        assertEquals(ChaldeaSyncNowOutcome.Done, outcome)
        assertEquals(1, commits.size)
    }

    @Test fun syncNowAsksBeforeClosingARunningChaldea() = fx {
        account("a")
        stateFn = { _, _ -> ChaldeaRunState.Background }
        assertEquals(ChaldeaSyncNowOutcome.NeedsConfirm(ChaldeaRunState.Background), sch.syncNow(null))
        assertEquals(0, count("forceStop"))
        assertEquals(ChaldeaSyncNowOutcome.Done, sch.syncNow(ChaldeaRunState.Background))
        assertEquals(1, count("forceStop"))
        assertEquals(1, count("commit"))
    }

    @Test fun syncNowAsksAgainWhenChaldeaBecameVisibleAfterTheDialog() = fx {
        account("a")
        stateFn = { _, _ -> ChaldeaRunState.Visible }
        assertEquals(ChaldeaSyncNowOutcome.NeedsConfirm(ChaldeaRunState.Visible), sch.syncNow(ChaldeaRunState.Background))
        assertEquals(0, count("forceStop"))
    }

    @Test fun syncNowNeverClosesAnUnknownState() = fx {
        account("a")
        stateFn = { _, _ -> ChaldeaRunState.Unknown }
        val o = sch.syncNow(ChaldeaRunState.Visible)
        assertIs<ChaldeaSyncNowOutcome.Unavailable>(o)
        assertEquals(0, count("forceStop"))
        assertEquals(0, count("commit"))
    }

    @Test fun syncNowIsRefusedWhileAScriptRuns() = fx {
        account("a")
        start()
        sch.onScriptStart()
        assertEquals(ChaldeaSyncNowOutcome.Unavailable(ChaldeaSyncStatus.WaitingScript), sch.syncNow(null))
        assertEquals(0, opened)
    }

    @Test fun syncNowWithUnidentifiedAccountAndRunningChaldeaReturnsDoneWithoutSession() = fx {
        account("a", code = null)
        stateFn = { _, _ -> ChaldeaRunState.Visible }
        assertNull(store.readInflight())
        assertEquals(ChaldeaSyncNowOutcome.Done, sch.syncNow(null))
        assertEquals(0, opened)
        assertEquals(ChaldeaSyncStatus.AccountNotIdentified, kr)
    }

    @Test fun syncNowWithCachedMapAndChangedNewMapUsesNewMapForPatch() = fx {
        numbers = ChaldeaCollectionNumbers(emptyMap(), emptyMap(), servants = mapOf(100100L to 2L))
        loaded["kr"] = ChaldeaAccountSource.Loaded(
            ChaldeaServer.Kr,
            AccountState(
                mapOf(
                    "userGame" to """[{"friendCode":"111","name":"K","genderType":1}]""",
                    "userSvtCollection" to """[{"svtId":100100,"status":2,"costumeIds":[12]}]""",
                    "userSvt" to """[{"id":1,"svtId":100100,"lv":90,"status":1}]""",
                ),
                "[]", null, 1L, 2L,
            ),
        )
        current[ChaldeaServer.Kr] = "kr"
        store.writeGameData(ChaldeaGameData("v1", mapOf(100100L to mapOf(12L to 800140L)), emptyMap()))
        gameDataFn = { known ->
            assertEquals("v1", known)
            ChaldeaGameDataResult.Loaded(ChaldeaGameData("v2", mapOf(100100L to mapOf(12L to 800150L)), emptyMap()))
        }
        assertEquals(ChaldeaSyncNowOutcome.Done, sch.syncNow(null))
        assertEquals(1, commits.size)
        assertEquals(mapOf(800150L to 1L), commits.single().first.servants!![2L]!!.costumes)
        assertEquals("v2", store.readGameData()?.version)
    }

    // ---- review round: S1, S2, S3, M1, M2, per-server results, coalescing ----

    @Test fun aSaveDuringTheWriteKeepsTheAccountPendingAndTheNewerPatchIsWritten() = fx {
        account("a", num = 1)
        commitDelay = 5_000
        start()
        sch.onAccountSaved(listOf("a"))
        advance(MIN + 1_000) // the first commit is running
        account("a", num = 2)
        sch.onAccountSaved(listOf("a"))
        advance(10_000) // the first commit is done
        assertEquals(1, commits.size)
        assertTrue("a" in store.read().pending, "the save that came during the write must not be wiped")
        commitDelay = 0
        advance(2 * MIN)
        assertEquals(2, commits.size)
        assertEquals(2L, commits[1].first.items!![100L])
        assertFalse("a" in store.read().pending)
        assertEquals(hash("a"), store.read().syncedPatchHash["a"])
    }

    @Test fun aSaveWhileWaitingForChaldeaIsWrittenOnceWhenItCloses() = fx {
        account("a", num = 1)
        stateFn = { _, _ -> ChaldeaRunState.Visible }
        start()
        sch.onAccountSaved(listOf("a"))
        advance(5 * MIN)
        account("a", num = 2)
        sch.onAccountSaved(listOf("a"))
        advance(2 * MIN)
        stateFn = { _, _ -> ChaldeaRunState.NotRunning }
        advance(6 * MIN)
        assertEquals(1, commits.size)
        assertEquals(2L, commits[0].first.items!![100L])
    }

    @Test fun waitingPollsDoNotLoadTheAccountAgain() = fx {
        account("a")
        stateFn = { _, _ -> ChaldeaRunState.Visible }
        start()
        sch.onAccountSaved(listOf("a"))
        advance(60 * MIN)
        assertTrue(opened > 10, "it kept polling")
        assertEquals(1, loads, "the patch is built once while waiting")
        account("a", num = 9)
        sch.onAccountSaved(listOf("a"))
        advance(6 * MIN)
        assertEquals(2, loads, "and again after a save")
    }

    @Test fun anInterruptedWriteThatCannotBeCheckedShowsTheReasonAndStopsAfterThreeTries() = fx {
        val dir = inflight()
        prepareCode = ChaldeaSyncCode.TooLarge
        account("a")
        start()
        advance(1_000)
        assertEquals(ChaldeaSyncStatus.RecoveryBlocked(ChaldeaSyncCode.TooLarge), sch.status.value)
        sch.onForeground()
        advance(1_000)
        sch.onForeground()
        advance(1_000)
        assertEquals(3, count("prepare"))
        sch.onForeground()
        sch.onAccountSaved(listOf("a"))
        advance(10 * MIN)
        assertEquals(3, count("prepare"), "no fourth try")
        assertEquals(0, count("commit"), "nothing is written over an unchecked file")
        assertEquals(ChaldeaSyncStatus.RecoveryBlocked(ChaldeaSyncCode.TooLarge), sch.status.value)
        assertNotNull(store.readInflight())
        assertTrue(File(dir, "userdata.json").isFile, "the backup is never deleted")
        // A switch toggle starts over.
        enabled = false
        sch.onSettingsChanged()
        advance(1_000)
        enabled = true
        prepareCode = ChaldeaSyncCode.Ok
        sch.onSettingsChanged()
        advance(10 * MIN)
        assertTrue(count("prepare") > 3)
    }

    @Test fun crashRecoveryRestoresEachFileToItsOwnOriginal() = fx {
        val origJson = "json-original".toByteArray()
        val origBak = "bak-original-different".toByteArray()
        inflight(origJson, origBak)
        baseSource = ChaldeaBaseSource.Bak // userdata.json was cut short by the crash
        start()
        advance(1_000)
        assertEquals(1, restores)
        val (json, bak) = ChaldeaBackupBundle.decode(restoredBundles.single())!!
        assertContentEquals(origJson, json)
        assertContentEquals(origBak, bak, ".bak keeps its own original, not a copy of userdata.json")
    }

    @Test fun hooksDoNothingWhileTheSwitchIsOff() = fx {
        enabled = false
        start()
        sch.onAccountSaved(listOf("a"))
        sch.onScriptStart()
        sch.onScriptEnd()
        assertEquals(0, sch.queuedEventCount())
        assertFalse(sch.scriptRunningState.value)
    }

    @Test fun burstsOfSavesShareOneQueuedEvent() = fx {
        account("a")
        start()
        repeat(1_000) { sch.onAccountSaved(listOf("a")) }
        assertEquals(1, sch.queuedEventCount())
        advance(MIN + 1_000)
        assertEquals(1, commits.size)
    }

    @Test fun oneServersSuccessDoesNotClearTheOthersFailure() = fx {
        account("kr", code = "111", server = ChaldeaServer.Kr)
        account("jp", code = "222", server = ChaldeaServer.Jp)
        commitResult = { n ->
            if (commits.last().first.server == ChaldeaServer.Kr) ChaldeaSyncResult(ChaldeaSyncCode.NotFound, packageName = PKG)
            else ChaldeaSyncResult(ChaldeaSyncCode.Ok, packageName = PKG, size = 5, sha256 = sha256Hex("w$n".toByteArray()))
        }
        start()
        sch.onAccountSaved(listOf("kr", "jp"))
        advance(MIN + 1_000)
        assertEquals(ChaldeaSyncStatus.FileNotFound, kr)
        assertIs<ChaldeaSyncStatus.Synced>(jp)
        // The JP account changes and syncs again: the KR failure stays until KR itself syncs.
        account("jp", code = "222", num = 7, server = ChaldeaServer.Jp)
        sch.onAccountSaved(listOf("jp"))
        advance(MIN + 1_000)
        assertEquals(ChaldeaSyncStatus.FileNotFound, kr)
        assertIs<ChaldeaSyncStatus.Synced>(jp)
    }

    @Test fun serverResultsClearOnAToggle() = fx {
        account("a", code = null)
        start()
        sch.onAccountSaved(listOf("a"))
        advance(MIN + 1_000)
        assertEquals(ChaldeaSyncStatus.AccountNotIdentified, kr)
        enabled = false
        sch.onSettingsChanged()
        advance(1_000)
        assertNull(kr)
    }

    @Test fun schedulerReadsCollectionNumbersOncePerPassAndPublishesStage2Status() = fx {
        numbers = ChaldeaCollectionNumbers(equips = mapOf(9300010L to 11L), commandCodes = mapOf(80001L to 1L))
        loaded["kr"] = ChaldeaAccountSource.Loaded(
            ChaldeaServer.Kr,
            AccountState(
                mapOf(
                    "userGame" to """[{"friendCode":"111","name":"K","genderType":1}]""",
                    "userSvtCoin" to """[{"svtId":100100,"num":15},{"svtId":200100,"num":30}]""",
                    "userSvtCollection" to """[{"svtId":9300010,"status":2}]""",
                    "userSvt" to """[{"svtId":9300010,"lv":40,"limitCount":2}]""",
                    "userCommandCodeCollection" to """[{"commandCodeId":80001,"status":2},{"commandCodeId":99999,"status":2}]""",
                ),
                "[]", null, 1L, 2L,
            ),
        )
        loaded["jp"] = ChaldeaAccountSource.Loaded(
            ChaldeaServer.Jp,
            AccountState(mapOf("userGame" to """[{"friendCode":"222","name":"J","genderType":2}]"""), "[]", null, 1L, 2L),
        )
        current[ChaldeaServer.Kr] = "kr"
        current[ChaldeaServer.Jp] = "jp"
        commitResult = { n ->
            val p = commits.last().first
            ChaldeaSyncResult(
                ChaldeaSyncCode.Ok, packageName = PKG, size = 5, sha256 = sha256Hex("w$n".toByteArray()),
                itemCount = p.items?.size ?: 0,
                coinCount = p.svtCoins?.size ?: 0,
                ceCount = p.craftEssences?.size ?: 0,
                ccCount = p.commandCodes?.size ?: 0,
            )
        }
        start()
        loads = 0
        collectionNumberCalls = 0
        sch.onAccountSaved(listOf("kr", "jp"))
        advance(MIN + 1_000)

        // Two passes (pre-check and write): one collectionNumbers() read per pass, not per account (loads = 4).
        assertEquals(4, loads)
        assertEquals(2, collectionNumberCalls)
        assertEquals(2, commits.size)
        val statusKr = assertIs<ChaldeaSyncStatus.Synced>(kr)
        assertEquals(2, statusKr.coins)
        assertEquals(1, statusKr.craftEssences)
        assertEquals(1, statusKr.commandCodes)
        assertEquals(1, statusKr.unmapped)
    }

    @Test fun settingsChangedAfterNumbersCachedResolvesPendingAccount() = fx {
        loaded["kr"] = ChaldeaAccountSource.Loaded(
            ChaldeaServer.Kr,
            AccountState(
                mapOf(
                    "userGame" to """[{"friendCode":"111","name":"K","genderType":1}]""",
                    "userSvtCollection" to """[{"svtId":9300010,"status":2}]""",
                    "userCommandCodeCollection" to """[{"commandCodeId":80001,"status":2}]""",
                ),
                "[]", null, 1L, 2L,
            ),
        )
        current[ChaldeaServer.Kr] = "kr"
        commitResult = { n ->
            val p = commits.last().first
            ChaldeaSyncResult(
                ChaldeaSyncCode.Ok, packageName = PKG, size = 5, sha256 = sha256Hex("w$n".toByteArray()),
                ceCount = p.craftEssences?.size ?: 0,
                ccCount = p.commandCodes?.size ?: 0,
            )
        }
        stateFn = { _, _ -> ChaldeaRunState.Visible }
        start()
        sch.onAccountSaved(listOf("kr"))
        advance(MIN + 1_000)
        // Account stays pending because Chaldea is visible.
        assertEquals(0, commits.size)
        assertEquals(ChaldeaSyncStatus.WaitingVisible, sch.status.value)

        // Collection lists finish caching in the background and notify the scheduler.
        numbers = ChaldeaCollectionNumbers(equips = mapOf(9300010L to 11L), commandCodes = mapOf(80001L to 1L))
        stateFn = { _, _ -> ChaldeaRunState.NotRunning }
        sch.onSettingsChanged()
        advance(MIN + 1_000)

        assertEquals(1, commits.size)
        val synced = assertIs<ChaldeaSyncStatus.Synced>(kr)
        assertEquals(1, synced.craftEssences)
        assertEquals(1, synced.commandCodes)
        assertEquals(0, synced.unmapped)
    }

    @Test fun gameDataRunsBeforeResolveTargetsInWritePassAndRequeuesOnceOnlyWhenContentChanges() = fx {
        numbers = ChaldeaCollectionNumbers(emptyMap(), emptyMap(), servants = mapOf(100100L to 2L))
        val eventsLog = mutableListOf<String>()
        val st = AccountState(
            mapOf(
                "userGame" to """[{"friendCode":"111","name":"K","genderType":1}]""",
                "userSvtCollection" to """[{"svtId":100100,"status":2,"costumeIds":[12]}]""",
                "userSvt" to """[{"id":1,"svtId":100100,"lv":90,"status":1}]""",
            ),
            "[]", null, 1L, 2L,
        )
        loaded["kr"] = ChaldeaAccountSource.Loaded(ChaldeaServer.Kr, st)
        current[ChaldeaServer.Kr] = "kr"

        val gd1 = ChaldeaGameData("v1", mapOf(100100L to mapOf(12L to 800140L)), emptyMap())
        val gd1SameContentDiffVer = ChaldeaGameData("v2", gd1.costumes, gd1.transforms)
        val gd2NewContent = ChaldeaGameData("v3", mapOf(100100L to mapOf(12L to 800150L)), emptyMap())

        val instrumentedSource = object : ChaldeaAccountSource {
            override fun load(userId: String): ChaldeaAccountSource.Loaded? {
                eventsLog += "load"
                return loaded[userId]
            }
            override fun currentIds() = current.toMap()
            override fun collectionNumbers(): ChaldeaCollectionNumbers {
                eventsLog += "numbers"
                return numbers
            }
        }
        val localSch = ChaldeaSyncScheduler(
            ts.backgroundScope, env, instrumentedSource, store, backups,
            clock = { ts.testScheduler.currentTime + clockOffset }, wallClock = { wall++ },
        )
        localSch.onSettingsChanged()
        ts.runCurrent()

        gameDataFn = { known ->
            eventsLog += "gameData:${known ?: "null"}"
            ChaldeaGameDataResult.Loaded(gd1)
        }
        commitResult = { n ->
            val p = commits.last().first
            ChaldeaSyncResult(
                ChaldeaSyncCode.Ok, packageName = PKG, size = 5, sha256 = sha256Hex("w$n".toByteArray()),
                servantCount = p.servants?.size ?: 0,
            )
        }

        // First write pass: pre-check calls load+numbers without opening session; then writePass calls gameData BEFORE resolveTargets!
        localSch.onAccountSaved(listOf("kr"))
        advance(MIN + 1_000)
        assertEquals(1, commits.size)
        assertEquals(mapOf(800140L to 1L), commits.last().first.servants!![2L]!!.costumes)
        val idxGameData = eventsLog.indexOf("gameData:null")
        assertTrue(idxGameData >= 0)
        assertTrue(eventsLog.drop(idxGameData + 1).contains("load"))
        assertEquals(1, assertIs<ChaldeaSyncStatus.Synced>(localSch.serverResults.value[ChaldeaServer.Kr]).servants)

        // New save triggers another pass; gameData returns Unchanged -> no extra re-queue / no loop
        account("kr", num = 2) // still keeps userItem only -> update to same svt tables with new item
        loaded["kr"] = ChaldeaAccountSource.Loaded(
            ChaldeaServer.Kr,
            AccountState(st.rawTables + ("userItem" to """[{"itemId":100,"num":2}]"""), "[]", null, 1L, 2L),
        )
        gameDataFn = { ChaldeaGameDataResult.Unchanged("v1") }
        localSch.onAccountSaved(listOf("kr"))
        advance(MIN + 1_000)
        assertEquals(2, commits.size)
        assertTrue(store.read().pending.isEmpty())

        // Different version but identical content updates cached version silently and does not re-queue
        loaded["kr"] = ChaldeaAccountSource.Loaded(
            ChaldeaServer.Kr,
            AccountState(st.rawTables + ("userItem" to """[{"itemId":100,"num":3}]"""), "[]", null, 1L, 2L),
        )
        gameDataFn = { ChaldeaGameDataResult.Loaded(gd1SameContentDiffVer) }
        localSch.onAccountSaved(listOf("kr"))
        advance(MIN + 1_000)
        assertEquals("v2", store.readGameData()?.version)
        assertTrue(store.read().pending.isEmpty())

        // New content re-queues current account even when account wasn't already synced cleanly via manualSync or next pass
        val krResult = localSch.syncNow(null)
        assertEquals(ChaldeaSyncNowOutcome.Done, krResult)
        gameDataFn = { known ->
            eventsLog += "gameData:${known ?: "null"}"
            ChaldeaGameDataResult.Loaded(gd2NewContent)
        }
        eventsLog.clear()
        val res = localSch.syncNow(null)
        assertEquals(ChaldeaSyncNowOutcome.Done, res)
        val idxSyncNowGd = eventsLog.indexOf("gameData:v2")
        assertTrue(idxSyncNowGd >= 0)
        assertEquals("gameData:v2", eventsLog.first { it.startsWith("gameData") })
        assertTrue(eventsLog.drop(idxSyncNowGd + 1).contains("load"))
        assertEquals(mapOf(800150L to 1L), commits.last().first.servants!![2L]!!.costumes)
        assertEquals("v3", store.readGameData()?.version)
    }

    @Test fun gameDataUpdatedInWritePassRequeuesSettledOtherCurrentAccount() = fx {
        numbers = ChaldeaCollectionNumbers(emptyMap(), emptyMap(), servants = mapOf(100100L to 2L))
        val stKr = AccountState(
            mapOf(
                "userGame" to """[{"friendCode":"111","name":"K","genderType":1}]""",
                "userSvtCollection" to """[{"svtId":100100,"status":2,"costumeIds":[12]}]""",
                "userSvt" to """[{"id":1,"svtId":100100,"lv":90,"status":1}]""",
            ),
            "[]", null, 1L, 2L,
        )
        val stJp = AccountState(
            mapOf(
                "userGame" to """[{"friendCode":"222","name":"J","genderType":2}]""",
                "userSvtCollection" to """[{"svtId":100100,"status":2,"costumeIds":[12]}]""",
                "userSvt" to """[{"id":2,"svtId":100100,"lv":90,"status":1}]""",
            ),
            "[]", null, 1L, 2L,
        )
        loaded["kr"] = ChaldeaAccountSource.Loaded(ChaldeaServer.Kr, stKr)
        loaded["jp"] = ChaldeaAccountSource.Loaded(ChaldeaServer.Jp, stJp)
        current[ChaldeaServer.Kr] = "kr"
        current[ChaldeaServer.Jp] = "jp"

        start()
        // Initial sync without game data -> both accounts sync with costumes = null and settle
        sch.onAccountSaved(listOf("kr", "jp"))
        advance(MIN + 1_000)
        assertEquals(2, commits.size)

        // Only kr changes; during kr's writePass, Chaldea now has game data -> jp is also re-queued and synced in the same pass!
        loaded["kr"] = ChaldeaAccountSource.Loaded(
            ChaldeaServer.Kr,
            AccountState(stKr.rawTables + ("userItem" to """[{"itemId":100,"num":9}]"""), "[]", null, 1L, 2L),
        )
        val gd = ChaldeaGameData("v1", mapOf(100100L to mapOf(12L to 800140L)), emptyMap())
        gameDataFn = { ChaldeaGameDataResult.Loaded(gd) }
        sch.onAccountSaved(listOf("kr"))
        advance(MIN + 1_000)

        assertEquals(4, commits.size) // +2 (kr + jp)
        assertEquals(mapOf(800140L to 1L), commits[2].first.servants!![2L]!!.costumes)
        assertEquals(mapOf(800140L to 1L), commits[3].first.servants!![2L]!!.costumes)
        assertTrue(store.read().pending.isEmpty())

        // Error on next pass leaves cache intact and re-queues nothing
        gameDataFn = { ChaldeaGameDataResult.Error(ChaldeaSyncCode.ServiceError.name) }
        sch.onAccountSaved(listOf("kr"))
        advance(MIN + 1_000)
        assertEquals(4, commits.size) // unchanged hash -> nothing committed
    }

    @Test fun patchExceedingBinderLimitRefusedBeforePrepareAndSettledWithoutStoringHash() = fx {
        // Build an oversized patch (> 350,000 chars) via a large items map (~25,000 items * ~15 chars = ~375,000 chars)
        val itemsJson = buildString {
            append('[')
            for (i in 1..28_000) {
                if (i > 1) append(',')
                append("""{"itemId":${10_000_000L + i},"num":${100_000_000L + i}}""")
            }
            append(']')
        }
        loaded["kr"] = ChaldeaAccountSource.Loaded(
            ChaldeaServer.Kr,
            AccountState(mapOf("userGame" to """[{"friendCode":"111","name":"K","genderType":1}]""", "userItem" to itemsJson), "[]", null, 1L, 2L),
        )
        current[ChaldeaServer.Kr] = "kr"
        assertTrue(ChaldeaPatch.from(loaded["kr"]!!.state, ChaldeaServer.Kr).toJson().length > ChaldeaPatch.MAX_JSON_CHARS)

        start()
        sch.onAccountSaved(listOf("kr"))
        advance(MIN + 1_000)

        assertEquals(ChaldeaSyncStatus.PatchTooLarge, kr)
        assertFalse("prepare" in log, "prepare must not be called when patch exceeds size limit")
        assertEquals(0, commits.size)
        assertTrue(store.read().pending.isEmpty())
        assertTrue(store.read().syncedPatchHash.isEmpty())

        // Advancing further must not retry in a loop
        val openedBefore = opened
        advance(20 * MIN)
        assertEquals(openedBefore, opened)
    }
}
