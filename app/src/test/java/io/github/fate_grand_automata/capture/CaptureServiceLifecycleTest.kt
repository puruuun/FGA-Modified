package io.github.fate_grand_automata.capture

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureServiceLifecycleTest {
    @Test fun stopsTrackerWithServiceWhenPreferenceIsOn() {
        assertTrue(CaptureServiceLifecycle.shouldStop(stopWithService = true))
    }

    @Test fun leavesTrackerRunningWhenPreferenceIsOff() {
        assertFalse(CaptureServiceLifecycle.shouldStop(stopWithService = false))
    }
}
