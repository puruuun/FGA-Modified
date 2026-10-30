package io.github.fate_grand_automata.scripts.locations

import io.github.lib_automata.Location
import io.github.lib_automata.Region

/** Template origins at 720p, measured against the existing main-screen reference frames.
 * Notice is the (20,24,148,40) cut in cut_weekly_templates.py. MENU's crop starts 175px from the right.
 * Wide headers use the same safe-area inset as WeeklyRouteLocations.topLeftButton. */
object GameAreaCalibrationLocations {
    fun notice720(wide: Boolean) = Location(20, 24).let {
        // Keep the half pixel: the script-space inset is 171, not 170.
        (it.x + if (wide) WIDE_EDGE_INSET / 2.0 else 0.0) to it.y.toDouble()
    }
    fun menu720(wide: Boolean) = (175.0 + if (wide) WIDE_EDGE_INSET / 2.0 else 0.0) to 622.0

    /** Exactly Locations.menuScreenRegion, in 1440p script space. */
    fun menuSearch(scriptWidth: Int, wide: Boolean) =
        if (wide) Region(scriptWidth - 600, 1200, 600, 240)
        else Region(scriptWidth - 460, 1200, 460, 240)
}
