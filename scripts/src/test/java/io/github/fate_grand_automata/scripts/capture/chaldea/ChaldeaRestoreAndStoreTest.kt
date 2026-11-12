package io.github.fate_grand_automata.scripts.capture.chaldea

import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Backup bundle, restore op, persisted store, backup folders and the Shizuku permission policy. Synthetic data only. */
class ChaldeaRestoreAndStoreTest {
    private lateinit var dir: File

    @BeforeTest fun setUp() {
        dir = Files.createTempDirectory("chaldea-restore").toFile()
    }

    @AfterTest fun tearDown() {
        dir.deleteRecursively()
    }

    private class Recording(private val inner: ChaldeaFileAccess) : ChaldeaFileAccess by inner {
        val writes = ArrayList<String>()
        override fun overwrite(name: String, bytes: ByteArray) {
            writes += name
            inner.overwrite(name, bytes)
        }
    }

    private fun engine(state: ChaldeaRunState = ChaldeaRunState.NotRunning, access: ChaldeaFileAccess = JavaChaldeaFileAccess(dir)) =
        ChaldeaSyncEngine(access, ChaldeaPackages.PLAY, { state })

    private val origJson = """{"version":4,"users":[],"a":1}""".toByteArray()
    private val origBak = """{"version":4,"users":[],"a":2,"older":true}""".toByteArray()
    private fun bundle(json: ByteArray? = origJson, bak: ByteArray? = origBak) = ChaldeaBackupBundle.encode(json, bak)

    // ---- bundle ----

    @Test fun bundleRoundTripsBothFilesAndAbsentOnes() {
        for ((j, b) in listOf(origJson to origBak, origJson to null, null to origBak, null to null)) {
            val (dj, db) = ChaldeaBackupBundle.decode(bundle(j, b))!!
            assertContentEquals(j, dj)
            assertContentEquals(b, db)
        }
        assertNull(ChaldeaBackupBundle.decode("not a bundle".toByteArray()))
        assertNull(ChaldeaBackupBundle.decode(bundle() + byteArrayOf(1)))
        assertNull(ChaldeaBackupBundle.decode(bundle().copyOf(10)))
    }

    @Test fun splitAndJoinKeepEachFileSeparate() {
        val out = File(dir, "out")
        assertTrue(ChaldeaBackupBundle.split(bundle().inputStream(), out))
        assertContentEquals(origJson, File(out, "userdata.json").readBytes())
        assertContentEquals(origBak, File(out, "userdata.json.bak").readBytes())
        val joined = java.io.ByteArrayOutputStream().also { ChaldeaBackupBundle.join(out, it) }.toByteArray()
        assertContentEquals(bundle(), joined)
        assertFalse(ChaldeaBackupBundle.split("junk".toByteArray().inputStream(), File(dir, "x")))
    }

    @Test fun prepareStreamsBothFilesAsABundleWithItsOwnHash() {
        File(dir, "userdata.json").writeBytes(origJson)
        File(dir, "userdata.json.bak").writeBytes(origBak)
        val prep = engine().prepare()
        val (json, bak) = ChaldeaBackupBundle.decode(prep.bundle!!)!!
        assertContentEquals(origJson, json)
        assertContentEquals(origBak, bak)
        assertEquals(sha256Hex(prep.bundle!!), prep.result.bundleSha256)
        assertEquals(prep.bundle!!.size.toLong(), prep.result.bundleSize)
        assertEquals(sha256Hex(origJson), prep.result.sha256, "the base hash is still the edited file's")
        assertTrue(ChaldeaBackupReceiver.matches(ChaldeaBackupReceiver.Received(prep.bundle!!.size.toLong(), sha256Hex(prep.bundle!!)), prep.result))
        assertFalse(ChaldeaBackupReceiver.matches(ChaldeaBackupReceiver.Received(prep.bundle!!.size.toLong(), "00"), prep.result))
    }

    @Test fun theSizeLimitIsSixteenMegabytes() {
        assertEquals(16L * 1024 * 1024, ChaldeaUserdataEditor.MAX_FILE_BYTES)
    }

    @Test fun splitRejectsAWrongMagicEvenWhenTheRestIsWellFormed() {
        val good = bundle()
        val bad = good.copyOf().also { it[0] = 'X'.code.toByte() }
        assertTrue(ChaldeaBackupBundle.split(good.inputStream(), File(dir, "ok")))
        assertFalse(ChaldeaBackupBundle.split(bad.inputStream(), File(dir, "bad")))
        assertFalse(File(dir, "bad/userdata.json").exists(), "nothing is written for a wrong magic")
        assertNull(ChaldeaBackupBundle.decode(bad))
    }

    // ---- commit re-checks both files (review S-1) ----

    private fun userJson() = """{"id":"u1","name":"N","region":"kr","lastImportId":"123456789","items":{"100":1}}"""
    private fun fileText() = """{"version":4,"curUserKey":1,"users":[${userJson()}]}"""
    private val patch = ChaldeaPatch("123456789", ChaldeaServer.Kr, "Nick", null, mapOf(100L to 5L), null, null)

