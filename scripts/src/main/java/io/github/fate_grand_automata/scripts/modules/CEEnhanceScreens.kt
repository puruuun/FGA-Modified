package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.prefs.IAddedFeatureTuning
import io.github.fate_grand_automata.scripts.prefs.FeatureTuning

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.locations.CEEnhanceLocations
import javax.inject.Inject

class CEEnhanceScreens @Inject constructor(
    api: IFgoAutomataApi,
    val loc: CEEnhanceLocations,
    private val addedTuning: IAddedFeatureTuning = IAddedFeatureTuning.Default
) : IFgoAutomataApi by api {
    enum class Recommended { Active, Disabled, Absent }

    companion object {
        // Active can score 0.852 against the disabled template. Compare both on the same frame.
        fun recommended(active: Double, disabled: Double, tuning: FeatureTuning = FeatureTuning()) = when {
            active >= tuning.similarity(0.85) && active > disabled -> Recommended.Active
            disabled >= tuning.similarity(0.85) && disabled > active -> Recommended.Disabled
            else -> Recommended.Absent
        }
    }

    fun onScreen(): Boolean = (prefs.gameServer is GameServer.Jp || prefs.gameServer is GameServer.Kr) &&
        loc.reselect.exists(images[Images.CEEnhanceReselect], similarity = addedTuning.ce.defaultSimilarity { prefs.platformPrefs.minSimilarity })

    fun recommendedScores(): Pair<Double, Double> = locations.servant.servantAutoSelectRegion.getPattern().use { crop ->
        fun score(image: Images) = crop.findMatches(images[image], 0.01).maxOfOrNull { it.score } ?: 0.0
        score(Images.ServantAutoSelect) to score(Images.ServantAutoSelectOff)
    }

    fun recommended(): Recommended = recommendedScores().let { (active, disabled) -> recommended(active, disabled, addedTuning.ce) }
}
