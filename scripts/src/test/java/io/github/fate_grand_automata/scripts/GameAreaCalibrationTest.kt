package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.lib_automata.*
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.OutputStream
import kotlin.math.roundToInt
import kotlin.test.*

/** Native JVM OpenCV, with the same INTER_AREA resize and TM_CCOEFF_NORMED as DroidCvPattern. */
class GameAreaCalibrationTest {
    companion object { init { nu.pattern.OpenCV.loadLocally() } }

    private class CvPattern(val mat: Mat) : Pattern {
        override val width get() = mat.cols()
        override val height get() = mat.rows()
        override var tag = "calibration-fixture"
        override fun resize(size: Size): Pattern = CvPattern(Mat().also {
            Imgproc.resize(mat, it, org.opencv.core.Size(size.width.toDouble(), size.height.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        })
        override fun resize(target: Pattern, size: Size) = error("unused")
        override fun findMatches(template: Pattern, similarity: Double): Sequence<Match> {
            if (template.width > width || template.height > height) return emptySequence()
            val result = Mat()
            return try {
                Imgproc.matchTemplate(mat, (template as CvPattern).mat, result, Imgproc.TM_CCOEFF_NORMED)
                val best = Core.minMaxLoc(result)
                if (best.maxVal < similarity) emptySequence() else sequenceOf(Match(
                    Region(best.maxLoc.x.roundToInt(), best.maxLoc.y.roundToInt(), template.width, template.height), best.maxVal))
            } finally { result.release() }
        }
        override fun crop(region: Region): Pattern = mat.submat(Rect(region.x, region.y, region.width, region.height)).let {
            try { CvPattern(it.clone()) } finally { it.release() }
        }
        override fun copy(): Pattern = CvPattern(mat.clone())
        override fun grayPixels() = ByteArray(width * height).also { mat.get(0, 0, it) }
        override fun close() = mat.release()
        override fun save(stream: OutputStream) = error("unused")
        override fun threshold(value: Double): Pattern = error("unused")
        override fun isWhite() = error("unused")
        override fun isBlack() = error("unused")
        override fun floodFill(x: Double, y: Double, maxDiff: Double, newValue: Double): Pattern = error("unused")
        override fun fillText(): Pattern = error("unused")
        override fun meanColor(): MeanColor? = null
    }

    private fun image(bytes: ByteArray): CvPattern {
        val encoded = MatOfByte(*bytes)
        return try { CvPattern(Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE)) } finally { encoded.release() }
    }
    private fun template(name: String) = image(File("../app/src/main/assets/$name").readBytes())
    private fun owner(): CvPattern {
        val mat = Mat.zeros(1904, 3040, CvType.CV_8UC1)
        image(checkNotNull(javaClass.getResourceAsStream("/game-area/tablet-right-pane.png")).use { it.readBytes() }).use { crop ->
            val roi = mat.submat(Rect(1000, 0, crop.width, crop.height))
            try { crop.mat.copyTo(roi) } finally { roi.release() }
        }
        return CvPattern(mat)
    }
    private fun calibrate(frame: Pattern, server: GameServer = GameServer.Kr) =
        template("${server.simple}/weekly_notice.png").use { notice -> template("En/menu.png").use { menu ->
            GameAreaCalibrator(notice, menu).calibrate(frame, server)
        } }

