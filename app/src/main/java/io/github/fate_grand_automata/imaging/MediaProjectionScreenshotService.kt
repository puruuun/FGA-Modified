package io.github.fate_grand_automata.imaging

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import io.github.fate_grand_automata.util.KnownException
import io.github.fate_grand_automata.util.StorageProvider
import io.github.lib_automata.ColorManager
import io.github.lib_automata.Pattern
import io.github.lib_automata.ScreenshotService
import io.github.lib_automata.Size
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * This class is responsible for creating screenshots using [mediaProjection].
 */
class MediaProjectionScreenshotService(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private var imageSize: Size,
    private var screenDensity: Int,
    private val storageProvider: StorageProvider,
    private val colorManager: ColorManager
) : StoppableScreenshotService {
    private val bufferMat = Mat()
    private val grayscaleMat = Mat()
    private val grayscalePattern = DroidCvPattern(grayscaleMat, ownsMat = false)
    private val colorMat = Mat()
    private val colorPattern = DroidCvPattern(colorMat, ownsMat = false)

    @SuppressLint("WrongConstant")
    private var imageReader = ImageReader.newInstance(imageSize.width, imageSize.height, PixelFormat.RGBA_8888, 2)
    private val closed = AtomicBoolean(false)

    override val isStopped: Boolean
        get() = closed.get()

    /**
     * Guards the [Mat]s and the [imageReader] against [close] running while a screenshot is
     * being taken on the script thread. Note that the returned [Pattern]s are read by the caller
     * after the lock is released, so this covers the capture and the colour conversion only.
     */
    private val lock = Any()

    /**
     * Counted down as soon as the [VirtualDisplay] hands out its first frame. Registered before
     * the display is created so no frame can slip past it.
     */
    @Volatile
    private var firstFrame = CountDownLatch(1)

    /**
     * Whether a frame ever made it into [bufferMat]. Deliberately not derived from [firstFrame]:
     * the listener is dispatched on the main looper, so the latch can still be closed while a
     * frame has already been captured straight off the reader.
     */
    private var hasFrame = false

    init {
        listenForFirstFrame()
    }

    private fun listenForFirstFrame() {
        val latch = firstFrame
        imageReader.setOnImageAvailableListener(
            { reader ->
                latch.countDown()

                // Only the first frame is waited on, so stop posting to the looper after it.
                reader.setOnImageAvailableListener(null, null)
            },
            Handler(Looper.getMainLooper())
        )
    }

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            close()
        }
    }

    private val virtualDisplay: VirtualDisplay? = mediaProjection.apply {
        this.registerCallback(mediaProjectionCallback, null)
    }.createVirtualDisplay(
        "ScreenCapture",
        imageSize.width, imageSize.height, screenDensity,
        0, imageReader.surface, null, null
    )

    /** Reuse the projection and its VirtualDisplay: Android 14 tokens are single-use. */
    @SuppressLint("WrongConstant")
    fun resize(size: Size, density: Int) = synchronized(lock) {
        if (closed.get() || (imageSize == size && screenDensity == density)) return@synchronized
        val display = virtualDisplay ?: return@synchronized
        val replacement = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2)
        val previous = imageReader
        try {
            display.surface = null
            display.resize(size.width, size.height, density)
            display.surface = replacement.surface
        } catch (e: RuntimeException) {
            replacement.close()
            throw e
        }
        previous.setOnImageAvailableListener(null, null)
        imageReader = replacement
        imageSize = size
        screenDensity = density
        hasFrame = false
        bufferMat.release()
        firstFrame = CountDownLatch(1)
        listenForFirstFrame()
        previous.close()
    }

    override fun takeScreenshot(): Pattern = synchronized(lock) {
        /*
         * The projection can stop at any moment: the system revokes it, another app takes it
         * over, or the display changes underneath us. [close] releases the Mats when that
         * happens, and a released Mat is empty, which is what blows up in cvtColor below.
         * A closed ImageReader hands out null images instead of throwing, so this is the only
         * place the script gets to hear about it.
         */
        if (closed.get()) {
            throw KnownException(KnownException.Reason.ScreenCaptureStopped)
        }

        screenshotIntoBuffer()

        if (colorManager.isColor) {
            Imgproc.cvtColor(bufferMat, colorMat, Imgproc.COLOR_RGBA2BGR)

            colorPattern
        } else {
            Imgproc.cvtColor(bufferMat, grayscaleMat, Imgproc.COLOR_RGBA2GRAY)

            grayscalePattern
        }
    }

    /**
     * Reuses the projection to take an owned native-display grayscale frame, then restores the previous
     * size. The same lock as ordinary captures keeps the temporary size invisible to script screenshots.
     * When the capture already has [size] and [density] (a 1280x720 display), both resizes return early:
     * no new reader, and the frame is the newest image or, if nothing changed, the last frame captured.
     */
    fun takeFullDisplayScreenshot(size: Size, density: Int): Pattern =
        withFullDisplayFrames(size, density) { first, _ -> first }

    /**
     * [takeFullDisplayScreenshot] for a caller that may need a newer frame before the restore (the rust
     * build's support score scan): [block] gets the first owned frame and a function for a newer one,
     * which answers null when the reader received nothing since the last. The lock and the full size
     * are held until [block] returns, and the previous size is restored once, after it, however it ends.
     */
    fun <T> withFullDisplayFrames(size: Size, density: Int, block: (first: Pattern, next: () -> Pattern?) -> T): T = synchronized(lock) {
        val previousSize = imageSize
        val previousDensity = screenDensity
        try {
            resize(size, density)
            if (closed.get()) throw KnownException(KnownException.Reason.ScreenCaptureStopped)
            screenshotIntoBuffer()
            block(grayCopy()) {
                if (closed.get()) throw KnownException(KnownException.Reason.ScreenCaptureStopped)
                if (screenshotIntoBuffer()) grayCopy() else null
            }
        } finally { resize(previousSize, previousDensity) }
    }

    private fun grayCopy(): Pattern {
        val gray = Mat()
        try {
            Imgproc.cvtColor(bufferMat, gray, Imgproc.COLOR_RGBA2GRAY)
            return DroidCvPattern(gray)
        } catch (e: Exception) { gray.release(); throw e }
    }

    /** What the virtual display renders into; [rendersIntoReader] is false when a resize left it without the current reader's surface. */
    data class CaptureState(val size: Size, val density: Int, val rendersIntoReader: Boolean)

    /**
     * Reads the capture's state under the capture lock and changes nothing. For a caller of
     * [withFullDisplayFrames] during a run (the rust build's support score scan), which compares
     * it before and after: a first resize that throws after detaching the surface leaves the display
     * without the reader, and later screenshots serve the old frame from [bufferMat] as "nothing new";
     * a restore that throws leaves the full-size reader in place, so later screenshots are of the full
     * size. Null once closed.
     */
    fun captureState(): CaptureState? = synchronized(lock) {
        if (closed.get()) return@synchronized null
        val display = virtualDisplay
        CaptureState(imageSize, screenDensity, display != null && display.surface === imageReader.surface)
    }

    /** True when a new image was copied into [bufferMat]. */
    private fun screenshotIntoBuffer(): Boolean {
        val image = imageReader.acquireLatestImage()
            ?: awaitFirstImage()
            /*
             * Nothing new since the last call, which just means the screen didn't change.
             * [bufferMat] still holds the previous frame.
             */
            ?: return false

        image.use {
            val plane = it.planes[0]
            val buffer = plane.buffer

            val rowStride = plane.rowStride.toLong()

            // Buffer memory isn't copied by OpenCV
            Mat(it.height, it.width, CvType.CV_8UC4, buffer, rowStride)
                .use { tempMat ->
                    tempMat.copyTo(bufferMat)
                }
        }

        hasFrame = true
        return true
    }

    /**
     * The [VirtualDisplay] needs a moment after being created before the system composes the
     * first frame into it. Until that happens, [ImageReader.acquireLatestImage] returns `null`
     * and [bufferMat] stays empty, which used to blow up in `cvtColor` further down.
     *
     * Only the very first frame is worth waiting for; afterwards a `null` image simply means
     * the screen contents didn't change.
     */
    private fun awaitFirstImage(): Image? {
        if (hasFrame) {
            return null
        }

        if (virtualDisplay == null) {
            throw KnownException(KnownException.Reason.NoVirtualDisplay)
        }

        if (!firstFrame.await(FIRST_FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw KnownException(KnownException.Reason.NoScreenshotReceived)
        }

        /*
         * close() counts the latch down too, so revoking the projection mid-wait doesn't
         * leave the script sitting here for the full timeout.
         */
        if (closed.get()) {
            throw KnownException(KnownException.Reason.ScreenCaptureStopped)
        }

        return imageReader.acquireLatestImage()
            ?: throw KnownException(KnownException.Reason.NoScreenshotReceived)
    }

    override fun close() {
        /*
         * stop() below makes the projection call onStop(), which lands right back here. Unregistering
         * first isn't enough on its own, because close() is also reached *from* onStop() when the
         * user revokes the projection.
         */
        if (!closed.compareAndSet(false, true)) {
            return
        }

        /*
         * Happens before taking the lock: a script thread waiting for the first frame has to be
         * let go before it can hand the lock over.
         */
        firstFrame.countDown()

        synchronized(lock) {
            bufferMat.release()
            grayscaleMat.release()
            grayscalePattern.close()
            colorMat.release()
            colorPattern.close()

            virtualDisplay?.release()

            imageReader.close()

            mediaProjection.unregisterCallback(mediaProjectionCallback)
            mediaProjection.stop()
        }
    }

    override fun startRecording() =
        MediaProjectionRecording(context, mediaProjection, imageSize, screenDensity, storageProvider)

    companion object {
        private const val FIRST_FRAME_TIMEOUT_SECONDS = 3L
    }
}
