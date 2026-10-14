package io.github.fate_grand_automata.scripts.locations

import io.github.lib_automata.Region
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

/** 1440p coordinates; templates were measured in the provided 720p KR/JP game areas. */
@ScriptScope
class QuestItemLocations @Inject constructor(transforms: IScriptAreaTransforms) : IScriptAreaTransforms by transforms {
    val mainControl = (if (isWide) Region(-1330, -242, 520, 158) else Region(-1140, -172, 520, 158))
        .xFromRight().yFromBottom()
    val partyHeading = Region(-800, 0, 800, 170).xFromRight()
    val dialogHeading = Region(-520, 160, 1040, 160).xFromCenter()
    val stormPodControl = Region(-250, 730, 200, 164).xFromCenter()
    val teapotControl = Region(250, 730, 200, 164).xFromCenter()
    val dialogClose = Region(-310, 1040, 620, 174).xFromCenter()
}
