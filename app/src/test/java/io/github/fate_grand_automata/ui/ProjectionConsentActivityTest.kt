package io.github.fate_grand_automata.ui

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.runner.ScriptRunnerService
import io.github.fate_grand_automata.ui.runner.ScriptRunnerUIAction
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ProjectionConsentActivityTest {
    private lateinit var serviceController: ServiceController<ScriptRunnerService>
    private lateinit var service: ScriptRunnerService

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        ScriptRunnerService.mediaProjectionToken = null
        serviceController = Robolectric.buildService(ScriptRunnerService::class.java).create()
        service = serviceController.get()
        service.prefsCore.useRootForScreenshots.set(false)
        service.controller.screenshotServiceHolder.close()
        service.controller.projectionRestart.reset()
        Shadows.shadowOf(service.application).clearNextStartedActivities()
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        if (::service.isInitialized) {
            service.controller.screenshotServiceHolder.close()
            service.controller.projectionRestart.reset()
        }
        ScriptRunnerService.mediaProjectionToken = null
        if (::serviceController.isInitialized) {
            serviceController.destroy()
        }
    }

    @Test
    fun manifestEntry_hasExcludeFromRecents_andDoesNotHaveNoHistory() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val activityInfo = app.packageManager.getActivityInfo(
            ComponentName(app, ProjectionConsentActivity::class.java),
            0
        )

        assertThat(activityInfo.flags and ActivityInfo.FLAG_NO_HISTORY).isEqualTo(0)
        assertThat(activityInfo.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS)
            .isEqualTo(ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS)
        assertThat(activityInfo.exported).isFalse()
    }

    @Test
    fun onCreate_launchesConsentOnce_andDoesNotRelaunchAfterConfigChange() {
        val activityController = Robolectric.buildActivity(ProjectionConsentActivity::class.java).setup()
        val activity = activityController.get()

        val expectedIntent = activity.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()
        val launched = Shadows.shadowOf(activity).nextStartedActivityForResult
        assertThat(launched).isNotNull()
        assertThat(launched.intent.action).isEqualTo(expectedIntent.action)
        assertThat(launched.intent.component).isEqualTo(expectedIntent.component)

        val outState = Bundle()
        activityController.saveInstanceState(outState).pause().stop().destroy()

        val recreated = Robolectric.buildActivity(ProjectionConsentActivity::class.java).setup(outState).get()
        assertThat(Shadows.shadowOf(recreated).nextStartedActivityForResult).isNull()
    }

    private fun requestConsentFromOverlay() {
        service.controller.overlay.act(ScriptRunnerUIAction.Start)
        assertThat(service.controller.projectionRestart.isPending).isTrue()
        val consentIntent = Shadows.shadowOf(service.application).nextStartedActivity
        assertThat(consentIntent?.component?.className).isEqualTo(ProjectionConsentActivity::class.java.name)
        Shadows.shadowOf(service.application).clearNextStartedActivities()
    }

    @Test
    fun resultCanceled_clearsPendingAndShowsToast() {
        requestConsentFromOverlay()

        val activityController = Robolectric.buildActivity(ProjectionConsentActivity::class.java).setup()
        val activity = activityController.get()
        val launched = Shadows.shadowOf(activity).nextStartedActivityForResult
        assertThat(launched).isNotNull()

        Shadows.shadowOf(activity).receiveResult(launched.intent, Activity.RESULT_CANCELED, null)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertThat(activity.isFinishing).isTrue()
        assertThat(service.controller.projectionRestart.isPending).isFalse()
        assertThat(ShadowToast.getTextOfLatestToast())
            .isEqualTo(service.getString(R.string.screen_capture_permission_denied))
    }

    @Test
    fun resultOk_deliversTokenToScriptRunnerServiceAndFinishes() {
        requestConsentFromOverlay()

        val activityController = Robolectric.buildActivity(ProjectionConsentActivity::class.java).setup()
        val activity = activityController.get()
        val expectedIntent = activity.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()
        val launched = Shadows.shadowOf(activity).nextStartedActivityForResult
        assertThat(launched).isNotNull()
        assertThat(launched.intent.action).isEqualTo(expectedIntent.action)

        val token = Intent("test.media.projection.GRANTED")
        Shadows.shadowOf(activity).receiveResult(launched.intent, Activity.RESULT_OK, token)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertThat(activity.isFinishing).isTrue()
        assertThat(ScriptRunnerService.mediaProjectionToken).isEqualTo(token)
        assertThat(service.controller.projectionRestart.isPending).isFalse()
    }

    @Test
    fun consentDialogInFront_pauseStopAndConfigChange_keepPendingUntilResultOk() {
        requestConsentFromOverlay()

        val activityController = Robolectric.buildActivity(ProjectionConsentActivity::class.java).setup()
        val activity = activityController.get()
        val expectedIntent = activity.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()
        val launched = Shadows.shadowOf(activity).nextStartedActivityForResult
        assertThat(launched).isNotNull()
        assertThat(launched.intent.action).isEqualTo(expectedIntent.action)

        // System consent dialog comes to the foreground: activity pauses and stops without finishing.
        activityController.pause().stop()
        assertThat(service.controller.projectionRestart.isPending).isTrue()

        // A configuration change while the dialog is in front destroys the activity without finishing.
        val outState = Bundle()
        activityController.saveInstanceState(outState).destroy()
        assertThat(service.controller.projectionRestart.isPending).isTrue()

        val recreatedController = Robolectric.buildActivity(ProjectionConsentActivity::class.java).setup(outState)
        val recreated = recreatedController.get()
        assertThat(Shadows.shadowOf(recreated).nextStartedActivityForResult).isNull()

        val token = Intent("test.media.projection.GRANTED_AFTER_RECREATE")
        Shadows.shadowOf(recreated).receiveResult(launched.intent, Activity.RESULT_OK, token)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertThat(recreated.isFinishing).isTrue()
        assertThat(ScriptRunnerService.mediaProjectionToken).isEqualTo(token)
        assertThat(service.controller.projectionRestart.isPending).isFalse()
    }

    @Test
    fun destroyedWhileFinishingWithoutResult_clearsPending() {
        requestConsentFromOverlay()

        val activityController = Robolectric.buildActivity(ProjectionConsentActivity::class.java).setup()
        val activity = activityController.get()
        activity.finish()
        activityController.pause().stop().destroy()

        assertThat(service.controller.projectionRestart.isPending).isFalse()
    }
}
