package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.locations.QuestPanelLocations
import io.github.fate_grand_automata.scripts.locations.QuestPanelAnchor
import io.github.lib_automata.OcrService
import io.github.lib_automata.OcrText
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.github.lib_automata.TextLineOcrOptions
import javax.inject.Inject

/** What the uppermost panel of a spot shows. [nameCells] is the quest name's ink width in full-width glyph cells. */
data class QuestPanel(val free: Boolean, val ap: Int?, val nameCells: Double?, val nameWidthPx: Int?, val nameText: String? = null)

sealed class PanelVerdict {
    data object Match : PanelVerdict()
    data object NoPanel : PanelVerdict()
    data object NotFree : PanelVerdict()
    data class ApUnreadable(val expected: Int) : PanelVerdict()
    data class ApMismatch(val read: Int, val expected: Int) : PanelVerdict()
    data class NameMismatch(val cells: Double, val expected: Double) : PanelVerdict()
}

/**
 * Reads the Free Quest emblem, AP and actual JP/KR name text. Glyph widths remain a preliminary/legacy QA signal;
 * production navigation also requires exact normalized name characters supported by multiple OCR reads.
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

    /** Fresh emblem positions, independent of panel slots or horizontal placement. */
    fun anchors(): List<QuestPanelAnchor> = label()?.let { pattern ->
        loc.freeSearchBand.findAll(pattern, FREE_SIMILARITY)
            .sortedWith(compareBy({ it.region.y }, { it.region.x }))
            .map { QuestPanelAnchor(it.region.location) }.filter { it.fits(loc.scriptArea) }.take(8)
    }.orEmpty()

    /** A clipped emblem may guide a recovery drag, but never authorizes a quest tap. */
    fun resetAnchor(): QuestPanelAnchor? = anchors().firstOrNull() ?: label()?.let { full ->
        val half = full.height / 2
        full.crop(Region(0, half, full.width, full.height - half)).use { bottom ->
            loc.freeSearchBand.findAll(bottom, 0.85).maxByOrNull { it.score }?.let { match ->
                QuestPanelAnchor(match.region.location + io.github.lib_automata.Location(0, -half * 2))
            }
        }
    }?.takeIf { anchor ->
        listOf(anchor.resetStart, anchor.resetEnd).all {
            it.x >= loc.scriptArea.x && it.x < loc.scriptArea.right && it.y >= loc.scriptArea.y && it.y < loc.scriptArea.bottom
        }
    }

    fun readAnchored(server: GameServer, anchor: QuestPanelAnchor, questNames: Collection<String>): QuestPanel = useSameSnapIn {
        if (!anchor.fits(loc.scriptArea)) return@useSameSnapIn QuestPanel(false, null, null, null)
        val mark = label() ?: return@useSameSnapIn QuestPanel(false, null, null, null)
        if (!Region(anchor.mark.x, anchor.mark.y, mark.width * 2, mark.height * 2)
                .exists(mark, similarity = FREE_SIMILARITY)) return@useSameSnapIn QuestPanel(false, null, null, null)
        val ap = anchor.ap.getPattern("anchored_panel_ap").use { image ->
            QuestPanelMatch.apConsensus(AP_PROFILES.map { ocr.detectTextLine(image, it) })
        }
        anchor.name.getPattern("anchored_panel_name").use { band ->
            val width = band.grayPixels()?.let { QuestPanelMatch.inkExtent(it, band.width, band.height) }
            val lang = if (server is GameServer.Kr) "kor_weekly" else "jpn_weekly"
            val reads = band.crop(Region(0, 0, minOf((width ?: band.width) + 8, band.width), band.height)).use { image ->
                listOf(2, 3).map { scale -> ocr.detectTextLine(image,
                    TextLineOcrOptions(scale = scale, threshold = 0.6, invert = true, padding = 12, language = lang)) }
            }
            val name = if (questNames.isEmpty()) QuestPanelMatch.nameConsensus(reads) else QuestPanelMatch.canonicalName(reads, questNames)
            QuestPanel(true, ap, width?.let { QuestPanelMatch.cellsOf(it, server) }, width, name)
        }
    }

    /** [slot] is the panel's place in the spot's panel list, 0 for the uppermost. */
    fun isFree(slot: Int = 0): Boolean =
        label()?.let { loc.slot(loc.freeLabel, slot).exists(it, similarity = FREE_SIMILARITY) } == true

    /** Raw OCR readings of the AP number, kept for the QA report. */
    fun apReadings(slot: Int = 0): List<OcrText> = loc.slot(loc.apDigits, slot).getPattern("quest_panel_ap").use { pattern ->
        AP_PROFILES.map { ocr.detectTextLine(pattern, it) }
    }

    fun readAp(slot: Int = 0): Int? = QuestPanelMatch.apConsensus(apReadings(slot))

    fun nameWidthPx(slot: Int = 0): Int? = loc.slot(loc.name, slot).getPattern("quest_panel_name").use { pattern ->
        val gray = pattern.grayPixels() ?: return null
        QuestPanelMatch.inkExtent(gray, pattern.width, pattern.height)
    }

    fun nameReadings(server: GameServer, slot: Int = 0): List<OcrText> =
        loc.slot(loc.name, slot).getPattern("quest_name").use { band ->
            val lang = if (server is GameServer.Kr) "kor_weekly" else "jpn_weekly"
            val width=(nameWidthPx(slot) ?: band.width)+8
            band.crop(Region(0,0,minOf(width,band.width),band.height)).use { pattern ->
                listOf(2,3).map { scale -> ocr.detectTextLine(pattern,
                    TextLineOcrOptions(scale=scale,threshold=0.6,invert=true,padding=12,language=lang)) }
            }
        }

    fun readName(server: GameServer, slot: Int = 0, questNames: Collection<String> = emptyList()): String? {
        val reads=nameReadings(server,slot)
        return if(questNames.isEmpty()) QuestPanelMatch.nameConsensus(reads) else QuestPanelMatch.canonicalName(reads,questNames)
    }

    fun read(server: GameServer, slot: Int = 0, questNames: Collection<String> = emptyList()): QuestPanel = useSameSnapIn {
        val free = isFree(slot)
        if (!free) return@useSameSnapIn QuestPanel(false, null, null, null)
        val width = nameWidthPx(slot)
        QuestPanel(true, readAp(slot), width?.let { QuestPanelMatch.cellsOf(it, server) }, width, readName(server,slot,questNames))
    }
}

