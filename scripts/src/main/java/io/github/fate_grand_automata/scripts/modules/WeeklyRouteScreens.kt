package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.prefs.IAddedFeatureTuning
import io.github.fate_grand_automata.scripts.prefs.FeatureTuning

import io.github.fate_grand_automata.scripts.*
import io.github.fate_grand_automata.scripts.locations.WeeklyRouteLocations
import io.github.fate_grand_automata.scripts.weekly.RouteScreen
import javax.inject.Inject

class WeeklyRouteScreens @Inject constructor(api: IFgoAutomataApi, private val loc: WeeklyRouteLocations,
    private val addedTuning: IAddedFeatureTuning = IAddedFeatureTuning.Default
) : IFgoAutomataApi by api {
    companion object {
        fun classify(notice: Double, terminal: Double, close: Double, tuning: FeatureTuning = FeatureTuning()): RouteScreen {
            val candidates = listOf(RouteScreen.Home to notice, RouteScreen.Map to terminal, RouteScreen.List to close)
                .sortedByDescending { it.second }
            val best = candidates.first()
            return if (best.second >= tuning.similarity(0.88) && best.second - candidates[1].second >= 0.05) best.first else RouteScreen.Unknown
        }
    }

    /** Caller may group these scores with content anchors in the same snapshot. */
    fun scores(): List<Double> = listOf(Images.WeeklyNotice, Images.WeeklyTerminal, Images.WeeklyClose).map { image ->
        loc.topLeftButton.find(images[image], similarity = addedTuning.weekly.similarity(0.7))?.score ?: 0.0
    }

    fun read(): RouteScreen = useSameSnapIn {
        val (notice, terminal, close) = scores()
        classify(notice, terminal, close, addedTuning.weekly)
    }
}
