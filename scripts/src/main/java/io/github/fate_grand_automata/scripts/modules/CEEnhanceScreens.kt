package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.locations.CEEnhanceLocations
import javax.inject.Inject

class CEEnhanceScreens @Inject constructor(
    api: IFgoAutomataApi,
    val loc: CEEnhanceLocations
) : IFgoAutomataApi by api {
    enum class Recommended { Active, Disabled, Absent }

    companion object {
        // Active can score 0.852 against the disabled template. Compare both on the same frame.
        fun recommended(active: Double, disabled: Double) = when {
            active >= 0.85 && active > disabled -> Recommended.Active
            disabled >= 0.85 && disabled > active -> Recommended.Disabled
            else -> Recommended.Absent
        }
    }

    fun onScreen(): Boolean = (prefs.gameServer is GameServer.Jp || prefs.gameServer is GameServer.Kr) &&
        loc.reselect.exists(images[Images.CEEnhanceReselect])

    fun recommendedScores(): Pair<Double, Double> = useSameSnapIn {
        val region = locations.servant.servantAutoSelectRegion
        (region.findAll(images[Images.ServantAutoSelect], 0.01).maxOfOrNull { it.score } ?: 0.0) to
            (region.findAll(images[Images.ServantAutoSelectOff], 0.01).maxOfOrNull { it.score } ?: 0.0)
    }

    fun recommended(): Recommended = recommendedScores().let { (active, disabled) -> recommended(active, disabled) }
}