object QuestPanelMatch {
    fun label(verdict: PanelVerdict): String = when(verdict) {
        PanelVerdict.Match -> "Match"
        PanelVerdict.NotFree -> "NotFree"
        PanelVerdict.NoPanel -> "NoPanel"
        is PanelVerdict.ApUnreadable -> "ApUnreadable"
        is PanelVerdict.ApMismatch -> "ApMismatch"
        is PanelVerdict.NameMismatch -> "NameMismatch"
    }

    fun normalizedName(s: String): String = java.text.Normalizer.normalize(s,java.text.Normalizer.Form.NFKC).filterNot { it.isWhitespace() }
    fun nameConsensus(readings: List<OcrText>): String? {
        val confident=readings.filter { it.confidence>=65 && it.text.isNotBlank() }.map { normalizedName(it.text) }
        return confident.distinct().singleOrNull()?.takeIf { confident.size>=2 && it.isNotEmpty() }
    }
    /**
     * Domain-constrained OCR, against ALL regional quest names rather than the target. At most two character edits,
     * <= one third of the word, a two-edit margin to every runner-up, and two agreeing readings are required. Unknown
     * names and close legitimate alternatives are not forced to match the route. Glyph width/AP remain separate guards.
     */
    fun canonicalName(readings: List<OcrText>, questNames: Collection<String>): String? {
        val names=questNames.map(::normalizedName).filter { it.isNotEmpty() }.distinct()
        if(names.isEmpty()) return null
        fun distance(a: String,b: String): Int {
            if(kotlin.math.abs(a.length-b.length)>3) return 4
            var row=IntArray(b.length+1) { it }
            for(i in a.indices) {
                val next=IntArray(b.length+1);next[0]=i+1
                for(j in b.indices) next[j+1]=minOf(next[j]+1,row[j+1]+1,row[j]+if(a[i]==b[j]) 0 else 1)
                row=next
            }
            return row.last()
        }
        val votes=readings.filter { it.confidence>=45 && it.text.isNotBlank() }.mapNotNull { read ->
            val text=normalizedName(read.text)
            val ranked=names.map { it to distance(text,it) }.sortedBy { it.second }
            val best=ranked.first()
            best.first.takeIf { best.second<=2 && best.second*3<=best.first.length &&
                (ranked.size==1 || ranked[1].second-best.second>=2) }
        }
        return votes.distinct().singleOrNull()?.takeIf { votes.size>=2 }
    }

    /** Production navigation requires actual name characters, never just a matching glyph count. */
    fun strictVerdict(panel: QuestPanel, expectedAp: Int, expectedName: String, server: GameServer): PanelVerdict {
        val basic=verdict(panel,expectedAp,expectedName,server)
        if (basic != PanelVerdict.Match) return basic
        return if (panel.nameText != null && normalizedName(panel.nameText)==normalizedName(expectedName)) PanelVerdict.Match
            else PanelVerdict.NameMismatch(panel.nameCells ?: 0.0,nameCells(expectedName))
    }

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
