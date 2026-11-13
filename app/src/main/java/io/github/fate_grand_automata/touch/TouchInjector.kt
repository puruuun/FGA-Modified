package io.github.fate_grand_automata.touch

import android.hardware.input.InputManager
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import androidx.annotation.Keep

internal data class ResolvedInputInjector(
    val sourceName: String,
    val inject: (InputEvent, Int) -> Boolean,
)

/**
 * Privileged touch injector shared by [TouchInjectorService] (Shizuku UserService) and [TouchRootService] (libsu
 * RootService). Runs in a shell (uid 2000) or root (uid 0) process, so it uses [Log] instead of Timber and never
 * throws across the binder.
 */
@Keep
class TouchInjector internal constructor(
    private val resolved: Result<ResolvedInputInjector> = resolveDefaultInjector(),
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val sleeper: (Long) -> Unit = SystemClock::sleep,
    private val uidProvider: () -> Int = { runCatching { Os.getuid() }.getOrDefault(-1) },
) {
    @Keep
    constructor() : this(resolveDefaultInjector())

    fun tap(x: Int, y: Int, holdMs: Long): Boolean {
        val injector = resolved.getOrElse { t ->
            Log.w(TAG, "tap($x, $y) aborted: injectInputEvent unavailable", t)
            return false
        }
        return try {
            val downTime = clock()
            if (!injectMotion(injector, downTime, downTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat())) {
                Log.w(TAG, "tap ACTION_DOWN returned false at ($x, $y)")
                return false
            }
            val hold = holdMs.coerceAtLeast(0L)
            if (hold > 0L) {
                sleeper(hold)
            }
            val upTime = maxOf(downTime, clock())
            val ok = injectMotion(injector, downTime, upTime, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat())
            if (!ok) {
                Log.w(TAG, "tap ACTION_UP returned false at ($x, $y)")
            }
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "tap($x, $y, $holdMs) failed", t)
            false
        }
    }

    fun drag(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val injector = resolved.getOrElse { t ->
            Log.w(TAG, "drag($x1, $y1 -> $x2, $y2) aborted: injectInputEvent unavailable", t)
            return false
        }
        return try {
            val totalMs = maxOf(MOVE_STEP_MS, durationMs)
            val steps = maxOf(1, (totalMs / MOVE_STEP_MS).toInt())
            val downTime = clock()
            if (!injectMotion(injector, downTime, downTime, MotionEvent.ACTION_DOWN, x1.toFloat(), y1.toFloat())) {
                Log.w(TAG, "drag ACTION_DOWN returned false at ($x1, $y1)")
                return false
            }
            for (step in 1..steps) {
                val targetTime = downTime + (totalMs * step) / steps
                val waitMs = targetTime - clock()
                if (waitMs > 0L) {
                    sleeper(waitMs)
                }
                val eventTime = maxOf(downTime, clock())
                val fraction = step.toFloat() / steps.toFloat()
                val cx = x1 + (x2 - x1) * fraction
                val cy = y1 + (y2 - y1) * fraction
                if (!injectMotion(injector, downTime, eventTime, MotionEvent.ACTION_MOVE, cx, cy)) {
                    Log.w(TAG, "drag ACTION_MOVE returned false at step $step/$steps ($cx, $cy)")
                    return false
                }
            }
            val upTime = maxOf(downTime, clock())
            val ok = injectMotion(injector, downTime, upTime, MotionEvent.ACTION_UP, x2.toFloat(), y2.toFloat())
            if (!ok) {
                Log.w(TAG, "drag ACTION_UP returned false at ($x2, $y2)")
            }
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "drag($x1, $y1 -> $x2, $y2, $durationMs) failed", t)
            false
        }
    }

    fun describe(): String = try {
        val uid = uidProvider()
        val reachability = resolved.fold(
            onSuccess = { "reachable(${it.sourceName})" },
            onFailure = { "unreachable(${it.javaClass.simpleName}: ${it.message})" },
        )
        "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), uid=$uid, injectInputEvent=$reachability"
    } catch (t: Throwable) {
        Log.w(TAG, "describe() failed", t)
        "error(${t.javaClass.simpleName})"
    }

    private fun injectMotion(
        injector: ResolvedInputInjector,
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): Boolean {
        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            x,
            y,
            1.0f,
            1.0f,
            0,
            1.0f,
            1.0f,
            0,
            0,
        )
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        // Display id stays the default display (0).
        return try {
            injector.inject(event, INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH)
        } finally {
            event.recycle()
        }
    }

    companion object {
        private const val TAG = "TouchInjector"
        const val MOVE_STEP_MS = 16L
        const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 2

        internal fun resolveDefaultInjector(
            classLoader: (String) -> Class<*> = { Class.forName(it) },
        ): Result<ResolvedInputInjector> = runCatching {
            resolveFromClass(InputManager::class.java, "InputManager")
                ?: resolveFromClass(classLoader("android.hardware.input.InputManagerGlobal"), "InputManagerGlobal")
                ?: throw NoSuchMethodException("Neither InputManager nor InputManagerGlobal exposes getInstance()+injectInputEvent(InputEvent, int)")
        }

        private fun findMethod(clazz: Class<*>, name: String, vararg parameterTypes: Class<*>?) =
            try {
                clazz.getDeclaredMethod(name, *parameterTypes)
            } catch (_: NoSuchMethodException) {
                clazz.getMethod(name, *parameterTypes)
            }.apply { isAccessible = true }

        internal fun resolveFromClass(clazz: Class<*>, label: String): ResolvedInputInjector? = try {
            val getInstance = findMethod(clazz, "getInstance")
            val instance = getInstance.invoke(null) ?: return null
            val injectMethod = findMethod(
                clazz,
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
            )
            ResolvedInputInjector(label) { event, mode ->
                injectMethod.invoke(instance, event, mode) as Boolean
            }
        } catch (_: Throwable) {
            null
        }
    }
}
