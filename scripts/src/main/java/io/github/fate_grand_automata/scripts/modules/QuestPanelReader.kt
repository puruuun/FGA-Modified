package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.prefs.IAddedFeatureTuning
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

/**
 * What the uppermost panel of a spot shows. [nameCells] is the quest name's ink width in full-width glyph cells.
 * [rawNames] are all OCR candidates of the name band; [spotText] and [rawSpots] come from the enlarged spot ribbon (D-29).
 */
data class QuestPanel(val free: Boolean, val ap: Int?, val nameCells: Double?, val nameWidthPx: Int?, val nameText: String? = null,
    val rawNames: List<OcrText> = emptyList(), val spotText: String? = null, val rawSpots: List<OcrText> = emptyList(),
    /** D-37: [nameText] was chosen among the tapped spot's own free quests (2 or more), not among the whole regional list. */
    val nameBySpot: Boolean = false)

/** The enlarged spot ribbon read once per frame: the canonical spot name (normalized) or null, and every OCR candidate. */
data class SpotReading(val text: String?, val raw: List<OcrText>)

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
    private val ocr: OcrService,
    private val addedTuning: IAddedFeatureTuning = IAddedFeatureTuning.Default
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
        loc.freeSearchBand.findAll(pattern, addedTuning.weekly.similarity(FREE_SIMILARITY))
            .sortedWith(compareBy({ it.region.y }, { it.region.x }))
            .map { QuestPanelAnchor(it.region.location) }.filter { it.fits(loc.scriptArea) }.take(8)
    }.orEmpty()

    /** A clipped emblem may guide a recovery drag, but never authorizes a quest tap. */
    fun resetAnchor(): QuestPanelAnchor? = anchors().firstOrNull() ?: label()?.let { full ->
        val half = full.height / 2
        full.crop(Region(0, half, full.width, full.height - half)).use { bottom ->
            loc.freeSearchBand.findAll(bottom, addedTuning.weekly.similarity(0.85)).maxByOrNull { it.score }?.let { match ->
                QuestPanelAnchor(match.region.location + io.github.lib_automata.Location(0, -half * 2))
            }
        }
    }?.takeIf { anchor ->
        listOf(anchor.resetStart, anchor.resetEnd).all {
            it.x >= loc.scriptArea.x && it.x < loc.scriptArea.right && it.y >= loc.scriptArea.y && it.y < loc.scriptArea.bottom
        }
    }

    fun readAnchored(server: GameServer, anchor: QuestPanelAnchor, questNames: Collection<String>, spot: SpotReading? = null,
                     spotQuestNames: Collection<String> = emptyList()): QuestPanel = useSameSnapIn {
        if (!anchor.fits(loc.scriptArea)) return@useSameSnapIn QuestPanel(false, null, null, null)
        val mark = label() ?: return@useSameSnapIn QuestPanel(false, null, null, null)
        if (!Region(anchor.mark.x, anchor.mark.y, mark.width * 2, mark.height * 2)
                .exists(mark, similarity = addedTuning.weekly.similarity(FREE_SIMILARITY))) return@useSameSnapIn QuestPanel(false, null, null, null)
        val ap = anchor.ap.getPattern("anchored_panel_ap").use { image ->
            QuestPanelMatch.apConsensus(AP_PROFILES.map { ocr.detectTextLine(image, it) })
        }
        anchor.name.getPattern("anchored_panel_name").use { band ->
            val width = band.grayPixels()?.let { QuestPanelMatch.inkExtent(it, band.width, band.height) }
            val lang = if (server is GameServer.Kr) "kor_weekly" else "jpn_weekly"
            // D-37: a spot with several free quests is voted among its own quests only.
            val scoped = QuestPanelMatch.spotScoped(spotQuestNames)
            fun choose(reads: List<OcrText>) = if (scoped.isNotEmpty()) QuestPanelMatch.canonicalSpotQuestName(reads, scoped)
                else if (questNames.isEmpty()) QuestPanelMatch.nameConsensus(reads) else QuestPanelMatch.canonicalName(reads, questNames)
            val reads = panelNameReadings(band, lang, ::choose)
            val name = choose(reads)
            QuestPanel(true, ap, width?.let { QuestPanelMatch.cellsOf(it, server) }, width, name,
                reads, spot?.text, spot?.raw.orEmpty(), nameBySpot = scoped.isNotEmpty() && name != null)
        }
    }

    private fun textReadings(image: Pattern, lang: String): List<OcrText> =
        listOf(
            TextLineOcrOptions(scale = 2, threshold = 0.6, invert = true, padding = 12, language = lang),
            TextLineOcrOptions(scale = 3, threshold = 0.6, invert = true, padding = 12, language = lang),
            TextLineOcrOptions(scale = 4, rawGrayscale = true, invert = true, padding = 12, language = lang),
            TextLineOcrOptions(scale = 4, threshold = 0.5, invert = true, padding = 12, language = lang)
        ).map { ocr.detectTextLine(image, it) }

    /** Issue #103: tight crops lose thin kanji strokes. Retry unresolved JP names with a little more context.
     * Keep every primary vote: additional profiles cannot override a contradictory readable name. */
    private fun panelNameReadings(band: Pattern, lang: String, choose: (List<OcrText>) -> String?): List<OcrText> {
        val bounds = band.grayPixels()?.let { QuestPanelMatch.inkBounds(it, band.width, band.height) }
            ?: Region(0, 0, band.width, band.height)
        val primary = band.crop(bounds).use { textReadings(it, lang) }
        if (lang != "jpn_weekly" || choose(primary) != null) return primary
        val left = maxOf(0, bounds.x - 4); val top = maxOf(0, bounds.y - 4)
        val padded = Region(left, top, minOf(band.width, bounds.right + 4) - left, minOf(band.height, bounds.bottom + 4) - top)
        return primary + band.crop(padded).use { image ->
            listOf(
                TextLineOcrOptions(scale = 1, threshold = 0.55, invert = true, padding = 12, language = lang),
                TextLineOcrOptions(scale = 3, threshold = 0.55, invert = true, padding = 12, language = lang),
                TextLineOcrOptions(scale = 3, rawGrayscale = true, invert = true, padding = 12, language = lang)
            ).map { ocr.detectTextLine(image, it) }
        }
    }

    fun spotReadings(server: GameServer): List<OcrText> = loc.enlargedSpotName.getPattern("enlarged_spot_name").use { band ->
        val lang = if (server is GameServer.Kr) "kor_weekly" else "jpn_weekly"
        val bounds = band.grayPixels()?.let { QuestPanelMatch.inkBounds(it, band.width, band.height) }
        band.crop(bounds ?: Region(0, 0, band.width, band.height)).use { textReadings(it, lang) }
    }

    /** D-29: the spot ribbon, canonicalized against the spot names of the quest's own chapter. */
    fun readSpot(server: GameServer, spotNames: Collection<String>): SpotReading {
        val raw = spotReadings(server)
        return SpotReading(if (spotNames.isEmpty()) null else QuestPanelMatch.canonicalSpot(raw, spotNames), raw)
    }

    /**
     * D-30: the panel name band above the spot ribbon, doubled in size, as the evidence picture of a success.
     * Call inside the same colour mode as the frame that was read (the probe uses colour).
     */
    fun evidence(anchor: QuestPanelAnchor): Pattern? = runCatching {
        anchor.name.getPattern("evidence_name").use { name ->
            loc.enlargedSpotName.getPattern("evidence_spot").use { spot ->
                name.stackedWith(spot).use { stacked -> stacked.resize(io.github.lib_automata.Size(stacked.width * 2, stacked.height * 2)) }
            }
        }
    }.getOrNull()

    /** [slot] is the panel's place in the spot's panel list, 0 for the uppermost. */
    fun isFree(slot: Int = 0): Boolean =
        label()?.let { loc.slot(loc.freeLabel, slot).exists(it, similarity = addedTuning.weekly.similarity(FREE_SIMILARITY)) } == true

    /** Raw OCR readings of the AP number, kept for the QA report. */
    fun apReadings(slot: Int = 0): List<OcrText> = loc.slot(loc.apDigits, slot).getPattern("quest_panel_ap").use { pattern ->
        AP_PROFILES.map { ocr.detectTextLine(pattern, it) }
    }

    fun readAp(slot: Int = 0): Int? = QuestPanelMatch.apConsensus(apReadings(slot))

    fun nameWidthPx(slot: Int = 0): Int? = loc.slot(loc.name, slot).getPattern("quest_panel_name").use { pattern ->
        val gray = pattern.grayPixels() ?: return null
        QuestPanelMatch.inkExtent(gray, pattern.width, pattern.height)
    }

    fun nameReadings(server: GameServer, slot: Int = 0, questNames: Collection<String> = emptyList()): List<OcrText> =
        loc.slot(loc.name, slot).getPattern("quest_name").use { band ->
            val lang = if (server is GameServer.Kr) "kor_weekly" else "jpn_weekly"
            if (server is GameServer.Jp) return@use panelNameReadings(band, lang) { reads ->
                if (questNames.isEmpty()) QuestPanelMatch.nameConsensus(reads) else QuestPanelMatch.canonicalName(reads, questNames)
            }
            val width=(nameWidthPx(slot) ?: band.width)+8
            band.crop(Region(0,0,minOf(width,band.width),band.height)).use { pattern ->
                listOf(2,3).map { scale -> ocr.detectTextLine(pattern,
                    TextLineOcrOptions(scale=scale,threshold=0.6,invert=true,padding=12,language=lang)) }
            }
        }

    fun readName(server: GameServer, slot: Int = 0, questNames: Collection<String> = emptyList()): String? {
        val reads=nameReadings(server,slot,questNames)
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

    /** A read that spells a catalogue name exactly is a vote from this confidence: no edit-distance margin can apply to it. */
    const val EXACT_MIN_CONFIDENCE = 50

    fun normalizedName(s: String): String = java.text.Normalizer.normalize(s,java.text.Normalizer.Form.NFKC).filterNot { it.isWhitespace() }
    fun nameConsensus(readings: List<OcrText>): String? {
        val confident=readings.filter { it.confidence>=65 && it.text.isNotBlank() }.map { normalizedName(it.text) }
        return confident.distinct().singleOrNull()?.takeIf { confident.size>=2 && it.isNotEmpty() }
    }
    /**
     * Domain-constrained OCR, against ALL regional quest names rather than the target. At most two character edits,
     * <= one third of the word, a two-edit margin to every runner-up, and two agreeing readings are required. Unknown
     * names and close legitimate alternatives are not forced to match the route. AP and glyph width are diagnostic only.
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
            if (read.confidence >= EXACT_MIN_CONFIDENCE && text in names) return@mapNotNull text
            val ranked=names.map { it to distance(text,it) }.sortedBy { it.second }
            val best=ranked.first()
            best.first.takeIf { best.second<=2 && best.second*3<=best.first.length &&
                (ranked.size==1 || ranked[1].second-best.second>=2) }
        }
        return votes.distinct().singleOrNull()?.takeIf { votes.size>=2 }
    }

    /** Production navigation requires actual name characters, never just a matching glyph count. */
    fun strictVerdict(panel: QuestPanel, expectedAp: Int, expectedName: String, server: GameServer): PanelVerdict {
        return verdict(panel, expectedAp, expectedName, server)
    }

    /** Dash-like characters OCR swaps for each other in spot names (long vowel mark, minus, hyphens). */
    private const val DASHES = "ー－−–—‐―-"
    /** Ribbon outline pieces OCR reads as stray symbols at either end of a spot name. */
    private const val NOISE_ENDS = "_^<>,.~|'\"ゝヽ、。:;"

    /** Spot names compare after NFKC, without blanks, dash look-alikes folded to one hyphen and stray symbols trimmed from both ends. */
    fun normalizedSpot(s: String): String =
        normalizedName(s).map { if (it in DASHES) '-' else it }.joinToString("").trim { it in NOISE_ENDS }

    /**
     * D-29: the enlarged spot ribbon against the spot names of one chapter. Same voting as [canonicalName] (two agreeing
     * readings, no contradicting one), but a verbatim catalogue spelling counts from confidence 50 (one-character neighbours
     * such as X-A and X-B can never pass the edit margin) and an edit match needs a margin of two edits to the runner-up.
     * Returns the normalized ([normalizedSpot]) name.
     */
    fun canonicalSpot(readings: List<OcrText>, spotNames: Collection<String>): String? {
        val names = spotNames.map(::normalizedSpot).filter { it.isNotEmpty() }.distinct()
        if (names.isEmpty()) return null
        val votes = readings.filter { it.confidence >= 45 && it.text.isNotBlank() }.mapNotNull { read ->
            val text = normalizedSpot(read.text)
            if (text.isEmpty()) return@mapNotNull null
            if (read.confidence >= EXACT_MIN_CONFIDENCE && text in names) return@mapNotNull text
            val ranked = names.map { it to editDistance(text, it) }.sortedBy { it.second }
            val best = ranked.first()
            best.first.takeIf { best.second <= 2 && best.second * 3 <= best.first.length &&
                (ranked.size == 1 || ranked[1].second - best.second >= 2) }
        }
        return votes.distinct().singleOrNull()?.takeIf { votes.size >= 2 }
    }

    /** D-37: below this a spot-scoped read is ignored. Lower than the chapter-wide 45 because only 2-3 names compete. */
    const val SPOT_QUEST_MIN_CONFIDENCE = 35

    /** The distinct normalized free quest names of a spot when there are two or more; fewer means the chapter-wide rule applies. */
    fun spotScoped(spotQuestNames: Collection<String>): List<String> =
        spotQuestNames.map(::normalizedName).filter { it.isNotEmpty() }.distinct().takeIf { it.size >= 2 }.orEmpty()

    private fun fullEditDistance(a: String, b: String): Int {
        var row = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val next = IntArray(b.length + 1); next[0] = i + 1
            for (j in b.indices) next[j + 1] = minOf(next[j] + 1, row[j + 1] + 1, row[j] + if (a[i] == b[j]) 0 else 1)
            row = next
        }
        return row.last()
    }

    /**
     * D-37: the quest name read among the free quests of the tapped spot only ([candidates], two or more, see [spotScoped]).
     * A read votes for its nearest candidate when that is at most half the candidate's length away and the runner-up is at
     * least two edits farther (a verbatim spelling from [EXACT_MIN_CONFIDENCE] on always votes). Two agreeing votes and no
     * contradicting one are required. A read of the sibling quest therefore never becomes the target.
     */
    fun canonicalSpotQuestName(readings: List<OcrText>, candidates: List<String>): String? {
        val names = candidates.map(::normalizedName).filter { it.isNotEmpty() }.distinct()
        if (names.size < 2) return null
        val votes = readings.filter { it.confidence >= SPOT_QUEST_MIN_CONFIDENCE && it.text.isNotBlank() }.mapNotNull { read ->
            val text = normalizedName(read.text)
            if (text.isEmpty()) return@mapNotNull null
            if (read.confidence >= EXACT_MIN_CONFIDENCE && text in names) return@mapNotNull text
            val ranked = names.map { it to fullEditDistance(text, it) }.sortedBy { it.second }
            val best = ranked[0]
            best.first.takeIf { best.second * 2 <= best.first.length && ranked[1].second - best.second >= 2 }
        }
        return votes.distinct().singleOrNull()?.takeIf { votes.size >= 2 }
    }

    internal fun editDistance(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > 3) return 4
        var row = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val next = IntArray(b.length + 1); next[0] = i + 1
            for (j in b.indices) next[j + 1] = minOf(next[j] + 1, row[j + 1] + 1, row[j] + if (a[i] == b[j]) 0 else 1)
            row = next
        }
        return row.last()
    }

    /**
     * D-29 verdict with its rule. The quest name stays primary: a readable name decides alone, and a readable different
     * name is never overridden. An unreadable name matches only on the spot ribbon, when the spot has exactly one Atlas free
     * quest, the Free Quest emblem is present and the name's ink width fits. Returns "quest-name", "spot-name+cells" or null.
     */
    fun matchBy(panel: QuestPanel, expectedName: String, expectedSpot: String, freeQuests: Int): String? {
        if (!panel.free) return null
        if (panel.nameText != null) return (if (panel.nameBySpot) "quest-name-spot" else "quest-name")
            .takeIf { normalizedName(panel.nameText) == normalizedName(expectedName) }
        if (freeQuests != 1 || expectedSpot.isBlank() || panel.spotText == null ||
            normalizedSpot(panel.spotText) != normalizedSpot(expectedSpot)) return null
        val cells = panel.nameCells ?: return null
        val expected = nameCells(expectedName)
        return "spot-name+cells".takeIf { kotlin.math.abs(cells - expected) <= nameTolerance(expected) }
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

    /** Tight bright-glyph bounds, excluding the spot ribbon's dim outline and padding. No target-name geometry. */
    fun inkBounds(gray: ByteArray, width: Int, height: Int): Region? {
        if (width <= 0 || height <= 0 || gray.size < width * height) return null
        val sorted = gray.map { it.toInt() and 255 }.sorted()
        val median = sorted[sorted.size / 2]
        val high = sorted[(sorted.size * 0.995).toInt().coerceAtMost(sorted.lastIndex)]
        if (high - median < 40) return null
        val threshold = median + 0.6 * (high - median)
        val xs = (0 until width).filter { x -> (0 until height).count { y -> (gray[y * width + x].toInt() and 255) >= threshold } >= 2 }
        if (xs.isEmpty()) return null
        val ys = (0 until height).filter { y -> xs.count { x -> (gray[y * width + x].toInt() and 255) >= threshold } >= 2 }
        if (ys.isEmpty()) return null
        val left = maxOf(0, xs.first() - 2); val top = maxOf(0, ys.first() - 2)
        return Region(left, top, minOf(width, xs.last() + 3) - left, minOf(height, ys.last() + 3) - top)
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
        return if (panel.nameText != null && normalizedName(panel.nameText) == normalizedName(expectedName)) PanelVerdict.Match
        else PanelVerdict.NameMismatch(panel.nameCells ?: 0.0, nameCells(expectedName))
    }
}
