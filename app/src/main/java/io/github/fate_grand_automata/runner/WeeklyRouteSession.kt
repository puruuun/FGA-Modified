package io.github.fate_grand_automata.runner

import io.github.fate_grand_automata.scripts.weekly.IWeeklyRouteSession
import io.github.fate_grand_automata.scripts.weekly.WeeklyRouteTarget
import javax.inject.Inject
import javax.inject.Singleton

/** Carries the confirmed route quest from the confirm dialog (service side) to the run's own Hilt component. */
@Singleton
class WeeklyRouteSession @Inject constructor() : IWeeklyRouteSession {
    @Volatile
    override var target: WeeklyRouteTarget? = null
}
