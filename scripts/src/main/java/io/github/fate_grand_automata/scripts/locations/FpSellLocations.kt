package io.github.fate_grand_automata.scripts.locations

import io.github.lib_automata.Location
import io.github.lib_automata.Region
import javax.inject.Inject

/**
 * 1440p coordinates, centered on the 16:9 game area. Window chrome is never included.
 * KR and JP use the same layout for the sell screen, the warning dialog and the summon menus.
 */
class FpSellLocations @Inject constructor(transforms: IScriptAreaTransforms) : IScriptAreaTransforms by transforms {
    /** First line of the warning dialog (centered text, so the word is searched across the whole band). */
    val fullDialogLine = Region(-920, 290, 1840, 90).xFromCenter()
    val fullSell = Region(-850, 880, 580, 160).xFromCenter()
    val heading = Region(760, 0, 500, 160).xFromCenter()
    val servantTab = Region(-1250, 185, 340, 140).xFromCenter()
    /** Tapped to bring the servant tab forward when the sell screen opens on the Craft Essence tab (D-17). */
    val servantTabClick = Location(-1120, 254).xFromCenter()
    val grid = Region(-1130, 338, 1850, 1102).xFromCenter()
    val sizeSwitch = Location(-1180, 1320).xFromCenter()
    val confirm = Region(820, 1240, 440, 180).xFromCenter()
    val convertHeading = Region(-180, 40, 400, 120).xFromCenter()
    val convert = Region(100, 1170, 620, 170).xFromCenter()
    val done = Region(-380, 400, 800, 160).xFromCenter()
    val doneClose = Region(-300, 1170, 600, 170).xFromCenter()
    val close = Location(-1120, 80).xFromCenter()
    val shop = Region(550, 0, 710, 160).xFromCenter()
    val menu = Region(890, 1210, 380, 200).xFromCenter()
    /** Wide enough for the JP summon icon (around x 533-596 at 720p) and the KR one (615-674). */
    val summonMenu = Region(-260, 1180, 340, 180).xFromCenter()
    /** Two dot rows at most (JP): one row near 720p y 686, the other near 703. */
    val dots = Region(-530, 1360, 1070, 64).xFromCenter()
    val bannerTitle = Region(-650, 600, 1300, 260).xFromCenter()
    val leftArrow = Location(-1230, 720).xFromCenter()
    val rightArrow = Location(1230, 720).xFromCenter()
    val swipeStart = Location(0, 1310).xFromCenter()
    /** D-23: the end depends on how many rows were fully evaluated, never on a fixed three-row jump. */
    fun swipeEnd(distance: Int) = swipeStart - Location(0, distance)
    // Card top-left X for small icons. Width 232, pitch 267; labels establish Y after scrolling.
    fun columnX(column: Int) = Location(-1124 + column * 267, 0).xFromCenter().x
    fun labelColumn(column: Int) = Region(columnX(column) + 65, 338, 110, 1102)
    fun newColumn(column: Int) = Region(columnX(column) + 48, 342, 140, 1086)
    fun card(column: Int, labelY: Int) = Region(columnX(column), labelY - 230, 232, 252)
    /** Label text is centered in its card: avoid accumulating a rounded column pitch (D-25). */
    fun card(label: Region) = Region(label.center.x - 116, label.y - 230, 232, 252)
    fun labelInsideGrid(label: Region) = label.bottom <= grid.bottom
    fun label(card: Region) = Region(card.x + 65, card.bottom - 24, 110, 24)
    fun stars(card: Region) = Region(card.x + 94, card.bottom - 68, 128, 40)
    /**
     * Slot [slot] (0 = rightmost) of the rarity star row: an 18x18 px (720p) window around a 12 px star. Stars sit 11 px
     * apart and end 4 px from the strip's right edge, so the window absorbs about 3 px of column drift.
     */
    fun starSlot(card: Region, slot: Int) = Region(card.x + 186 - 22 * slot, card.bottom - 66, 36, 36)
    fun newLabel(card: Region) = Region(card.x + 48, card.y - 28, 140, 46)
    val frameOffsets = -8..8
    val frameBands = listOf(72, 160)
    /** Single-pixel columns across the outer edge, at two bands clear of class/number badges and card art. */
    fun frameStrip(card: Region, offset: Int, band: Int) = Region(card.x + offset * 2, card.y + band, 2, 32)
    /** The padlock sits on the left edge of a locked tile, and the overlay words cover the tile. */
    fun lock(card: Region) = Region(card.x - 32, card.y, card.width + 32, card.height)
}
