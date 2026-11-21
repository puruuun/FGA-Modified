package io.github.fate_grand_automata.rusttest

import io.github.fate_grand_automata.imaging.MediaProjectionScreenshotService
import io.github.fate_grand_automata.runner.ScreenshotServiceHolder
import io.github.fate_grand_automata.scripts.FgoCaptureGeometry
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanCaptureState
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNative
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNativeCapture
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNativeChecks
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNativeFrames
import io.github.fate_grand_automata.util.CutoutManager
import io.github.fate_grand_automata.util.DisplayHelper
import io.github.lib_automata.GameAreaManager
import io.github.lib_automata.Pattern
import io.github.lib_automata.ResizedScreenshotProvider
import io.github.lib_automata.Scale
import io.github.lib_automata.Size
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Issue #600, rust build type only: the support score scan's full-display frame, through the same
 * [ScreenshotServiceHolder.takeFullDisplayScreenshot] the settings calibration uses. It runs on the
 * script thread, which is the only thread that takes the run's 720p screenshots, and the capture lock
 * keeps the temporary size invisible to them. The decisions are in [ScoreScanNativeChecks].
 *
 * MediaProjection: the virtual display is resized to the full display and back. The restore installs
 * a new reader and forgets the last frame, so the next 720p screenshot waits for a new frame at the
 * restored size; a resize that threw is caught by comparing [MediaProjectionScreenshotService.captureState]
 * before and after. Root: the provider takes its own screencap and changes no state.
 */
class HolderScoreScanNativeFrames @Inject constructor(
    private val holder: ScreenshotServiceHolder,
    private val display: DisplayHelper,
    private val cutoutManager: CutoutManager,
    private val gameAreaManager: GameAreaManager,
    private val scale: Scale,
) : ScoreScanNativeFrames {
    override fun capture(): ScoreScanNativeCapture {
        val service = holder.screenshotService ?: return ScoreScanNativeCapture.Failed(ScoreScanNative.NO_SERVICE)
        if (service !is MediaProjectionScreenshotService && service !is ResizedScreenshotProvider) {
            return ScoreScanNativeCapture.Failed(ScoreScanNative.UNSUPPORTED, service.javaClass.name)
        }
        val projection = service as? MediaProjectionScreenshotService
        val metrics = display.metrics
        val displaySize = Size(metrics.widthPixels, metrics.heightPixels)
        val geometry = FgoCaptureGeometry.forLandscape(displaySize, cutoutManager::getCutoutAppliedRegion)
        val scriptCrop = gameAreaManager.gameArea * (scale.screenToImage ?: 1.0)

        val before = projection?.captureState()?.toScan()
        ScoreScanNativeChecks.before(projection != null, before, geometry, scriptCrop)?.let { return it }

        var error: Exception? = null
        val frame: Pattern? = try {
            runBlocking { holder.takeFullDisplayScreenshot() }
        } catch (e: Exception) {
            error = e
            null
        }
        val after = projection?.captureState()?.toScan()
        val frameSize = frame?.let { Size(it.width, it.height) }

        return when (val verdict = ScoreScanNativeChecks.after(
            projection != null, before, after, error?.toString(), frameSize, displaySize, geometry,
        )) {
            is ScoreScanNativeChecks.Verdict.Keep -> ScoreScanNativeCapture.Frame(frame!!, verdict.map)
            is ScoreScanNativeChecks.Verdict.Drop -> {
                frame?.close()
                verdict.capture
            }
        }
    }

    private fun MediaProjectionScreenshotService.CaptureState.toScan() = ScoreScanCaptureState(size, density, rendersIntoReader)
}
