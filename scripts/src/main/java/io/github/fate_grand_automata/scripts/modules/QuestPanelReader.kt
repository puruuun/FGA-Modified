package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.locations.QuestPanelLocations
import io.github.lib_automata.OcrService
import io.github.lib_automata.OcrText
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.github.lib_automata.TextLineOcrOptions
import javax.inject.Inject

/** What the uppermost panel of a spot shows. [nameCells] is the quest name's ink width in full-width glyph cells. */
data class QuestPanel(val free: Boolean, val ap: Int?, val nameCells: Double?, val nameWidthPx: Int?)

sealed class PanelVerdict {
    data object Match : PanelVerdict()
    data object NoPanel : PanelVerdict()
    data object NotFree : PanelVerdict()
    data class ApUnreadable(val expected: Int) : PanelVerdict()
    data class ApMismatch(val read: Int, val expected: Int) : PanelVerdict()
    data class NameMismatch(val cells: Double, val expected: Double) : PanelVerdict()
}

/**
 * Reads the quest panel without OCR of the name: the app only ships English OCR data, so the name is compared by the
 * width of its text in glyph cells (see [QuestPanelMatch.nameCells]). The "Free Quest" emblem and the AP number are exact.
 */
class QuestPanelReader @Inject constructor(
    api: IFgoAutomataApi,
    private val loc: QuestPanelLocations,
    private val ocr: OcrService
) : IFgoAutomataApi by api {
    companion object {
        const val FREE_SIMILARITY = 0.75

        /** Primary reads of the gray "AP" number: light digits on a gray bar, several thresholds, both polarities. */
        val AP_PROFILES = listOf(0.45, 0.5, 0.55, 0.6, 0.65, 0.7).flatMap { threshold ->
            listOf(false, true).map { invert ->
                TextLineOcrOptions(scale = 3, threshold = threshold, invert = invert, padding = 8, allowedCharacters = "0123456789")
            }
        }
    }

    private fun label(): Pattern? = runCatching { images[Images.FreeQuestLabel] }.getOrNull()

    fun isFree(): Boolean = label()?.let { loc.freeLabel.exists(it, similarity = FREE_SIMILARITY) } == true

    /** Raw OCR readings of the AP number, kept for the QA report. */
    fun apReadings(): List<OcrText> = loc.apDigits.getPattern("quest_panel_ap").use { pattern ->
        AP_PROFILES.map { ocr.detectTextLine(pattern, it) }
    }

    fun readAp(): Int? = QuestPanelMatch.apConsensus(apReadings())

    fun nameWidthPx(): Int? = loc.name.getPattern("quest_panel_name").use { pattern ->
        val gray = pattern.grayPixels() ?: return null
        QuestPanelMatch.inkExtent(gray, pattern.width, pattern.height)
    }

    fun read(server: GameServer): QuestPanel = useSameSnapIn {
        val free = isFree()
        if (!free) return@useSameSnapIn QuestPanel(false, null, null, null)
        val width = nameWidthPx()
        QuestPanel(true, readAp(), width?.let { QuestPanelMatch.cellsOf(it, server) }, width)
    }
}

object QuestPanelMatch {
    /** Ink width of one full-width glyph at 720p: JP kana and kanji, KR hangul. Measured on the owner captures. */
    private const val JP_CELL_PX = 18.3
    private const val KR_CELL_PX = 16.5
    /** A space is about half a glyph; Latin letters, digits and symbols are narrower than a CJK glyph. */
    private const val SPACE_CELLS = 0.45
    private const val NARROW_CELLS = 0.6

    const val MIN_CONFIDENCE = 60
    private const val MIN_AP = 1
    private const val MAX_AP = 99

    fun cellPx(server: GameServer) = if (server is GameServer.Kr) KR_CELL_PX else JP_CELL_PX

    fun cellsOf(widthPx: Int, server: GameServer) = widthPx / cellPx(server)

    /** Expected glyph cells of an official quest name. */
    fun nameCells(name: String): Double = name.sumOf { c ->
        when {
            c == ' ' || c == '　' -> SPACE_CELLS
            c.code < 0x2E80 || c == '・' -> NARROW_CELLS
            else -> 1.0
        }
    }

    /** Name tolerance: half a glyph of slack per six glyphs, at least a glyph and a half. */
    fun nameTolerance(expected: Double) = maxOf(1.5, expected * 0.2)

    /**
     * First to last bright column of the band. The threshold is relative to the band itself (median and 99.5th percentile),
     * because KR text is thinner and dimmer than JP text and a fixed threshold loses it.
     */
    fun inkExtent(gray: ByteArray, width: Int, height: Int): Int? {
        if (width <= 0 || height <= 0 || gray.size < width * height) return null
        val values = IntArray(width * height) { gray[it].toInt() and 0xFF }
        val sorted = values.sorted()
        val median = sorted[sorted.size / 2]
        val high = sorted[(sorted.size * 0.995).toInt().coerceAtMost(sorted.size - 1)]
        if (high - median < 40) return null
        val threshold = median + 0.6 * (high - median)
        val columns = (0 until width).filter { x -> (0 until height).count { y -> values[y * width + x] >= threshold } >= 2 }
        return if (columns.isEmpty()) null else columns.last() - columns.first() + 1
    }

    /** One value supported by at least two confident readings and contradicted by none. */
    fun apConsensus(readings: List<OcrText>): Int? {
        val values = readings.filter { it.confidence >= MIN_CONFIDENCE }
            .mapNotNull { r -> r.text.trim().takeIf { it.matches(Regex("[0-9]{1,2}")) }?.toIntOrNull() }
        if (values.any { it !in MIN_AP..MAX_AP }) return null
        return values.distinct().singleOrNull()?.takeIf { values.size >= 2 }
    }

    fun verdict(panel: QuestPanel, expectedAp: Int, expectedName: String, server: GameServer): PanelVerdict {
        if (!panel.free) return PanelVerdict.NotFree
        val ap = panel.ap ?: return PanelVerdict.ApUnreadable(expectedAp)
        if (ap != expectedAp) return PanelVerdict.ApMismatch(ap, expectedAp)
        val cells = panel.nameCells ?: return PanelVerdict.NoPanel
        val expected = nameCells(expectedName)
        return if (Math.abs(cells - expected) <= nameTolerance(expected)) PanelVerdict.Match
        else PanelVerdict.NameMismatch(cells, expected)
    }
}
