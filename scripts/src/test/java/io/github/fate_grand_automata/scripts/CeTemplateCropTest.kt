package io.github.fate_grand_automata.scripts

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Built-in support CE templates are matched the way DroidCvPattern does it (grayscale, 720p, TM_CCOEFF_NORMED,
 * best match). During an event the game draws the bonus label over the bottom of the CE strip from about
 * row 30, so a template taller than 25 rows (the height Support Image Maker and the Atlas crops use) stops matching.
 */
class CeTemplateCropTest {
    private val ceFolder = File("../app/src/main/assets/Support/ce")
    private val referenceImages = File("../docs/reference-images")

    private class Best(val score: Double, val x: Int, val y: Int)

    private fun gray(file: File): Mat {
        nu.pattern.OpenCV.loadLocally()
        val mat = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
        check(!mat.empty()) { "cannot read $file" }
        return mat
    }

    private fun frame720(file: File): Mat {
        val mat = gray(file)
        if (mat.rows() == 720) return mat
        val out = Mat()
        Imgproc.resize(mat, out, Size((mat.cols() * 720 / mat.rows()).toDouble(), 720.0), 0.0, 0.0, Imgproc.INTER_AREA)
        return out
    }

    private fun best(frame: Mat, template: Mat): Best {
        val result = Mat()
        Imgproc.matchTemplate(frame, template, result, Imgproc.TM_CCOEFF_NORMED)
        val mm = Core.minMaxLoc(result)
        return Best(mm.maxVal, mm.maxLoc.x.toInt(), mm.maxLoc.y.toInt())
    }

    private fun builtIn(name: String) = gray(File(ceFolder, "$name.png"))

    @Test
    fun `every built-in CE template is at most 25 rows high`() {
        val files = ceFolder.listFiles { f -> f.extension == "png" }!!.sortedBy { it.name }
        assertTrue(files.size >= 23, "built-in CE templates not found under ${ceFolder.absolutePath}")
        val tall = files.filter { gray(it).rows() > 25 }.map { it.name }
        assertTrue(tall.isEmpty(), "taller than 25 rows: $tall")
    }

    /**
     * Left portrait column of a JP tablet (16:10) support list at 720p during an event; the first CE strip
     * (Chaldea Teatime) carries the "+100%" bonus label under it. The column starts at y=100 of the frame.
     */
    private val bonusSample = File(referenceImages, "Jp/ce-bonus/jp-tablet-bonus-portraits-720.png")

    @Test
    fun `cropped Teatime matches the strip under an event bonus label`() {
        val sample = gray(bonusSample)
        val match = best(sample, builtIn("Chaldea Teatime"))
        assertTrue(match.score >= 0.80, "Teatime scored ${match.score}")
        assertEquals(56, match.x)
        assertEquals(228, match.y)
    }

    @Test
    fun `the old 41-row Teatime file scores below the default similarity on the same strip`() {
        val sample = gray(bonusSample)
        val old = beforeCrop("Chaldea Teatime")
        assertEquals(41, old.rows())
        val match = best(sample, old)
        assertTrue(match.score < 0.80, "old Teatime scored ${match.score}")
    }

    @Test
    fun `no other built-in CE reaches the default similarity on the bonus sample`() {
        val sample = gray(bonusSample)
        val scores = ceFolder.listFiles { f -> f.extension == "png" }!!
            .filter { it.nameWithoutExtension != "Chaldea Teatime" }
            .associate { it.nameWithoutExtension to best(sample, gray(it)).score }
        val hits = scores.filterValues { it >= 0.80 }
        assertTrue(hits.isEmpty(), "false positives: $hits")
    }

    /** Frames without a bonus label: the cropped file still matches, at the position the full-height file had. */
    private data class Frame(val ce: String, val file: String)

    private val cleanFrames = listOf(
        Frame("Chaldea Teatime", "support-score-d2/1920x1080/01.png"),
        Frame("Chaldea Teatime", "Jp/np-20261018-r3/11-masked720.png"),
        Frame("Great Library of Memories", "support-score-d2/1920x1080/02.png"),
        Frame("Great Library of Memories", "support-score-holdout/2340x1080/03.png"),
        Frame("Chaldea Lunchtime", "support-score-audit/172.png"),
        Frame("Chaldea Lunchtime", "support-score-audit/181.png"),
        Frame("Mona Lisa", "support-score-d2/2340x1080/04.png"),
        Frame("Black Grail", "Jp/support/support-grand-bond-ce-16x9.png")
    )

    @Test
    fun `cropped templates still match real frames where the old file matched, at the same top-left`() {
        for ((ce, file) in cleanFrames) {
            val frame = frame720(File(referenceImages, file))
            val now = best(frame, builtIn(ce))
            val before = best(frame, beforeCrop(ce))
            assertTrue(now.score >= 0.80, "$ce on $file scored ${now.score}")
            assertTrue(
                Math.abs(now.x - before.x) <= 1 && Math.abs(now.y - before.y) <= 1,
                "$ce on $file moved from (${before.x},${before.y}) to (${now.x},${now.y})"
            )
        }
    }

    /** The five files as they were before the crop (41, 41, 45, 45 and 29 rows). */
    private fun beforeCrop(name: String) = gray(File("src/test/resources/ce-bonus/$name.png"))
}
