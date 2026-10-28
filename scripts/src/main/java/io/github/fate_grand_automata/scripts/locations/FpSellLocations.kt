package io.github.fate_grand_automata.scripts.locations

import io.github.lib_automata.Location
import io.github.lib_automata.Region
import javax.inject.Inject

/**
 * 1440p coordinates, centered on the 16:9 game area. Window chrome is never included.
 * KR and JP use the same layout for the sell screen, the warning dialog and the summon menus.
 *
 * Wide screens (design 0012): the sell screen's own UI is measured on the owner's 21:9 JP capture. Buttons and the
 * title at the screen edges use [xFromLeftEdge] / [xFromRightEdge] with 16:9 values; the card grid stays centred
 * (it lies between the two edge columns), and dialogs stay centred. Owner 2560x1080 captures 17-22 (design 0012 D-4)
 * show the summon screen, the workshop, the warning and the convert/done dialogs: only the banner arrow sits at the left
 * edge (inset like the other left UI); the summon buttons, FP label, menus and dialogs stay centred or right-anchored
 * as they were.
 */
class FpSellLocations @Inject constructor(transforms: IScriptAreaTransforms) : IScriptAreaTransforms by transforms {
    /** First line of the warning dialog (centered text, so the word is searched across the whole band). */
    val fullDialogLine = Region(-920, 290, 1840, 90).xFromCenter()
    val fullSell = Region(-850, 880, 580, 160).xFromCenter()
    val servantTab = Region(30, 185, 340, 140).xFromLeftEdge()
    /** Tapped to bring the servant tab forward when the sell screen opens on the Craft Essence tab (D-17). */
    val servantTabClick = Location(160, 254).xFromLeftEdge()
    /** The grid is centred between the left button column and the scroll bar; on 21:9 it sits 5 px (720p) right of the centre. */
    private val gridShift = if (isWide) 10 else 0
    val grid = Region(-1130 + gridShift, 338, 1850, 1102).xFromCenter()
    /** Left column's last button (cards per row). The wide left column is packed closer together: 25 px higher on 21:9. */
    val sizeSwitch = (if (isWide) Location(100, 1268) else Location(100, 1320)).xFromLeftEdge()
    val confirm = Region(-460, 1240, 440, 180).xFromRightEdge()
    val convert = Region(100, 1170, 620, 170).xFromCenter()
    val doneClose = Region(-300, 1170, 600, 170).xFromCenter()
    val close = Location(160, 80).xFromLeftEdge()
    /** Banner arrow of the summon screen: left-edge UI with the shared wide inset (owner capture 17). */
    val leftArrowRegion = Region(0, 600, 150, 250).xFromLeftEdge()
    val summonButton = Region(250, 940, 500, 180).xFromCenter()
    val dailySummonButton = Region(-220, 940, 440, 180).xFromCenter()
    val menu = Region(-390, 1210, 380, 200).xFromRightEdge()
    /** Wide enough for the JP summon icon (around x 533-596 at 720p) and the KR one (615-674). */
    val summonMenu = Region(-260, 1180, 340, 180).xFromCenter()
    val bannerTitle = Region(-650, 600, 1300, 260).xFromCenter()
    val leftArrow = Location(50, 720).xFromLeftEdge()
    val swipeStart = Location(0, 1310).xFromCenter()
    /** D-23: the end depends on how many rows were fully evaluated, never on a fixed three-row jump. */
    fun swipeEnd(distance: Int) = swipeStart - Location(0, distance)
    // Fixed seven-column pitch (133.25 px at 720p); one row phase is fitted per page.
    fun columnX(column: Int) = Location(-1124 + gridShift + kotlin.math.round(column * 266.5).toInt(), 0).xFromCenter().x
    /** 720p frame positions for the existing 5/6/7 size cycle (JP fp-sell/42,43 and KR/18). */
    fun layoutColumns(columns: Int) = if (columns == 7) (0..6).map { (columnX(it) - grid.x) / 2 }
        else (0 until columns).map { 5 + kotlin.math.round(it * 937.5 / columns).toInt() }
    fun layoutPitch(columns: Int) = when (columns) { 5 -> 200; 6 -> 166; else -> rowPitch / 2 }
    fun layoutCardHeight(columns: Int) = when (columns) { 5 -> 180; 6 -> 150; else -> cardHeight / 2 }
    val rowPitch = 284
    val cardWidth = 232
    val cardHeight = 252
    fun card(column: Int, top: Int) = Region(columnX(column), top, cardWidth, cardHeight)
    fun presence(card: Region) = Region(card.x - 16, card.y + 24, card.width + 32, 184)
    val frameOffsets = -8..8
    val frameBands = listOf(72, 120, 160)
    /** Single-pixel columns across the outer edge, at independent bands clear of class/number badges and card art. */
    fun frameStrip(card: Region, offset: Int, band: Int) = Region(card.x + offset * 2, card.y + band, 2, 32)
    /** The padlock sits on the left edge of a locked tile. */
    fun lock(card: Region) = Region(card.x - 32, card.y, card.width + 32, card.height)
}
