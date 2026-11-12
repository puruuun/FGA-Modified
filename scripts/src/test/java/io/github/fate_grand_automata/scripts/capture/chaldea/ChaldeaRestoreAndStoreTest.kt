package io.github.fate_grand_automata.scripts.capture.chaldea

import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Restore op, persisted store and result wire format. Synthetic data only. */
class ChaldeaRestoreAndStoreTest {
    private lateinit var dir: File

    @BeforeTest fun setUp() {
        dir = Files.createTempDirectory("chaldea-restore").toFile()
    }

    @AfterTest fun tearDown() {
        dir.deleteRecursively()
    }

    private fun engine(state: ChaldeaRunState = ChaldeaRunState.NotRunning) =
        ChaldeaSyncEngine(JavaChaldeaFileAccess(dir), ChaldeaPackages.PLAY, { state })

    private val original = """{"version":4,"users":[]}""".toByteArray()

    @Test fun restoreWritesTheBackupIntoBothExistingFiles() {
        File(dir, "userdata.json").writeText("broken")
        File(dir, "userdata.json.bak").writeText("also broken")
        val r = engine().restoreFromBackup(original, sha256Hex(original))
        assertEquals(ChaldeaSyncCode.Ok, r.code)
        assertContentEquals(original, File(dir, "userdata.json").readBytes())
        assertContentEquals(original, File(dir, "userdata.json.bak").readBytes())
    }

    @Test fun restoreRefusesWrongBytesAndRunningChaldea() {
        File(dir, "userdata.json").writeText("broken")
        assertEquals(ChaldeaSyncCode.FileChanged, engine().restoreFromBackup(original, "00").code)
        assertEquals("broken", File(dir, "userdata.json").readText())
        assertEquals(ChaldeaSyncCode.ChaldeaRunning, engine(ChaldeaRunState.Background).restoreFromBackup(original, sha256Hex(original)).code)
        assertEquals(ChaldeaSyncCode.StateUnknown, engine(ChaldeaRunState.Unknown).restoreFromBackup(original, sha256Hex(original)).code)
    }

    @Test fun restoreNeverCreatesAMissingFile() {
        assertEquals(ChaldeaSyncCode.NotFound, engine().restoreFromBackup(original, sha256Hex(original)).code)
        assertFalse(File(dir, "userdata.json").exists())
        File(dir, "userdata.json").writeText("x")
        engine().restoreFromBackup(original, sha256Hex(original))
        assertFalse(File(dir, "userdata.json.bak").exists())
    }

    @Test fun storeRoundTripsAndSurvivesGarbage() {
        val store = ChaldeaSyncStore(File(dir, "s"))
        assertEquals(ChaldeaSyncStore.Data(), store.read())
        store.update { it.copy(pending = setOf("a", "b"), backgroundSince = 5, lastObservation = 7, syncedPatchHash = mapOf("a" to "h"), lastWritten = mapOf("p" to "w")) }
        val d = store.read()
        assertEquals(setOf("a", "b"), d.pending)
        assertEquals(5L, d.backgroundSince)
        assertEquals(7L, d.lastObservation)
        assertEquals("h", d.syncedPatchHash["a"])
        assertEquals("w", d.lastWritten["p"])
        File(dir, "s/state.json").writeText("not json")
        assertEquals(ChaldeaSyncStore.Data(), store.read())
        val i = ChaldeaSyncStore.Inflight("pkg", "/x", "sha", 3)
        store.writeInflight(i)
        assertEquals(i, store.readInflight())
        store.clearInflight()
        assertNull(store.readInflight())
    }

    @Test fun resultCarriesBothPackagesFlag() {
        val r = ChaldeaSyncResult(ChaldeaSyncCode.Ok, bothPackages = true)
        assertTrue(ChaldeaSyncResult.fromJson(r.toJson()).bothPackages)
        assertFalse(ChaldeaSyncResult.fromJson(ChaldeaSyncResult(ChaldeaSyncCode.Ok).toJson()).bothPackages)
    }
}
