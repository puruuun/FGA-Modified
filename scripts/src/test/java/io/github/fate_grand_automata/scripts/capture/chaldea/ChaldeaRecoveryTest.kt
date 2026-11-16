package io.github.fate_grand_automata.scripts.capture.chaldea

import io.github.fate_grand_automata.scripts.capture.AccountState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import kotlin.test.*

private const val PACKAGE = "cc.narumi.chaldea"

/**
 * Crash recovery against the real [ChaldeaSyncEngine] on a real folder: the session is a thin adapter, so what the
 * scheduler concludes and what ends up in the files is what the production code would do. Synthetic data only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChaldeaRecoveryTest {
    private lateinit var tmp: File
    private lateinit var chaldea: File

    @BeforeTest fun setUp() {
        tmp = Files.createTempDirectory("chaldea-recovery").toFile()
        chaldea = File(tmp, "user").also { it.mkdirs() }
    }

    @AfterTest fun tearDown() {
        tmp.deleteRecursively()
    }

    private class EngineSession(
        dir: File,
        val restoreFails: Boolean,
        val readError: ChaldeaIoException.Kind? = null,
    ) : ChaldeaSyncSession {
        private val fileAccess: ChaldeaFileAccess = if (readError == null) {
            JavaChaldeaFileAccess(dir)
        } else {
            val inner = JavaChaldeaFileAccess(dir)
            object : ChaldeaFileAccess by inner {
                override fun read(name: String): ByteArray {
                    if (name == ChaldeaSyncEngine.JSON) throw ChaldeaIoException(readError)
                    return inner.read(name)
                }
            }
        }
        val engine = ChaldeaSyncEngine(fileAccess, PACKAGE, { ChaldeaRunState.NotRunning })
        var restores = 0
        override suspend fun state() = ChaldeaStateResult(ChaldeaRunState.NotRunning, PACKAGE)
        override suspend fun forceStop() = state()
        override suspend fun prepare(sink: OutputStream): ChaldeaSyncSession.PrepareOutcome {
            val p = engine.prepare()
            val bundle = p.bundle
            if (bundle != null) {
                sink.write(bundle)
                sink.flush()
            }
            val received = bundle?.let { ChaldeaBackupReceiver.Received(it.size.toLong(), sha256Hex(it)) }
            return ChaldeaSyncSession.PrepareOutcome(p.result, received, received != null && ChaldeaBackupReceiver.matches(received, p.result))
        }

        override suspend fun commit(patch: ChaldeaPatch, expectedSha256: String, allowCreate: Boolean, expectedPackage: String, expectedBundleSha256: String) =
            engine.commit(patch.toJson(), expectedSha256, allowCreate, expectedBundleSha256)

        override suspend fun restore(bundle: File, sha256: String, packageName: String): ChaldeaSyncResult {
            restores++
            return if (restoreFails) ChaldeaSyncResult(ChaldeaSyncCode.RestoreFailed, packageName = packageName)
            else engine.restoreFromBackup(bundle.readBytes(), sha256)
        }

        override fun close() {}
    }

    private class World(val ts: TestScope, val tmp: File, val chaldea: File) {
        var restoreFails = false
        var readError: ChaldeaIoException.Kind? = null
        var packageOverride: String = PACKAGE
        var sessions = mutableListOf<EngineSession>()
        val loadedAccounts = mutableMapOf<String, ChaldeaAccountSource.Loaded>()
        val currentAccounts = mutableMapOf<ChaldeaServer, String>()
        val store = ChaldeaSyncStore(File(tmp, "sync"))
        val backups = ChaldeaBackupStore(File(tmp, "bk"))
        val env = object : ChaldeaSyncEnv {
            override fun enabled() = true
            override fun closeMinutes() = 30
            override fun access() = ChaldeaAccess(RootState.Unchecked, ShizukuState.Ready)
            override suspend fun openSession(): ChaldeaSyncSession = EngineSession(chaldea, restoreFails, readError).also { sessions += it }
        }
        val source = object : ChaldeaAccountSource {
            override fun load(userId: String): ChaldeaAccountSource.Loaded? = loadedAccounts[userId]
            override fun currentIds() = currentAccounts.toMap()
        }
        val sch = ChaldeaSyncScheduler(ts.backgroundScope, env, source, store, backups, clock = { ts.testScheduler.currentTime })

        fun json(name: String) = File(chaldea, name).readBytes()

        fun addPendingAccount(id: String = "a") {
            loadedAccounts[id] = ChaldeaAccountSource.Loaded(
                ChaldeaServer.Kr,
                AccountState(
                    mapOf(
                        "userGame" to """[{"friendCode":"111","name":"N","genderType":1}]""",
                        "userItem" to """[{"itemId":100,"num":1}]""",
                    ),
                    "[]",
                    null,
                    1L,
                    2L,
                ),
            )
            currentAccounts[ChaldeaServer.Kr] = id
            store.update { it.copy(pending = it.pending + id) }
        }

        /** What a write that died halfway leaves behind: the backup taken, the record persisted, the files as given. */
        fun interrupted(
            origJson: ByteArray,
            origBak: ByteArray?,
            nowJson: ByteArray?,
            nowBak: ByteArray?,
            pkg: String = packageOverride,
        ) {
            val bundle = ChaldeaBackupBundle.encode(origJson, origBak)
            val incoming = backups.incoming()
            incoming.writeBytes(bundle)
            val folder = backups.adopt(pkg, incoming, skipRotating = false, timeMs = 1)
            store.writeInflight(ChaldeaSyncStore.Inflight(pkg, folder.path, sha256Hex(origJson), bundle.size.toLong(), sha256Hex(bundle), 1))
            if (nowJson != null) File(chaldea, "userdata.json").writeBytes(nowJson)
            if (nowBak != null) File(chaldea, "userdata.json.bak").writeBytes(nowBak)
        }

        suspend fun run() {
            sch.onSettingsChanged()
            ts.runCurrent()
            ts.advanceTimeBy(5_000)
            ts.runCurrent()
        }
    }

    private fun world(block: suspend World.() -> Unit) = runTest { World(this, tmp, chaldea).block() }

    private val origJson = """{"version":4,"users":[],"marker":"original-json"}""".toByteArray()
    private val origBak = """{"version":4,"users":[],"marker":"original-bak-different"}""".toByteArray()
    private val newJson = """{"version":4,"users":[],"marker":"new-result"}""".toByteArray()

    @Test fun validJsonWithATruncatedBakIsAnInterruptedWriteAndBothFilesAreRestored() = world {
        interrupted(origJson, origBak, newJson, origBak.copyOf(7))
        run()
        assertContentEquals(origJson, json("userdata.json"), "userdata.json goes back to its original")
        assertContentEquals(origBak, json("userdata.json.bak"), ".bak goes back to its own original")
        assertNull(store.readInflight())
        assertFalse(backups.inflightDir(PACKAGE).exists(), "released only after the restore")
        assertEquals(ChaldeaSyncStatus.FailedRestored, sch.status.value)
    }

    @Test fun validJsonWithAnEmptyBakIsRestoredToo() = world {
        interrupted(origJson, origBak, newJson, ByteArray(0))
        run()
        assertContentEquals(origJson, json("userdata.json"))
        assertContentEquals(origBak, json("userdata.json.bak"))
        assertNull(store.readInflight())
    }

    @Test fun theBackupIsKeptWhenTheRestoreFails() = world {
        restoreFails = true
        interrupted(origJson, origBak, newJson, ByteArray(0))
        run()
        assertNotNull(store.readInflight(), "the record stays so that the restore is tried again")
        assertTrue(File(backups.inflightDir(PACKAGE), "userdata.json").isFile)
        assertTrue(File(backups.inflightDir(PACKAGE), "userdata.json.bak").isFile)
        assertIs<ChaldeaSyncStatus.RestoreFailed>(sch.status.value)
        assertContentEquals(newJson, json("userdata.json"), "nothing else was touched")
    }

    @Test fun aFailedRestoreUsesTheExistingRecoveryCounter() = world {
        restoreFails = true
        interrupted(origJson, origBak, newJson, ByteArray(0))
        run()
        repeat(2) {
            sch.onForeground()
            ts.runCurrent()
            ts.advanceTimeBy(5_000)
            ts.runCurrent()
        }
        val tries = sessions.sumOf { it.restores }
        assertEquals(3, tries)
        sch.onForeground()
        ts.runCurrent()
        ts.advanceTimeBy(5_000)
        ts.runCurrent()
        assertEquals(3, sessions.sumOf { it.restores }, "no fourth try")
    }

    @Test fun validJsonWithABakEqualToTheNewResultIsFinished() = world {
        interrupted(origJson, origBak, newJson, newJson)
        run()
        assertContentEquals(newJson, json("userdata.json"))
        assertContentEquals(newJson, json("userdata.json.bak"))
        assertEquals(0, sessions.sumOf { it.restores })
        assertNull(store.readInflight())
        assertFalse(backups.inflightDir(PACKAGE).exists())
    }

    @Test fun validJsonWithABakStillAtItsOriginalIsFinishedToo() = world {
        // The process died after userdata.json was written and before .bak was touched.
        interrupted(origJson, origBak, newJson, origBak)
        run()
        assertContentEquals(newJson, json("userdata.json"))
        assertContentEquals(origBak, json("userdata.json.bak"))
        assertEquals(0, sessions.sumOf { it.restores })
        assertNull(store.readInflight())
    }

    @Test fun aBackupWithoutBakDoesNotLookAtTheCurrentBak() = world {
        interrupted(origJson, null, newJson, "whatever".toByteArray())
        run()
        assertEquals(0, sessions.sumOf { it.restores })
        assertNull(store.readInflight())
    }

    @Test fun sameContentComparesBytesNotJustSizes() {
        val store = ChaldeaBackupStore(File(tmp, "bk2"))
        val a = File(tmp, "a").also { it.writeBytes(ByteArray(200_000) { i -> (i % 7).toByte() }) }
        val b = File(tmp, "b").also { it.writeBytes(a.readBytes()) }
        assertTrue(store.sameContent(a, b))
        b.writeBytes(a.readBytes().also { it[199_999] = 99 })
        assertFalse(store.sameContent(a, b))
        b.writeBytes(a.readBytes().copyOf(199_999))
        assertFalse(store.sameContent(a, b))
    }

    @Test fun cutJsonWithMissingBakIsRestoredAndBakStaysAbsent() = world {
        interrupted(origJson, origBak = null, nowJson = origJson.copyOf(10), nowBak = null)
        run()
        assertEquals(1, sessions.sumOf { it.restores })
        assertContentEquals(origJson, json("userdata.json"))
        assertFalse(File(chaldea, "userdata.json.bak").exists())
        assertNull(store.readInflight())
        assertFalse(backups.inflightDir(PACKAGE).exists())
        assertEquals(ChaldeaSyncStatus.FailedRestored, sch.status.value)
    }

    @Test fun cutJsonWithInvalidBakIsRestored() = world {
        interrupted(origJson, origBak, nowJson = origJson.copyOf(10), nowBak = "{broken".toByteArray())
        run()
        assertEquals(1, sessions.sumOf { it.restores })
        assertContentEquals(origJson, json("userdata.json"))
        assertContentEquals(origBak, json("userdata.json.bak"))
        assertNull(store.readInflight())
        assertFalse(backups.inflightDir(PACKAGE).exists())
        assertEquals(ChaldeaSyncStatus.FailedRestored, sch.status.value)
    }

    @Test fun invalidUtf8OrEmptyJsonWithMissingBakIsRestored() = world {
        for (broken in listOf(byteArrayOf(-1, -2, 0), ByteArray(0))) {
            File(chaldea, "userdata.json").delete()
            File(chaldea, "userdata.json.bak").delete()
            interrupted(origJson, origBak = null, nowJson = broken, nowBak = null)
            val s = ChaldeaSyncScheduler(ts.backgroundScope, env, source, store, backups, clock = { ts.testScheduler.currentTime })
            s.onSettingsChanged()
            ts.runCurrent()
            ts.advanceTimeBy(5_000)
            ts.runCurrent()
            assertContentEquals(origJson, json("userdata.json"))
            assertFalse(File(chaldea, "userdata.json.bak").exists())
            assertNull(store.readInflight())
            assertEquals(ChaldeaSyncStatus.FailedRestored, s.status.value)
        }
    }

    @Test fun missingJsonWithInvalidBakAttemptsRestoreWithoutCreatingMainFile() = world {
        interrupted(origJson, origBak, nowJson = null, nowBak = "{broken".toByteArray())
        run()
        assertEquals(1, sessions.sumOf { it.restores })
        // restoreFromBackup never creates a missing userdata.json (restoreNeverCreatesAMissingFile).
        assertFalse(File(chaldea, "userdata.json").exists())
        assertContentEquals(origBak, json("userdata.json.bak"))
        assertIs<ChaldeaSyncStatus.RestoreFailed>(sch.status.value)
        assertNotNull(store.readInflight())
    }

    @Test fun ioReadErrorDoesNotRestoreAndCountsAsRecoveryFailure() = world {
        readError = ChaldeaIoException.Kind.Unreadable
        val cut = origJson.copyOf(10)
        interrupted(origJson, origBak, nowJson = cut, nowBak = null)
        run()
        assertEquals(0, sessions.sumOf { it.restores })
        assertEquals(ChaldeaSyncStatus.RecoveryBlocked(ChaldeaSyncCode.Unreadable), sch.status.value)
        assertContentEquals(cut, json("userdata.json"))
        assertNotNull(store.readInflight())

        repeat(3) {
            sch.onForeground()
            ts.runCurrent()
            ts.advanceTimeBy(5_000)
            ts.runCurrent()
        }
        assertEquals(3, sessions.size)
        assertEquals(0, sessions.sumOf { it.restores })
    }

    @Test fun noAccessErrorDoesNotRestoreAndCountsAsRecoveryFailure() = world {
        readError = ChaldeaIoException.Kind.NoAccess
        val cut = origJson.copyOf(10)
        interrupted(origJson, origBak, nowJson = cut, nowBak = null)
        run()
        assertEquals(0, sessions.sumOf { it.restores })
        assertEquals(ChaldeaSyncStatus.RecoveryBlocked(ChaldeaSyncCode.NoAccess), sch.status.value)
        assertContentEquals(cut, json("userdata.json"))
        assertNotNull(store.readInflight())
    }

    @Test fun validJsonWrongShapeMissingUsersKeepsRefusalAndRestoresInRecovery() = world {
        val missingUsers = """{"version":4}""".toByteArray()
        File(chaldea, "userdata.json").writeBytes(missingUsers)
        File(chaldea, "userdata.json.bak").writeBytes(origBak)
        assertEquals(
            ChaldeaSyncCode.MissingUsers,
            ChaldeaSyncEngine(JavaChaldeaFileAccess(chaldea), PACKAGE, { ChaldeaRunState.NotRunning }).prepare().result.code,
        )

        interrupted(origJson, origBak, nowJson = missingUsers, nowBak = origBak)
        run()
        assertEquals(1, sessions.sumOf { it.restores })
        assertContentEquals(origJson, json("userdata.json"))
        assertContentEquals(origBak, json("userdata.json.bak"))
        assertNull(store.readInflight())
    }

    @Test fun normalSyncWithBrokenJsonAndMissingOrInvalidBakReportsFileUnreadable() = world {
        addPendingAccount("a")
        File(chaldea, "userdata.json").writeBytes(origJson.copyOf(10))
        run()
        assertEquals(1, sessions.size)
        assertEquals(ChaldeaSyncStatus.FileUnreadable, sch.serverResults.value[ChaldeaServer.Kr])
        assertContentEquals(origJson.copyOf(10), json("userdata.json"))

        File(chaldea, "userdata.json.bak").writeBytes("{broken".toByteArray())
        addPendingAccount("b")
        sch.onAccountSaved(listOf("b"))
        ts.runCurrent()
        ts.advanceTimeBy(65_000)
        ts.runCurrent()
        assertEquals(2, sessions.size)
        assertEquals(ChaldeaSyncStatus.FileUnreadable, sch.serverResults.value[ChaldeaServer.Kr])
    }

    @Test fun recoveryKeepsPackageAndBundleChecksForInvalidJson() = world {
        val otherPkg = "cc.narumi.chaldea.fdroid"
        val cut = origJson.copyOf(10)
        interrupted(origJson, origBak = null, nowJson = cut, nowBak = null, pkg = otherPkg)
        run()
        assertEquals(0, sessions.sumOf { it.restores })
        assertEquals(ChaldeaSyncStatus.RecoveryBlocked(ChaldeaSyncCode.FileChanged), sch.status.value)
        assertContentEquals(cut, json("userdata.json"))
        assertNotNull(store.readInflight())

        // Corrupt the stored bundle hash so bundle integrity check rejects restore.
        val prev = store.readInflight()!!
        store.writeInflight(prev.copy(packageName = PACKAGE, bundleSha256 = "00"))
        File(backups.inflightDir(PACKAGE), "userdata.json").apply {
            parentFile!!.mkdirs()
            writeBytes(origJson)
        }
        val s2 = ChaldeaSyncScheduler(ts.backgroundScope, env, source, store, backups, clock = { ts.testScheduler.currentTime })
        s2.onSettingsChanged()
        ts.runCurrent()
        ts.advanceTimeBy(5_000)
        ts.runCurrent()
        assertEquals(0, sessions.sumOf { it.restores })
        assertIs<ChaldeaSyncStatus.RestoreFailed>(s2.status.value)
        assertContentEquals(cut, json("userdata.json"))
    }
}