    @Test fun `owner tablet split picture is calibrated in original display pixels`() {
        owner().use { frame ->
            val started = System.nanoTime()
            val actual = calibrate(frame)
            val result = assertIs<GameAreaCalibration.Success>(actual, actual.toString())
            println("Owner calibration: $result elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
            assertEquals(1049.0, result.region.x.toDouble(), 3.0)
            assertEquals(1991.0, result.region.width.toDouble(), 4.0)
            assertEquals(431.0, result.region.y.toDouble(), 3.0)
            assertEquals(result.region, FgoGameAreaManager(result.region.size) { result.region.location }.gameArea)
            assertEquals(result.region, result.offsets.region(result.displaySize, false))
        }
    }

    @Test fun `full screen sixteen nine crop calibrates to zero offsets`() {
        owner().use { full -> full.crop(Region(1049, 431, 1991, 1120)).use { frame ->
            val actual = calibrate(frame)
            val result = assertIs<GameAreaCalibration.Success>(actual, actual.toString())
            println("Full-screen calibration: $result")
            for (offset in listOf(result.offsets.left, result.offsets.top, result.offsets.right, result.offsets.bottom)) assertTrue(offset in 0..3)
            assertEquals(result.region, FgoGameAreaManager(result.region.size) { result.region.location }.gameArea)
        } }
    }

    @Test fun `blank frame and painted over MENU fail without offsets`() {
        CvPattern(Mat.zeros(1080, 1920, CvType.CV_8UC1)).use {
            assertEquals(GameAreaCalibration.Failure(GameAreaCalibration.Reason.AnchorsNotFound), calibrate(it))
        }
        owner().use { frame ->
            Imgproc.rectangle(frame.mat, org.opencv.core.Point(2720.0, 1350.0), org.opencv.core.Point(3039.0, 1549.0), Scalar(0.0), Imgproc.FILLED)
            assertEquals(GameAreaCalibration.Failure(GameAreaCalibration.Reason.AnchorsNotFound), calibrate(frame))
        }
    }

    private fun put(frame: CvPattern, template: Pattern, x: Int, y: Int, scale: Double) {
        template.resize(template.size * scale).use { resized ->
            val roi = frame.mat.submat(Rect(x, y, resized.width, resized.height))
            try { (resized as CvPattern).mat.copyTo(roi) } finally { roi.release() }
        }
    }

    @Test fun `one anchor at a different scale is rejected`() {
        CvPattern(Mat.zeros(1904, 3040, CvType.CV_8UC1)).use { frame ->
            template("Kr/weekly_notice.png").use { put(frame, it, 1080, 467, 1.55) }
            template("En/menu.png").use { put(frame, it, 2600, 1350, 2.0) }
            assertEquals(GameAreaCalibration.Failure(GameAreaCalibration.Reason.ScalesDisagree), calibrate(frame))
        }
    }

    @Test fun `readable anchors with incompatible geometry never produce saved offsets`() {
        CvPattern(Mat.zeros(1080, 1920, CvType.CV_8UC1)).use { frame ->
            template("Kr/weekly_notice.png").use { put(frame, it, 80, 50, 1.0) }
            template("En/menu.png").use { put(frame, it, 800, 700, 1.0) }
            assertEquals(GameAreaCalibration.Failure(GameAreaCalibration.Reason.InvalidRectangle), calibrate(frame))
        }
    }

    @Test fun `wide JP and KR anchors preserve the saved rectangle through the normal manager`() {
        for (server in listOf(GameServer.Kr, GameServer.Jp.Original)) {
            CvPattern(Mat.zeros(900, 1800, CvType.CV_8UC1)).use { frame ->
                template("${server.simple}/weekly_notice.png").use { put(frame, it, 166, 104, 1.0) }
                template("En/menu.png").use { put(frame, it, 1480, 702, 1.0) }
                val result = assertIs<GameAreaCalibration.Success>(calibrate(frame, server))
                println("Wide $server calibration: $result")
                assertEquals(60.0, result.region.x.toDouble(), 2.0)
                assertEquals(1680.0, result.region.width.toDouble(), 3.0)
                assertEquals(result.region, FgoGameAreaManager(result.region.size) { result.region.location }.gameArea)
            }
        }
    }

    @Test fun `unsupported server never attempts matching`() {
        template("Kr/weekly_notice.png").use { notice -> template("En/menu.png").use { menu ->
            CvPattern(Mat.zeros(720, 1280, CvType.CV_8UC1)).use { frame ->
                assertEquals(GameAreaCalibration.Failure(GameAreaCalibration.Reason.UnsupportedServer),
                    GameAreaCalibrator(notice, menu).calibrate(frame, GameServer.En.Original))
            }
        } }
    }

    @Test fun `saved picture offsets preserve the physical origin in reverse landscape`() {
        for (region in listOf(Region(1048, 430, 1991, 1120), Region(60, 80, 1680, 720))) {
            val display = Size(3040, 1904)
            val physical = GameAreaOffsets(region.x, region.y, display.width - region.right, display.height - region.bottom)
            val stored = physical.copy(left = physical.right, right = physical.left)
            val selected = stored.region(display, true)
            assertEquals(region, selected)
            assertEquals(region, FgoGameAreaManager(selected.size) { selected.location }.gameArea)
        }
    }
}
