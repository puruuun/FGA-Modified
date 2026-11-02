package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.enums.GameServer

/**
 * D-42 (issue #130): a quest whose name the OCR cannot read (thin-stroke kanji) is recognised by the picture of its
 * name band instead. The template is matched only inside the tapped panel's name band ([io.github.fate_grand_automata.scripts.locations.QuestPanelAnchor.name]).
 * Only quests listed here are ever checked this way; every other quest and every other server keeps the OCR-only rule.
 */
object QuestNameTemplates {
    /** [threshold] is the lowest normalized correlation that counts as the same name band. */
    data class Entry(val image: Images, val threshold: Double)

    /** Reported as `by=` in the `panel` / `probe` log lines and in the probe evidence. */
    const val BY = "name-image"

    /**
     * Measured on the saved JP frames of 93020303 (16:9, 21:9 and 19.5:9): every capture of the quest scores 0.984 or
     * more, and the best of 40 other JP Free Quest panels is 0.54. 0.9 keeps a wide margin on both sides.
     */
    const val SENJU_THRESHOLD = 0.9

    private val jp: Map<Int, Entry> = mapOf(
        93020303 to Entry(Images.WeeklyNameSenju, SENJU_THRESHOLD)
    )

    /** The template for [questId] on [server]; null (no rule) for KR/EN/CN/TW and for every quest without an entry. */
    fun entry(server: GameServer, questId: Int): Entry? = if (server is GameServer.Jp) jp[questId] else null

    /**
     * Best normalized correlation (TM_CCOEFF_NORMED) of the template over every position of the band, both 8-bit
     * grayscale row-major. 0.0 when the template does not fit in the band or is flat.
     */
    fun bestScore(band: ByteArray, bandWidth: Int, bandHeight: Int, tpl: ByteArray, tplWidth: Int, tplHeight: Int): Double {
        if (tplWidth <= 0 || tplHeight <= 0 || tplWidth > bandWidth || tplHeight > bandHeight) return 0.0
        if (band.size < bandWidth * bandHeight || tpl.size < tplWidth * tplHeight) return 0.0
        val n = tplWidth * tplHeight
        val t = DoubleArray(n) { (tpl[it].toInt() and 255).toDouble() }
        val tMean = t.average()
        var tVar = 0.0
        for (i in 0 until n) { t[i] -= tMean; tVar += t[i] * t[i] }
        if (tVar < 1e-9) return 0.0
        var best = -1.0
        for (y in 0..bandHeight - tplHeight) for (x in 0..bandWidth - tplWidth) {
            var sum = 0.0
            for (ty in 0 until tplHeight) { val row = (y + ty) * bandWidth + x
                for (tx in 0 until tplWidth) sum += band[row + tx].toInt() and 255 }
            val mean = sum / n
            var dot = 0.0; var variance = 0.0
            for (ty in 0 until tplHeight) { val row = (y + ty) * bandWidth + x; val trow = ty * tplWidth
                for (tx in 0 until tplWidth) {
                    val v = (band[row + tx].toInt() and 255) - mean
                    dot += v * t[trow + tx]; variance += v * v
                }
            }
            if (variance < 1e-9) continue
            val score = dot / kotlin.math.sqrt(variance * tVar)
            if (score > best) best = score
        }
        return best.coerceAtLeast(0.0)
    }

    /**
     * `"name-image"` when the OCR did not settle on a name ([QuestPanel.nameText] null: a readable different name is
     * never overridden), the panel shows the Free Quest emblem, the picture matches at [Entry.threshold] or more and the AP, if it
     * was read, is the quest's own. Null otherwise, which keeps today's behaviour (retries, then panel_mismatch).
     */
    fun matchBy(panel: QuestPanel, score: Double?, entry: Entry?, expectedAp: Int): String? {
        if (entry == null || score == null || !panel.free || panel.nameText != null) return null
        if (panel.ap != null && panel.ap != expectedAp) return null
        return BY.takeIf { score >= entry.threshold }
    }
}
