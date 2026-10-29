package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.weekly.*
import io.github.lib_automata.*
import io.mockk.every
import io.mockk.mockk
import org.opencv.core.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.test.*

/** Offline owner frames, 720p height, and map 30601 at half size. No network in tests. */
class WeeklyOlympusTest {
    companion object { init { nu.pattern.OpenCV.loadLocally() } }

    private fun image(name: String): Mat {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/weekly/olympus/$name.png")).use { it.readBytes() }
        val encoded = MatOfByte(*bytes)
        return try { Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE) } finally { encoded.release() }
    }

    /** Same SIFT settings, ratio filter and unique-scene-point rule as DroidCvPattern.featureMatches. */
    private fun matches(map: Mat, scene: Mat, region: Region): List<ImageFeatureMatch> {
        val sift = SIFT.create(5000, 3, 0.025)
        val matcher = BFMatcher.create(Core.NORM_L2, false)
        val mapKeys = MatOfKeyPoint(); val sceneKeys = MatOfKeyPoint()
        val mapDesc = Mat(); val sceneDesc = Mat(); val empty = Mat()
        val mask = Mat.zeros(scene.rows(), scene.cols(), CvType.CV_8UC1)
        val pairs = mutableListOf<MatOfDMatch>()
        try {
            Imgproc.rectangle(mask, Point(region.x.toDouble(), region.y.toDouble()),
                Point((region.right - 1).toDouble(), (region.bottom - 1).toDouble()), Scalar(255.0), Imgproc.FILLED)
            sift.detectAndCompute(map, empty, mapKeys, mapDesc)
            sift.detectAndCompute(scene, mask, sceneKeys, sceneDesc)
            matcher.knnMatch(mapDesc, sceneDesc, pairs, 2)
            val a = mapKeys.toArray(); val b = sceneKeys.toArray()
            return pairs.mapNotNull { pair -> pair.toArray().let { p ->
                p.firstOrNull()?.takeIf { p.size == 2 && it.distance < 0.70 * p[1].distance }
            } }.sortedBy { it.distance }.distinctBy { it.trainIdx }.map { m ->
                ImageFeatureMatch(a[m.queryIdx].pt.x, a[m.queryIdx].pt.y, b[m.trainIdx].pt.x, b[m.trainIdx].pt.y)
            }
        } finally {
            pairs.forEach { it.release() }; mapKeys.release(); sceneKeys.release(); mapDesc.release(); sceneDesc.release()
            empty.release(); mask.release()
        }
    }

    private fun localize(frame: String) {
        val scene = image(frame)
        try {
            val screen = mockk<Pattern>()
            every { screen.width } returns scene.cols(); every { screen.height } returns scene.rows()
            val fits = listOf("base", "composite").map { name ->
                val mat = image(name)
                try {
                    val pattern = mockk<Pattern>()
                    every { pattern.featureMatches(screen, any()) } answers { matches(mat, scene, secondArg()) }
                    MapLocalizer().localize(screen, pattern)
                } finally { mat.release() }
            }
            println("Olympus $frame base=${fits[0]} composite=${fits[1]}")
            assertTrue(MapLocalizer.accepts(fits[1]), "$frame: ${fits[1]}")
            assertEquals(1.64, fits[1]!!.scaleX, 0.12)
        } finally { scene.release() }
    }

    @Test fun `JP top accepts composite`() = localize("jp-olympus-top")
    @Test fun `JP bottom accepts composite`() = localize("jp-olympus-bottom")
    @Test fun `KR top accepts composite`() = localize("kr-olympus-top")
    @Test fun `KR bottom accepts composite`() = localize("kr-olympus-bottom")

    @Test fun `layers blend alpha in depth order with scaled and clipped positions`() {
        val gray = ByteArray(16) { 100 }
        val white = MapOverlay(2, 2, IntArray(4) { 0x80FFFFFF.toInt() })
        val black = MapOverlay(1, 1, intArrayOf(0xFF000000.toInt()))
        MapGimmickCompositor.composite(gray, 4, 4, listOf(
            MapGimmick(x = 0.0, y = 0.0, depthOffset = 2, scale = 2000) to black,
            MapGimmick(x = 1.0, y = 1.0, depthOffset = 1) to white))
        assertEquals(0, gray[0].toInt() and 255)
        assertEquals(178, gray[1].toInt() and 255)
        assertEquals(100, gray[15].toInt() and 255)
    }
}
