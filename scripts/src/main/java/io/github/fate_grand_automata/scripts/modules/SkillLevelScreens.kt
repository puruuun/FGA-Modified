package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.locations.SkillLevelLocations
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import javax.inject.Inject

/**
 * Cheap image checks of the skill (3 slot) and append (5 slot) enhancement screens, KR and JP.
 * A template missing on the current server only narrows what can be seen: it never counts as a match.
 */
class SkillLevelScreens @Inject constructor(api: IFgoAutomataApi, val loc: SkillLevelLocations) : IFgoAutomataApi by api {
    companion object {
        /**
         * Centre-left material-list label with the pink chevron icon, 720p TM_CCOEFF_NORMED on the owner's 20 captures
         * (KR and JP, 16:9 and 21:9): the right label on its own screen scores 0.998-1.000 (also with no servant selected,
         * a Lv.10 slot and a locked append slot). The other label scores at most 0.32 (append on skill), 0.68 (JP skill on
         * append) and 0.46 (KR skill on append): the append text contains the skill text, the icon keeps the score low.
         */
        const val LABEL_SIMILARITY = 0.90
        /** Re-select button; 0.99-1.0 on the owner screens, at most 0.45 elsewhere. */
        const val RESELECT_SIMILARITY = 0.85
        /** Floor of the diagnostic best-score search: anything below shows as "<0.30". */
        const val SCORE_FLOOR = 0.3
        /** The grey (disabled) unlock button still scores 0.72 (JP) and 0.85 (KR), everything else stays below 0.64. */
        const val UNLOCK_BUTTON_SIMILARITY = 0.7
        const val MAX_BUTTON_SIMILARITY = 0.8
        /** The watermark scores 0.89 on the KR dialogs and 0.99-1.0 on JP; the best negative is 0.41. */
        const val DIALOG_SIMILARITY = 0.8
        const val LOCK_SIMILARITY = 0.85
        const val DIALOG_TITLE_SIMILARITY = 0.8
        /** The result prompt sits on character art; 0.75-0.77 on the append results, 0.3 at most elsewhere. */
        const val RESULT_SIMILARITY = 0.7

    }

    private fun template(image: Images): Pattern? = runCatching { images[image] }.getOrNull()

    private fun seen(region: Region, image: Images, similarity: Double): Boolean =
        template(image)?.let { region.exists(it, similarity = similarity) } == true

    private fun labelImage(mode: SkillLevelMode) =
        if (mode == SkillLevelMode.Skill) Images.SkillLevelLabel else Images.AppendLevelLabel

    /** The screen of [mode] is showing: its centre-left label (see [LABEL_SIMILARITY]). */
    fun onLabel(mode: SkillLevelMode): Boolean = useSameSnapIn { seen(loc.label, labelImage(mode), LABEL_SIMILARITY) }

    /** The append label is checked first: its text contains the skill one, so the skill label can reach 0.68 on append screens. */
    fun detectMode(): SkillLevelMode? = useSameSnapIn {
        when {
            onLabel(SkillLevelMode.Append) -> SkillLevelMode.Append
            onLabel(SkillLevelMode.Skill) -> SkillLevelMode.Skill
            else -> null
        }
    }

    private fun bestScore(region: Region, image: Images): Double? =
        template(image)?.let { pattern -> region.findAll(pattern, SCORE_FLOOR).maxOfOrNull { it.score } }

    /** Best append label, skill label and re-select scores on one frame (null = below [SCORE_FLOOR] or no template): for logs only. */
    fun bestScores(): SkillLevelScores = useSameSnapIn {
        SkillLevelScores(
            append = bestScore(loc.label, Images.AppendLevelLabel),
            skill = bestScore(loc.label, Images.SkillLevelLabel),
            reselect = bestScore(loc.reselect, Images.SkillLevelReselect)
        )
    }

    /** D-10: the re-select button only exists once a servant is selected (before that only "close" shows). */
    fun servantSelected(): Boolean = useSameSnapIn { seen(loc.reselect, Images.SkillLevelReselect, RESELECT_SIMILARITY) }

    fun onScreen(mode: SkillLevelMode): Boolean = useSameSnapIn { onLabel(mode) && servantSelected() }

    fun dialogVisible(): Boolean = useSameSnapIn { seen(loc.dialogWatermark, Images.SkillLevelConfirmation, DIALOG_SIMILARITY) }

    /** The dialog is the enhancement one, not the unlock one: its title ends with the word "enhance" / "level up". */
    fun dialogIsEnhance(mode: SkillLevelMode): Boolean = useSameSnapIn {
        val image = if (mode == SkillLevelMode.Skill) Images.SkillLevelDialogTitle else Images.AppendLevelDialogTitle
        seen(loc.dialogTitle(mode), image, DIALOG_TITLE_SIMILARITY)
    }

    fun unlockButtonVisible(): Boolean = useSameSnapIn { seen(loc.buttonLabel, Images.SkillLevelUnlockButton, UNLOCK_BUTTON_SIMILARITY) }

    fun maxButtonVisible(): Boolean = useSameSnapIn { seen(loc.buttonLabel, Images.SkillLevelMaxButton, MAX_BUTTON_SIMILARITY) }

    fun slotLocked(mode: SkillLevelMode, slot: Int): Boolean =
        useSameSnapIn { seen(loc.slotLock(mode, slot), Images.SkillLevelLock, LOCK_SIMILARITY) }

    fun onResult(): Boolean = useSameSnapIn { seen(loc.result, Images.SkillLevelResult, RESULT_SIMILARITY) }
}

/** Best template scores of one frame, see [SkillLevelScreens.bestScores]. */
data class SkillLevelScores(val append: Double?, val skill: Double?, val reselect: Double?) {
    /** One log line, for example `scores label-append=1.000 label-skill=0.680 reselect=<0.30 detected=Append`. */
    fun describe(detected: SkillLevelMode?): String =
        "scores label-append=${fmt(append)} label-skill=${fmt(skill)} reselect=${fmt(reselect)} detected=${detected ?: "none"}"

    private fun fmt(score: Double?) = score?.let { String.format(java.util.Locale.ROOT, "%.3f", it) }
        ?: ("<" + String.format(java.util.Locale.ROOT, "%.2f", SkillLevelScreens.SCORE_FLOOR))
}
