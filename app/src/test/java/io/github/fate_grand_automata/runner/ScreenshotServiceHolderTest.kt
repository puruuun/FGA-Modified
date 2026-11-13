package io.github.fate_grand_automata.runner

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.fate_grand_automata.imaging.StoppableScreenshotService
import io.github.lib_automata.Pattern
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ScreenshotServiceHolderTest {
    private lateinit var serviceController: ServiceController<ScriptRunnerService>
    private lateinit var service: ScriptRunnerService
    private lateinit var holder: ScreenshotServiceHolder

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        ScriptRunnerService.mediaProjectionToken = null
        serviceController = Robolectric.buildService(ScriptRunnerService::class.java).create()
        service = serviceController.get()
        holder = service.controller.screenshotServiceHolder
        service.prefsCore.useRootForScreenshots.set(false)
        holder.close()
    }

    @After
    fun tearDown() {
        if (::holder.isInitialized) {
            holder.close()
        }
        ScriptRunnerService.mediaProjectionToken = null
        if (::serviceController.isInitialized) {
            serviceController.destroy()
        }
    }

    @Test
    fun needsNewConsent_mediaProjectionMode_noService_returnsTrue() {
        assertThat(holder.needsNewConsent()).isTrue()
    }

    @Test
    fun needsNewConsent_mediaProjectionMode_liveStoppableService_returnsFalse() {
        val fake = FakeStoppableScreenshotService(stopped = false)
        holder.setScreenshotServiceForTest(fake)

        assertThat(holder.needsNewConsent()).isFalse()
    }

    @Test
    fun needsNewConsent_mediaProjectionMode_stoppedService_returnsTrue() {
        val fake = FakeStoppableScreenshotService(stopped = true)
        holder.setScreenshotServiceForTest(fake)

        assertThat(holder.needsNewConsent()).isTrue()
    }

    @Test
    fun needsNewConsent_mediaProjectionMode_afterClose_returnsTrue() {
        val fake = FakeStoppableScreenshotService(stopped = false)
        holder.setScreenshotServiceForTest(fake)
        assertThat(holder.needsNewConsent()).isFalse()

        holder.close()

        assertThat(holder.screenshotService).isNull()
        assertThat(fake.closed).isTrue()
        assertThat(holder.needsNewConsent()).isTrue()
    }

    @Test
    fun needsNewConsent_rootMode_alwaysReturnsFalse() {
        service.prefsCore.useRootForScreenshots.set(true)

        assertThat(holder.needsNewConsent()).isFalse()

        holder.setScreenshotServiceForTest(FakeStoppableScreenshotService(stopped = true))
        assertThat(holder.needsNewConsent()).isFalse()
    }

    private class FakeStoppableScreenshotService(
        private val stopped: Boolean
    ) : StoppableScreenshotService {
        var closed = false
            private set

        override val isStopped: Boolean
            get() = stopped || closed

        override fun takeScreenshot(): Pattern {
            error("Not used in test")
        }

        override fun startRecording(): AutoCloseable? = null

        override fun close() {
            closed = true
        }
    }
}
