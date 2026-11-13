package io.github.fate_grand_automata.scripts.capture.chaldea

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Runs the write sequence against a real temp folder. Entirely synthetic data. */
class ChaldeaSyncEngineTest {
    private lateinit var dir: File

    @BeforeTest fun setUp() {
        dir = Files.createTempDirectory("chaldea-engine").toFile()
    }

    @AfterTest fun tearDown() {
        dir.deleteRecursively()
    }

    private val code = "123456789"
    private fun userJson(items: String = """{"100":1}""") =
        """{"id":"u1","name":"N","region":"kr","lastImportId":"$code","items":$items}"""

    private fun fileText(users: String = "[${userJson()}]") = """{"version":4,"curUserKey":1,"users":$users}"""
    private fun patch(items: Map<Long, Long> = mapOf(100L to 5L, 200L to 7L), friend: String? = code) =
        ChaldeaPatch(friend, ChaldeaServer.Kr, "Nick", null, items, null, null)

    private fun put(name: String, text: String) = File(dir, name).writeBytes(text.toByteArray())
    private fun put(name: String, bytes: ByteArray) = File(dir, name).writeBytes(bytes)
    private fun get(name: String) = File(dir, name).readBytes()

    private class Flaky(
        private val inner: ChaldeaFileAccess,
        val failWrite: (name: String, nth: Int) -> Boolean = { _, _ -> false },
        val corruptWrite: (name: String, nth: Int) -> Boolean = { _, _ -> false },
    ) : ChaldeaFileAccess by inner {
        val writes = ArrayList<String>()
        private val counts = HashMap<String, Int>()
        override fun overwrite(name: String, bytes: ByteArray) {
            writes += name
            val nth = counts.merge(name, 1, Int::plus)!!
            if (failWrite(name, nth)) throw ChaldeaIoException(ChaldeaIoException.Kind.WriteFailed)
            inner.overwrite(name, if (corruptWrite(name, nth)) bytes.copyOf(bytes.size / 2) else bytes)
        }
    }

    private fun engine(access: ChaldeaFileAccess = JavaChaldeaFileAccess(dir), state: () -> ChaldeaRunState = { ChaldeaRunState.NotRunning }) =
        ChaldeaSyncEngine(access, ChaldeaPackages.PLAY, state)

    private fun commitAfterPrepare(e: ChaldeaSyncEngine, p: ChaldeaPatch = patch(), allowCreate: Boolean = true): ChaldeaSyncResult {
        val prep = e.prepare()
        assertEquals(ChaldeaSyncCode.Ok, prep.result.code)
        return e.commit(p.toJson(), prep.result.sha256!!, allowCreate)
    }

