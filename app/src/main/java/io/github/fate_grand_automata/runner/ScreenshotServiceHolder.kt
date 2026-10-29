package io.github.fate_grand_automata.runner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import io.github.fate_grand_automata.imaging.MediaProjectionScreenshotService
import io.github.fate_grand_automata.root.RootScreenshotService
import io.github.fate_grand_automata.root.SuperUser
import io.github.fate_grand_automata.scripts.FgoCaptureGeometry
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.fate_grand_automata.scripts.prefs.wantsMediaProjectionToken
import io.github.fate_grand_automata.util.DisplayHelper
import io.github.fate_grand_automata.util.CutoutManager
import io.github.fate_grand_automata.util.StorageProvider
import io.github.lib_automata.ColorManager
import io.github.lib_automata.PlatformImpl
import io.github.lib_automata.ResizedScreenshotProvider
import io.github.lib_automata.ScreenshotService
import io.github.lib_automata.Size
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.roundToInt

@ServiceScoped
class ScreenshotServiceHolder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefs: IPreferences,
    private val storageProvider: StorageProvider,
    private val display: DisplayHelper,
    private val colorManager: ColorManager,
    private val mediaProjectionManager: MediaProjectionManager,
    private val platformImpl: PlatformImpl,
    private val cutoutManager: CutoutManager
) : AutoCloseable {
    var screenshotService: ScreenshotService? = null
        private set

    fun prepareScreenshotService() {
        val metrics = display.metrics
        val geometry = FgoCaptureGeometry.forLandscape(
            Size(metrics.widthPixels, metrics.heightPixels), cutoutManager::getCutoutAppliedRegion)
        val scale = geometry.scale

        screenshotService = try {
            if (prefs.wantsMediaProjectionToken) {
                // Cloning the Intent allows reuse.
                // Otherwise, the Intent gets consumed and MediaProjection cannot be started multiple times.
                val token = ScriptRunnerService.mediaProjectionToken?.clone() as Intent

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // not allowed to reuse tokens on Android 14
                    ScriptRunnerService.mediaProjectionToken = null
                }

                val mediaProjection =
                    mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, token)

                if (mediaProjection == null) {
                    Timber.e("MediaProjection is null, cannot prepare screenshot service")

                    null
                } else {
                    val scaledSize = geometry.captureSize
                    val scaledDensity = (metrics.densityDpi / scale).roundToInt()

                    MediaProjectionScreenshotService(
                        context,
                        mediaProjection,
                        scaledSize,
                        scaledDensity,
                        storageProvider,
                        colorManager
                    )
                }
            } else {
                val rootSS = RootScreenshotService(
                    SuperUser(),
                    colorManager
                )

                ResizedScreenshotProvider(rootSS, scale, platformImpl)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error preparing screenshot service")
            null
        }
    }

    override fun close() {
        screenshotService?.close()
        screenshotService = null
    }

    fun resizeCapture() {
        val service = screenshotService ?: return
        val metrics = display.metrics
        val geometry = FgoCaptureGeometry.forLandscape(
            Size(metrics.widthPixels, metrics.heightPixels), cutoutManager::getCutoutAppliedRegion)
        when (service) {
            is MediaProjectionScreenshotService -> service.resize(
                geometry.captureSize, (metrics.densityDpi / geometry.scale).roundToInt())
            is ResizedScreenshotProvider -> service.resize(geometry.scale)
        }
    }
}
