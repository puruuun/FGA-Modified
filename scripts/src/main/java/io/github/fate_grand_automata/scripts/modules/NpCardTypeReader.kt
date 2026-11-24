package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.lib_automata.OcrService
import io.github.lib_automata.Pattern
import io.github.lib_automata.TextLineOcrOptions

/**
 * Reads the NP card type in a Servant's details dialog (INFO tab) and the remaining-turns digit of the banner
 * that covers it after a type-changing skill. Shared by [ServantTracker] and the `npType` device QA suite.
 */
class NpCardTypeReader(
    api: IFgoAutomataApi,
    private val ocr: OcrService?
) : IFgoAutomataApi by api {
    data class Reading(val type: CardTypeEnum?, val scores: Map<CardTypeEnum, Double>)

    data class TurnsReading(val text: String, val turns: Int?)

    // Loaded on the first read, so a run without Chain Priority never loads them.
    private val templates by lazy {
        mapOf(
            CardTypeEnum.Buster to images[Images.NpBuster],
            CardTypeEnum.Arts to images[Images.NpArts],
            CardTypeEnum.Quick to images[Images.NpQuick],
        )
    }

    fun readType(minSimilarity: Double, tag: String = "NP type"): Reading =
        locations.battle.servantNpCardTypeRegion.getPattern(tag).use { readType(it, minSimilarity) }

    /** The best type at or above [minSimilarity], with every type's score for the log. */
    fun readType(npImage: Pattern, minSimilarity: Double): Reading {
        // Similarity 0 returns the best score even when it is too low, so the log can show it
        val scores = templates.mapValues { (_, template) -> npImage.findBestMatch(template, 0.0)?.score ?: 0.0 }
        val type = scores
            .filterValues { it >= minSimilarity }
            .maxByOrNull { it.value }
            ?.key
        return Reading(type, scores)
    }

    /** The banner's remaining turns, or null without OCR. A text that is not a single positive number gives null turns. */
    fun readChangeTurns(): TurnsReading? {
        val ocr = ocr ?: return null
        val text = locations.battle.servantNpChangeTurnsRegion.getPattern("NP change turns").use { pattern ->
            ocr.detectTextLine(pattern, TURN_DIGIT_OCR).text
        }
        return TurnsReading(text, parseNpChangeTurns(text))
    }

    companion object {
        /**
         * Upstream's OCR input for this digit: the 720p crop enlarged 2x with INTER_CUBIC, then thresholded at 0.5.
         * Not tuned on our captures; the npType device suite reports whether Tesseract reads KR and JP digits.
         */
        val TURN_DIGIT_OCR = TextLineOcrOptions(scale = 2, threshold = 0.5)
    }
}
