package io.github.fate_grand_automata.capture.chaldea

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.capture.GameNames
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        prefsCore = PrefsCore(PrefMaker(sp), app)
        namesDir = File(app.filesDir, "test_names_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        ChaldeaSyncManager.resetForTest()
        namesDir.deleteRecursively()
    }

    @Test
    fun switchOnDownloadsAtMostOncePerProcessOffMainThread() {
        val mainThread = Looper.getMainLooper().thread
        val urls = CopyOnWriteArrayList<String>()
        val threads = CopyOnWriteArrayList<Thread>()
        val done = CountDownLatch(4)
        ChaldeaSyncManager.gameNamesFactory = {
            GameNames(namesDir, download = { url ->
                urls += url
                threads += Thread.currentThread()
                done.countDown()
                when {
                    url.endsWith("basic_equip.json") -> """[{"id":9300010,"collectionNo":11}]"""
                    url.endsWith("basic_command_code.json") -> """[{"id":80001,"collectionNo":1}]"""
                    else -> "[]"
                }
            }) { 1_000L }
        }
        ChaldeaSyncManager.init(app, prefsCore)

        prefsCore.chaldeaSyncEnabled.set(true)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(4, urls.size)
        assertTrue(threads.all { it !== mainThread })

        // Toggling off and back on in the same process does not start a second background download.
        prefsCore.chaldeaSyncEnabled.set(false)
        Thread.sleep(100)
        prefsCore.chaldeaSyncEnabled.set(true)
        Thread.sleep(150)
        assertEquals(4, urls.size)
    }

    @Test
    fun switchOnDuringScriptDefersCollectionDownloadUntilScriptEnds() {
        val urls = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(4)
        ChaldeaSyncManager.gameNamesFactory = {
            GameNames(namesDir, download = { url ->
                urls += url
                done.countDown()
                when {
                    url.endsWith("basic_equip.json") -> """[{"id":9300010,"collectionNo":11}]"""
                    url.endsWith("basic_command_code.json") -> """[{"id":80001,"collectionNo":1}]"""
                    else -> "[]"
                }
            }) { 1_000L }
        }
        ChaldeaSyncManager.init(app, prefsCore)
        ChaldeaSyncManager.onScriptStart()

        prefsCore.chaldeaSyncEnabled.set(true)
        Thread.sleep(150)
        assertFalse(urls.isNotEmpty(), "Must not download while a script runs")

        ChaldeaSyncManager.onScriptEnd()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(4, urls.size)
    }
}
