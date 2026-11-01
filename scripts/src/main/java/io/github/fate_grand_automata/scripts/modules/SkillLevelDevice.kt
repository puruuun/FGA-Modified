package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.prefs.IAddedFeatureTuning
import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.locations.SkillLevelLocations

/** The real [SkillLevelPanel]: screen reads from [SkillLevelScreens] / [SkillLevelDigits] and taps from the api. */
class SkillLevelDevice(
    private val api: IFgoAutomataApi,
    private val mode: SkillLevelMode,
    private val loc: SkillLevelLocations,
    private val screens: SkillLevelScreens,
    private val digits: SkillLevelDigits,
    private val panelReader: SkillLevelPanelReader,
    private val addedTuning: IAddedFeatureTuning = IAddedFeatureTuning.Default
) : SkillLevelPanel, IFgoAutomataApi by api {
    /** The padlock only exists on append slots. */
    override fun isLocked(slot: Int) = mode == SkillLevelMode.Append && screens.slotLocked(mode, slot)

    override fun select(slot: Int) {
        loc.slotClick(mode, slot).click()
        addedTuning.skill.delay(SkillLevelTiming.SELECT_SETTLE).wait()
    }

    override fun unlockButtonVisible() = screens.unlockButtonVisible()
    override fun maxButtonVisible() = screens.maxButtonVisible()
    override var panelLevelEvidence: PanelLevelEvidence? = null
        private set

    override fun readPanelLevel(slot: Int): Int? {
        val evidence = panelReader.read(mode, slot)
        panelLevelEvidence = evidence
        return evidence.level.takeUnless { evidence.locked }
    }

    override fun pressEnhance() {
        // Last look before the one button that is also the unlock button.
        check(!screens.unlockButtonVisible()) { "refusing to press an unlock button" }
        locations.enhancementClick.click()
        addedTuning.skill.delay(SkillLevelTiming.AFTER_ENHANCE).wait()
    }

    override fun awaitDialog() = poll(SkillLevelTiming.DIALOG_POLLS) { screens.dialogVisible() }
    override fun dialogIsEnhance() = screens.dialogIsEnhance(mode)
    override fun readLevels() = digits.read(mode)

    override fun confirm() = loc.dialogConfirm(mode).click()

    override fun cancel(): Boolean {
        loc.dialogCancel(mode).click()
        addedTuning.skill.delay(SkillLevelTiming.AFTER_CANCEL).wait()
        return poll(SkillLevelTiming.CLOSE_POLLS) { !screens.dialogVisible() }
    }

    /**
     * Skip taps on the title area every 0.5 s. The screen counts as back when the skill title and the re-select
     * button show and the dialog is gone, after the animation was seen to leave (or a few taps without seeing it).
     * In skill mode, the label without the re-select button and without the dialog on two polls in a row (under the
     * same condition) means the game deselected the servant because all three skills reached 10.
     */
    override fun skipToScreen(): SkipResult {
        var left = false
        var deselectedPolls = 0
        for (tap in 1..SkillLevelTiming.SKIP_POLLS) {
            loc.skipClick.click()
            SkillLevelTiming.SKIP_INTERVAL.wait()
            val frame = useSameSnapIn {
                val label = screens.onLabel(mode)
                val selected = screens.servantSelected()
                val dialog = screens.dialogVisible()
                when {
                    label && selected && !dialog -> SkipFrame.Back
                    mode == SkillLevelMode.Skill && label && !selected && !dialog -> SkipFrame.Deselected
                    else -> SkipFrame.Other
                }
            }
            val counts = left || tap >= SkillLevelTiming.SKIP_MIN_POLLS
            if (frame != SkipFrame.Back) left = true
            if (frame == SkipFrame.Back && counts) {
                addedTuning.skill.delay(SkillLevelTiming.AFTER_SKIP).wait()
                return SkipResult.Back
            }
            deselectedPolls = if (frame == SkipFrame.Deselected && counts) deselectedPolls + 1 else 0
            if (deselectedPolls >= SkillLevelTiming.DESELECT_POLLS) return SkipResult.Deselected
        }
        return SkipResult.Timeout
    }

    private enum class SkipFrame { Back, Deselected, Other }

    private fun poll(polls: Int, signal: () -> Boolean): Boolean {
        repeat(polls) {
            if (useSameSnapIn(signal)) return true
            SkillLevelTiming.FIND_POLL.wait()
        }
        return false
    }
}
