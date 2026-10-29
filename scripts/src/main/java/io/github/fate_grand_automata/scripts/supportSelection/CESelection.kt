package io.github.fate_grand_automata.scripts.supportSelection

import io.github.fate_grand_automata.scripts.prefs.IAddedFeatureTuning
import io.github.fate_grand_automata.SupportImageKind
import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.enums.BondCEEffectEnum
import io.github.fate_grand_automata.scripts.prefs.ISupportPreferences
import io.github.lib_automata.Region
import io.github.lib_automata.Match
import io.github.lib_automata.Pattern
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import java.util.Locale

@ScriptScope
class CESelection @Inject constructor(
    api: IFgoAutomataApi,
    private val supportPrefs: ISupportPreferences,
    private val starChecker: SupportSelectionStarChecker,
    private val grandChecker: SupportSelectionGrandChecker,
    private val addedTuning: IAddedFeatureTuning = IAddedFeatureTuning.Default
) : IFgoAutomataApi by api {
    companion object {
        // #43 KR device: true NP 0.5926–0.7317, Default 0.737/0.874, wrong icon 0.15–0.25;
        // host CE-column background <= 0.45. Require the selected icon to beat its opposite too.
        const val BOND_EFFECT_SIMILARITY = 0.55
    }

    fun check(ces: List<String>, bounds: SupportBounds): Boolean {
        // TODO: Only check the lower part (excluding Servant)
        val searchRegion = bounds.region.clip(locations.support.listRegion)
        val grandSearchRegion = bounds.region.clip(locations.support.grandCeListRegion)

        if (ces.isEmpty()) {
            // servant must not have blank ce
            return !searchRegion.exists(images[Images.SupportBlankCE])
        }

        if (isGrandServant(grandSearchRegion)) {
            val matched = ces
                .flatMap { entry -> images.loadSupportPattern(SupportImageKind.CE, entry) }
                .mapNotNull {
                    grandSearchRegion.find(it, similarity = addedTuning.support.defaultSimilarity { prefs.platformPrefs.minSimilarity })
                }
                .filter {
                    !supportPrefs.mlb || isGrandLimitBroken(it.region)
                }

            val grandCeRegion1 = locations.support.grandCeRegion1.copy(y = searchRegion.y + locations.support.grandCeRegion1.y)
            val grandCeRegion3 = locations.support.grandCeRegion3.copy(y = searchRegion.y + locations.support.grandCeRegion3.y)
            val bondRegion = locations.support.bondCeRegion.copy(y = searchRegion.y + locations.support.bondCeRegion.y)

            val normalMatch = matched.any { grandCeRegion1.contains(it.region) }
            val rewardMatch = matched.any { grandCeRegion3.contains(it.region) }

            val ceSlotMatch = if (supportPrefs.requireBothNormalAndRewardMatch) {
                normalMatch && rewardMatch
            } else {
                normalMatch || rewardMatch
            }

            val (np, default) = if (supportPrefs.bondCEEffect != BondCEEffectEnum.Ignore || messages.supportCeDiagnosticsEnabled) {
                bondRegion.getPattern().use { crop ->
                    bestMatch(bondRegion, crop, images[Images.BondCeEffectNP]) to
                        bestMatch(bondRegion, crop, images[Images.BondCeEffectDefault])
                }
            } else null to null
            val npScore = np?.score ?: 0.0
            val defaultScore = default?.score ?: 0.0
            val bondEffectMatch = when (supportPrefs.bondCEEffect) {
                BondCEEffectEnum.NP -> npScore >= addedTuning.support.similarity(BOND_EFFECT_SIMILARITY) && npScore > defaultScore
                BondCEEffectEnum.Default -> defaultScore >= addedTuning.support.similarity(BOND_EFFECT_SIMILARITY) && defaultScore > npScore
                BondCEEffectEnum.Ignore -> true
            }

            val decision = ceSlotMatch && bondEffectMatch
            if (messages.supportCeDiagnosticsEnabled) {
                logGrandDecision(ces, grandSearchRegion, grandCeRegion1, grandCeRegion3, bondRegion, np, default, bondEffectMatch, decision)
            }
            return decision
        } else {
            val matched = ces
                .flatMap { entry -> images.loadSupportPattern(SupportImageKind.CE, entry) }
                .mapNotNull {
                    searchRegion.find(it)
                }
                .filter {
                    !supportPrefs.mlb || isLimitBroken(it.region)
                }
            return matched.isNotEmpty()
                && !supportPrefs.requireBothNormalAndRewardMatch
                && supportPrefs.bondCEEffect == BondCEEffectEnum.Ignore
        }
    }

    private fun isLimitBroken(craftEssence: Region): Boolean {
        val limitBreakRegion = locations.support.limitBreakRegion
            .copy(y = craftEssence.y)

        return starChecker.isStarPresent(limitBreakRegion)
    }

    private fun isGrandServant(ces: Region): Boolean {
        val multiCeLabelRegion = locations.support.grandCeLabelRegion
            .copy(y = ces.y + 40)

        return grandChecker.isGrandPresent(multiCeLabelRegion)
    }

    private fun isGrandLimitBroken(craftEssence: Region): Boolean {
        val limitBreakRegion = locations.support.grandLimitBreakRegion
            .copy(y = craftEssence.y)

        return starChecker.isStarPresent(limitBreakRegion)
    }

    /** Diagnostic scans never feed the selection decision or change its configured similarity. */
    private fun logGrandDecision(
        ces: List<String>, search: Region, normal: Region, reward: Region, bond: Region,
        np: Match?, default: Match?, bondMatched: Boolean, decision: Boolean
    ) {
        val label = locations.support.grandCeLabelRegion.copy(y = search.y + 40)
        val patterns = ces.flatMap { name -> images.loadSupportPattern(SupportImageKind.CE, name).map { name to it } }
        fun slot(name: String, region: Region): String {
            val visible = search.clip(region)
            val best = patterns.mapNotNull { (entry, template) -> bestMatch(visible, template)?.let { entry to it } }
                .maxByOrNull { it.second.score }
            val mlb = best?.let { isGrandLimitBroken(it.second.region) }
            return "$name={ce=${best?.first ?: "none"} best=${describe(best?.second)} search=$region mlb=$mlb}"
        }
        val selected = when (supportPrefs.bondCEEffect) {
            BondCEEffectEnum.NP -> np
            BondCEEffectEnum.Default -> default
            BondCEEffectEnum.Ignore -> null
        }
        messages.log(ScriptLog.SupportCe(
            "grand={present=true best=${describe(bestMatch(label, images[Images.GrandCeLabel]))} search=$label} " +
                "${slot("normal", normal)} ${slot("reward", reward)} mlbRequired=${supportPrefs.mlb} " +
                "bothSlots=${supportPrefs.requireBothNormalAndRewardMatch} " +
                "bond={setting=${supportPrefs.bondCEEffect} best=${describe(selected)} search=$bond " +
                "np=${describe(np)} default=${describe(default)} threshold=$BOND_EFFECT_SIMILARITY matched=$bondMatched} " +
                "decision=$decision",
            np, default, decision
        ))
    }

    private fun bestMatch(region: Region, template: Pattern): Match? = region.getPattern().use { crop ->
        bestMatch(region, crop, template)
    }

    private fun bestMatch(region: Region, crop: Pattern, template: Pattern): Match? {
        if (crop.width < template.width || crop.height < template.height) return null
        // Exhaust the lazy matcher so its native result Mat is released. The positive scan floor
        // stops after suppressed matches reach zero; the decision applies its own threshold.
        return crop.findMatches(template, 0.01).maxByOrNull { it.score }
            ?.let { Match(it.region * 2.0 + region.location, it.score) }
    }

    private fun describe(match: Match?): String = match?.let {
        "${String.format(Locale.ROOT, "%.4f", it.score)}@${it.region}"
    } ?: "none"
}
