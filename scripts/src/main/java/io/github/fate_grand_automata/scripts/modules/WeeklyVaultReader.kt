package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.prefs.IAddedFeatureTuning
import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.locations.WeeklyRouteLocations
import io.github.fate_grand_automata.scripts.locations.WeeklyVaultAnchor
import io.github.lib_automata.MeanColor
import io.github.lib_automata.OcrService
import io.github.lib_automata.OcrText
import io.github.lib_automata.ScriptAbortException
import io.github.lib_automata.TextLineOcrOptions
import java.text.Normalizer
import javax.inject.Inject

data class VaultColor(val hue: Double, val saturation: Double) {
    val yellow get() = hue in 38.0..52.0 && saturation >= 0.80
}

object WeeklyVaultPolicy {
    fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).replace(Regex("[\\s\\p{P}\\p{S}]"), "")
    fun consensus(reads: List<OcrText>): String? = reads.filter { it.text.isNotBlank() && it.confidence >= 50 }
        .groupBy { normalize(it.text) }.filter { it.value.size >= 2 }.maxByOrNull { it.value.size }?.key

    fun color(rgb: MeanColor): VaultColor {
        val r = rgb.red / 255; val g = rgb.green / 255; val b = rgb.blue / 255
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
        val raw = when {
            d == 0.0 -> 0.0
            max == r -> 60 * ((g - b) / d % 6)
            max == g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }
        return VaultColor((raw + 360) % 360, if (max == 0.0) 0.0 else d / max)
    }
}

data class VaultPanel(val anchor: WeeklyVaultAnchor, val color: VaultColor?, val name: String?,
                      val rawNames: List<OcrText>, val ap: Int?, val accepted: Boolean) {
    val reason get() = if (accepted) "accepted" else "vault colour mismatch"
}

/** The first yellow panel is Beginner. Name and displayed campaign AP are log-only evidence. */
class WeeklyVaultReader @Inject constructor(
    api: IFgoAutomataApi, private val loc: WeeklyRouteLocations, private val ocr: OcrService,
    private val addedTuning: IAddedFeatureTuning = IAddedFeatureTuning.Default
) : IFgoAutomataApi by api {
    companion object {
        const val ANCHOR_SIMILARITY = 0.85
        // White cost digits on the dark arrow: inverted reads agree on 10 on JP/KR.
        // The opposite polarity misreads the thin 1 as 4, contradicting otherwise correct reads.
        val AP_PROFILES = QuestPanelReader.AP_PROFILES.filter { it.invert }
    }

    fun anchorMatches() = loc.listArea.findAll(images[Images.WeeklyDailyQuest], addedTuning.weekly.similarity(ANCHOR_SIMILARITY))
        .filter { WeeklyVaultAnchor(it.region.location).fits(loc.scriptArea) }.toList()

    fun anchors() = anchorMatches().sortedBy { it.region.y }.map { WeeklyVaultAnchor(it.region.location) }.take(8)

    private fun <T> diagnostic(read: () -> T): T? = try { read() }
        catch (e: ScriptAbortException) { throw e }
        catch (e: InterruptedException) { throw e }
        catch (_: Exception) { null }

    fun read(): List<VaultPanel> {
        val anchors = useSameSnapIn { anchors() }
        return anchors.map { anchor ->
            val color = useColor { anchor.color.getPattern().use { it.meanColor()?.let(WeeklyVaultPolicy::color) } }
            val (reads, ap) = useSameSnapIn {
                val language = if (prefs.gameServer is GameServer.Kr) "kor_weekly" else "jpn_weekly"
                val reads = diagnostic { anchor.name.getPattern().use { image ->
                    listOf(
                        TextLineOcrOptions(scale = 3, threshold = 0.85, invert = true, padding = 12, language = language),
                        TextLineOcrOptions(scale = 4, threshold = 0.85, invert = true, padding = 12, language = language),
                        TextLineOcrOptions(scale = 3, threshold = 0.75, invert = true, padding = 12, language = language)
                    ).map { ocr.detectTextLine(image, it) }
                } + anchor.tightName.getPattern().use { image ->
                    ocr.detectTextLine(image, TextLineOcrOptions(scale = 2, threshold = 0.75, invert = true, padding = 12, language = language))
                } }.orEmpty()
                val ap = diagnostic { anchor.ap.getPattern().use { image ->
                    QuestPanelMatch.apConsensus(AP_PROFILES.map { ocr.detectTextLine(image, it) })
                } }
                reads to ap
            }
            val name = WeeklyVaultPolicy.consensus(reads)
            VaultPanel(anchor, color, name, reads, ap,
                color?.yellow == true)
        }
    }
}
