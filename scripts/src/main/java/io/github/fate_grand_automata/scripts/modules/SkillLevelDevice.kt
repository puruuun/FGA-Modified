package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.locations.SkillLevelLocations

/** The real [SkillLevelPanel]: screen reads from [SkillLevelScreens] / [SkillLevelDigits] and taps from the api. */
class SkillLevelDevice(
    private val api: IFgoAutomataApi,
    private val mode: SkillLevelMode,
    private val loc: SkillLevelLocations,
    private val screens: SkillLevelScreens,
    private val digits: SkillLevelDigits
) : SkillLevelPanel, IFgoAutomataApi by api {
    /** The padlock only exists on append slots. */
    override fun isLocked(slot: Int) = mode == SkillLevelMode.Append && screens.slotLocked(mode, slot)

    override fun select(slot: Int) {
        loc.slotClick(mode, slot).click()
        SkillLevelTiming.SELECT_SETTLE.wait()
    }

    override fun band() = screens.band()
    override fun unlockButtonVisible() = screens.unlockButtonVisible()
    override fun maxButtonVisible() = screens.maxButtonVisible()

    override fun pressEnhance() {
        // Last look before the one button that is also the unlock button.
        check(!screens.unlockButtonVisible() && screens.band() != SkillLevelBand.Unlock) { "refusing to press an unlock button" }
        locations.enhancementClick.click()
        SkillLevelTiming.AFTER_ENHANCE.wait()
    }

    override fun awaitDialog() = poll(SkillLevelTiming.DIALOG_POLLS) { screens.dialogVisible() }
    override fun dialogIsEnhance() = screens.dialogIsEnhance(mode)
    override fun readLevels() = digits.read(mode)

    override fun confirm() = loc.dialogConfirm(mode).click()

    override fun cancel(): Boolean {
        loc.dialogCancel(mode).click()
        return poll(SkillLevelTiming.CLOSE_POLLS) { !screens.dialogVisible() }
    }

    /**
     * Skip taps on the title area every 0.5 s. The screen counts as back when the skill title and the re-select
     * button show and the dialog is gone, after the animation was seen to leave (or a few taps without seeing it).
     */
    override fun skipToScreen(): Boolean {
        var left = false
        for (tap in 1..SkillLevelTiming.SKIP_POLLS) {
            loc.skipClick.click()
            SkillLevelTiming.SKIP_INTERVAL.wait()
            val back = useSameSnapIn { screens.onScreen(mode) && !screens.dialogVisible() }
            if (!back) left = true
            else if (left || tap >= SkillLevelTiming.SKIP_MIN_POLLS) {
                SkillLevelTiming.AFTER_SKIP.wait()
                return true
            }
        }
        return false
    }

    private fun poll(polls: Int, signal: () -> Boolean): Boolean {
        repeat(polls) {
            if (useSameSnapIn(signal)) return true
            SkillLevelTiming.FIND_POLL.wait()
        }
        return false
    }
}
