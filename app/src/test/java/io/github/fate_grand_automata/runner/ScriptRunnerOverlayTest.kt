package io.github.fate_grand_automata.runner

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.fate_grand_automata.imaging.StoppableScreenshotService
import io.github.fate_grand_automata.ui.ProjectionConsentActivity
import io.github.fate_grand_automata.ui.runner.ScriptRunnerUIAction
import io.github.fate_grand_automata.util.ScriptState
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
class ScriptRunnerOverlayTest {
    private lateinit var serviceController: ServiceController<ScriptRunnerService>
    private lateinit var service: ScriptRunnerService
    private lateinit var controller: ScriptRunnerServiceController

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        ScriptRunnerService.mediaProjectionToken = null
        serviceController = Robolectric.buildService(ScriptRunnerService::class.java).create()
        service = serviceController.get()
        controller = service.controller
        service.prefsCore.useRootForScreenshots.set(false)
        controller.screenshotServiceHolder.close()
        controller.projectionRestart.reset()
        Shadows.shadowOf(service.application).clearNextStartedActivities()
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) {
            controller.screenshotServiceHolder.close()
            controller.projectionRestart.reset()
        }
        ScriptRunnerService.mediaProjectionToken = null
        if (::serviceController.isInitialized) {
            serviceController.destroy()
        }
    }

    @Test
    fun actStart_whenCaptureStopped_closesDeadServiceStartsConsentAndDoesNotStartScript() {
        val deadService = FakeStoppableScreenshotService(stopped = true)
        controller.screenshotServiceHolder.setScreenshotServiceForTest(deadService)

        controller.overlay.act(ScriptRunnerUIAction.Start)

        val startedIntent = Shadows.shadowOf(service.application).nextStartedActivity
        assertThat(startedIntent).isNotNull()
        assertThat(startedIntent.component?.className).isEqualTo(ProjectionConsentActivity::class.java.name)
        assertThat(startedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertThat(deadService.closed).isTrue()
        assertThat(controller.screenshotServiceHolder.screenshotService).isNull()
        assertThat(controller.projectionRestart.isPending).isTrue()
        assertThat(controller.scriptManager.scriptState).isInstanceOf<ScriptState.Stopped>()
    }

    @Test
    fun actStart_whileConsentAlreadyPending_ignoresSecondPlayTap() {
        controller.overlay.act(ScriptRunnerUIAction.Start)
        val firstIntent = Shadows.shadowOf(service.application).nextStartedActivity
        assertThat(firstIntent).isNotNull()

        controller.overlay.act(ScriptRunnerUIAction.Start)

        assertThat(Shadows.shadowOf(service.application).nextStartedActivity).isNull()
        assertThat(controller.projectionRestart.isPending).isTrue()
        assertThat(controller.scriptManager.scriptState).isInstanceOf<ScriptState.Stopped>()
    }

    @Test
    fun onNewMediaProjectionToken_whenPrepareFails_clearsPendingAndDoesNotRelauchConsent() {
        controller.overlay.act(ScriptRunnerUIAction.Start)
        assertThat(Shadows.shadowOf(service.application).nextStartedActivity).isNotNull()
        assertThat(controller.projectionRestart.isPending).isTrue()

        // In Robolectric there is no live system MediaProjection for a dummy token, so
        // prepareScreenshotService() leaves screenshotService == null. Pending must clear
        // without re-opening ProjectionConsentActivity (no loop).
        ScriptRunnerService.mediaProjectionToken = Intent("test.media.projection.TOKEN")

        assertThat(controller.projectionRestart.isPending).isFalse()
        assertThat(Shadows.shadowOf(service.application).nextStartedActivity).isNull()
        assertThat(controller.scriptManager.scriptState).isInstanceOf<ScriptState.Stopped>()
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
