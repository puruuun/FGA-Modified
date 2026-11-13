package io.github.fate_grand_automata.ui.touchtest

import android.view.MotionEvent
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TouchTestLogicTest {

    @Test
    fun `counter records DOWN-UP pairs as completed taps`() {
        val counter = TouchPadCounter()
        counter.onEvent(MotionEvent.ACTION_DOWN, 100f, 200f)
        counter.onEvent(MotionEvent.ACTION_UP, 100f, 200f)
        counter.onEvent(MotionEvent.ACTION_DOWN, 102f, 198f)
        // Stationary MOVE within drag slop still counts as a tap (Accessibility stroke sampling).
        counter.onEvent(MotionEvent.ACTION_MOVE, 104f, 201f)
        counter.onEvent(MotionEvent.ACTION_UP, 104f, 201f)

        val counts = counter.snapshot()
        assertEquals(2, counts.down)
        assertEquals(1, counts.move)
        assertEquals(2, counts.up)
        assertEquals(0, counts.cancel)
        assertEquals(2, counts.completedTaps)
        assertEquals(0, counts.completedDrags)
    }

    @Test
    fun `counter records drags and action-only DOWN-MOVE-UP sequences`() {
        val counter = TouchPadCounter()
        counter.onEvent(MotionEvent.ACTION_DOWN, 100f, 200f)
        counter.onEvent(MotionEvent.ACTION_MOVE, 140f, 200f)
        counter.onEvent(MotionEvent.ACTION_MOVE, 220f, 200f)
        counter.onEvent(MotionEvent.ACTION_UP, 220f, 200f)

        // Action-only overload without coordinates
        counter.onEvent(MotionEvent.ACTION_DOWN)
        counter.onEvent(MotionEvent.ACTION_MOVE)
        counter.onEvent(MotionEvent.ACTION_UP)

        val counts = counter.snapshot()
        assertEquals(2, counts.down)
        assertEquals(3, counts.move)
        assertEquals(2, counts.up)
        assertEquals(0, counts.cancel)
        assertEquals(0, counts.completedTaps)
        assertEquals(2, counts.completedDrags)
    }

    @Test
    fun `counter does not count sequences interrupted by CANCEL as completed`() {
        val counter = TouchPadCounter()
        // Cancelled tap
        counter.onEvent(MotionEvent.ACTION_DOWN, 50f, 50f)
        counter.onEvent(MotionEvent.ACTION_CANCEL, 50f, 50f)
        // Stray UP after CANCEL must not complete anything
        counter.onEvent(MotionEvent.ACTION_UP, 50f, 50f)

        // Cancelled drag
        counter.onEvent(MotionEvent.ACTION_DOWN, 50f, 50f)
        counter.onEvent(MotionEvent.ACTION_MOVE, 150f, 50f)
        counter.onEvent(MotionEvent.ACTION_CANCEL, 150f, 50f)

        // Valid tap afterwards
        counter.onEvent(MotionEvent.ACTION_DOWN, 50f, 50f)
        counter.onEvent(MotionEvent.ACTION_UP, 50f, 50f)

        val counts = counter.snapshot()
        assertEquals(3, counts.down)
        assertEquals(1, counts.move)
        assertEquals(2, counts.up)
        assertEquals(2, counts.cancel)
        assertEquals(1, counts.completedTaps)
        assertEquals(0, counts.completedDrags)
    }

    @Test
    fun `counter ignores MOVE-only sequences without prior DOWN for completion`() {
        val counter = TouchPadCounter()
        counter.onEvent(MotionEvent.ACTION_MOVE, 10f, 10f)
        counter.onEvent(MotionEvent.ACTION_MOVE, 80f, 10f)
        counter.onEvent(MotionEvent.ACTION_UP, 80f, 10f)

        val counts = counter.snapshot()
        assertEquals(0, counts.down)
        assertEquals(2, counts.move)
        assertEquals(1, counts.up)
        assertEquals(0, counts.cancel)
        assertEquals(0, counts.completedTaps)
        assertEquals(0, counts.completedDrags)
    }

    @Test
    fun `pattern generator produces 20 taps then 10 drags inside the pad with required timings`() {
        val bounds = PadBounds(left = 100, top = 300, width = 800, height = 600)
        val steps = TouchTestPattern.generate(bounds)

        assertEquals(30, steps.size)
        val taps = steps.take(20)
        val drags = steps.drop(20)
        assertEquals(20, taps.size)
        assertEquals(10, drags.size)

        val expectedCenterX = 100 + 800 / 2 // 500
        val expectedCenterY = 300 + 600 / 2 // 600
        val expectedDragEndX = expectedCenterX + (800 * 0.30).toInt() // 740

        for (step in taps) {
            assertTrue(step is TouchTestStep.Tap)
            assertEquals(expectedCenterX, step.x)
            assertEquals(expectedCenterY, step.y)
            assertEquals(60L, step.holdMs)
            assertEquals(700L, step.intervalMs)
            assertTrue(bounds.contains(step.x, step.y))
        }

        for (step in drags) {
            assertTrue(step is TouchTestStep.Drag)
            assertEquals(expectedCenterX, step.x1)
            assertEquals(expectedCenterY, step.y1)
            assertEquals(expectedDragEndX, step.x2)
            assertEquals(expectedCenterY, step.y2)
            assertEquals(400L, step.durationMs)
            assertEquals(1200L, step.intervalMs)
            assertTrue(bounds.contains(step.x1, step.y1))
            assertTrue(bounds.contains(step.x2, step.y2))
        }

        assertTrue(TouchTestPattern.generate(PadBounds(0, 0, 0, 100)).isEmpty())
    }

    @Test
    fun `bounds guard rejects paused activity and out-of-bounds coordinates`() {
        val bounds = PadBounds(left = 50, top = 100, width = 400, height = 300)
        val insideTap = TouchTestStep.Tap(x = 250, y = 250)
        val insideDrag = TouchTestStep.Drag(x1 = 250, y1 = 250, x2 = 370, y2 = 250)

        assertTrue(TouchBoundsGuard.canInject(activityResumed = true, bounds = bounds, step = insideTap))
        assertTrue(TouchBoundsGuard.canInject(activityResumed = true, bounds = bounds, step = insideDrag))

        // Activity paused -> rejected even when inside bounds
        assertFalse(TouchBoundsGuard.canInject(activityResumed = false, bounds = bounds, step = insideTap))
        assertFalse(TouchBoundsGuard.canInject(activityResumed = false, bounds = bounds, step = insideDrag))

        // Points outside or on the exclusive right/bottom edge -> rejected
        assertFalse(
            TouchBoundsGuard.canInject(
                activityResumed = true,
                bounds = bounds,
                step = TouchTestStep.Tap(x = 49, y = 250),
            ),
        )
        assertFalse(
            TouchBoundsGuard.canInject(
                activityResumed = true,
                bounds = bounds,
                step = TouchTestStep.Tap(x = 450, y = 250),
            ),
        )
        assertFalse(
            TouchBoundsGuard.canInject(
                activityResumed = true,
                bounds = bounds,
                step = TouchTestStep.Drag(x1 = 250, y1 = 250, x2 = 450, y2 = 250),
            ),
        )
        assertFalse(
            TouchBoundsGuard.canInject(
                activityResumed = true,
                bounds = PadBounds(50, 100, 0, 300),
                step = insideTap,
            ),
        )
    }

    @Test
    fun `jsonl writer appends records, updates note on last line, and caps at 200 lines`(@TempDir tempDir: File) {
        val file = TouchTestJsonlWriter.fileIn(tempDir)
        assertFalse(file.exists())

        fun sampleRecord(index: Int, note: String = "note-$index") = TouchTestRunRecord(
            timestampMs = 1_000L + index,
            path = "shizuku",
            status = "completed",
            androidRelease = "15",
            sdkInt = 35,
            deviceModel = "Pixel",
            injectorInfo = "reachable(InputManagerGlobal)",
            sentTaps = 20,
            sentDrags = 10,
            completedTaps = 20,
            completedDrags = 10,
            downCount = 30,
            moveCount = 250,
            upCount = 30,
            cancelCount = 0,
            accessibilityCancelled = 0,
            injectorFailures = 0,
            durationMs = 26_000L,
            note = note,
        )

        for (i in 1..205) {
            TouchTestJsonlWriter.append(file, sampleRecord(i))
        }

        assertTrue(file.isFile)
        val lines = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(200, lines.size)

        val firstKept = TouchTestJsonlWriter.decodeLine(lines.first())
        val lastKept = TouchTestJsonlWriter.decodeLine(lines.last())
        assertEquals(1_006L, firstKept.timestampMs)
        assertEquals("note-6", firstKept.note)
        assertEquals(1_205L, lastKept.timestampMs)
        assertEquals("note-205", lastKept.note)

        // Updating the note on the last run modifies the last line in place without adding a 201st line
        TouchTestJsonlWriter.updateLast(file, lastKept.copy(note = "scroll interrupted"))
        val afterUpdate = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(200, afterUpdate.size)
        assertEquals("scroll interrupted", TouchTestJsonlWriter.decodeLine(afterUpdate.last()).note)
    }
}
