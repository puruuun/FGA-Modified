package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.modules.QuestNameTemplates
import io.github.fate_grand_automata.scripts.modules.QuestPanel
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.test.*

/** D-42 (#130): the name-band picture of 93020303 (千手千眼) on the JP server, on saved device frames. */
class WeeklyNameImageTest {
    private val repo = "../"
    private val senju = 93020303

    private fun load(path: String): Mat {
        nu.pattern.OpenCV.loadLocally()
        val mat = Imgcodecs.imread(File(repo + path).absolutePath, Imgcodecs.IMREAD_GRAYSCALE)
        check(!mat.empty()) { "cannot read $path" }
        return mat
    }

    private fun bytes(mat: Mat): ByteArray = ByteArray(mat.rows() * mat.cols()).also { mat.get(0, 0, it) }

    private val template by lazy { load("app/src/main/assets/Jp/weekly_name_senju.png") }
    private val label by lazy { load("app/src/main/assets/En/free_quest_label.png") }

    /** The panel name band (QuestPanelAnchor.name at 720p: label + (126, -19), 347x30) of the frame's Free Quest emblem; null without one. */
    private fun band(path: String): Mat? {
        val frame = load(path)
        if (frame.rows() < label.rows() || frame.cols() < label.cols()) return null
        val result = Mat()
        Imgproc.matchTemplate(frame, label, result, Imgproc.TM_CCOEFF_NORMED)
        val best = Core.minMaxLoc(result)
        if (best.maxVal < 0.85) return null
        val x = best.maxLoc.x.toInt() + 126; val y = best.maxLoc.y.toInt() - 19
        if (y < 0 || x + 347 > frame.cols() || y + 30 > frame.rows()) return null
        return frame.submat(y, y + 30, x, x + 347).clone()
    }

    private fun score(path: String): Double? = band(path)?.let {
        QuestNameTemplates.bestScore(bytes(it), it.cols(), it.rows(), bytes(template), template.cols(), template.rows())
    }

    private val positives = listOf(
        "docs/reference-images/Jp/weekly-route/probe-beta8/21-q93020303-a0.webp",     // 16:9
        "docs/reference-images/Jp/weekly-route/probe-beta9/12-q93020303-a0.webp",     // 16:9
        "docs/reference-images/Jp/weekly-route/beta9-shimousa.webp",                  // 21:9
        "docs/reference-images/Jp/weekly-route/issue130/10-q93020303-a0.webp"         // 19.5:9, the live failure
    )

    private val unread = QuestPanel(free = true, ap = null, nameCells = null, nameWidthPx = null, nameText = null)

    @Test fun `the 16-9, 21-9 and 19_5-9 captures of the quest match the template`() {
        for (path in positives) {
            val s = assertNotNull(score(path), path)
            assertTrue(s >= QuestNameTemplates.SENJU_THRESHOLD + 0.05, "$path scored $s")
            val entry = QuestNameTemplates.entry(GameServer.Jp.Original, senju)
            assertEquals("name-image", QuestNameTemplates.matchBy(unread, s, entry, 21), path)
        }
    }

    @Test fun `no other JP free quest panel comes near the threshold`() {
        val skip = positives.map { File(repo + it).canonicalPath }.toSet()
        var scored = 0; var highest = 0.0
        File(repo + "docs/reference-images/Jp").walkTopDown().filter { it.extension in listOf("png", "webp") }
            .filter { it.canonicalPath !in skip }.forEach { file ->
                val s = score(file.canonicalFile.relativeTo(File(repo).canonicalFile).path.replace('\\', '/')) ?: return@forEach
                scored++; highest = maxOf(highest, s)
                assertTrue(s < QuestNameTemplates.SENJU_THRESHOLD - 0.2, "${file.name} scored $s")
            }
        assertTrue(scored >= 30, "only $scored negative panels found")
        println("name-image: $scored negatives, highest $highest")
    }

    @Test fun `a different panel never matches by picture`() {
        val s = assertNotNull(score("docs/reference-images/Jp/weekly-route/probe-beta8/16-q93000709-a0.webp"))
        assertNull(QuestNameTemplates.matchBy(unread, s, QuestNameTemplates.entry(GameServer.Jp.Original, senju), 21))
    }

    @Test fun `only JP quest 93020303 has a template`() {
        assertNotNull(QuestNameTemplates.entry(GameServer.Jp.Original, senju))
        assertNotNull(QuestNameTemplates.entry(GameServer.Jp.BetterFGO, senju))
        assertNull(QuestNameTemplates.entry(GameServer.Kr, senju))
        assertNull(QuestNameTemplates.entry(GameServer.En.Original, senju))
        assertNull(QuestNameTemplates.entry(GameServer.Jp.Original, 93020305))
        assertNull(QuestNameTemplates.entry(GameServer.Jp.Original, 93000713))
    }

    @Test fun `an AP other than 21, a readable name, a missing emblem or a low score reject`() {
        val entry = QuestNameTemplates.entry(GameServer.Jp.Original, senju)
        assertEquals("name-image", QuestNameTemplates.matchBy(unread.copy(ap = 21), 0.99, entry, 21))
        assertNull(QuestNameTemplates.matchBy(unread.copy(ap = 20), 0.99, entry, 21))
        assertNull(QuestNameTemplates.matchBy(unread.copy(ap = 40), 0.99, entry, 21))
        assertNull(QuestNameTemplates.matchBy(unread.copy(nameText = "戦戦恐恐"), 0.99, entry, 21))
        assertNull(QuestNameTemplates.matchBy(unread.copy(free = false), 0.99, entry, 21))
        assertNull(QuestNameTemplates.matchBy(unread, 0.89, entry, 21))
        assertNull(QuestNameTemplates.matchBy(unread, null, entry, 21))
        assertNull(QuestNameTemplates.matchBy(unread, 0.99, null, 21))
    }

    @Test fun `the correlation is 1 for the template itself and 0 when it does not fit or is flat`() {
        val t = bytes(template)
        assertEquals(1.0, QuestNameTemplates.bestScore(t, template.cols(), template.rows(), t, template.cols(), template.rows()), 1e-6)
        assertEquals(0.0, QuestNameTemplates.bestScore(ByteArray(10), 5, 2, t, template.cols(), template.rows()))
        val flat = ByteArray(template.cols() * template.rows()) { 100 }
        assertEquals(0.0, QuestNameTemplates.bestScore(t, template.cols(), template.rows(), flat, template.cols(), template.rows()))
    }
}
