package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.*
import io.github.fate_grand_automata.scripts.locations.WeeklyRouteLocations
import io.github.fate_grand_automata.scripts.weekly.RouteScreen
import javax.inject.Inject

class WeeklyRouteScreens @Inject constructor(api: IFgoAutomataApi,private val loc: WeeklyRouteLocations): IFgoAutomataApi by api {
    fun read(): RouteScreen = useSameSnapIn {
        val candidates=listOf(Images.WeeklyNotice to RouteScreen.Home,Images.WeeklyTerminal to RouteScreen.Map,Images.WeeklyClose to RouteScreen.List)
            .map { (image,screen) -> screen to (loc.topLeftButton.find(images[image],similarity=0.7)?.score ?: 0.0) }
            .sortedByDescending { it.second }
        val best=candidates.first()
        if(best.second>=0.88 && best.second-candidates[1].second>=0.05) best.first else RouteScreen.Unknown
    }
}
