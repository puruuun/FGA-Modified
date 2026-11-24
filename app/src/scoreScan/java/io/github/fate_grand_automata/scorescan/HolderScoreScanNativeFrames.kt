package io.github.fate_grand_automata.scorescan

import io.github.fate_grand_automata.imaging.MediaProjectionScreenshotService
import io.github.fate_grand_automata.runner.ScreenshotServiceHolder
import io.github.fate_grand_automata.scripts.FgoCaptureGeometry
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanCaptureState
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanFrameJudge
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNative
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNativeCapture
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNativeChecks
import io.github.fate_grand_automata.scripts.supportSelection.ScoreScanNativeFrameLoop
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
 * Issue #600, beta and rust build types only (the scoreScan source set): the support score scan's
 * full-display frame, through [ScreenshotServiceHolder.withFullDisplayFrames], the same capture as the settings calibration's
 * [ScreenshotServiceHolder.takeFullDisplayScreenshot] with the chance to take a newer frame before the
 * restore. It is called on the script thread, the only thread that takes the run's 720p screenshots,
 * which waits in `runBlocking` while the holder runs the capture, the frame loop and the judging on a
 * `Dispatchers.Default` thread; the capture lock keeps the temporary size invisible to any other
 * screenshot. The judge only reads the stop flag, so it does not wait on a pause. The decisions are
 * in [ScoreScanNativeChecks] and [ScoreScanNativeFrameLoop].
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
    override fun capture(judge: ScoreScanFrameJudge): ScoreScanNativeCapture {
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
        val landscape = ScoreScanNativeChecks.landscape(displaySize)
        val map = ScoreScanNativeMap.of(geometry, landscape)
        val frame: Pattern? = try {
            runBlocking {
                holder.withFullDisplayFrames { first, next ->
                    // A frame of another size is dropped by the checks below; it is not judged.
                    if (first.width != landscape.width || first.height != landscape.height) first
                    else ScoreScanNativeFrameLoop.run(first, map, next, { Thread.sleep(it.inWholeMilliseconds) }, judge)
                }
            }
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
