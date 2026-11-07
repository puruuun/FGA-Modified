package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.R
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JpCaptureStartTest {
    @Test fun disabledPreferenceOrUnavailableFeaturesMakesNoInstallerCalls() = runBlocking {
        val fake = FakeJpCaInstaller()
        assertNull(JpCaptureStart.start(this, true, false, fake) { error("unexpected") })
        assertNull(JpCaptureStart.start(this, false, true, fake) { error("unexpected") })
        assertEquals(0, fake.checks)
        assertEquals(0, fake.injections)
    }
    @Test fun delayedInjectionDoesNotBlockKrStart() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fake = object : FakeJpCaInstaller() {
            override fun inject(): RootCaResult {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                return RootCaResult.Injected
            }
        }
        val job = JpCaptureStart.start(this, true, true, fake) {}
        try {
            // This is the next synchronous VPN-start step; it runs while injection is still blocked.
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(1L, release.count)
        } finally { release.countDown() }
        job!!.join()
        assertEquals(1, fake.checks)
    }
    @Test fun failurePostsMessageAndPresentCaSkipsInjection() = runBlocking {
        val messages = mutableListOf<Int>()
        val fake = FakeJpCaInstaller().apply { result = RootCaResult.Denied }
        JpCaptureStart.start(this, true, true, fake, messages::add)!!.join()
        assertEquals(listOf(R.string.capture_jp_start_failed), messages)
        fake.present = true
        JpCaptureStart.start(this, true, true, fake, messages::add)!!.join()
        assertEquals(1, fake.injections)
    }
    @Test fun freshInjectionAsksToRestartOnlyIfGameIsRunning() = runBlocking {
        val messages = mutableListOf<Int>()
        val fake = FakeJpCaInstaller().apply { gameRunning = true }
        JpCaptureStart.start(this, true, true, fake, messages::add)!!.join()
        assertEquals(listOf(R.string.capture_jp_restart), messages)
        messages.clear()
        fake.result = RootCaResult.AlreadyPresent
        JpCaptureStart.start(this, true, true, fake, messages::add)!!.join()
        assertTrue(messages.isEmpty())
    }
}
