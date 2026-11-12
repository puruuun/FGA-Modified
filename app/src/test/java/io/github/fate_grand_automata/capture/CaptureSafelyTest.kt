package io.github.fate_grand_automata.capture

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CaptureSafelyTest {
    @Test
    fun rethrowsJavaCancellationException() {
        assertFailsWith<java.util.concurrent.CancellationException> {
            captureSafely("cancelJava") {
                throw java.util.concurrent.CancellationException("java cancel")
            }
        }
    }

    @Test
    fun rethrowsKotlinCancellationException() {
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            captureSafely("cancelKotlin") {
                throw kotlinx.coroutines.CancellationException("kotlin cancel")
            }
        }
    }

    @Test
    fun swallowsOutOfMemoryErrorAndReturnsDefault() {
        var ranAfter = false
        val result = captureSafely("oom", default = "safe") {
            throw OutOfMemoryError("forced OOM")
        }
        assertEquals("safe", result)

        captureSafely("oomUnit") {
            throw OutOfMemoryError("forced OOM")
        }
        ranAfter = true
        assertTrue(ranAfter)
    }

    @Test
    fun swallowsThrowableAndReturnsDefault() {
        val result = captureSafely("err", default = 42) {
            throw RuntimeException("synthetic")
        }
        assertEquals(42, result)
    }

    @Test
    fun returnsValueOnSuccess() {
        val result = captureSafely("success", default = "fallback") {
            "computed"
        }
        assertEquals("computed", result)
    }
}
