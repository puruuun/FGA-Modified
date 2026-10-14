package io.github.fate_grand_automata.scripts.locations

import io.github.lib_automata.Region
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

/** Coordinates at 1440p; only the five result columns are used. */
@ScriptScope
class BondLocations @Inject constructor(transforms: IScriptAreaTransforms) : IScriptAreaTransforms by transforms {
    val resultTitle = Region(-1200, 280, 1000, 180).xFromCenter()
    val popupTitle = Region(0, 560, 1220, 220).xFromCenter()
    val popupArrow = Region(680, 700, 400, 240).xFromCenter()
    fun reachedLevel(arrow: Region) = Region(arrow.right + 22, arrow.y - 24, 180, 110)
    fun gain(index: Int) = Region(-1115 + index * 450, 976, 430, 92).xFromCenter()
    fun levelUpMarker(index: Int) = Region(-1125 + index * 450, 660, 450, 190).xFromCenter()
}
