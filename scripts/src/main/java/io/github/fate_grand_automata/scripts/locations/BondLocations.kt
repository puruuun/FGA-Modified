package io.github.fate_grand_automata.scripts.locations

import io.github.lib_automata.Region
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

/** Coordinates at 1440p; result columns exclude the support and retain party order. */
@ScriptScope
class BondLocations @Inject constructor(transforms: IScriptAreaTransforms) : IScriptAreaTransforms by transforms {
    val resultTitle = Region(-1200, 280, 1000, 180).xFromCenter()
    val popupTitle = Region(0, 560, 1220, 220).xFromCenter()
    val popupArrow = Region(680, 700, 400, 240).xFromCenter()
    val partyHeading = Region(-1280, 160, 650, 160).xFromCenter()
    fun reachedLevel(arrow: Region) = Region(arrow.right + 22, arrow.y - 24, 180, 110)
    fun gain(index: Int) = Region(-1115 + index * 450, 976, 430, 92).xFromCenter()
    fun levelUpMarker(index: Int) = Region(-1125 + index * 450, 660, 450, 190).xFromCenter()
    fun partySupport(index: Int) = Region(-1280 + index * 426, 310, 500, 170).xFromCenter()
    fun partyLevel(index: Int) = Region(-1280 + index * 426, 1030, 426, 120).xFromCenter()
    fun partyVacant(index: Int) = Region(-1250 + index * 426, 1060, 160, 50).xFromCenter()
}
