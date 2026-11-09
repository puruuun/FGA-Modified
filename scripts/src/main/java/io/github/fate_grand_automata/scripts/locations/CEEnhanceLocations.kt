package io.github.fate_grand_automata.scripts.locations

import io.github.lib_automata.Location
import io.github.lib_automata.Region
import javax.inject.Inject

class CEEnhanceLocations @Inject constructor(
    transforms: IScriptAreaTransforms
) : IScriptAreaTransforms by transforms {
    val execute = Region(675, 1210, 300, 120).xFromCenter()
    val autoFill = Region(265, 1013, 100, 80).xFromCenter()
    val reselect = Region(340, 0, 340, 180).xFromLeftEdge()

    /** Right-hand confirm button of the game's "recommended material target" warning dialog. */
    val filterWarningConfirmRegion = Region(300, 1085, 260, 90).xFromCenter()
    val filterWarningConfirm = Location(400, 1130).xFromCenter()
}
