package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.models.*
import io.github.lib_automata.*
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

@ScriptScope
class BondReader @Inject constructor(api: IFgoAutomataApi, private val ocr: OcrService) : IFgoAutomataApi by api {
    companion object {
        const val TEXT_CONFIDENCE = 85
        const val VOTE_CONFIDENCE = 60
        const val IMAGE_SIMILARITY = 0.85
        val IMAGE_SCALES = listOf(0.9, 0.95, 1.0, 1.05, 1.1)
        val GAIN_PROFILES = listOf(3, 4, 6).flatMap { scale ->
            // Higher binary thresholds erase the thin interior of the KR digit 9.
            listOf(0, 80).map { threshold ->
                TextLineOcrOptions(scale = scale, threshold = threshold / 255.0, invert = true,
                    rawGrayscale = threshold == 0, padding = 12, allowedCharacters = "+(),0123456789 ")
            }
        }
        val JP_GAIN_PROFILES = GAIN_PROFILES + listOf(3, 4, 6).flatMap { scale ->
            listOf(110, 140).map { threshold ->
                GAIN_PROFILES.first().copy(scale = scale, threshold = threshold / 255.0, rawGrayscale = false)
            }
        }
        // Only retry inconclusive lines. Widening thin glyphs helps long bonuses and downscaled captures.
        val GAIN_FALLBACK_PROFILES = listOf(3, 4, 6).flatMap { scale ->
            listOf(0, 80, 110).map { threshold ->
                GAIN_PROFILES.first().copy(scale = scale, threshold = threshold / 255.0,
                    rawGrayscale = threshold == 0, horizontalScale = 1.4)
            }
        }
        // Compact rendering separates the JP parenthesis/plus glyphs that wide retries can merge.
        // Use it only after both existing passes fail, and retain all earlier conflict evidence.
        val GAIN_COMPACT_PROFILES = listOf(0, 60, 140).map { threshold ->
            GAIN_PROFILES.first().copy(scale = 2, threshold = threshold / 255.0,
                rawGrayscale = threshold == 0, horizontalScale = 0.8)
        }
        val LEVEL_PROFILES = JP_GAIN_PROFILES.filter { it.scale != 6 }.map { it.copy(allowedCharacters = "0123456789 ") }
        private val gain = Regex("""\+?(\d{1,3}(?:,\d{3})*|\d+)\(\+?(\d{1,3}(?:,\d{3})*|\d+)\)""")

        fun parseGain(text: String): BondValue.Gain? {
            val match = gain.matchEntire(text.replace(Regex("""\s+"""), "")) ?: return null
            val base = match.groupValues[1].replace(",", "").toIntOrNull() ?: return null
            val bonus = match.groupValues[2].replace(",", "").toIntOrNull() ?: return null
            // A complete pair is mandatory. Never join unrelated numbers from the NEXT row.
            return BondValue.Gain(base, bonus)
        }

        internal fun readGain(profiles: List<TextLineOcrOptions>, read: (TextLineOcrOptions) -> OcrText): BondValue.Gain? {
            val readings = profiles.map(read)
            // Retain primary evidence through every consensus retry and the majority fallback.
            confirmedGain(readings)?.let { return it }
            val widened = readings + GAIN_FALLBACK_PROFILES.map(read)
            confirmedGain(widened)?.let { return it }
            val all = widened + GAIN_COMPACT_PROFILES.map(read)
            return confirmedGain(all) ?: majorityGain(all)
        }

        internal fun readLevel(read: (TextLineOcrOptions) -> OcrText): Int? {
            val readings = LEVEL_PROFILES.map(read)
            val fallback = LEVEL_PROFILES.map { it.copy(horizontalScale = GAIN_FALLBACK_PROFILES.first().horizontalScale) }
            return confirmedLevel(readings) ?: confirmedLevel(readings + fallback.map(read))
        }

        fun confirmedGain(readings: List<OcrText>): BondValue.Gain? = consensus(readings, ::parseGain)
        internal fun majorityGain(readings: List<OcrText>, base: Int? = null): BondValue.Gain? {
            val votes = readings.filter { it.confidence >= VOTE_CONFIDENCE }.mapNotNull { reading ->
                parseGain(reading.text)?.takeIf { base == null || it.base == base }?.let { it to reading.confidence }
            }.groupBy({ it.first }, { it.second }).entries.sortedByDescending { it.value.size }
            val winner = votes.firstOrNull() ?: return null
            val runnerUp = votes.getOrNull(1)?.value?.size ?: 0
            return winner.key.takeIf {
                winner.value.size >= 3 && winner.value.any { it >= TEXT_CONFIDENCE } &&
                    winner.value.size - runnerUp >= 2 && runnerUp * 2 <= winner.value.size
            }
        }

        /** Third path only: all >=60 candidates agree, with six votes and independent same-frame base evidence. */
        internal fun unanimousGain(readings: List<OcrText>, base: Int): BondValue.Gain? {
            val candidates = readings.filter { it.confidence >= VOTE_CONFIDENCE }
            if (candidates.size < 6) return null
            val values = candidates.map { parseGain(it.text) ?: return null }
            return values.distinct().singleOrNull()?.takeIf { it.base == base }
        }

        /** Use a snapshot so column order cannot create its own corroborating evidence. */
        internal fun crossCheck(columns: List<ColumnReading>): List<ColumnReading> = columns.map { column ->
            if (column.candidates.isEmpty()) return@map column
            val bases = columns.filter { it.column != column.column }.mapNotNull { (it.value as? BondValue.Gain)?.base }
                .groupingBy { it }.eachCount().filterValues { it >= 2 }.keys
            val base = bases.singleOrNull()
            if (bases.size > 1) column.copy(value = BondValue.Unknown, reason = "conflicting column bases")
            else if (base == null) column
            else {
                val conflict = column.candidates.any {
                    it.confidence >= VOTE_CONFIDENCE && parseGain(it.text)?.let { gain -> gain.base != base } == true
                }
                if ((column.value as? BondValue.Gain)?.base == base && column.reason == "consensus") column
                else if (!conflict) {
                    val gain = if (column.value == BondValue.Unknown && confirmedGain(column.candidates) == null &&
                        majorityGain(column.candidates) == null) unanimousGain(column.candidates, base) else null
                    if (gain == null) column else column.copy(value = gain, reason = "unanimous+base")
                }
                else {
                    val gain = majorityGain(column.candidates, base)
                    column.copy(value = gain ?: BondValue.Unknown,
                        reason = if (gain == null) "base conflict" else "majority+base")
                }
            }
        }
        fun confirmedLevel(readings: List<OcrText>): Int? = consensus(readings) { text ->
            text.trim().takeIf { it.matches(Regex("[0-9]{1,2}")) }?.toIntOrNull()?.takeIf { it in 1..99 }
        }
        private fun <T> consensus(readings: List<OcrText>, parse: (String) -> T?): T? {
            val values = readings.filter { it.confidence >= TEXT_CONFIDENCE }.mapNotNull { parse(it.text) }
            return values.distinct().singleOrNull()?.takeIf { values.size >= 2 }
        }
    }

