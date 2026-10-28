package io.github.fate_grand_automata.scripts.locations

import io.github.lib_automata.Location
import io.github.lib_automata.Region
import javax.inject.Inject

/**
 * Screens of the weekly route (design 0009), measured on the owner's 1280x720 normalized captures: 1440p = 720p * 2,
 * measured from the left edge or the right edge of the game area (the main screen, chapter lists and map keep their
 * buttons at the screen edges).
 */
class WeeklyRouteLocations @Inject constructor(transforms: IScriptAreaTransforms) : IScriptAreaTransforms by transforms {
    /**
     * The top-left button: notice (main screen), close (chapter list, quest panel open) or terminal (map).
     * Header UI: on wide screens it sits one safe-area inset further in (owner 21:9 captures 01-03: +85 px at 720p).
     */
    val topLeftButton = Region(10, 30, 330, 140).xFromLeftEdge()

    /** D-16: repeat dialog's RIGHT button is a signal only; never tap it when exiting the weekly repeat. */
    val repeatDialogSignal = Region(180, 1100, 560, 130).xFromCenter()
    /** LEFT close button centre, measured on owner frame 32 (720p: 443,583). */
    val repeatDialogClose = Location(-394, 1166).xFromCenter()

    /** The scrolling button column of the main screen and the chapter lists: everything right of the portrait, below the title. */
    val listArea = Region(-1500, 200, 1500, 1140).xFromRight()
    val homeOtherBanner = listArea.copy(height = 360)

    /** Owner 0009 and 0012: rail y=95..589, core x=right-27..right-19 at 720p. */
    val listScrollbar = Region(-54, 190, 16, 988).xFromRightEdge()
    /** Banner column only: exclude the animated portrait, header, scrollbar and MENU. */
    val listContent = Region(-1280, 250, 1140, 930).xFromRightEdge()

    /** Where a list scroll starts and its finger travel: up 640 px moves the list down by 640 px. */
    val listScrollStart = Location(-700, 1180).xFromRight()
    val listScrollEnd = Location(-700, 540).xFromRight()

    /** Map-only central area for before/after feature displacement; excludes header, portrait, MENU and rail. */
    val mapMotion = Region(500, 360, scriptArea.width - 740, 600)

    /** The two-finger zoom-out is centred on the map and uses the middle part of the screen. */
    val pinchBounds = Region(400, 300, scriptArea.width - 800, 800)

    /** The panel column; used to look for Free Quest emblems of every panel slot. The panels move in with the right inset on wide screens. */
    val panelColumn = Region(-1330, 250, 330, 1100).xFromRightEdge()
}
