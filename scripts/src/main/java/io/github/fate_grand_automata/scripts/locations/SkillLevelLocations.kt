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
    /** Top-right title banner: tells the skill screen from the append screen. */
    val title = Region(80, 0, 1200, 150).xFromCenter()
    /** Top-left "re-select servant" button: only on screen once a servant is selected. */
    val reselect = Region(-940, 0, 340, 180).xFromCenter()
    /** Words "enhance" / "unlock" of the instruction band above the slots. */
    val bandWords = Region(-500, 196, 520, 104).xFromCenter()
    /** The band's text row; the text turns red when material, QP or coins are short. */
    val bandColor = Region(-490, 216, 740, 64).xFromCenter()
    /** Bottom-right button label: "unlock", or the JP red "cannot enhance any more". */
    val buttonLabel = Region(780, 1260, 500, 180).xFromCenter()

    private val skillSlotX = listOf(473, 759, 1047)
    private val appendSlotX = listOf(473, 650, 828, 1005, 1183)
    private fun slotX(mode: SkillLevelMode) = if (mode == SkillLevelMode.Skill) skillSlotX else appendSlotX
    /** Icon centre, tapped to select the slot (icon centre y is 261 at 720p). */
    fun slotClick(mode: SkillLevelMode, slot: Int) = Location((slotX(mode)[slot] - 640) * 2, 522).xFromCenter()
    /** The padlock sits in the icon centre. */
    fun slotLock(mode: SkillLevelMode, slot: Int) = Region((slotX(mode)[slot] - 40 - 640) * 2, 410, 160, 224).xFromCenter()

    /** Confirm dialog: the "confirmation" watermark sits lower in the skill dialog than in the append dialog. */
    val dialogWatermark = Region(-240, 1180, 500, 120).xFromCenter()
    fun dialogTitle(mode: SkillLevelMode) = when (mode) {
        SkillLevelMode.Skill -> Region(-80, 268, 320, 112)
        SkillLevelMode.Append -> Region(60, 216, 320, 112)
    }.xFromCenter()
    /** Digits only; the yellow "Lv." label is left of both. The next level can have two digits ("10"). */
    fun currentLevel(mode: SkillLevelMode) = Region(-208, levelTop(mode), 104, 120).xFromCenter()
    fun nextLevel(mode: SkillLevelMode) = Region(216, levelTop(mode), 164, 120).xFromCenter()
    private fun levelTop(mode: SkillLevelMode) = if (mode == SkillLevelMode.Skill) 624 else 524
    fun dialogCancel(mode: SkillLevelMode) = Location(-390, dialogButtonY(mode)).xFromCenter()
    fun dialogConfirm(mode: SkillLevelMode) = Location(400, dialogButtonY(mode)).xFromCenter()
    private fun dialogButtonY(mode: SkillLevelMode) = if (mode == SkillLevelMode.Skill) 1180 else 1132

    /** Tapped to skip the animation and the result: the title area, which does nothing on the skill screens. */
    val skipClick = Location(720, 72).xFromCenter()
    /** "Touch the screen" prompt of the result. */
    val result = Region(-520, 1280, 1120, 140).xFromCenter()
}
