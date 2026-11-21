package io.github.fate_grand_automata.scripts.supportSelection

import io.github.fate_grand_automata.scripts.FgoCaptureGeometry
import io.github.lib_automata.Location
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.github.lib_automata.Size
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

/**
 * Issue #600: one full-display grayscale frame per logged screen of the support score scan, so the
 * score fields can be studied at the display's own resolution. The Android side is in the rust
 * source set; no other build binds it.
 */
interface ScoreScanNativeFrames {
    /** Never throws for a capture problem: it answers [ScoreScanNativeCapture.Failed] or [ScoreScanNativeCapture.RestoreFailed]. */
    fun capture(): ScoreScanNativeCapture
}

sealed interface ScoreScanNativeCapture {
    /** [frame] is owned by the caller; [map] is the geometry the 720p snapshots were captured with. */
    class Frame(val frame: Pattern, val map: ScoreScanNativeMap) : ScoreScanNativeCapture

    data class Failed(val reason: String, val detail: String? = null) : ScoreScanNativeCapture

    /**
     * The capture could not put the 720p capture back as it was, so the next snapshot could be stale
     * or of another size: the scan must end before any gesture.
     */
    data class RestoreFailed(val detail: String) : ScoreScanNativeCapture
}

/**
 * Maps the scan's 720p image coordinates (inside the game-area crop, as [io.github.lib_automata.Transformer.toImage]
 * gives them) to pixels of a full-display frame. [capture] and [origin] are what the 720p capture was
 * made with ([FgoCaptureGeometry.captureSize] and the game-area crop's corner); the display scales the
 * whole screen into the capture, so each axis has its own ratio.
 */
class ScoreScanNativeMap(val frame: Size, val capture: Size, val origin: Location) {
    companion object {
        /** The geometry ScreenshotServiceHolder captures with, for a full-display frame of [frame]. */
        fun of(display: Size, window: (Size) -> Region, frame: Size) =
            of(FgoCaptureGeometry.forLandscape(display, window), frame)

        fun of(geometry: FgoCaptureGeometry, frame: Size) =
            ScoreScanNativeMap(frame, geometry.captureSize, geometry.crop.location)
    }

    val scaleX = frame.width.toDouble() / capture.width
    val scaleY = frame.height.toDouble() / capture.height

    /** Each edge rounded on its own, so neighbouring regions share their edges; may reach outside the frame. */
    fun toNative(image: Region): Region {
        val left = ((origin.x + image.x) * scaleX).roundToInt()
        val top = ((origin.y + image.y) * scaleY).roundToInt()
        val right = ((origin.x + image.x + image.width) * scaleX).roundToInt()
        val bottom = ((origin.y + image.y + image.height) * scaleY).roundToInt()
        return Region(left, top, maxOf(1, right - left), maxOf(1, bottom - top))
    }
}

/** What is saved of one row: [region] in the frame, the score [field] inside it, and the sides cut by the frame. */
data class ScoreScanNativeCrop(val region: Region, val field: Region, val clamped: List<String>)

object ScoreScanNative {
    const val FOLDER = "native"

    /**
     * 20 px at 720p on each side of the 60x30 field: room for later experiments to move and resize the
     * crop. On KR and JP (16:9 and wide) that holds the score panel with its label, the laurel
     * background of the row and the dark band below the row; on some screens the left margin also
     * holds the right edge of the neighbouring icon, a game UI fragment. No friend name, login time, FP
     * text or servant name was found in it (86 crops cut from every support reference screenshot).
     */
    const val MARGIN = 20

    /**
     * Checked after the capture has returned; it cannot interrupt it. A first frame after a resize
     * normally arrives within a few display frames and the full-size copy takes tens of milliseconds;
     * 1.5 s is half the capture's own first-frame timeout (3 s, which ends the capture with an
     * exception, logged as `error`), so a slower capture means the compositor stalled and the frame
     * may not show the screen the rows were read from.
     */
    val CAPTURE_BOUND = 1500.milliseconds

    const val UNSUPPORTED = "unsupported"
    const val NO_SERVICE = "no_service"
    const val NULL_FRAME = "null_frame"
    const val FRAME_SIZE = "frame_size"
    const val GEOMETRY_MISMATCH = "geometry_mismatch"
    const val SLOW = "slow"
    const val ERROR = "error"
    const val FIELD_OUTSIDE_FRAME = "field_outside_frame"
    const val RESTORE_FAILED = "restore_failed"

