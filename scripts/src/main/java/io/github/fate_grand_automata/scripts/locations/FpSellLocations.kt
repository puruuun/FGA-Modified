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
    val cancel = Region(-680, 1170, 580, 170).xFromCenter()
    val convert = Region(100, 1170, 620, 170).xFromCenter()
    val done = Region(-380, 400, 800, 160).xFromCenter()
    val doneClose = Region(-300, 1170, 600, 170).xFromCenter()
    val close = Location(-1120, 80).xFromCenter()
    val screenClose = Region(-1280, 0, 380, 180).xFromCenter()
    val leftArrowRegion = Region(-1280, 600, 150, 250).xFromCenter()
    val summonButton = Region(250, 940, 500, 180).xFromCenter()
    val dailySummonButton = Region(-220, 940, 440, 180).xFromCenter()
    val shop = Region(550, 0, 710, 160).xFromCenter()
    val menu = Region(890, 1210, 380, 200).xFromCenter()
    /** Wide enough for the JP summon icon (around x 533-596 at 720p) and the KR one (615-674). */
    val summonMenu = Region(-260, 1180, 340, 180).xFromCenter()
    val bannerTitle = Region(-650, 600, 1300, 260).xFromCenter()
    val leftArrow = Location(-1230, 720).xFromCenter()
    val swipeStart = Location(0, 1310).xFromCenter()
    /** D-23: the end depends on how many rows were fully evaluated, never on a fixed three-row jump. */
    fun swipeEnd(distance: Int) = swipeStart - Location(0, distance)
    // Fixed seven-column pitch (133.25 px at 720p); one row phase is fitted per page.
    fun columnX(column: Int) = Location(-1124 + kotlin.math.round(column * 266.5).toInt(), 0).xFromCenter().x
    val rowPitch = 284
    val cardWidth = 232
    val cardHeight = 252
    fun card(column: Int, top: Int) = Region(columnX(column), top, cardWidth, cardHeight)
    fun presence(card: Region) = Region(card.x - 16, card.y + 24, card.width + 32, 184)
    val frameOffsets = -8..8
    val frameBands = listOf(72, 120, 160)
    /** Single-pixel columns across the outer edge, at independent bands clear of class/number badges and card art. */
    fun frameStrip(card: Region, offset: Int, band: Int) = Region(card.x + offset * 2, card.y + band, 2, 32)
    /** The padlock sits on the left edge of a locked tile, and the overlay words cover the tile. */
    fun temporary(card: Region) = Region(card.x, card.y - 32, card.width, card.height + 32)
    fun lock(card: Region) = Region(card.x - 32, card.y, card.width + 32, card.height)
}
