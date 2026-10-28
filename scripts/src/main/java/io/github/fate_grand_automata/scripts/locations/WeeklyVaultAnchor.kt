package io.github.fate_grand_automata.scripts.locations

import io.github.lib_automata.Location
import io.github.lib_automata.Region

/** Offsets from the Daily Quest lettering, measured on JP 8 / KR 11 (1440p). */
data class WeeklyVaultAnchor(val mark: Location) {
    val name = Region(mark.x - 8, mark.y - 64, 660, 56)
    val tightName = Region(mark.x - 8, mark.y - 58, 660, 40)
    // Empty title-bar background, right of the lettering and left of the level/class text.
    val color = Region(mark.x + 500, mark.y, 80, 20)
    // Numeric cost only, excluding the adjacent AP lettering on both servers.
    val ap = Region(mark.x + 68, mark.y + 76, 70, 60)
    val tap = Location(mark.x + 200, mark.y - 36)
    fun fits(area: Region) = listOf(name, color, ap).all { area.contains(it) }
}
