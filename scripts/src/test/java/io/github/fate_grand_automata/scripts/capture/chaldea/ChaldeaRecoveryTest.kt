package io.github.fate_grand_automata.scripts.capture.chaldea

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

    private class EngineSession(dir: File, val restoreFails: Boolean) : ChaldeaSyncSession {
        val engine = ChaldeaSyncEngine(JavaChaldeaFileAccess(dir), PACKAGE, { ChaldeaRunState.NotRunning })
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
        var sessions = mutableListOf<EngineSession>()
        val store = ChaldeaSyncStore(File(tmp, "sync"))
        val backups = ChaldeaBackupStore(File(tmp, "bk"))
        val env = object : ChaldeaSyncEnv {
            override fun enabled() = true
            override fun closeMinutes() = 30
            override fun access() = ChaldeaAccess(RootState.Unchecked, ShizukuState.Ready)
            override suspend fun openSession(): ChaldeaSyncSession = EngineSession(chaldea, restoreFails).also { sessions += it }
        }
        val source = object : ChaldeaAccountSource {
            override fun load(userId: String): ChaldeaAccountSource.Loaded? = null
            override fun currentIds() = emptyMap<ChaldeaServer, String>()
        }
        val sch = ChaldeaSyncScheduler(ts.backgroundScope, env, source, store, backups, clock = { ts.testScheduler.currentTime })

        fun json(name: String) = File(chaldea, name).readBytes()

        /** What a write that died halfway leaves behind: the backup taken, the record persisted, the files as given. */
        fun interrupted(origJson: ByteArray, origBak: ByteArray?, nowJson: ByteArray, nowBak: ByteArray?) {
            val bundle = ChaldeaBackupBundle.encode(origJson, origBak)
            val incoming = backups.incoming()
            incoming.writeBytes(bundle)
            val folder = backups.adopt(PACKAGE, incoming, skipRotating = false, timeMs = 1)
            store.writeInflight(ChaldeaSyncStore.Inflight(PACKAGE, folder.path, sha256Hex(origJson), bundle.size.toLong(), sha256Hex(bundle), 1))
            File(chaldea, "userdata.json").writeBytes(nowJson)
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
}