    @Test fun commitAbortsWhenTheOtherFileChangedAfterPrepare() {
        File(dir, "userdata.json").writeText(fileText())
        File(dir, "userdata.json.bak").writeText("old bak")
        val e = engine()
        val prep = e.prepare()
        File(dir, "userdata.json.bak").writeText("changed by someone else")
        val r = e.commit(patch.toJson(), prep.result.sha256!!, true, prep.result.bundleSha256)
        assertEquals(ChaldeaSyncCode.FileChanged, r.code)
        assertEquals("changed by someone else", File(dir, "userdata.json.bak").readText(), "nothing was written")
        assertEquals(fileText(), File(dir, "userdata.json").readText())
    }

    @Test fun commitWritesWhenBothFilesAreAsPrepareSawThem() {
        File(dir, "userdata.json").writeText(fileText())
        File(dir, "userdata.json.bak").writeText("old bak")
        val e = engine()
        val prep = e.prepare()
        assertEquals(ChaldeaSyncCode.Ok, e.commit(patch.toJson(), prep.result.sha256!!, true, prep.result.bundleSha256).code)
        assertTrue(File(dir, "userdata.json").readText().contains(""""100":5"""))
    }

    // ---- restore ----

    @Test fun restoreGivesEachFileItsOwnOriginalAndLeavesTheIntactOneAlone() {
        // A crash cut userdata.json short; userdata.json.bak still holds its own, different original.
        File(dir, "userdata.json").writeText("""{"version":4,"us""")
        File(dir, "userdata.json.bak").writeBytes(origBak)
        val access = Recording(JavaChaldeaFileAccess(dir))
        val b = bundle()
        val r = engine(access = access).restoreFromBackup(b, sha256Hex(b))
        assertEquals(ChaldeaSyncCode.Ok, r.code)
        assertContentEquals(origJson, File(dir, "userdata.json").readBytes())
        assertContentEquals(origBak, File(dir, "userdata.json.bak").readBytes(), ".bak must not become a copy of userdata.json")
        assertEquals(listOf("userdata.json"), access.writes, "the untouched file is not written")
    }

    @Test fun restoreFixesBothWhenBothWereDamaged() {
        File(dir, "userdata.json").writeText("broken")
        File(dir, "userdata.json.bak").writeText("also broken")
        val b = bundle()
        assertEquals(ChaldeaSyncCode.Ok, engine().restoreFromBackup(b, sha256Hex(b)).code)
        assertContentEquals(origJson, File(dir, "userdata.json").readBytes())
        assertContentEquals(origBak, File(dir, "userdata.json.bak").readBytes())
    }

    @Test fun restoreLeavesAFileAloneThatDidNotExistBefore() {
        File(dir, "userdata.json").writeText("broken")
        File(dir, "userdata.json.bak").writeText("created later by something")
        val b = bundle(origJson, null)
        assertEquals(ChaldeaSyncCode.Ok, engine().restoreFromBackup(b, sha256Hex(b)).code)
        assertEquals("created later by something", File(dir, "userdata.json.bak").readText())
    }

    @Test fun restoreRefusesWrongBytesAndRunningChaldea() {
        File(dir, "userdata.json").writeText("broken")
        val b = bundle()
        assertEquals(ChaldeaSyncCode.FileChanged, engine().restoreFromBackup(b, "00").code)
        assertEquals("broken", File(dir, "userdata.json").readText())
        assertEquals(ChaldeaSyncCode.ChaldeaRunning, engine(ChaldeaRunState.Background).restoreFromBackup(b, sha256Hex(b)).code)
        assertEquals(ChaldeaSyncCode.StateUnknown, engine(ChaldeaRunState.Unknown).restoreFromBackup(b, sha256Hex(b)).code)
        val junk = "junk".toByteArray()
        assertEquals(ChaldeaSyncCode.BadPatch, engine().restoreFromBackup(junk, sha256Hex(junk)).code)
    }

    @Test fun restoreNeverCreatesAMissingFile() {
        val b = bundle()
        assertEquals(ChaldeaSyncCode.RestoreFailed, engine().restoreFromBackup(b, sha256Hex(b)).code)
        assertFalse(File(dir, "userdata.json").exists())
        File(dir, "userdata.json").writeText("x")
        engine().restoreFromBackup(b, sha256Hex(b))
        assertContentEquals(origJson, File(dir, "userdata.json").readBytes())
        assertEquals(false, File(dir, "userdata.json.bak").exists(), "a missing .bak is not created either")
    }

    // ---- backup folders ----

