package io.github.fate_grand_automata.scripts.supportSelection

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
    private val grandChecker: SupportSelectionGrandChecker
) : IFgoAutomataApi by api {
    companion object {
        // #23: tiny bond icons need margin after screenshot scaling. Other checks keep their own thresholds.
        const val BOND_EFFECT_SIMILARITY = 0.70
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
                    grandSearchRegion.find(it)
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

            val bondEffectMatch = when (supportPrefs.bondCEEffect) {
                BondCEEffectEnum.Default -> {
                    bondRegion.find(images[Images.BondCeEffectDefault], similarity = BOND_EFFECT_SIMILARITY) != null
                }
                BondCEEffectEnum.NP -> {
                    bondRegion.find(images[Images.BondCeEffectNP], similarity = BOND_EFFECT_SIMILARITY) != null
                }
                else -> {
                    true
                }
            }

            val decision = ceSlotMatch && bondEffectMatch
            if (messages.supportCeDiagnosticsEnabled) {
                logGrandDecision(ces, grandSearchRegion, grandCeRegion1, grandCeRegion3, bondRegion, bondEffectMatch, decision)
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
        bondMatched: Boolean, decision: Boolean
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
        val np = bestMatch(bond, images[Images.BondCeEffectNP])
        val default = bestMatch(bond, images[Images.BondCeEffectDefault])
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
        if (crop.width < template.width || crop.height < template.height) return@use null
        // Take only the first (highest) result, including scores below the product threshold.
        crop.findMatches(template, -1.0).firstOrNull()?.let { Match(it.region * 2.0 + region.location, it.score) }
    }

    private fun describe(match: Match?): String = match?.let {
        "${String.format(Locale.ROOT, "%.4f", it.score)}@${it.region}"
    } ?: "none"
}
