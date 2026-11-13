package io.github.fate_grand_automata.ui.touchtest

import android.view.MotionEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.roundToInt

enum class TouchTestPath(val wireName: String) {
    Accessibility("accessibility"),
    Shizuku("shizuku"),
    Root("root"),
}

data class TouchPadCounts(
    val down: Int = 0,
    val move: Int = 0,
    val up: Int = 0,
    val cancel: Int = 0,
    val completedTaps: Int = 0,
    val completedDrags: Int = 0,
)

/**
 * Counts every [MotionEvent] received by the touch test pad (issue #387, R2).
 *
 * A gesture counts as a completed tap or drag only when it receives `DOWN … UP` without `CANCEL`:
 * - When coordinates are supplied, moving at least [dragSlopPx] pixels from the `DOWN` coordinate makes the
 *   completed gesture a drag; staying within [dragSlopPx] makes it a tap (even if stationary `MOVE` events arrive).
 * - When only action codes are supplied (`onEvent(action)`), `DOWN, UP` is a tap and `DOWN, MOVE+, UP` is a drag.
 */
class TouchPadCounter(
    private val dragSlopPx: Float = DEFAULT_DRAG_SLOP_PX,
) {
    private var downCount = 0
    private var moveCount = 0
    private var upCount = 0
    private var cancelCount = 0
    private var completedTapCount = 0
    private var completedDragCount = 0

    private var inGesture = false
    private var hasCoordinates = false
    private var downX = 0f
    private var downY = 0f
    private var maxDistanceSq = 0f
    private var sawMove = false

    @Synchronized
    fun onEvent(actionMasked: Int): TouchPadCounts {
        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downCount++
                inGesture = true
                hasCoordinates = false
                downX = 0f
                downY = 0f
                maxDistanceSq = 0f
                sawMove = false
            }

            MotionEvent.ACTION_MOVE -> {
                moveCount++
                if (inGesture) {
                    sawMove = true
                }
            }

            MotionEvent.ACTION_UP -> {
                upCount++
                if (inGesture) {
                    if (sawMove) {
                        completedDragCount++
                    } else {
                        completedTapCount++
                    }
                    inGesture = false
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelCount++
                inGesture = false
            }
        }
        return snapshot()
    }

    @Synchronized
    fun onEvent(actionMasked: Int, x: Float, y: Float): TouchPadCounts {
        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downCount++
                inGesture = true
                hasCoordinates = true
                downX = x
                downY = y
                maxDistanceSq = 0f
                sawMove = false
            }

            MotionEvent.ACTION_MOVE -> {
                moveCount++
                if (inGesture) {
                    sawMove = true
                    if (hasCoordinates) {
                        val dx = x - downX
                        val dy = y - downY
                        maxDistanceSq = maxOf(maxDistanceSq, dx * dx + dy * dy)
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                upCount++
                if (inGesture) {
                    if (hasCoordinates) {
                        val dx = x - downX
                        val dy = y - downY
                        maxDistanceSq = maxOf(maxDistanceSq, dx * dx + dy * dy)
                    }
                    val isDrag = if (hasCoordinates) {
                        maxDistanceSq >= dragSlopPx * dragSlopPx
                    } else {
                        sawMove
                    }
                    if (isDrag) {
                        completedDragCount++
                    } else {
                        completedTapCount++
                    }
                    inGesture = false
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelCount++
                inGesture = false
            }
        }
        return snapshot()
    }

    @Synchronized
    fun reset(): TouchPadCounts {
        downCount = 0
        moveCount = 0
        upCount = 0
        cancelCount = 0
        completedTapCount = 0
        completedDragCount = 0
        inGesture = false
        hasCoordinates = false
        downX = 0f
        downY = 0f
        maxDistanceSq = 0f
        sawMove = false
        return snapshot()
    }

    @Synchronized
    fun snapshot(): TouchPadCounts = TouchPadCounts(
        down = downCount,
        move = moveCount,
        up = upCount,
        cancel = cancelCount,
        completedTaps = completedTapCount,
        completedDrags = completedDragCount,
    )

    companion object {
        const val DEFAULT_DRAG_SLOP_PX = 10f
    }
}

/**
 * Physical screen rectangle of the touch test pad obtained from `View.getLocationOnScreen` and the view dimensions.
 */
data class PadBounds(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    val isValid: Boolean get() = width > 0 && height > 0

    fun contains(x: Int, y: Int): Boolean =
        isValid && x in left until right && y in top until bottom
}

sealed interface TouchTestStep {
    val intervalMs: Long

    data class Tap(
        val x: Int,
        val y: Int,
        val holdMs: Long = TouchTestPattern.TAP_HOLD_MS,
        override val intervalMs: Long = TouchTestPattern.TAP_INTERVAL_MS,
    ) : TouchTestStep