    /** The look diagnosis's word for a capture that delivered a frame. */
    const val OK = "ok"
    const val CAPTURE_STOPPED = "capture_stopped"

    /** Null when the field itself is not wholly inside the frame. */
    fun crop(map: ScoreScanNativeMap, fieldImage: Region): ScoreScanNativeCrop? {
        val frame = Region(Location(), map.frame)
        val field = map.toNative(fieldImage)
        if (field !in frame) return null
        val grown = map.toNative(
            Region(fieldImage.x - MARGIN, fieldImage.y - MARGIN, fieldImage.width + 2 * MARGIN, fieldImage.height + 2 * MARGIN)
        )
        val left = maxOf(0, grown.x)
        val top = maxOf(0, grown.y)
        val right = minOf(map.frame.width, grown.x + grown.width)
        val bottom = minOf(map.frame.height, grown.y + grown.height)
        val clamped = buildList {
            if (grown.x < 0) add("left")
            if (grown.y < 0) add("top")
            if (grown.x + grown.width > map.frame.width) add("right")
            if (grown.y + grown.height > map.frame.height) add("bottom")
        }
        val region = Region(left, top, right - left, bottom - top)
        return ScoreScanNativeCrop(region, field - region.location, clamped)
    }

    fun png(pattern: Pattern): ByteArray = ByteArrayOutputStream().also { pattern.save(it) }.toByteArray()

    /**
     * The native field area-averaged down to the 720p field's size, compared by Pearson correlation
     * with the 720p pixels that were read: it ignores the small brightness and contrast differences of
     * the two scaling paths but falls fast when the content moved. Null when either side has no
     * variation (the measure is undefined) or the sizes differ.
     */
    fun match(frame: Pattern, field: Region, readPixels: ByteArray, readSize: Size): Double? =
        frame.crop(field).use { native ->
            native.resize(readSize).use { it.grayPixels() }
        }?.let { correlation(it, readPixels) }

    fun correlation(a: ByteArray, b: ByteArray): Double? {
        if (a.size != b.size || a.isEmpty()) return null
        val n = a.size
        var sumA = 0.0
        var sumB = 0.0
        for (i in 0 until n) {
            sumA += a[i].toInt() and 0xff
            sumB += b[i].toInt() and 0xff
        }
        val meanA = sumA / n
        val meanB = sumB / n
        var cov = 0.0
        var varA = 0.0
        var varB = 0.0
        for (i in 0 until n) {
            val da = (a[i].toInt() and 0xff) - meanA
            val db = (b[i].toInt() and 0xff) - meanB
            cov += da * db
            varA += da * da
            varB += db * db
        }
        if (varA == 0.0 || varB == 0.0) return null
        return cov / sqrt(varA * varB)
    }
}

/** One logged row of a screen, kept until that screen's native frame is taken. */
class ScoreScanLoggedRow(
    val screen: Int,
    val row: Int,
    val cropSha256: String,
    val fieldImage: Region,
    val fieldPixels: ByteArray,
    val fieldSize: Size,
)

/** The result for one logged row: [native] is null when [reason] says why. */
data class ScoreScanNativeRecord(
    val time: Long,
    val server: String,
    val screen: Int,
    val row: Int,
    val cropSha256: String,
    val native: ScoreScanNativeResult?,
    val reason: String?,
    val detail: String?,
    val captureMicros: Long?,
    /** How many looks the list check after this screen's capture needed; null when none was made. */
    val postNativeLooks: Int? = null,
)

data class ScoreScanNativeResult(
    val crop: String,
    val cropSha256: String,
    val frame: Size,
    val scaleX: Double,
    val scaleY: Double,
    val region: Region,
    val field: Region,
    val clamped: List<String>,
    val match: Double?,
    val captureMicros: Long,
)

/** The native part of summary.json; per screen for frames and times, per row for the match. */
class ScoreScanNativeTally {
    var taken = 0; private set
    val failed = sortedMapOf<String, Int>()
    var frame: Size? = null; private set
    var scaleX: Double? = null; private set
    var scaleY: Double? = null; private set
    val captureMicros = mutableListOf<Long>()
    val matches = mutableListOf<Double>()

