package io.github.fate_grand_automata.capture

import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureDroppedCounterTest {
    @Before
    fun setup() {
        CaptureTracker.delete()
        CaptureTracker.setRunning(false)
    }

    @After
    fun cleanup() {
        CaptureTracker.delete()
        CaptureTracker.setRunning(false)
    }

    @Test
    fun countsResponsesNotRecordedByReason() {
        CaptureTracker.recordDroppedResponse("/battle/result", 1000L, CaptureDropReason.TooLarge)
        CaptureTracker.recordDroppedResponse("/login/top", 2000L, CaptureDropReason.OutOfMemory)
        CaptureTracker.recordDroppedResponse("/friend/top", 3000L, CaptureDropReason.HandlerFailure)

        assertEquals(3, CaptureTracker.droppedCount.value)
        assertEquals(1, CaptureTracker.droppedCount(CaptureDropReason.TooLarge))
        assertEquals(1, CaptureTracker.droppedCount(CaptureDropReason.OutOfMemory))
        assertEquals(1, CaptureTracker.droppedCount(CaptureDropReason.HandlerFailure))
        assertEquals("friend/top", CaptureTracker.lastDroppedPath.value)
    }

    @Test
    fun boundedAtTenRecentEntriesWithFifoEviction() {
        for (i in 1..15) {
            CaptureTracker.recordDroppedResponse("/path/$i?query=param", i.toLong(), CaptureDropReason.TooLarge)
        }

        assertEquals(15, CaptureTracker.droppedCount.value)
        val entries = CaptureTracker.droppedEntries.value
        assertEquals(10, entries.size)
        // First entry in the bounded list should be index 6
        assertEquals("path/6", entries.first().path)
        assertEquals(6L, entries.first().size)
        // Last entry should be index 15
        assertEquals("path/15", entries.last().path)
        assertEquals(15L, entries.last().size)
        assertEquals("path/15", CaptureTracker.lastDroppedPath.value)
    }

    @Test
    fun resetsWhenCollectionStarts() {
        CaptureTracker.recordDroppedResponse("/test/path", 500L, CaptureDropReason.OutOfMemory)
        assertEquals(1, CaptureTracker.droppedCount.value)
        assertEquals(1, CaptureTracker.droppedEntries.value.size)

        CaptureTracker.setRunning(true)

        assertEquals(0, CaptureTracker.droppedCount.value)
        assertTrue(CaptureTracker.droppedEntries.value.isEmpty())
        assertNull(CaptureTracker.lastDroppedPath.value)
        assertEquals(0, CaptureTracker.droppedCount(CaptureDropReason.OutOfMemory))
    }
}
