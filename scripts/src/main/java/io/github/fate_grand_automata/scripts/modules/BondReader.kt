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
        val LEVEL_PROFILES = JP_GAIN_PROFILES.filter { it.scale != 6 }.map { it.copy(allowedCharacters = "0123456789 ") }
        private val gain = Regex("""\+?(\d{1,3}(?:,\d{3})*|\d+)\(\+?(\d{1,3}(?:,\d{3})*|\d+)\)""")

        fun parseGain(text: String): BondValue.Gain? {
            val match = gain.matchEntire(text.replace(Regex("""\s+"""), "")) ?: return null
            val base = match.groupValues[1].replace(",", "").toIntOrNull() ?: return null
            val bonus = match.groupValues[2].replace(",", "").toIntOrNull() ?: return null
            // A complete pair is mandatory. Never join unrelated numbers from the NEXT row.
            return BondValue.Gain(base, bonus)
        }

        fun confirmedGain(readings: List<OcrText>): BondValue.Gain? = consensus(readings, ::parseGain)
        fun confirmedLevel(readings: List<OcrText>): Int? = consensus(readings) { text ->
            text.trim().takeIf { it.matches(Regex("[0-9]{1,2}")) }?.toIntOrNull()?.takeIf { it in 1..99 }
        }
        private fun <T> consensus(readings: List<OcrText>, parse: (String) -> T?): T? {
            val values = readings.filter { it.confidence >= TEXT_CONFIDENCE }.mapNotNull { parse(it.text) }
            return values.distinct().singleOrNull()?.takeIf { values.size >= 2 }
        }
    }

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
        if (matches(locations.bond.popupTitle, Images.BondPopupTitle).isNotEmpty()) {
            val arrow = matches(locations.bond.popupArrow, Images.Bond).maxByOrNull { it.score }
            val level = arrow?.let { number(locations.bond.reachedLevel(it.region), LEVEL_PROFILES, ::confirmedLevel) }
            val columns = (0..4).filter { matches(locations.bond.levelUpMarker(it), Images.BondLevelUpMarker).isNotEmpty() }
            return@useSameSnapIn BondFrame.LevelUp(level, columns.singleOrNull())
        }
        if (matches(locations.bond.resultTitle, Images.BondResultTitle).isEmpty()) return@useSameSnapIn BondFrame.Other
        BondFrame.Normal((0..4).map { index ->
            val region = locations.bond.gain(index)
            if (matches(region, Images.BondMax).isNotEmpty()) BondValue.Max
            else if (readGains) number(region,
                if (prefs.gameServer is GameServer.Kr) GAIN_PROFILES else JP_GAIN_PROFILES,
                ::confirmedGain) ?: BondValue.Unknown
            else BondValue.Unknown
        })
    }

    private fun <T> number(region: Region, profiles: List<TextLineOcrOptions>, confirm: (List<OcrText>) -> T?): T? =
        region.getPattern("bond_text").use { pattern ->
            if (pattern.width * 2 < region.width - 2 || pattern.height * 2 < region.height - 2) null
            else confirm(profiles.map { ocr.detectTextLine(pattern, it) })
        }
}