    fun taken(map: ScoreScanNativeMap, micros: Long) {
        taken++
        frame = map.frame
        scaleX = map.scaleX
        scaleY = map.scaleY
        captureMicros += micros
    }

    fun failed(reason: String, micros: Long?) {
        failed[reason] = (failed[reason] ?: 0) + 1
        micros?.let { captureMicros += it }
    }

    fun match(value: Double?) {
        value?.takeIf { it.isFinite() }?.let { matches += it }
    }

    /** Screens with at least one match value, all below [LOW_MATCH]: the list had moved since the snapshot. */
    var lowMatchScreens = 0; private set

    fun screenMatches(values: List<Double?>) {
        val known = values.filterNotNull().filter { it.isFinite() }
        if (known.isNotEmpty() && known.all { it < LOW_MATCH }) lowMatchScreens++
    }

    var lookScreens = 0; private set
    var lookScreensMoreThanOne = 0; private set
    var maxLooks = 0; private set

    fun looks(count: Int) {
        lookScreens++
        if (count > 1) lookScreensMoreThanOne++
        maxLooks = maxOf(maxLooks, count)
    }

    companion object {
        const val LOW_MATCH = 0.9
    }
}

/** The MediaProjection capture's state, as `MediaProjectionScreenshotService.captureState()` reads it. */
data class ScoreScanCaptureState(val size: Size, val density: Int, val rendersIntoReader: Boolean)

/**
 * The checks around one full-display capture, kept apart from the Android calls so they can be tested
 * on the JVM. [projection] is true for MediaProjection, false for root capture, which changes no state.
 */
object ScoreScanNativeChecks {
    sealed interface Verdict {
        data class Keep(val map: ScoreScanNativeMap) : Verdict
        data class Drop(val capture: ScoreScanNativeCapture) : Verdict
    }

    /** Null to go on with the capture. */
    fun before(
        projection: Boolean,
        state: ScoreScanCaptureState?,
        geometry: FgoCaptureGeometry,
        scriptCrop: Region,
    ): ScoreScanNativeCapture.Failed? = when {
        // Closed: the next 720p snapshot throws ScreenCaptureStopped, so no stale frame can be read.
        projection && state == null -> ScoreScanNativeCapture.Failed(ScoreScanNative.CAPTURE_STOPPED)
        // ScreenshotManager cuts each 720p screenshot at scriptCrop; the map must start from the same.
        geometry.crop != scriptCrop ->
            ScoreScanNativeCapture.Failed(ScoreScanNative.GEOMETRY_MISMATCH, "capture ${geometry.crop}, script $scriptCrop")
        projection && state?.size != geometry.captureSize ->
            ScoreScanNativeCapture.Failed(ScoreScanNative.GEOMETRY_MISMATCH, "capture size ${state?.size}, geometry ${geometry.captureSize}")
        else -> null
    }

    /**
     * [frameSize] is null when no frame came back. A projection whose state changed, or whose display
     * no longer renders into the current reader, could serve a stale or full-size frame next.
     */
    fun after(
        projection: Boolean,
        before: ScoreScanCaptureState?,
        after: ScoreScanCaptureState?,
        error: String?,
        frameSize: Size?,
        display: Size,
        geometry: FgoCaptureGeometry,
    ): Verdict {
        if (projection) {
            if (after == null) return Verdict.Drop(ScoreScanNativeCapture.Failed(ScoreScanNative.CAPTURE_STOPPED, error))
            if (after != before || !after.rendersIntoReader) {
                return Verdict.Drop(ScoreScanNativeCapture.RestoreFailed("before $before, after $after${error?.let { ", $it" } ?: ""}"))
            }
        }
        if (error != null) return Verdict.Drop(ScoreScanNativeCapture.Failed(ScoreScanNative.ERROR, error))
        if (frameSize == null) return Verdict.Drop(ScoreScanNativeCapture.Failed(ScoreScanNative.NULL_FRAME))
        val landscape = Size(maxOf(display.width, display.height), minOf(display.width, display.height))
        if (frameSize != landscape) {
            return Verdict.Drop(ScoreScanNativeCapture.Failed(ScoreScanNative.FRAME_SIZE, "frame $frameSize, display $landscape"))
        }
        return Verdict.Keep(ScoreScanNativeMap.of(geometry, landscape))
    }
}