    @Test fun adoptKeepsBothFilesInTheFirstOriginalAndTheRotatingCopies() {
        val store = ChaldeaBackupStore(File(dir, "bk"))
        for (n in 1..5) {
            val incoming = store.incoming()
            incoming.writeBytes(bundle("json-$n".toByteArray(), "bak-$n".toByteArray()))
            store.adopt("pkg", incoming, skipRotating = false, timeMs = n.toLong())
            store.release("pkg")
        }
        assertEquals("json-1", File(store.firstOriginal("pkg"), "userdata.json").readText())
        assertEquals("bak-1", File(store.firstOriginal("pkg"), "userdata.json.bak").readText())
        assertEquals(listOf("json-5", "json-4", "json-3"), store.rotating("pkg").map { File(it, "userdata.json").readText() })
        assertEquals(listOf("bak-5", "bak-4", "bak-3"), store.rotating("pkg").map { File(it, "userdata.json.bak").readText() })
        assertFalse(store.inflightDir("pkg").exists())
    }

    @Test fun adoptRefusesAMalformedBundleAndKeepsTheOlderBackups() {
        val store = ChaldeaBackupStore(File(dir, "bk"))
        val good = store.incoming()
        good.writeBytes(bundle())
        store.adopt("pkg", good, false, 1)
        val bad = store.incoming()
        bad.writeText("junk")
        assertFailsWith<java.io.IOException> { store.adopt("pkg", bad, false, 2) }
        assertEquals(1, store.rotating("pkg").size)
        assertTrue(store.firstOriginal("pkg").isDirectory)
    }

    @Test fun bundleOfRebuildsTheBundleForTheService() {
        val store = ChaldeaBackupStore(File(dir, "bk"))
        val incoming = store.incoming()
        val b = bundle()
        incoming.writeBytes(b)
        val folder = store.adopt("pkg", incoming, false, 1)
        val scratch = store.restoreScratch()
        val rec = store.bundleOf(folder, scratch)
        assertContentEquals(b, scratch.readBytes())
        assertEquals(sha256Hex(b), rec.sha256)
        assertEquals(b.size.toLong(), rec.size)
    }

    // ---- store ----

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
        val i = ChaldeaSyncStore.Inflight("pkg", "/x", "sha", 10, "bsha", 3)
        store.writeInflight(i)
        assertEquals(i, store.readInflight())
        store.clearInflight()
        assertNull(store.readInflight())
    }

    @Test fun resultCarriesBothPackagesAndBundleFields() {
        val r = ChaldeaSyncResult(ChaldeaSyncCode.Ok, bothPackages = true, bundleSize = 9, bundleSha256 = "x")
        val back = ChaldeaSyncResult.fromJson(r.toJson())
        assertTrue(back.bothPackages)
        assertEquals(9L, back.bundleSize)
        assertEquals("x", back.bundleSha256)
        assertFalse(ChaldeaSyncResult.fromJson(ChaldeaSyncResult(ChaldeaSyncCode.Ok).toJson()).bothPackages)
    }

    // ---- Shizuku permission policy (review B1) ----

    @Test fun permissionIsRequestedWhenTheSwitchIsTurnedOnAndShizukuNeedsIt() {
        val p = ShizukuPermissionRequester()
        assertFalse(p.shouldRequest(ShizukuState.NeedsPermission), "not asked for: the user did not turn the switch on")
        p.arm()
        assertTrue(p.shouldRequest(ShizukuState.NeedsPermission))
        assertFalse(p.shouldRequest(ShizukuState.NeedsPermission), "no second dialog while one is open")
    }

    @Test fun permissionWaitsForShizukuToStartThenAsksOnce() {
        val p = ShizukuPermissionRequester()
        p.arm()
        assertFalse(p.shouldRequest(ShizukuState.NotRunning))
        assertFalse(p.shouldRequest(ShizukuState.NotInstalled))
        assertTrue(p.shouldRequest(ShizukuState.NeedsPermission))
    }

    @Test fun permissionIsForgottenOnceDecidedAndOnSwitchOff() {
        val p = ShizukuPermissionRequester()
        p.arm()
        assertFalse(p.shouldRequest(ShizukuState.Ready))
        assertFalse(p.shouldRequest(ShizukuState.NeedsPermission), "granted before: the wish is gone")
        p.arm()
        p.disarm()
        assertFalse(p.shouldRequest(ShizukuState.NeedsPermission))
        p.arm()
        assertFalse(p.shouldRequest(ShizukuState.Denied))
        assertFalse(p.shouldRequest(ShizukuState.NeedsPermission), "denied: no repeated nagging")
    }

    @Test fun permissionIsAskedAgainOnlyOnceMoreWhenNoResultCameBack() {
        val p = ShizukuPermissionRequester()
        p.arm()
        assertTrue(p.shouldRequest(ShizukuState.NeedsPermission))
        p.released() // the screen was resumed and no result ever arrived
        assertTrue(p.shouldRequest(ShizukuState.NeedsPermission))
        p.released()
        assertFalse(p.shouldRequest(ShizukuState.NeedsPermission), "at most two requests per switch-on")
    }
}
