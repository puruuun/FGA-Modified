package io.github.fate_grand_automata.scripts.locations

import io.github.lib_automata.Region
import javax.inject.Inject

/**
 * The uppermost quest panel of a spot (the one AutoBattle taps through [Locations.menuSelectQuestClick]).
 * 1440p coordinates measured on the owner's 1280x720 normalized captures (x1440 = x720 * 2 - 2560 from the right edge,
 * y1440 = y720 * 2). JP and KR share the layout. Wide screens move the panel in by the right safe-area inset
 * ([WIDE_EDGE_INSET]): owner 21:9 capture 04 shows the whole panel 85 px (720p) further left, with the same size and
 * inner layout. The anchored reads ([QuestPanelAnchor]) do not depend on this at all.
 */
class QuestPanelLocations @Inject constructor(transforms: IScriptAreaTransforms) : IScriptAreaTransforms by transforms {
    /** D-20: find the emblem across the game area, independent of panel slot or horizontal placement. */
    val freeSearchBand get() = Region(24, 160, scriptArea.width - 48, 1160)

    /** Enlarged spot ribbon after a spot tap; measured on owner 720p panels, centred in the left map area. */
    val enlargedSpotName = Region(-880, 936, 700, 64).xFromCenter()

    private fun panel(region: Region) = region.xFromRightEdge()

    companion object {
        /** Distance between two panels of one spot (1440p). Measured on the spot with a mission quest above its free quest. */
        const val SLOT_PITCH = 376
        /** A spot's panel list shows this many panels at once; further ones need a scroll the route does not do. */
        const val SLOTS = 3
    }

    fun slot(region: Region, slot: Int) = region + io.github.lib_automata.Location(0, slot * SLOT_PITCH)

    /** The middle of a panel, clear of the info button and the reward icon on its right. */
    fun tap(slot: Int) = panel(Region(-800, 384 + slot * SLOT_PITCH, 1, 1)).center

    /** The "Free Quest" emblem on the left of the panel. Mission and one-off quests show other artwork here. */
    val freeLabel = panel(Region(-1330, 270, 330, 220))

    /** Digits of "AP 5" only, the "AP" letters are left of this region. Up to two digits. */
    val apDigits = panel(Region(-922, 410, 100, 76))

    /** Quest name band, up to the recommended-level column. */
    val name = panel(Region(-1004, 272, 694, 60))
}

/** Offsets from the matched Free Quest template's top-left, in 1440p script coordinates.
 * Measured: JP01 mark=(652,155), name=(778,136), AP=(819,205), tap=(880,192) at 720p.
 * JP10's lower panel and KR30 use the same relative geometry (KR is one pixel higher).
 */
data class QuestPanelAnchor(val mark: io.github.lib_automata.Location) {
    val ap = Region(mark.x + 334, mark.y + 100, 100, 76)
    val name = Region(mark.x + 252, mark.y - 38, 694, 60)
    val tap = mark + io.github.lib_automata.Location(456, 74)
    /** Name/progress area, well left of the reward/info buttons. Finger moves down 100px at 720p, never a tap. */
    val resetLane = Region(mark.x + 600, mark.y - 40, 100, 220)
    val resetStart = mark + io.github.lib_automata.Location(650, -30)
    val resetEnd = mark + io.github.lib_automata.Location(650, 170)
    fun fits(area: Region): Boolean = listOf(ap, name).all {
        it.x >= area.x && it.y >= area.y && it.right <= area.right && it.bottom <= area.bottom
    } && listOf(tap, resetStart, resetEnd).all {
        it.x >= area.x && it.x < area.right && it.y >= area.y && it.y < area.bottom
    }
}
