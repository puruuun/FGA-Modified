package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.*
import io.github.fate_grand_automata.scripts.locations.WeeklyRouteLocations
import io.github.fate_grand_automata.scripts.weekly.RouteScreen
import javax.inject.Inject

class WeeklyRouteScreens @Inject constructor(api: IFgoAutomataApi,private val loc: WeeklyRouteLocations): IFgoAutomataApi by api {
    companion object {
        const val TITLE_SIMILARITY = 0.85
        const val TITLE_MARGIN = 0.05
        fun listScreen(gate: Double, daily: Double): RouteScreen = when {
            gate >= TITLE_SIMILARITY && gate - daily >= TITLE_MARGIN -> RouteScreen.GateList
            daily >= TITLE_SIMILARITY && daily - gate >= TITLE_MARGIN -> RouteScreen.DailyList
            maxOf(gate, daily) < 0.7 -> RouteScreen.List // Close alone: existing main-record lists/panels.
            else -> RouteScreen.Unknown
        }
    }

    fun titleScores(): Pair<Double, Double> {
        fun score(image: Images) = loc.gateHeader.findAll(images[image], 0.01).maxOfOrNull { it.score } ?: 0.0
        return score(Images.WeeklyGateTitle) to score(Images.WeeklyDailyTitle)
    }

    fun read(): RouteScreen = useSameSnapIn {
        val candidates=listOf(Images.WeeklyNotice to RouteScreen.Home,Images.WeeklyTerminal to RouteScreen.Map,Images.WeeklyClose to RouteScreen.List)
            .map { (image,screen) -> screen to (loc.topLeftButton.find(images[image],similarity=0.7)?.score ?: 0.0) }
            .sortedByDescending { it.second }
        val best=candidates.first()
        if(best.second>=0.88 && best.second-candidates[1].second>=0.05) {
            if (best.first == RouteScreen.List) titleScores().let { (gate, daily) -> listScreen(gate, daily) }
            else best.first
        } else RouteScreen.Unknown
    }
}
