package io.github.fate_grand_automata.rusttest

import io.github.fate_grand_automata.imaging.MediaProjectionScreenshotService
import io.github.fate_grand_automata.runner.ScreenshotServiceHolder
import io.github.fate_grand_automata.scripts.FgoCaptureGeometry
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNative
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNativeCapture
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNativeFrames
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNativeMap
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
 * keeps the temporary size invisible to them.
 *
 * MediaProjection: the virtual display is resized to the full display and back. The restore installs
 * a new reader and forgets the last frame, so the next 720p screenshot waits for a new frame at the
 * restored size; a restore that threw is caught here by comparing [MediaProjectionScreenshotService.captureState]
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
        val metrics = display.metrics
        val displaySize = Size(metrics.widthPixels, metrics.heightPixels)
        val geometry = FgoCaptureGeometry.forLandscape(displaySize, cutoutManager::getCutoutAppliedRegion)
        // ScreenshotManager crops each 720p screenshot at this region; the map must start from the same.
        val scriptCrop = gameAreaManager.gameArea * (scale.screenToImage ?: 1.0)
        if (geometry.crop != scriptCrop) {
            return ScoreScanNativeCapture.Failed(ScoreScanNative.GEOMETRY_MISMATCH, "capture ${geometry.crop}, script $scriptCrop")
        }

        val before = (service as? MediaProjectionScreenshotService)?.captureState()
        if (service is MediaProjectionScreenshotService && before?.size != geometry.captureSize) {
            return ScoreScanNativeCapture.Failed(
                ScoreScanNative.GEOMETRY_MISMATCH, "capture size ${before?.size}, geometry ${geometry.captureSize}"
            )
        }

        var error: Exception? = null
        val frame: Pattern? = try {
            runBlocking { holder.takeFullDisplayScreenshot() }
        } catch (e: Exception) {
            error = e
            null
        }

        if (service is MediaProjectionScreenshotService) {
            val after = service.captureState()
            if (after == null || after != before || !after.rendersIntoReader) {
                frame?.close()
                return ScoreScanNativeCapture.RestoreFailed("before $before, after $after${error?.let { ", $it" } ?: ""}")
            }
        }

        if (error != null) return ScoreScanNativeCapture.Failed(ScoreScanNative.ERROR, error.toString())
        if (frame == null) return ScoreScanNativeCapture.Failed(ScoreScanNative.NULL_FRAME)

        val landscape = Size(maxOf(displaySize.width, displaySize.height), minOf(displaySize.width, displaySize.height))
        if (frame.width != landscape.width || frame.height != landscape.height) {
            val size = "${frame.width}x${frame.height}"
            frame.close()
            return ScoreScanNativeCapture.Failed(ScoreScanNative.FRAME_SIZE, "frame $size, display $landscape")
        }
        return ScoreScanNativeCapture.Frame(frame, ScoreScanNativeMap.of(geometry, landscape))
    }
}
