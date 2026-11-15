package io.github.fate_grand_automata.capture

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnsureThrottleTest {
    @Test
    fun allowsOncePerInterval() {
        var now = 1_000L
        val throttle = EnsureThrottle(30_000L) { now }
        assertTrue(throttle.tryAcquire())
        now += 29_999L
        assertFalse(throttle.tryAcquire())
        now += 1L
        assertTrue(throttle.tryAcquire())
        now += 1L
        assertFalse(throttle.tryAcquire())
    }

    @Test
    fun firstCallAtClockZeroIsAllowed() {
        val throttle = EnsureThrottle(30_000L) { 0L }
        assertTrue(throttle.tryAcquire())
        assertFalse(throttle.tryAcquire())
    }

    @Test
    fun clockGoingBackwardsDoesNotBlockForever() {
        var now = 100_000L
        val throttle = EnsureThrottle(30_000L) { now }
        assertTrue(throttle.tryAcquire())
        now = 10L
        assertTrue(throttle.tryAcquire())
    }
}
