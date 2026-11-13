package io.github.fate_grand_automata.touch

import android.app.Application
import android.content.ServiceConnection
import android.os.Looper
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TouchInjectorTest {

    data class CapturedMotion(
        val action: Int,
        val x: Float,
        val y: Float,
        val downTime: Long,
        val eventTime: Long,
        val source: Int,
        val mode: Int,
    )

    class FakeInputManagerGlobal {
        @Suppress("unused")
        fun injectInputEvent(event: InputEvent, mode: Int): Boolean =
            mode == TouchInjector.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH

        companion object {
            private val singleton = FakeInputManagerGlobal()

            @JvmStatic
            fun getInstance(): FakeInputManagerGlobal = singleton
        }
    }

    class MissingInputManager

    @Test
    fun resolveFromClassFindsGetInstanceAndInjectInputEventViaReflection() {
        val missing = TouchInjector.resolveFromClass(MissingInputManager::class.java, "InputManager")
        assertEquals(null, missing)

        val global = TouchInjector.resolveFromClass(FakeInputManagerGlobal::class.java, "InputManagerGlobal")
        assertNotNull(global)
        assertEquals("InputManagerGlobal", global.sourceName)
    }

    @Test
    fun tapAndDragEmitTouchscreenEventsWithCorrectTimingsAndMode() {
        val events = mutableListOf<CapturedMotion>()
        var nowMs = 1_000L
        val injector = TouchInjector(
            resolved = Result.success(
                ResolvedInputInjector("InputManagerGlobal") { event, mode ->
                    val me = event as MotionEvent
                    events.add(
                        CapturedMotion(
                            action = me.actionMasked,
                            x = me.x,
                            y = me.y,
                            downTime = me.downTime,
                            eventTime = me.eventTime,
                            source = me.source,
                            mode = mode,
                        ),
                    )
                    true
                },
            ),
            clock = { nowMs },
            sleeper = { delta -> nowMs += delta },
            uidProvider = { 2000 },
        )

        assertTrue(injector.tap(300, 500, 60L))
        assertEquals(2, events.size)
        assertEquals(MotionEvent.ACTION_DOWN, events[0].action)
        assertEquals(300f, events[0].x)
        assertEquals(500f, events[0].y)
        assertEquals(1_000L, events[0].downTime)
        assertEquals(1_000L, events[0].eventTime)
        assertEquals(InputDevice.SOURCE_TOUCHSCREEN, events[0].source)
        assertEquals(TouchInjector.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH, events[0].mode)

        assertEquals(MotionEvent.ACTION_UP, events[1].action)
        assertEquals(1_000L, events[1].downTime)
        assertEquals(1_060L, events[1].eventTime)

        events.clear()
        assertTrue(injector.drag(100, 200, 350, 200, 400L))
        // 400 ms / 16 ms = 25 MOVE steps + 1 DOWN + 1 UP = 27 events
        assertEquals(27, events.size)
        assertEquals(MotionEvent.ACTION_DOWN, events.first().action)
        assertEquals(100f, events.first().x)
        assertTrue(events.subList(1, 26).all { it.action == MotionEvent.ACTION_MOVE })
        assertEquals(MotionEvent.ACTION_UP, events.last().action)
        assertEquals(350f, events.last().x)
        assertEquals(200f, events.last().y)
        assertEquals(events.first().downTime, events.last().downTime)
        assertEquals(events.first().downTime + 400L, events.last().eventTime)
    }

    @Test
    fun describeReportsReachabilityAndNeverThrowsWhenInjectorFails() {
        val okInjector = TouchInjector(
            resolved = Result.success(ResolvedInputInjector("InputManagerGlobal") { _, _ -> true }),
            uidProvider = { 2000 },
        )
        val desc = okInjector.describe()
        assertTrue(desc.contains("uid=2000"), desc)
        assertTrue(desc.contains("reachable(InputManagerGlobal)"), desc)

        val failingInjector = TouchInjector(
            resolved = Result.failure(NoSuchMethodException("missing")),
            uidProvider = { 0 },
        )
        val failDesc = failingInjector.describe()
        assertTrue(failDesc.contains("uid=0"), failDesc)
        assertTrue(failDesc.contains("unreachable(NoSuchMethodException"), failDesc)
        assertFalse(failingInjector.tap(100, 100, 60L))
        assertFalse(failingInjector.drag(100, 100, 200, 100, 400L))
    }

    @Test
    fun rootClientBindsOnDemandAndUnbindsOnceWhenClosed() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        var binds = 0
        var unbinds = 0
        val stub = TouchInjectorService(
            TouchInjector(
                resolved = Result.success(ResolvedInputInjector("InputManagerGlobal") { _, _ -> true }),
                clock = { 100L },
                sleeper = {},
                uidProvider = { 0 },
            ),
            onDestroyProcess = {},
        )
        val client = TouchInjectorClient.forRoot(
            context = context,
            isRootShell = { true },
            bindService = { _, conn: ServiceConnection ->
                binds++
                conn.onServiceConnected(null, stub)
            },
            unbindService = { unbinds++ },
        )

        var session: TouchInjectorSession? = null
        val job = launch(Dispatchers.Default) {
            session = client.open(timeoutMs = 5_000L)
        }
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (job.isCompleted) return@repeat
            Thread.sleep(10)
        }

        val opened = assertNotNull(session)
        assertEquals(1, binds)
        assertEquals(0, unbinds)
        assertTrue(opened.tap(50, 50, 60L))
        assertTrue(opened.describe().contains("uid=0"))

        opened.close()
        opened.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, unbinds)
    }
}
