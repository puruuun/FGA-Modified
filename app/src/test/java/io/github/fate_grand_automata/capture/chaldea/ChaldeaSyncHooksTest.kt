package io.github.fate_grand_automata.capture.chaldea

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.capture.CaptureAutoStart
import io.github.fate_grand_automata.capture.CaptureTracker
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.enums.GameServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The sync hooks sit in capture and script code: they must run first and never let a failure out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ChaldeaSyncHooksTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var prefs: PrefsCore

    private class Throwing : ChaldeaSyncHooks {
        override fun onAccountsSaved(ids: Collection<String>) = throw IllegalStateException("synthetic")
        override fun onAccountDeleted(id: String?) = throw OutOfMemoryError("synthetic")
        override fun onScriptStart() = throw IllegalStateException("synthetic")
        override fun onScriptEnd() = throw IllegalStateException("synthetic")
    }

    private class Recording : ChaldeaSyncHooks {
        val calls = mutableListOf<String>()
        override fun onAccountsSaved(ids: Collection<String>) { calls += "saved" }
        override fun onAccountDeleted(id: String?) { calls += "deleted" }
        override fun onScriptStart() { calls += "start" }
        override fun onScriptEnd() { calls += "end" }
    }

    @Before fun setup() {
        val store = context.getSharedPreferences("chaldea-hooks-test", Context.MODE_PRIVATE)
        store.edit().clear().commit()
        prefs = PrefsCore(PrefMaker(store), context)
        CaptureTracker.delete()
        CaptureTracker.starting = false
    }

    @After fun cleanup() {
        ChaldeaSyncManager.hooks = null
        CaptureTracker.delete()
        CaptureTracker.starting = false
        CaptureAutoStart.onStartStepHook = null
    }

    @Test fun aThrowingHookNeverReachesTheScriptStartOrEnd() {
        ChaldeaSyncManager.hooks = Throwing()
        CaptureAutoStart.onScriptStart(context, prefs, GameServer.Kr, battle = true)
        CaptureAutoStart.onScriptEnd()
    }

    @Test fun aThrowingHookNeverReachesTheAccountDeletion() {
        ChaldeaSyncManager.hooks = Throwing()
        CaptureTracker.delete()
        CaptureTracker.delete("missing")
    }

    @Test fun theStartHookRunsBeforeAnyEarlyReturnOrFailureOfTheCaptureSteps() {
        val rec = Recording()
        ChaldeaSyncManager.hooks = rec
        // The capture step fails at once; the sync must still have been told.
        CaptureAutoStart.onStartStepHook = { throw IllegalStateException("synthetic") }
        CaptureAutoStart.onScriptStart(context, prefs, GameServer.Kr, battle = false)
        assertEquals(listOf("start"), rec.calls)
        CaptureAutoStart.onScriptEnd()
        assertTrue(rec.calls.last() == "end")
    }

    @Test fun withNoTargetTheHooksDoNothing() {
        ChaldeaSyncManager.hooks = null
        CaptureAutoStart.onScriptStart(context, prefs, GameServer.Kr)
        CaptureAutoStart.onScriptEnd()
    }

    private fun feedLogin(id: Int) = CaptureTracker.onGameResponse(
        "https://yfgo-game.netmarble.com/login/top",
        """{"response":[{"nid":"login","resCode":"00","success":{}}],
        "cache":{"replaced":{"userGame":[{"userId":$id,"name":"Master $id","friendCode":"123","exp":100}],
        "userItem":[{"itemId":100,"num":5}]}}}""".toByteArray(),
        null,
    )

    /** Starts and stops the VPN state, which flushes the unsaved accounts on the save thread. */
    private fun flushSaves() {
        CaptureTracker.setRunning(true)
        CaptureTracker.setRunning(false)
    }

    @Test fun aThrowingSaveHookDoesNotPropagateOutOfTheSave() {
        ChaldeaSyncManager.hooks = Throwing()
        // The save runs on a future that swallows exceptions, so the guard is checked on the call itself.
        CaptureTracker.notifyChaldeaSync(listOf("7001"))
        ChaldeaSyncManager.hooks = object : ChaldeaSyncHooks {
            override fun onAccountsSaved(ids: Collection<String>) = throw OutOfMemoryError("synthetic")
            override fun onAccountDeleted(id: String?) {}
            override fun onScriptStart() {}
            override fun onScriptEnd() {}
        }
        CaptureTracker.notifyChaldeaSync(listOf("7001"))
    }

    @Test fun theSaveHookGetsOnlyTheAccountsThatWereWritten() {
        CaptureTracker.init(context)
        val ids = java.util.concurrent.CopyOnWriteArrayList<String>()
        val first = java.util.concurrent.CountDownLatch(1)
        ChaldeaSyncManager.hooks = object : ChaldeaSyncHooks {
            override fun onAccountsSaved(ids2: Collection<String>) {
                ids += ids2
                first.countDown()
            }
            override fun onAccountDeleted(id: String?) {}
            override fun onScriptStart() {}
            override fun onScriptEnd() {}
        }
        feedLogin(7001)
        flushSaves()
        assertTrue(first.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(listOf("7001"), ids.toList())
        flushSaves() // nothing is unsaved now
        Thread.sleep(500)
        assertEquals(listOf("7001"), ids.toList(), "a save that wrote no account state must not notify")
    }
}
