package io.github.fate_grand_automata.sensing

import io.github.lib_automata.ExitManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `ExitManager.isExitRequested`, which the rust build type's engine reads during its slot wait. */
class ExitRequestedTest {
    @Test fun `it is false before exit and true after`() {
        val exitManager = ExitManager()
        assertFalse(exitManager.isExitRequested)
        exitManager.exit()
        assertTrue(exitManager.isExitRequested)
    }

    @Test fun `reading it while the run is paused does not wait`() {
        val exitManager = ExitManager()
        exitManager.pause()
        val answers = mutableListOf<Boolean>()
        val read = CountDownLatch(2)
        val reader = thread {
            answers += exitManager.isExitRequested
            read.countDown()
            exitManager.exit()
            answers += exitManager.isExitRequested
            read.countDown()
        }
        assertTrue(read.await(5, TimeUnit.SECONDS), "isExitRequested waited on the pause")
        reader.join(5_000)
        assertEquals(listOf(false, true), answers)
        exitManager.resume()
    }
}
