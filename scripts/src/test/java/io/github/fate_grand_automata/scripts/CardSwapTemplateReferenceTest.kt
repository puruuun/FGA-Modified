package io.github.fate_grand_automata.scripts

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CardSwapTemplateReferenceTest {
    private val offsets = listOf(
        "A" to -1280,
        "B" to -768,
        "C" to -256,
        "D" to 256,
        "E" to 768
    )

    private fun resolveRepoFile(relativePath: String): File =
        listOf(File(relativePath), File("../$relativePath")).firstOrNull { it.isFile }
            ?: error("Missing reference file: $relativePath")

    private fun loadCardSwapTemplate(): Pair<Mat, Mat> {
        nu.pattern.OpenCV.loadLocally()
        val rgba = Imgcodecs.imread(
            resolveRepoFile("app/src/main/assets/En/card_swap.png").absolutePath,
            Imgcodecs.IMREAD_UNCHANGED
        )
        check(!rgba.empty()) { "cannot read card_swap.png" }
        assertEquals(38, rgba.cols(), "template width")
        assertEquals(38, rgba.rows(), "template height")
        assertEquals(4, rgba.channels(), "template channels")

        val templateGray = Mat()
        val alpha = Mat()
        val mask = Mat()
        try {
            Imgproc.cvtColor(rgba, templateGray, Imgproc.COLOR_BGRA2GRAY)
            Core.extractChannel(rgba, alpha, 3)
            Core.compare(alpha, Scalar(0.0), mask, Core.CMP_GT)
            val opaque = Core.countNonZero(mask)
            assertTrue(opaque > 0 && opaque < 38 * 38, "mask must have both opaque and transparent pixels, got $opaque")
            return templateGray to mask
        } finally {
            alpha.release()
            rgba.release()
        }
    }

    private fun loadGrayFrame(file: String): Mat {
        val mat = Imgcodecs.imread(
            resolveRepoFile("docs/reference-images/Jp/battle/$file").absolutePath,
            Imgcodecs.IMREAD_GRAYSCALE
        )
        check(!mat.empty()) { "cannot read $file" }
        assertEquals(1280, mat.cols(), "$file width")
        assertEquals(720, mat.rows(), "$file height")
        return mat
    }

    private fun maskedMaxScore(scene: Mat, template: Mat, mask: Mat): Double {
        val result = Mat()
        val ok = Mat()
        val bad = Mat()
        try {
            Imgproc.matchTemplate(scene, template, result, Imgproc.TM_CCOEFF_NORMED, mask)
            Core.inRange(result, Scalar(-1.01), Scalar(1.01), ok)
            Core.bitwise_not(ok, bad)
            result.setTo(Scalar(0.0), bad)
            return Core.minMaxLoc(result).maxVal
        } finally {
            bad.release()
            ok.release()
            result.release()
        }
    }

    @Test
    fun `masked card_swap template classifies all reference frames`() {
        val (template, mask) = loadCardSwapTemplate()
        try {
            val expectedHits = mapOf(
                "card-swap-aoko-buster-mumu.webp" to setOf("B"),
                "card-swap-super-aoko-arts-mumu.webp" to setOf("B"),
                "card-swap-aoko-buster-tablet-window.webp" to setOf("C"),
                "card-swap-none-tablet.webp" to emptySet(),
                "card-swap-flat-grey.webp" to emptySet()
            )

            for ((file, hitCards) in expectedHits) {
                val frame = loadGrayFrame(file)
                try {
                    for ((card, offset) in offsets) {
                        val x0 = 640 + offset / 2
                        val roi = Rect(x0 + 120, 520, 136, 120)
                        val crop = frame.submat(roi)
                        val score = try {
                            maskedMaxScore(crop, template, mask)
                        } finally {
                            crop.release()
                        }
                        println("CARD_SWAP $file card=$card score=${"%.6f".format(score)} raw=$score")
                        if (card in hitCards) {
                            assertTrue(score >= 0.85, "$file card $card expected >= 0.85, got $score")
                        } else {
                            assertTrue(score < 0.50, "$file card $card expected < 0.50, got $score")
                        }
                    }

                    if (file == "card-swap-flat-grey.webp") {
                        val wholeScore = maskedMaxScore(frame, template, mask)
                        println("CARD_SWAP $file whole score=${"%.6f".format(wholeScore)}")
                        assertTrue(wholeScore < 0.50, "$file whole frame expected < 0.50, got $wholeScore")
                        assertEquals(0.0, wholeScore, "$file whole frame expected 0.0")
                    }
                } finally {
                    frame.release()
                }
            }
        } finally {
            mask.release()
            template.release()
        }
    }
}
