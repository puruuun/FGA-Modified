package io.github.fate_grand_automata.scripts.locations

import io.github.fate_grand_automata.scripts.modules.SkillLevelMode
import io.github.lib_automata.Location
import io.github.lib_automata.Region
import javax.inject.Inject

/**
 * 1440p coordinates, centered on the 16:9 game area, measured on the owner's 1280x720 normalized captures
 * (x1440 = (x720 - 640) * 2, y1440 = y720 * 2). KR and JP share the layout. Window chrome is never included.
 * The enhance / unlock button is [Locations.enhancementClick] (the same bottom-right button as the servant screens).
 */
class SkillLevelLocations @Inject constructor(transforms: IScriptAreaTransforms) : IScriptAreaTransforms by transforms {
    /**
     * Wide screens (design 0012, owner 21:9 JP skill screen): the title and the re-select button are header UI at the
     * right / left edge, the servant card and the panel stay centred but sit higher (band 14 px, slots 22 px at 720p),
     * and the bottom-right button moves in and up like [Locations.enhancementClick]. Owner 2560x1080 captures 15, 16 and 32-35
     * show the dialogs and results (centred, same heights as 16:9) and the append screen, whose five slot icons rise by the
     * same 22 px as the skill ones.
     */
    private val bandRise = if (isWide) 28 else 0
    // Append measured independently on capture 33: lock top 216 vs 238 at 720p, the same rise as skill.
    private val slotRise = if (isWide) 44 else 0

    /** Top-right title banner: tells the skill screen from the append screen. */
    val title = Region(-1200, 0, 1200, 150).xFromRightEdge()
    /** Top-left "re-select servant" button: only on screen once a servant is selected. */
    val reselect = Region(340, 0, 340, 180).xFromLeftEdge()
    /** Words "enhance" / "unlock" of the instruction band above the slots. */
    val bandWords = Region(-500, 196 - bandRise, 520, 104).xFromCenter()
    /** The band's text row; the text turns red when material, QP or coins are short. */
    val bandColor = Region(-490, 216 - bandRise, 740, 64).xFromCenter()
    /** Bottom-right button label: "unlock", or the JP red "cannot enhance any more". */
    val buttonLabel = (if (isWide) Region(-500 - WIDE_ACTION_INSET, 1200, 500, 180) else Region(-500, 1260, 500, 180)).xFromRight()

    private val skillSlotX = listOf(473, 759, 1047)
    private val appendSlotX = listOf(473, 650, 828, 1005, 1183)
    private fun slotX(mode: SkillLevelMode) = if (mode == SkillLevelMode.Skill) skillSlotX else appendSlotX
    /** Icon centre, tapped to select the slot (icon centre y is 261 at 720p). */
    fun slotClick(mode: SkillLevelMode, slot: Int) = Location((slotX(mode)[slot] - 640) * 2, 522 - slotRise).xFromCenter()
    /** The padlock sits in the icon centre. */
    fun slotLock(mode: SkillLevelMode, slot: Int) = Region((slotX(mode)[slot] - 40 - 640) * 2, 410 - slotRise, 160, 224).xFromCenter()

    /** Panel level row, before Enhance. Same icon coordinates on real/exchange 0021 captures.
     * Skill includes the Lv. and /10 text; Append isolates the current outlined glyph column.
     * 16:10 uses the same 16:9 game-area coordinates after letterbox removal. */
    fun panelLevel(mode: SkillLevelMode, slot: Int): Region {
        require(slot in slotX(mode).indices)
        val x = slotX(mode)[slot] - 640
        return (if (mode == SkillLevelMode.Skill) Region((x + 65) * 2, 584 - slotRise, 290, 68)
            else Region((x - 5) * 2, 590 - slotRise, 66, 42)).xFromCenter()
    }

    /** Confirm dialog: the "confirmation" watermark sits lower in the skill dialog than in the append dialog. */
    val dialogWatermark = Region(-240, 1180, 500, 120).xFromCenter()
    fun dialogTitle(mode: SkillLevelMode) = when (mode) {
        SkillLevelMode.Skill -> Region(-80, 268, 320, 112)
        SkillLevelMode.Append -> Region(60, 216, 320, 112)
    }.xFromCenter()
    /** Digits only; the yellow "Lv." label is left of both. The next level can have two digits ("10"). */
    // The tablet append 2 is not clipped: the old crop includes the Lv. period and background icons,
    // producing a confident 4 at threshold 0.6. 0019 shifts the same-width digit column left 1 px
    // at 720p for even outline padding on tablet and wide KR/JP, while excluding the Lv. period.
    fun currentLevel(mode: SkillLevelMode) = (if (mode == SkillLevelMode.Append)
        Region(-206, levelTop(mode), 64, 120) else Region(-208, levelTop(mode), 104, 120)).xFromCenter()
    fun nextLevel(mode: SkillLevelMode) = (when (mode) {
        // Tablet append 9 -> 10: glyph top 522, right 371; retain clear padding on both sides.
        SkillLevelMode.Append -> Region(216, 508, 192, 136)
        SkillLevelMode.Skill -> Region(216, levelTop(mode), 164, 120)
    }).xFromCenter()
    private fun levelTop(mode: SkillLevelMode) = if (mode == SkillLevelMode.Skill) 624 else 524
    fun dialogCancel(mode: SkillLevelMode) = Location(-390, dialogButtonY(mode)).xFromCenter()
    fun dialogConfirm(mode: SkillLevelMode) = Location(400, dialogButtonY(mode)).xFromCenter()
    private fun dialogButtonY(mode: SkillLevelMode) = if (mode == SkillLevelMode.Skill) 1180 else 1132

    /** Tapped to skip the animation and the result: the title area, which does nothing on the skill screens. */
    val skipClick = Location(720, 72).xFromCenter()
    /** "Touch the screen" prompt of the result. */
    val result = Region(-520, 1280, 1120, 140).xFromCenter()
}
