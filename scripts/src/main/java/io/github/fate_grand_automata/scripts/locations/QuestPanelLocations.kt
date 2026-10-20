package io.github.fate_grand_automata.scripts.locations

import io.github.lib_automata.Region
import javax.inject.Inject

/**
 * The uppermost quest panel of a spot (the one AutoBattle taps through [Locations.menuSelectQuestClick]).
 * 1440p coordinates measured on the owner's 1280x720 normalized captures (x1440 = x720 * 2 - 2560 from the right edge,
 * y1440 = y720 * 2). JP and KR share the layout. Wide screens move the panel 190 left like [Locations.menuSelectQuestClick];
 * that branch has not been measured on a wide device.
 */
class QuestPanelLocations @Inject constructor(transforms: IScriptAreaTransforms) : IScriptAreaTransforms by transforms {
    private fun panel(region: Region) = (if (isWide) region + io.github.lib_automata.Location(-190, 0) else region).xFromRight()

    /** The "Free Quest" emblem on the left of the panel. Mission and one-off quests show other artwork here. */
    val freeLabel = panel(Region(-1330, 270, 330, 220))

    /** Digits of "AP 5" only, the "AP" letters are left of this region. Up to two digits. */
    val apDigits = panel(Region(-922, 410, 100, 76))

    /** Quest name band, up to the recommended-level column. */
    val name = panel(Region(-1004, 272, 694, 60))
}