    @Test fun successWritesBothFilesIdenticallyAndReportsCounts() {
        put("userdata.json", fileText()); put("userdata.json.bak", fileText("[${userJson("""{"100":0}""")}]"))
        val r = commitAfterPrepare(engine())
        assertEquals(ChaldeaSyncCode.Ok, r.code)
        assertEquals(2, r.itemCount)
        assertTrue(r.bakWritten)
        assertContentEquals(get("userdata.json"), get("userdata.json.bak"))
        assertEquals(r.sha256, sha256Hex(get("userdata.json")))
        assertTrue(String(get("userdata.json")).contains(""""200":7"""))
        assertEquals(ChaldeaBaseSource.UserdataJson, r.base)
    }

    @Test fun prepareReturnsBaseBytesSizeAndHash() {
        val text = fileText(); put("userdata.json", text); put("userdata.json.bak", "old")
        val prep = engine().prepare()
        assertEquals(text.toByteArray().size.toLong(), prep.result.size)
        assertEquals(sha256Hex(text.toByteArray()), prep.result.sha256)
        assertContentEquals(text.toByteArray(), prep.baseBytes)
        assertEquals(ChaldeaPackages.PLAY, prep.result.packageName)
    }

    @Test fun prepareWritesNothing() {
        put("userdata.json", fileText())
        val flaky = Flaky(JavaChaldeaFileAccess(dir))
        engine(flaky).prepare()
        assertTrue(flaky.writes.isEmpty())
    }

    @Test fun runningChaldeaIsRefusedByPrepareAndCommit() {
        put("userdata.json", fileText()); put("userdata.json.bak", fileText())
        val before = get("userdata.json")
        for (s in listOf(ChaldeaRunState.Visible, ChaldeaRunState.Background)) {
            assertEquals(ChaldeaSyncCode.ChaldeaRunning, engine(state = { s }).prepare().result.code)
            assertEquals(ChaldeaSyncCode.ChaldeaRunning, engine(state = { s }).commit(patch().toJson(), "x", true).code)
        }
        assertEquals(ChaldeaSyncCode.StateUnknown, engine(state = { ChaldeaRunState.Unknown }).prepare().result.code)
        assertContentEquals(before, get("userdata.json"))
    }

    @Test fun chaldeaStartingBetweenPrepareAndCommitAbortsWithoutWriting() {
        put("userdata.json", fileText())
        var state = ChaldeaRunState.NotRunning
        val flaky = Flaky(JavaChaldeaFileAccess(dir))
        val e = engine(flaky) { state }
        val prep = e.prepare()
        state = ChaldeaRunState.Visible
        assertEquals(ChaldeaSyncCode.ChaldeaRunning, e.commit(patch().toJson(), prep.result.sha256!!, true).code)
        assertTrue(flaky.writes.isEmpty())
    }

    @Test fun hashMismatchAtCommitIsFileChangedAndNothingIsWritten() {
        put("userdata.json", fileText()); put("userdata.json.bak", fileText())
        val flaky = Flaky(JavaChaldeaFileAccess(dir))
        val e = engine(flaky)
        val prep = e.prepare()
        put("userdata.json", fileText("[${userJson("""{"100":99}""")}]"))
        val changed = get("userdata.json")
        assertEquals(ChaldeaSyncCode.FileChanged, e.commit(patch().toJson(), prep.result.sha256!!, true).code)
        assertTrue(flaky.writes.isEmpty())
        assertContentEquals(changed, get("userdata.json"))
    }

    @Test fun prepareFailureLeavesFilesAlone() {
        val flaky = Flaky(JavaChaldeaFileAccess(dir))
        assertEquals(ChaldeaSyncCode.NotFound, engine(flaky).prepare().result.code)
        put("userdata.json", "not json"); put("userdata.json.bak", "also not")
        assertEquals(ChaldeaSyncCode.Unreadable, engine(flaky).prepare().result.code)
        assertTrue(flaky.writes.isEmpty())
        assertEquals("not json", String(get("userdata.json")))
    }

    @Test fun bakWriteFailureRestoresBothFilesToTheirOwnOriginals() {
        val origJson = fileText(); val origBak = fileText("[${userJson("""{"100":42}""")}]")
        put("userdata.json", origJson); put("userdata.json.bak", origBak)
        val flaky = Flaky(JavaChaldeaFileAccess(dir), failWrite = { n, nth -> n == "userdata.json.bak" && nth == 1 })
        val r = commitAfterPrepare(engine(flaky))
        assertEquals(ChaldeaSyncCode.Restored, r.code)
        assertEquals(ChaldeaSyncCode.Unreadable, r.cause)
        assertEquals(origJson, String(get("userdata.json")))
        assertEquals(origBak, String(get("userdata.json.bak")))
    }

    @Test fun failedRestoreIsReported() {
        put("userdata.json", fileText()); put("userdata.json.bak", fileText())
        // The bak write fails and so does every later write to userdata.json (the restore).
        val flaky = Flaky(JavaChaldeaFileAccess(dir), failWrite = { n, nth ->
            (n == "userdata.json.bak" && nth == 1) || (n == "userdata.json" && nth >= 2)
        })
        val r = commitAfterPrepare(engine(flaky))
        assertEquals(ChaldeaSyncCode.RestoreFailed, r.code)
    }

    @Test fun verifyMismatchAfterWriteRestores() {
        val orig = fileText()
        put("userdata.json", orig); put("userdata.json.bak", orig)
        val flaky = Flaky(JavaChaldeaFileAccess(dir), corruptWrite = { n, nth -> n == "userdata.json" && nth == 1 })
        val r = commitAfterPrepare(engine(flaky))
        assertEquals(ChaldeaSyncCode.Restored, r.code)
        assertEquals(ChaldeaSyncCode.VerifyFailed, r.cause)
        assertEquals(orig, String(get("userdata.json")))
        assertEquals(orig, String(get("userdata.json.bak")))
        assertEquals(listOf("userdata.json", "userdata.json"), flaky.writes) // bak never touched
    }

    @Test fun truncatedJsonWithValidBakUsesBakAndWritesBoth() {
        val good = fileText()
        put("userdata.json", good.take(good.length / 2)); put("userdata.json.bak", good)
        val prep = engine().prepare()
        assertEquals(ChaldeaBaseSource.Bak, prep.result.base)
        assertContentEquals(good.toByteArray(), prep.baseBytes)
        val r = engine().commit(patch().toJson(), prep.result.sha256!!, true)
        assertEquals(ChaldeaSyncCode.Ok, r.code)
        assertEquals(ChaldeaBaseSource.Bak, r.base)
        assertContentEquals(get("userdata.json"), get("userdata.json.bak"))
        assertTrue(String(get("userdata.json")).contains(""""200":7"""))
    }

    @Test fun restoreAfterBakBaseGivesEachFileItsOwnBytes() {
        val good = fileText(); val broken = good.take(10)
        put("userdata.json", broken); put("userdata.json.bak", good)
        val flaky = Flaky(JavaChaldeaFileAccess(dir), failWrite = { n, nth -> n == "userdata.json.bak" && nth == 1 })
        val r = commitAfterPrepare(engine(flaky))
        assertEquals(ChaldeaSyncCode.Restored, r.code)
        assertEquals(broken, String(get("userdata.json")))
        assertEquals(good, String(get("userdata.json.bak")))
    }

    @Test fun bothInvalidIsRefusedAndNothingIsWritten() {
        put("userdata.json", "{broken"); put("userdata.json.bak", """{"version":3,"users":[]}""")
        val flaky = Flaky(JavaChaldeaFileAccess(dir))
        val prep = engine(flaky).prepare()
        assertEquals(ChaldeaSyncCode.Unreadable, prep.result.code)
        assertNull(prep.baseBytes)
        assertEquals(ChaldeaSyncCode.Unreadable, engine(flaky).commit(patch().toJson(), "x", true).code)
        assertTrue(flaky.writes.isEmpty())
    }

    @Test fun oversizeIsRefused() {
        put("userdata.json", fileText())
        val big = object : ChaldeaFileAccess by JavaChaldeaFileAccess(dir) {
            override fun length(name: String) = if (name == "userdata.json") ChaldeaUserdataEditor.MAX_FILE_BYTES + 1 else 1L
        }
        assertEquals(ChaldeaSyncCode.TooLarge, engine(big).prepare().result.code)
    }

    @Test fun missingMainFileWithValidBakIsNotWrittenBecauseItWouldHaveToBeCreated() {
        put("userdata.json.bak", fileText())
        val e = engine()
        val prep = e.prepare()
        assertEquals(ChaldeaSyncCode.Ok, prep.result.code)
        assertEquals(ChaldeaSyncCode.NotFound, e.commit(patch().toJson(), prep.result.sha256!!, true).code)
        assertFalse(File(dir, "userdata.json").exists())
    }

    @Test fun missingBakIsNotCreated() {
        put("userdata.json", fileText())
        val r = commitAfterPrepare(engine())
        assertEquals(ChaldeaSyncCode.Ok, r.code)
        assertFalse(r.bakWritten)
        assertFalse(File(dir, "userdata.json.bak").exists())
    }

    @Test fun editorRefusalsBecomeCodesAndNothingIsWritten() {
        put("userdata.json", fileText()); put("userdata.json.bak", fileText())
        val flaky = Flaky(JavaChaldeaFileAccess(dir))
        val e = engine(flaky)
        val sha = e.prepare().result.sha256!!
        assertEquals(ChaldeaSyncCode.AccountNotIdentified, e.commit(patch(friend = " ").toJson(), sha, true).code)
        assertEquals(ChaldeaSyncCode.NoMatchingUser, e.commit(patch(friend = "other").toJson(), sha, false).code)
        assertEquals(ChaldeaSyncCode.BadPatch, e.commit("not a patch", sha, true).code)
        assertTrue(flaky.writes.isEmpty())
        put("userdata.json", """{"version":5,"users":[]}""")
        assertEquals(ChaldeaSyncCode.UnknownVersion, e.prepare().result.code)
    }

    @Test fun createdUserIsReported() {
        put("userdata.json", fileText()); put("userdata.json.bak", fileText())
        val r = commitAfterPrepare(engine(), patch(friend = "555"), allowCreate = true)
        assertTrue(r.created)
    }

    @Test fun lockIsHeldDuringCommitSoDestroyWaits() {
        put("userdata.json", fileText())
        var heldDuringWrite = false
        var holder: ChaldeaSyncEngine? = null
        val spy = object : ChaldeaFileAccess by JavaChaldeaFileAccess(dir) {
            override fun overwrite(name: String, bytes: ByteArray) {
                heldDuringWrite = heldDuringWrite || holder!!.lock.isHeldByCurrentThread
                JavaChaldeaFileAccess(dir).overwrite(name, bytes)
            }
        }
        val e = engine(spy)
        holder = e
        commitAfterPrepare(e)
        assertTrue(heldDuringWrite)
        assertFalse(e.lock.isLocked)
    }

    // ---- wire formats and the backup pipe ----

    @Test fun resultJsonRoundTripsAndBadTextBecomesServiceError() {
        val r = ChaldeaSyncResult(
            ChaldeaSyncCode.Restored, cause = ChaldeaSyncCode.VerifyFailed, packageName = "p",
            base = ChaldeaBaseSource.Bak, size = 5, sha256 = "ab", itemCount = 1, created = true, regionMismatch = true,
        )
        assertEquals(r, ChaldeaSyncResult.fromJson(r.toJson()))
        assertEquals(ChaldeaSyncCode.ServiceError, ChaldeaSyncResult.fromJson("{").code)
        assertEquals(ChaldeaSyncCode.ServiceError, ChaldeaSyncResult.fromJson(null).code)
        val s = ChaldeaStateResult(ChaldeaRunState.Background, "p")
        assertEquals(s, ChaldeaStateResult.fromJson(s.toJson()))
        assertEquals(ChaldeaRunState.Unknown, ChaldeaStateResult.fromJson("nope").state)
    }

    @Test fun backupReceiverCountsAndHashesAndDetectsMismatch() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        val sink = ByteArrayOutputStream()
        val got = ChaldeaBackupReceiver.receive(ByteArrayInputStream(data), sink)
        assertContentEquals(data, sink.toByteArray())
        val announced = ChaldeaSyncResult(ChaldeaSyncCode.Ok, bundleSize = data.size.toLong(), bundleSha256 = sha256Hex(data))
        assertTrue(ChaldeaBackupReceiver.matches(got, announced))
        assertFalse(ChaldeaBackupReceiver.matches(got, announced.copy(bundleSize = 1)))
        assertFalse(ChaldeaBackupReceiver.matches(got, announced.copy(bundleSha256 = "00")))
        assertFalse(ChaldeaBackupReceiver.matches(got, announced.copy(code = ChaldeaSyncCode.NotFound)))
        val short = ChaldeaBackupReceiver.receive(ByteArrayInputStream(data.copyOf(10)), ByteArrayOutputStream())
        assertFalse(ChaldeaBackupReceiver.matches(short, announced))
    }

    @Test fun javaFileAccessNeverCreatesAFile() {
        assertFailsWith<ChaldeaIoException> { JavaChaldeaFileAccess(dir).overwrite("userdata.json", byteArrayOf(1)) }
        assertFalse(File(dir, "userdata.json").exists())
        assertEquals(ChaldeaIoException.Kind.NotFound, assertFailsWith<ChaldeaIoException> { JavaChaldeaFileAccess(dir).read("x") }.kind)
    }

    @Test fun resultJsonRoundTripsStage2CountsAndReadsOldJsonWithZeroes() {
        val r = ChaldeaSyncResult(
            ChaldeaSyncCode.Ok,
            packageName = "p",
            itemCount = 4,
            mysticCodeCount = 2,
            boardCount = 1,
            coinCount = 5,
            ceCount = 6,
            ccCount = 3,
        )
        assertEquals(r, ChaldeaSyncResult.fromJson(r.toJson()))
        val oldJson = """{"code":"Ok","package":"p","items":4,"mysticCodes":2,"boards":1}"""
        val decodedOld = ChaldeaSyncResult.fromJson(oldJson)
        assertEquals(ChaldeaSyncCode.Ok, decodedOld.code)
        assertEquals(4, decodedOld.itemCount)
        assertEquals(0, decodedOld.coinCount)
        assertEquals(0, decodedOld.ceCount)
        assertEquals(0, decodedOld.ccCount)
    }

    @Test fun engineCommitFillsStage2CountsFromPatch() {
        put("userdata.json", fileText())
        val p = ChaldeaPatch(
            friendCode = "111",
            server = ChaldeaServer.Kr,
            nickname = "Synth",
            isGirl = true,
            items = mapOf(100L to 3L),
            mysticCodes = null,
            classBoards = null,
            svtCoins = mapOf(10010001L to 12L, 20010001L to 34L),
            craftEssences = mapOf(11L to ChaldeaPatch.CeStatus(2L, 50L, 4L)),
            commandCodes = mapOf(
                1L to ChaldeaPatch.CcStatus(2L, 1L),
                2L to ChaldeaPatch.CcStatus(1L, 0L),
                3L to ChaldeaPatch.CcStatus(2L, 2L),
            ),
        )
        val r = commitAfterPrepare(engine(), p)
        assertEquals(ChaldeaSyncCode.Ok, r.code)
        assertEquals(1, r.itemCount)
        assertEquals(2, r.coinCount)
        assertEquals(1, r.ceCount)
        assertEquals(3, r.ccCount)
    }
}
