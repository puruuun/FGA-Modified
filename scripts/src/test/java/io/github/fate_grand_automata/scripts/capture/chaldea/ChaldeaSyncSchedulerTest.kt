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
    var openThrows = false
    var clockOffset = 0L
    var wall = 1000L

    // Scripted service
    var stateFn: (now: Long, call: Int) -> ChaldeaRunState = { _, _ -> ChaldeaRunState.NotRunning }
    var stateCalls = 0
    var base = "base-bytes".toByteArray()
    var baseSource: ChaldeaBaseSource = ChaldeaBaseSource.UserdataJson
    var prepareCode = ChaldeaSyncCode.Ok
    var backupMatches = true
    var commitDelay = 0L
    var commitResult: (Int) -> ChaldeaSyncResult = { n ->
        ChaldeaSyncResult(ChaldeaSyncCode.Ok, packageName = PKG, size = 5, sha256 = sha256Hex("written-$n".toByteArray()), itemCount = 2)
    }
    var restoreCode = ChaldeaSyncCode.Ok
    val log = mutableListOf<String>()
    val openTimes = mutableListOf<Long>()
    val commits = mutableListOf<Pair<ChaldeaPatch, Boolean>>()
    var opened = 0
    var closed = 0
    var restores = 0

    val loaded = mutableMapOf<String, ChaldeaAccountSource.Loaded>()
    val current = mutableMapOf<ChaldeaServer, String>()

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
            sink.write(base)
            sink.flush()
            val sha = sha256Hex(base)
            val result = ChaldeaSyncResult(prepareCode, packageName = PKG, base = baseSource, size = base.size.toLong(), sha256 = sha)
            return ChaldeaSyncSession.PrepareOutcome(result, ChaldeaBackupReceiver.Received(base.size.toLong(), sha), backupMatches)
        }

        override suspend fun commit(patch: ChaldeaPatch, expectedSha256: String, allowCreate: Boolean, expectedPackage: String): ChaldeaSyncResult {
            log += "commit"
            commits += patch to allowCreate
            if (commitDelay > 0) delay(commitDelay)
            return commitResult(commits.size).also { if (it.ok) base = "written-${commits.size}".toByteArray() }
        }

        override suspend fun restore(backup: File, sha256: String, packageName: String): ChaldeaSyncResult {
            log += "restore"
            restores++
            return ChaldeaSyncResult(restoreCode, packageName = packageName)
        }

        override fun close() {
            closed++
        }
    }

    val env = object : ChaldeaSyncEnv {
        override fun enabled() = enabled
        override fun closeMinutes() = closeMinutes
        override fun shizukuState() = shizuku
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
        override fun load(userId: String) = loaded[userId]
        override fun currentIds() = current.toMap()
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
        assertIs<ChaldeaSyncStatus.Synced>(sch.status.value)
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
        sch.onShizukuChanged()
        advance(1_000)
        assertEquals(1, commits.size)
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
        assertEquals(ChaldeaSyncStatus.AccountNotIdentified, sch.status.value)
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
        assertEquals("orig-1", first.readText(), "the first original is never replaced")
        val rotating = backups.rotating(PKG)
        assertEquals(3, rotating.size)
        assertEquals(listOf("orig-6", "orig-5", "orig-4"), rotating.map { it.readText() })
        // The base now equals what FGA wrote last: no new rotating backup, so a user's own data is not buried.
        account("a", num = 99)
        sch.onAccountSaved(listOf("a"))
        advance(MIN + 1_000)
        assertEquals(7, commits.size)
        assertEquals(listOf("orig-6", "orig-5", "orig-4"), backups.rotating(PKG).map { it.readText() })
    }

    @Test fun backupThatDoesNotMatchMeansNoCommit() = fx {
        account("a")
        backupMatches = false
        start()
        sch.onAccountSaved(listOf("a"))
        advance(10 * MIN)
        assertEquals(0, count("commit"))
        assertEquals(ChaldeaSyncStatus.BackupFailed, sch.status.value)
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
        assertEquals(ChaldeaSyncStatus.BackupFailed, sch.status.value)
        assertTrue("a" in store.read().pending)
    }

    @Test fun prepareFailureMeansNoWrite() = fx {
        account("a")
        prepareCode = ChaldeaSyncCode.NotFound
        start()
        sch.onAccountSaved(listOf("a"))
        advance(10 * MIN)
        assertEquals(0, count("commit"))
        assertEquals(ChaldeaSyncStatus.FileNotFound, sch.status.value)
    }

    @Test fun inflightIsPersistedBeforeCommitAndRemovedAfterSuccess() = fx {
        account("a")
        var seen: ChaldeaSyncStore.Inflight? = null
        commitResult = { n ->
            seen = store.readInflight()
            ChaldeaSyncResult(ChaldeaSyncCode.Ok, packageName = PKG, size = 5, sha256 = sha256Hex("w$n".toByteArray()))
        }
        start()
        sch.onAccountSaved(listOf("a"))
        advance(MIN + 1_000)
        assertNotNull(seen)
        assertEquals(sha256Hex("base-bytes".toByteArray()), seen!!.baseSha256)
        assertTrue(File(seen!!.backupPath).isFile || !File(seen!!.backupPath).exists())
        assertNull(store.readInflight())
    }

    @Test fun failedAndRestoredIsReported() = fx {
        account("a")
        commitResult = { ChaldeaSyncResult(ChaldeaSyncCode.Restored, cause = ChaldeaSyncCode.VerifyFailed, packageName = PKG) }
        start()
        sch.onAccountSaved(listOf("a"))
        advance(MIN + 1_000)
        assertEquals(ChaldeaSyncStatus.FailedRestored, sch.status.value)
        assertNull(store.read().syncedPatchHash["a"])
        assertNull(store.readInflight())
    }

    @Test fun failedRestoreKeepsTheInflightRecordAndNamesTheBackup() = fx {
        account("a")
        commitResult = { ChaldeaSyncResult(ChaldeaSyncCode.RestoreFailed, cause = ChaldeaSyncCode.VerifyFailed, packageName = PKG) }
        start()
        sch.onAccountSaved(listOf("a"))
        advance(MIN + 1_000)
        val s = sch.status.value
        assertIs<ChaldeaSyncStatus.RestoreFailed>(s)
        assertTrue(s.backupPath.contains(PKG))
        assertNotNull(store.readInflight())
    }

    // ---- in-flight recovery ----

    private fun Fx.inflight(bytes: ByteArray = "orig".toByteArray()): File {
        val f = File(backups.packageDir(PKG), ChaldeaBackupStore.INFLIGHT)
        f.parentFile.mkdirs()
        f.writeBytes(bytes)
        store.writeInflight(ChaldeaSyncStore.Inflight(PKG, f.path, sha256Hex(bytes), 1))
        return f
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
                    sch.onShizukuChanged()
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
}