    data class Drag(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val durationMs: Long = TouchTestPattern.DRAG_DURATION_MS,
        override val intervalMs: Long = TouchTestPattern.DRAG_INTERVAL_MS,
    ) : TouchTestStep
}

/**
 * Fixed touch test pattern (issue #387, R2):
 * - 20 taps into the centre of the pad (hold 60 ms, one every 700 ms);
 * - 10 drags from the centre of the pad to 30 % of the pad width to the right (400 ms each, one every 1200 ms).
 */
object TouchTestPattern {
    const val COUNTDOWN_SECONDS = 5
    const val TAP_COUNT = 20
    const val TAP_HOLD_MS = 60L
    const val TAP_INTERVAL_MS = 700L
    const val DRAG_COUNT = 10
    const val DRAG_WIDTH_FRACTION = 0.30
    const val DRAG_DURATION_MS = 400L
    const val DRAG_INTERVAL_MS = 1200L

    fun generate(bounds: PadBounds): List<TouchTestStep> {
        if (!bounds.isValid) return emptyList()
        val cx = bounds.centerX
        val cy = bounds.centerY
        val rawDeltaX = (bounds.width * DRAG_WIDTH_FRACTION).roundToInt()
        val endX = (cx + rawDeltaX).coerceIn(bounds.left, bounds.right - 1)
        return buildList(TAP_COUNT + DRAG_COUNT) {
            repeat(TAP_COUNT) {
                add(TouchTestStep.Tap(x = cx, y = cy, holdMs = TAP_HOLD_MS, intervalMs = TAP_INTERVAL_MS))
            }
            repeat(DRAG_COUNT) {
                add(
                    TouchTestStep.Drag(
                        x1 = cx,
                        y1 = cy,
                        x2 = endX,
                        y2 = cy,
                        durationMs = DRAG_DURATION_MS,
                        intervalMs = DRAG_INTERVAL_MS,
                    ),
                )
            }
        }
    }
}

/**
 * Safety guard checked before every injected touch (issue #387, R3):
 * the pad's activity must be resumed and all touch coordinates must lie strictly inside the pad's on-screen bounds.
 */
object TouchBoundsGuard {
    fun canInjectPoint(activityResumed: Boolean, bounds: PadBounds, x: Int, y: Int): Boolean =
        activityResumed && bounds.contains(x, y)

    fun canInject(activityResumed: Boolean, bounds: PadBounds, step: TouchTestStep): Boolean {
        if (!activityResumed || !bounds.isValid) return false
        return when (step) {
            is TouchTestStep.Tap -> bounds.contains(step.x, step.y)
            is TouchTestStep.Drag -> bounds.contains(step.x1, step.y1) && bounds.contains(step.x2, step.y2)
        }
    }
}

@Serializable
data class TouchTestRunRecord(
    val timestampMs: Long,
    val path: String,
    val status: String,
    val androidRelease: String,
    val sdkInt: Int,
    val deviceModel: String,
    val injectorInfo: String = "",
    val sentTaps: Int,
    val sentDrags: Int,
    val completedTaps: Int,
    val completedDrags: Int,
    val downCount: Int,
    val moveCount: Int,
    val upCount: Int,
    val cancelCount: Int,
    val accessibilityCancelled: Int,
    val injectorFailures: Int,
    val durationMs: Long,
    val note: String = "",
)

/**
 * Appends per-run JSON lines to `files/diagnostics/touch-separation.jsonl` and keeps at most the last [MAX_LINES]
 * lines (issue #387, R2).
 */
object TouchTestJsonlWriter {
    const val MAX_LINES = 200
    const val RELATIVE_PATH = "diagnostics/touch-separation.jsonl"

    private val lock = Any()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun fileIn(filesDir: File): File = File(filesDir, RELATIVE_PATH)

    fun encodeLine(record: TouchTestRunRecord): String = json.encodeToString(TouchTestRunRecord.serializer(), record)

    fun decodeLine(line: String): TouchTestRunRecord = json.decodeFromString(TouchTestRunRecord.serializer(), line)

    fun append(file: File, record: TouchTestRunRecord) = synchronized(lock) {
        file.parentFile?.mkdirs()
        val existing = if (file.isFile) {
            file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        } else {
            emptyList()
        }
        val updated = (existing + encodeLine(record)).takeLast(MAX_LINES)
        file.writeText(updated.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
    }

    /**
     * Updates the most recently appended run record (for example when the owner edits the note field after the run
     * completes) while keeping the [MAX_LINES] cap.
     */
    fun updateLast(file: File, record: TouchTestRunRecord) = synchronized(lock) {
        file.parentFile?.mkdirs()
        val existing = if (file.isFile) {
            file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        } else {
            emptyList()
        }
        val updated = if (existing.isEmpty()) {
            listOf(encodeLine(record))
        } else {
            (existing.dropLast(1) + encodeLine(record)).takeLast(MAX_LINES)
        }
        file.writeText(updated.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
    }
}
