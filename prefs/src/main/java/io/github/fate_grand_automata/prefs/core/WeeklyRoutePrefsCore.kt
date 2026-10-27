package io.github.fate_grand_automata.prefs.core

class WeeklyRoutePrefsCore(maker: PrefMaker) {
    val disabled = maker.stringSet("weekly_route_disabled")
    val unselected = maker.stringSet("weekly_route_unselected")
}
