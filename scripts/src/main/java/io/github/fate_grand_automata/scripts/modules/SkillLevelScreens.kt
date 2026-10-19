package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.locations.SkillLevelLocations
import io.github.lib_automata.MeanColor
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import javax.inject.Inject

/**
 * Cheap image checks of the skill (3 slot) and append (5 slot) enhancement screens, KR and JP.
 * A template missing on the current server only narrows what can be seen: it never counts as a match.
 */
class SkillLevelScreens @Inject constructor(api: IFgoAutomataApi, val loc: SkillLevelLocations) : IFgoAutomataApi by api {
    companion object {
        /** Positives 0.99-1.0, negatives at most 0.45 on the owner screens. */
        const val TITLE_SIMILARITY = 0.85
        const val BAND_SIMILARITY = 0.8
        /** The grey (disabled) unlock button still scores 0.72 (JP) and 0.85 (KR), everything else stays below 0.64. */
        const val UNLOCK_BUTTON_SIMILARITY = 0.7
        const val MAX_BUTTON_SIMILARITY = 0.8
        /** The watermark scores 0.89 on the KR dialogs and 0.99-1.0 on JP; the best negative is 0.41. */
        const val DIALOG_SIMILARITY = 0.8
        const val LOCK_SIMILARITY = 0.85
        const val DIALOG_TITLE_SIMILARITY = 0.8
        /** The result prompt sits on character art; 0.75-0.77 on the append results, 0.3 at most elsewhere. */
        const val RESULT_SIMILARITY = 0.7

        /** The red band text turns the otherwise teal band red (R 46-54 above G 33-38, normal bands have R 14-22 under G 64-74). */
        fun isShortageColor(color: MeanColor?): Boolean = color != null && color.red >= 35.0 && color.red > color.green
    }

    private fun template(image: Images): Pattern? = runCatching { images[image] }.getOrNull()

    private fun seen(region: Region, image: Images, similarity: Double): Boolean =
        template(image)?.let { region.exists(it, similarity = similarity) } == true

    private fun titleImage(mode: SkillLevelMode) =
        if (mode == SkillLevelMode.Skill) Images.SkillLevelTitle else Images.AppendLevelTitle

    fun onTitle(mode: SkillLevelMode): Boolean = useSameSnapIn { seen(loc.title, titleImage(mode), TITLE_SIMILARITY) }

    /** The append title is checked first: on KR the skill title is also part of the append one. */
    fun detectMode(): SkillLevelMode? = useSameSnapIn {
        when {
            onTitle(SkillLevelMode.Append) -> SkillLevelMode.Append
            onTitle(SkillLevelMode.Skill) -> SkillLevelMode.Skill
            else -> null
        }
    }

    /** D-10: the re-select button only exists once a servant is selected (before that only "close" shows). */
    fun servantSelected(): Boolean = useSameSnapIn { seen(loc.reselect, Images.SkillLevelReselect, TITLE_SIMILARITY) }

    fun onScreen(mode: SkillLevelMode): Boolean = useSameSnapIn { onTitle(mode) && servantSelected() }

    fun dialogVisible(): Boolean = useSameSnapIn { seen(loc.dialogWatermark, Images.SkillLevelConfirmation, DIALOG_SIMILARITY) }

    /** The dialog is the enhancement one, not the unlock one: its title ends with the word "enhance" / "level up". */
    fun dialogIsEnhance(mode: SkillLevelMode): Boolean = useSameSnapIn {
        val image = if (mode == SkillLevelMode.Skill) Images.SkillLevelDialogTitle else Images.AppendLevelDialogTitle
        seen(loc.dialogTitle(mode), image, DIALOG_TITLE_SIMILARITY)
    }

    fun band(): SkillLevelBand {
        val color = useColor { useSameSnapIn { loc.bandColor.getPattern("skill_level_band").use { it.meanColor() } } }
        if (isShortageColor(color)) return SkillLevelBand.Shortage
        return useSameSnapIn {
            when {
                // Unlock wins when both words somehow match: it is the one that must never be pressed.
                seen(loc.bandWords, Images.SkillLevelBandUnlock, BAND_SIMILARITY) -> SkillLevelBand.Unlock
                seen(loc.bandWords, Images.SkillLevelBandEnhance, BAND_SIMILARITY) -> SkillLevelBand.Enhance
                else -> SkillLevelBand.Other
            }
        }
    }

    fun unlockButtonVisible(): Boolean = useSameSnapIn { seen(loc.buttonLabel, Images.SkillLevelUnlockButton, UNLOCK_BUTTON_SIMILARITY) }

    fun maxButtonVisible(): Boolean = useSameSnapIn { seen(loc.buttonLabel, Images.SkillLevelMaxButton, MAX_BUTTON_SIMILARITY) }

    fun slotLocked(mode: SkillLevelMode, slot: Int): Boolean =
        useSameSnapIn { seen(loc.slotLock(mode, slot), Images.SkillLevelLock, LOCK_SIMILARITY) }

    fun onResult(): Boolean = useSameSnapIn { seen(loc.result, Images.SkillLevelResult, RESULT_SIMILARITY) }
}
