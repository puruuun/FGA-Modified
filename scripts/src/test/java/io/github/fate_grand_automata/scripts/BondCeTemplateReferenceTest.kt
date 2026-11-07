package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.GameServers
import io.github.fate_grand_automata.scripts.locations.IScriptAreaTransforms
import io.github.fate_grand_automata.scripts.locations.SupportScreenLocations
import io.github.fate_grand_automata.scripts.supportSelection.CESelection
import io.github.lib_automata.Location
import io.github.lib_automata.Region
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
 * Real Grand support frames scored with the bundled bond templates, the way CESelection scores them
 * (one grayscale 720p crop of the bond region, TM_CCOEFF_NORMED, best match per template, threshold
 * [CESelection.BOND_EFFECT_SIMILARITY] and "higher than the opposite icon").
 *
 * Frames: the owner's JP 2560x1080 capture (1706x720 game area, as the device QA does) and upstream's
 * 16:9 and wide JP captures (upstream sync v3070, `bond_ce_effect_np.png` replaced upstream).
 */
class BondCeTemplateReferenceTest {
    private data class Row(val file: String, val boundY: Int, val expected: String)

    private val rows = listOf(
        Row("Jp/wide-2560/27-grand-lancer-support.webp", 179, "NP"),
        Row("Jp/wide-2560/27-grand-lancer-support.webp", 379, "NP"),
        Row("Jp/wide-2560/27-grand-lancer-support.webp", 579, "Default"),
        Row("Jp/support/support-grand-bond-ce-16x9.png", 190, "NP"),
        Row("Jp/support/support-grand-bond-ce-16x9.png", 390, "NP"),
        Row("Jp/support/support-grand-bond-ce-16x9.png", 579, "Default"),
        Row("Jp/support/support-grand-bond-ce-wide.png", 319, "NP"),
        Row("Jp/support/support-grand-bond-ce-wide.png", 108, "Default")
    )

    private fun transforms(width: Int): IScriptAreaTransforms = object : IScriptAreaTransforms {
        override val scriptArea = Region(0, 0, width, 1440)
        override val isWide = scriptArea.size.isWide()
        override val gameServer: GameServer = GameServers.default
        override val canLongSwipe = false
        override fun Location.xFromCenter() = this + Location(scriptArea.center.x, 0)
        override fun Region.xFromCenter() = this + Location(scriptArea.center.x, 0)
        override fun Location.xFromRight() = this + Location(scriptArea.right, 0)
        override fun Region.xFromRight() = this + Location(scriptArea.right, 0)
        override fun Location.yFromBottom() = this + Location(0, scriptArea.bottom)
        override fun Region.yFromBottom() = this + Location(0, scriptArea.bottom)
    }

    private fun gray(path: String): Mat {
        nu.pattern.OpenCV.loadLocally()
        val mat = Imgcodecs.imread(File(path).absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
        check(!mat.empty()) { "cannot read $path" }
        return mat
    }

    /** Game area at 720p: frames taller than 720 are scaled down, 2560x1080 becomes 1706x720. */
    private fun frame720(file: String): Mat {
        val mat = gray("../docs/reference-images/$file")
        if (mat.rows() == 720) return mat
        val out = Mat()
        Imgproc.resize(mat, out, Size((mat.cols() * 720 / mat.rows()).toDouble(), 720.0), 0.0, 0.0, Imgproc.INTER_AREA)
        return out
    }

    private fun best(crop: Mat, template: Mat): Double {
        val result = Mat()
        Imgproc.matchTemplate(crop, template, result, Imgproc.TM_CCOEFF_NORMED)
        return Core.minMaxLoc(result).maxVal
    }

    private class Scores(val np: Double, val default: Double)

    private fun score(row: Row, np: Mat, default: Mat): Scores {
        val frame = frame720(row.file)
        val loc = SupportScreenLocations(transforms(frame.cols() * 2))
        val region = loc.bondCeRegion.copy(y = row.boundY * 2 + loc.bondCeRegion.y)
        val x = (region.x + 1) / 2
        val y = region.y / 2
        val crop = frame.submat(y, y + (region.height + 1) / 2, x, x + (region.width + 1) / 2)
        return Scores(best(crop, np), best(crop, default))
    }

    @Test fun `bundled bond templates classify every Grand reference row`() {
        val np = gray("../app/src/main/assets/En/bond_ce_effect_np.png")
        val default = gray("../app/src/main/assets/En/bond_ce_effect_default.png")
        val threshold = CESelection.BOND_EFFECT_SIMILARITY
        var margins = 0.0
        for (row in rows) {
            val s = score(row, np, default)
            val npMatch = s.np >= threshold && s.np > s.default
            val defaultMatch = s.default >= threshold && s.default > s.np
            val label = "${row.file}@${row.boundY} NP=${"%.4f".format(s.np)} Default=${"%.4f".format(s.default)}"
            assertEquals(row.expected == "NP", npMatch, label)
            assertEquals(row.expected == "Default", defaultMatch, label)
            margins += if (row.expected == "NP") s.np - s.default else s.default - s.np
        }
        // The previous NP template averaged 0.647 over the same rows
        assertTrue(margins / rows.size > 0.647, "average margin ${margins / rows.size}")
    }

    @Test fun `owner JP capture still reproduces the recorded device scores`() {
        val np = gray("../app/src/main/assets/En/bond_ce_effect_np.png")
        val default = gray("../app/src/main/assets/En/bond_ce_effect_default.png")
        val third = score(rows[2], np, default)
        // Default row of the 1.7.0-beta.1 validation record (0.844201 / opposite icon well below the threshold)
        assertEquals(0.844201, third.default, 0.0005)
        assertTrue(third.np < CESelection.BOND_EFFECT_SIMILARITY)
        val first = score(rows[0], np, default)
        assertEquals(0.220356, first.default, 0.0005)
    }
}
