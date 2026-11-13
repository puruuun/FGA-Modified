package io.github.fate_grand_automata.capture.chaldea

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.capture.AtlasVersions
import io.github.fate_grand_automata.capture.CaptureConsent
import io.github.fate_grand_automata.capture.GameNames
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.capture.AccountState
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaAccountSource
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaBaseSource
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaCollectionNumbers
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaPatch
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaRunState
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaServer
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaStateResult
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncCode
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncResult
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncSession
import io.github.fate_grand_automata.scripts.capture.chaldea.ChaldeaSyncStore
import io.github.fate_grand_automata.scripts.capture.chaldea.ShizukuState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ChaldeaSyncManagerCollectionTest {
    private lateinit var app: Application
    private lateinit var prefsCore: PrefsCore
    private lateinit var namesDir: File

    @Before
    fun setUp() {
        ChaldeaSyncManager.resetForTest()
        app = ApplicationProvider.getApplicationContext()
        val sp = app.getSharedPreferences("chaldea_collection_test", Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        File(app.filesDir, "chaldea-sync").deleteRecursively()
        File(app.filesDir, "chaldea-backup").deleteRecursively()
        prefsCore = PrefsCore(PrefMaker(sp), app)
        namesDir = File(app.filesDir, "test_names_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        ChaldeaSyncManager.resetForTest()
        File(app.filesDir, "chaldea-sync").deleteRecursively()
        File(app.filesDir, "chaldea-backup").deleteRecursively()
        namesDir.deleteRecursively()
    }

    private fun awaitUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5))
        }
        assertTrue(condition(), "Condition was not met within ${timeoutMs}ms")
    }

    private fun visibleSession() = object : ChaldeaSyncSession {
        override suspend fun state() = ChaldeaStateResult(ChaldeaRunState.Visible, "cc.narumi.chaldea")
        override suspend fun forceStop() = ChaldeaStateResult(ChaldeaRunState.NotRunning, "cc.narumi.chaldea")
        override suspend fun prepare(sink: OutputStream) = ChaldeaSyncSession.PrepareOutcome(
            ChaldeaSyncResult(ChaldeaSyncCode.NotFound), null, false,
        )
        override suspend fun commit(
            patch: ChaldeaPatch, expectedSha256: String, allowCreate: Boolean,
            expectedPackage: String, expectedBundleSha256: String,
        ) = ChaldeaSyncResult(ChaldeaSyncCode.ServiceError)
        override suspend fun restore(bundle: File, sha256: String, packageName: String) =
            ChaldeaSyncResult(ChaldeaSyncCode.ServiceError)
        override fun close() {}
    }

    @Test
    fun switchOnDownloadsAtMostOncePerProcessOffMainThread() {
        val mainThread = Looper.getMainLooper().thread
        val urls = CopyOnWriteArrayList<String>()
        val threads = CopyOnWriteArrayList<Thread>()
        val done = CountDownLatch(6)
        ChaldeaSyncManager.gameNamesFactory = {
            GameNames(namesDir, download = { url ->
                if (url == AtlasVersions.INFO_URL) error("offline")
                urls += url
                threads += Thread.currentThread()
                done.countDown()
                when {
                    url.endsWith("basic_equip.json") -> """[{"id":9300010,"collectionNo":11}]"""
                    url.endsWith("basic_command_code.json") -> """[{"id":80001,"collectionNo":1}]"""
                    url.endsWith("basic_servant.json") -> """[{"id":100100,"collectionNo":1}]"""
                    else -> "[]"
                }
            }) { 1_000L }
        }
        ChaldeaSyncManager.init(app, prefsCore)
        awaitUntil { ChaldeaSyncManager.applyCount >= 1 }

        val beforeOn = ChaldeaSyncManager.applyCount
        prefsCore.chaldeaSyncEnabled.set(true)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        runBlocking { ChaldeaSyncManager.collectionJob?.join() }
        awaitUntil { ChaldeaSyncManager.applyCount > beforeOn && ChaldeaSyncManager.isOn }
        assertEquals(6, urls.size)
        assertTrue(threads.all { it !== mainThread })

        // Toggling off and back on in the same process does not start a second background download.
        val beforeOff = ChaldeaSyncManager.applyCount
        prefsCore.chaldeaSyncEnabled.set(false)
        awaitUntil { ChaldeaSyncManager.applyCount > beforeOff && !ChaldeaSyncManager.isOn }

        val beforeSecondOn = ChaldeaSyncManager.applyCount
        prefsCore.chaldeaSyncEnabled.set(true)
        awaitUntil { ChaldeaSyncManager.applyCount > beforeSecondOn && ChaldeaSyncManager.isOn }
        runBlocking { ChaldeaSyncManager.collectionJob?.join() }
        assertEquals(6, urls.size)
    }

    @Test
    fun switchOnDuringScriptDefersCollectionDownloadUntilScriptEnds() {
        val urls = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(6)
        ChaldeaSyncManager.gameNamesFactory = {
            GameNames(namesDir, download = { url ->
                if (url == AtlasVersions.INFO_URL) error("offline")
                urls += url
                done.countDown()
                when {
                    url.endsWith("basic_equip.json") -> """[{"id":9300010,"collectionNo":11}]"""
                    url.endsWith("basic_command_code.json") -> """[{"id":80001,"collectionNo":1}]"""
                    url.endsWith("basic_servant.json") -> """[{"id":100100,"collectionNo":1}]"""
                    else -> "[]"
                }
            }) { 1_000L }
        }
        ChaldeaSyncManager.init(app, prefsCore)
        awaitUntil { ChaldeaSyncManager.applyCount >= 1 }
        ChaldeaSyncManager.onScriptStart()

        val beforeOn = ChaldeaSyncManager.applyCount
        prefsCore.chaldeaSyncEnabled.set(true)
        awaitUntil { ChaldeaSyncManager.applyCount > beforeOn && ChaldeaSyncManager.isOn }
        assertTrue(ChaldeaSyncManager.isCollectionDownloadDeferred)
        assertFalse(urls.isNotEmpty(), "Must not download while a script runs")

        ChaldeaSyncManager.onScriptEnd()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        runBlocking { ChaldeaSyncManager.collectionJob?.join() }
        assertEquals(6, urls.size)
    }

    @Test
    fun schedulerNotifiedWhenCollectionDownloadCachesNewData() {
        prefsCore.captureTrackerEnabled.set(true)
        prefsCore.captureConsentVersion.set(CaptureConsent.VERSION)
        ChaldeaSyncStore(File(app.filesDir, "chaldea-sync")).update { it.copy(pending = setOf("kr-1")) }

        val seenNumbers = CopyOnWriteArrayList<ChaldeaCollectionNumbers>()
        val openCalls = AtomicInteger(0)
        val firstOpen = CountDownLatch(1)
        val secondOpen = CountDownLatch(1)
        val downloadStarted = CountDownLatch(1)
        val releaseDownload = CountDownLatch(1)
        val settingsCalls = AtomicInteger(0)

        val names = GameNames(namesDir, download = { url ->
            downloadStarted.countDown()
            assertTrue(releaseDownload.await(5, TimeUnit.SECONDS))
            when {
                url.endsWith("basic_equip.json") -> """[{"id":9300010,"collectionNo":11}]"""
                url.endsWith("basic_command_code.json") -> """[{"id":80001,"collectionNo":1}]"""
                url.endsWith("basic_servant.json") -> """[{"id":100100,"collectionNo":1}]"""
                else -> "[]"
            }
        }) { 1_000L }

        ChaldeaSyncManager.gameNamesFactory = { names }
        ChaldeaSyncManager.shizukuStateReader = { ShizukuState.Ready }
        ChaldeaSyncManager.shizukuOpener = {
            when (openCalls.incrementAndGet()) {
                1 -> firstOpen.countDown()
                2 -> secondOpen.countDown()
            }
            visibleSession()
        }
        ChaldeaSyncManager.accountSourceFactory = {
            object : ChaldeaAccountSource {
                override fun load(userId: String) = ChaldeaAccountSource.Loaded(
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
                override fun currentIds() = mapOf(ChaldeaServer.Kr to "kr-1")
                override fun collectionNumbers(): ChaldeaCollectionNumbers =
                    names.collectionNumbers().also { seenNumbers += it }
            }
        }
        ChaldeaSyncManager.onSettingsChangedForTest = { settingsCalls.incrementAndGet() }

        ChaldeaSyncManager.init(app, prefsCore)
        awaitUntil { ChaldeaSyncManager.applyCount >= 1 }

        prefsCore.chaldeaSyncEnabled.set(true)
        assertTrue(downloadStarted.await(5, TimeUnit.SECONDS))
        assertTrue(firstOpen.await(5, TimeUnit.SECONDS))
        assertEquals(ChaldeaCollectionNumbers.EMPTY, seenNumbers.first())
        assertEquals(1, settingsCalls.get())

        releaseDownload.countDown()
        assertTrue(secondOpen.await(5, TimeUnit.SECONDS))
        runBlocking { ChaldeaSyncManager.collectionJob?.join() }

        assertEquals(2, settingsCalls.get())
        assertEquals(2, openCalls.get())
        assertEquals(
            ChaldeaCollectionNumbers(
                equips = mapOf(9300010L to 11L),
                commandCodes = mapOf(80001L to 1L),
                servants = mapOf(100100L to 1L),
            ),
            seenNumbers.last(),
        )
    }

    @Test
    fun schedulerNotNotifiedWhenCollectionDownloadCachesNothingNew() {
        prefsCore.captureTrackerEnabled.set(true)
        prefsCore.captureConsentVersion.set(CaptureConsent.VERSION)
        ChaldeaSyncStore(File(app.filesDir, "chaldea-sync")).update { it.copy(pending = setOf("kr-1")) }

        val openCalls = AtomicInteger(0)
        val firstOpen = CountDownLatch(1)
        val downloadDone = CountDownLatch(6)
        val settingsCalls = AtomicInteger(0)

        val names = GameNames(namesDir, download = { url ->
            if (url == AtlasVersions.INFO_URL) error("offline")
            downloadDone.countDown()
            """[{"id":9300010,"collectionNo":0}]"""
        }) { 1_000L }

        ChaldeaSyncManager.gameNamesFactory = { names }
        ChaldeaSyncManager.shizukuStateReader = { ShizukuState.Ready }
        ChaldeaSyncManager.shizukuOpener = {
            if (openCalls.incrementAndGet() == 1) firstOpen.countDown()
            visibleSession()
        }
        ChaldeaSyncManager.accountSourceFactory = {
            object : ChaldeaAccountSource {
                override fun load(userId: String) = ChaldeaAccountSource.Loaded(
                    ChaldeaServer.Kr,
                    AccountState(
                        mapOf("userGame" to """[{"friendCode":"111","name":"K","genderType":1}]"""),
                        "[]", null, 1L, 2L,
                    ),
                )
                override fun currentIds() = mapOf(ChaldeaServer.Kr to "kr-1")
                override fun collectionNumbers() = names.collectionNumbers()
            }
        }
        ChaldeaSyncManager.onSettingsChangedForTest = { settingsCalls.incrementAndGet() }

        ChaldeaSyncManager.init(app, prefsCore)
        awaitUntil { ChaldeaSyncManager.applyCount >= 1 }

        prefsCore.chaldeaSyncEnabled.set(true)
        assertTrue(firstOpen.await(5, TimeUnit.SECONDS))
        assertTrue(downloadDone.await(5, TimeUnit.SECONDS))
        runBlocking { ChaldeaSyncManager.collectionJob?.join() }

        assertEquals(1, settingsCalls.get())
        assertEquals(1, openCalls.get())
    }

    @Test
    fun collectionDownloadRunsAgainAfterOneHourFromAccountSavedHookAndNotBefore() {
        var nowMs = 1_000L
        var jpHash = "jp1"
        var hasNewJpServant = false
        val urls = CopyOnWriteArrayList<String>()
        val firstDone = CountDownLatch(6)
        val secondDone = CountDownLatch(3)
        val settingsCalls = AtomicInteger(0)

        ChaldeaSyncManager.collectionClock = { nowMs }
        ChaldeaSyncManager.gameNamesFactory = {
            GameNames(namesDir, download = { url ->
                urls += url
                when {
                    url == AtlasVersions.INFO_URL ->
                        """{"KR":{"hash":"kr1","timestamp":1},"JP":{"hash":"$jpHash","timestamp":2}}"""
                    url.endsWith("/JP/basic_servant.json") -> {
                        firstDone.countDown()
                        if (hasNewJpServant) secondDone.countDown()
                        if (hasNewJpServant) {
                            """[{"id":200100,"collectionNo":12},{"id":705500,"collectionNo":400}]"""
                        } else {
                            """[{"id":200100,"collectionNo":12}]"""
                        }
                    }
                    url.endsWith("basic_equip.json") -> {
                        firstDone.countDown()
                        if (hasNewJpServant && "/JP/" in url) secondDone.countDown()
                        """[{"id":9300010,"collectionNo":11}]"""
                    }
                    url.endsWith("basic_command_code.json") -> {
                        firstDone.countDown()
                        if (hasNewJpServant && "/JP/" in url) secondDone.countDown()
                        """[{"id":80001,"collectionNo":1}]"""
                    }
                    else -> {
                        firstDone.countDown()
                        """[{"id":100100,"collectionNo":1}]"""
                    }
                }
            }) { nowMs }
        }
        ChaldeaSyncManager.onSettingsChangedForTest = { settingsCalls.incrementAndGet() }

        ChaldeaSyncManager.init(app, prefsCore)
        awaitUntil { ChaldeaSyncManager.applyCount >= 1 }

        prefsCore.chaldeaSyncEnabled.set(true)
        assertTrue(firstDone.await(5, TimeUnit.SECONDS))
        runBlocking { ChaldeaSyncManager.collectionJob?.join() }
        assertEquals(2, settingsCalls.get())

        // Just before 1 hour: onAccountsSaved does not launch another collection download.
        nowMs += AtlasVersions.CHECK_INTERVAL_MS - 1
        val firstJob = ChaldeaSyncManager.collectionJob
        urls.clear()
        ChaldeaSyncManager.onAccountsSaved(listOf("kr-1"))
        assertTrue(ChaldeaSyncManager.collectionJob === firstJob)
        assertTrue(urls.isEmpty())

        // At 1 hour, with updated JP hash and new collection number: runs again and calls notifySettingsChanged.
        nowMs += 1
        jpHash = "jp2"
        hasNewJpServant = true
        ChaldeaSyncManager.onAccountsSaved(listOf("kr-1"))
        assertTrue(secondDone.await(5, TimeUnit.SECONDS))
        runBlocking { ChaldeaSyncManager.collectionJob?.join() }
        assertEquals(3, settingsCalls.get())
        assertTrue(urls.any { it.endsWith("/JP/basic_servant.json") })
        assertTrue(urls.none { "/KR/" in it })
    }

    @Test
    fun accountSavedHookDefersCollectionDownloadDuringScriptUntilScriptEnd() {
        var nowMs = 1_000L
        var jpHash = "jp1"
        val firstDone = CountDownLatch(6)
        val secondDone = CountDownLatch(3)
        val urls = CopyOnWriteArrayList<String>()

        ChaldeaSyncManager.collectionClock = { nowMs }
        ChaldeaSyncManager.gameNamesFactory = {
            GameNames(namesDir, download = { url ->
                urls += url
                when {
                    url == AtlasVersions.INFO_URL ->
                        """{"KR":{"hash":"kr1","timestamp":1},"JP":{"hash":"$jpHash","timestamp":2}}"""
                    url.endsWith("basic_equip.json") -> {
                        firstDone.countDown()
                        if (jpHash == "jp2" && "/JP/" in url) secondDone.countDown()
                        """[{"id":9300010,"collectionNo":11}]"""
                    }
                    url.endsWith("basic_command_code.json") -> {
                        firstDone.countDown()
                        if (jpHash == "jp2" && "/JP/" in url) secondDone.countDown()
                        """[{"id":80001,"collectionNo":1}]"""
                    }
                    else -> {
                        firstDone.countDown()
                        if (jpHash == "jp2" && "/JP/" in url) secondDone.countDown()
                        """[{"id":100100,"collectionNo":1}]"""
                    }
                }
            }) { nowMs }
        }
        ChaldeaSyncManager.init(app, prefsCore)
        awaitUntil { ChaldeaSyncManager.applyCount >= 1 }

        prefsCore.chaldeaSyncEnabled.set(true)
        assertTrue(firstDone.await(5, TimeUnit.SECONDS))
        runBlocking { ChaldeaSyncManager.collectionJob?.join() }

        nowMs += AtlasVersions.CHECK_INTERVAL_MS
        jpHash = "jp2"
        urls.clear()
        ChaldeaSyncManager.onScriptStart()
        ChaldeaSyncManager.onAccountsSaved(listOf("kr-1"))
        assertTrue(ChaldeaSyncManager.isCollectionDownloadDeferred)
        assertTrue(urls.isEmpty(), "Must not download while a script runs")

        ChaldeaSyncManager.onScriptEnd()
        assertTrue(secondDone.await(5, TimeUnit.SECONDS))
        runBlocking { ChaldeaSyncManager.collectionJob?.join() }
        assertFalse(ChaldeaSyncManager.isCollectionDownloadDeferred)
    }

    @Test
    fun notifySettingsChangedNotCalledWhenSecondRunFindsUnchangedNumbers() {
        var nowMs = 1_000L
        var jpHash = "jp1"
        val firstDone = CountDownLatch(6)
        val secondDone = CountDownLatch(3)
        val settingsCalls = AtomicInteger(0)

        ChaldeaSyncManager.collectionClock = { nowMs }
        ChaldeaSyncManager.gameNamesFactory = {
            GameNames(namesDir, download = { url ->
                when {
                    url == AtlasVersions.INFO_URL ->
                        """{"KR":{"hash":"kr1","timestamp":1},"JP":{"hash":"$jpHash","timestamp":2}}"""
                    url.endsWith("basic_equip.json") -> {
                        firstDone.countDown()
                        if (jpHash == "jp2" && "/JP/" in url) secondDone.countDown()
                        """[{"id":9300010,"collectionNo":11}]"""
                    }
                    url.endsWith("basic_command_code.json") -> {
                        firstDone.countDown()
                        if (jpHash == "jp2" && "/JP/" in url) secondDone.countDown()
                        """[{"id":80001,"collectionNo":1}]"""
                    }
                    else -> {
                        firstDone.countDown()
                        if (jpHash == "jp2" && "/JP/" in url) secondDone.countDown()
                        """[{"id":100100,"collectionNo":1}]"""
                    }
                }
            }) { nowMs }
        }
        ChaldeaSyncManager.onSettingsChangedForTest = { settingsCalls.incrementAndGet() }

        ChaldeaSyncManager.init(app, prefsCore)
        awaitUntil { ChaldeaSyncManager.applyCount >= 1 }

        prefsCore.chaldeaSyncEnabled.set(true)
        assertTrue(firstDone.await(5, TimeUnit.SECONDS))
        runBlocking { ChaldeaSyncManager.collectionJob?.join() }
        assertEquals(2, settingsCalls.get())

        // After 1 hour, JP hash changes, but returned lists have identical collection numbers -> no extra notifySettingsChanged.
        nowMs += AtlasVersions.CHECK_INTERVAL_MS
        jpHash = "jp2"
        ChaldeaSyncManager.onAccountsSaved(listOf("kr-1"))
        assertTrue(secondDone.await(5, TimeUnit.SECONDS))
        runBlocking { ChaldeaSyncManager.collectionJob?.join() }
        assertEquals(2, settingsCalls.get())
    }

    @Test
    fun scriptStartingAfterFirstCollectionListStopsRemainingDownloadsAndResumesAtScriptEnd() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val allDone = CountDownLatch(6)
        val urls = CopyOnWriteArrayList<String>()

        ChaldeaSyncManager.collectionClock = { 1_000L }
        ChaldeaSyncManager.gameNamesFactory = {
            GameNames(namesDir, download = { url ->
                if (url == AtlasVersions.INFO_URL) error("offline")
                urls += url
                if (urls.size == 1) {
                    firstStarted.countDown()
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                }
                allDone.countDown()
                when {
                    url.endsWith("basic_equip.json") -> """[{"id":9300010,"collectionNo":11}]"""
                    url.endsWith("basic_command_code.json") -> """[{"id":80001,"collectionNo":1}]"""
                    url.endsWith("basic_servant.json") -> """[{"id":100100,"collectionNo":1}]"""
                    else -> "[]"
                }
            }) { 1_000L }
        }
        ChaldeaSyncManager.init(app, prefsCore)
        awaitUntil { ChaldeaSyncManager.applyCount >= 1 }

        prefsCore.chaldeaSyncEnabled.set(true)
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
        ChaldeaSyncManager.onScriptStart()
        releaseFirst.countDown()
        runBlocking { ChaldeaSyncManager.collectionJob?.join() }

        assertEquals(1, urls.size)
        assertTrue(ChaldeaSyncManager.isCollectionDownloadDeferred)

        ChaldeaSyncManager.onScriptEnd()
        assertTrue(allDone.await(5, TimeUnit.SECONDS))
        runBlocking { ChaldeaSyncManager.collectionJob?.join() }
        assertEquals(6, urls.size)
        assertFalse(ChaldeaSyncManager.isCollectionDownloadDeferred)
    }
}