    /** Evidence from exactly the last frame, including every OCR profile actually used. */
    data class ColumnReading(val column: Int, val value: BondValue, val candidates: List<OcrText>, val reason: String?) {
        fun compact(): String = "c${column + 1}=$value" + (reason?.let { "($it)" } ?: "") +
            candidates.groupingBy { "${it.text.trim().replace(Regex("\\s+"), " ")}@${it.confidence}" }
                .eachCount().entries.joinToString(prefix = " ocr=[", postfix = "]") { (text, count) -> "$text*$count" }
    }
    var lastColumns: List<ColumnReading> = emptyList()
        private set

    private fun matches(region: Region, image: Images): List<Match> {
        val template = images[image]
        return IMAGE_SCALES.flatMap { scale ->
            if (scale == 1.0) region.findAll(template, IMAGE_SIMILARITY)
            else template.resize(Size((template.width * scale).toInt(), (template.height * scale).toInt())).use {
                region.findAll(it, IMAGE_SIMILARITY)
            }
        }
    }

    /** Cheap detector; OCR and mutable bookkeeping only run in the handler. */
    fun isScreen(): Boolean = matches(locations.bond.popupTitle, Images.BondPopupTitle).isNotEmpty() ||
        matches(locations.bond.resultTitle, Images.BondResultTitle).isNotEmpty()

    /** All text and image evidence belongs to this one frame. Never reuse across a click. */
    fun read(readGains: Boolean = true): BondFrame = useSameSnapIn {
        lastColumns = emptyList()
        if (matches(locations.bond.popupTitle, Images.BondPopupTitle).isNotEmpty()) {
            val arrow = matches(locations.bond.popupArrow, Images.Bond).maxByOrNull { it.score }
            val level = arrow?.let { match ->
                number(locations.bond.reachedLevel(match.region)) { pattern -> readLevel { ocr.detectTextLine(pattern, it) } }
            }
            val columns = (0..4).filter { matches(locations.bond.levelUpMarker(it), Images.BondLevelUpMarker).isNotEmpty() }
            return@useSameSnapIn BondFrame.LevelUp(level, columns.singleOrNull())
        }
        if (matches(locations.bond.resultTitle, Images.BondResultTitle).isEmpty()) return@useSameSnapIn BondFrame.Other
        val columns = (0..4).map { index ->
            val candidates = mutableListOf<OcrText>()
            var reason: String? = null
            val value = if (matches(locations.bond.gain(index), Images.BondMax).isNotEmpty()) BondValue.Max
            else if (readGains) number(locations.bond.gainText(index)) { pattern ->
                readGain(if (prefs.gameServer is GameServer.Kr) GAIN_PROFILES else JP_GAIN_PROFILES) {
                    ocr.detectTextLine(pattern, it).also(candidates::add)
                }
            } ?: BondValue.Unknown.also {
                reason = if (candidates.isEmpty()) "crop clipped" else "OCR consensus failed"
            }
            else BondValue.Unknown.also { reason = "gain reading disabled" }
            if (value is BondValue.Gain) reason = if (confirmedGain(candidates) == value) "consensus" else "majority"
            ColumnReading(index, value, candidates, reason)
        }
        lastColumns = crossCheck(columns)
        BondFrame.Normal(lastColumns.map { it.value })
    }

    private fun <T> number(region: Region, read: (Pattern) -> T?): T? =
        region.getPattern("bond_text").use { pattern ->
            if (pattern.width * 2 < region.width - 2 || pattern.height * 2 < region.height - 2) null
            else read(pattern)
        }
}
