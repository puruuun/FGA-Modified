package io.github.fate_grand_automata.scripts.modules

/** Skill screen has 3 slots, append screen 5. The loop is the same, only slots, coordinates and titles differ. */
enum class SkillLevelMode(val slots: Int) {
    Skill(3),
    Append(5)
}

/** What the instruction band above the slots says about the selected slot. */
enum class SkillLevelBand {
    /** "Touch the enhance button". */
    Enhance,
    /** "Touch the unlock button": the selected slot is locked. */
    Unlock,
    /** Red text: materials, QP or servant coins are short. */
    Shortage,
    Other
}

/** The two levels read from the confirm dialog (`Lv.current > Lv.next`). Null means not read. */
data class SkillLevelReading(val current: Int?, val next: Int?)

enum class SlotStop {
    /** Target 0. */
    Off,
    /** The slot is at the target level (read in the dialog, or reached by the last confirmed enhancement). */
    Reached,
    /** Level 10: nothing left to enhance. */
    Max,
    /** Material, QP or coin shortage. */
    Shortage,
    /** No dialog opened and no reason could be told. */
    CannotEnhance,
    /** Locked append slot, never unlocked. */
    Locked,
    /** The level in the dialog was not read, was 0, or was out of range. */
    Unreadable,
    /** The level did not go up by exactly one after a confirmed enhancement. */
    NoIncrease,
    /** The dialog was not an enhancement dialog. */
    UnexpectedDialog,
    /** The game did not return to the skill screen. */
    Stuck
}

/**
 * [startLevel] is the first level read in the dialog; [endLevel] the last known one (null when never read).
 */
data class SlotResult(val slot: Int, val target: Int, val startLevel: Int?, val endLevel: Int?, val stop: SlotStop)

/** Everything the loop needs from the game. The real one reads the screen and taps; tests fake it. */
interface SkillLevelPanel {
    /** Append only: the padlock on the slot icon. Always false for skills. */
    fun isLocked(slot: Int): Boolean
    /** Taps the slot icon and lets the screen settle. */
    fun select(slot: Int)
    fun band(): SkillLevelBand
    /** The bottom-right button reads "unlock". */
    fun unlockButtonVisible(): Boolean
    /** The bottom-right button reads "cannot enhance any more" (JP). */
    fun maxButtonVisible(): Boolean
    /** Taps the bottom-right button. Only ever called for a slot that passed every unlock guard. */
    fun pressEnhance()
    /** Waits up to 2 s for the confirm dialog. */
    fun awaitDialog(): Boolean
    /** The dialog is the enhancement dialog (title), not the unlock one. */
    fun dialogIsEnhance(): Boolean
    fun readLevels(): SkillLevelReading
    fun confirm()
    /** Taps cancel and waits for the dialog to close. Returns false when it stayed open. */
    fun cancel(): Boolean
    /** Taps through the animation and the result until the skill screen is back. Returns false after 20 s. */
    fun skipToScreen(): Boolean
}

/**
 * D-11 / D-14. Safety, in the order the checks run for every slot:
 * never tap unlock (padlock, band, button label, dialog level 0), never confirm an unread level, never confirm
 * when the level did not go up by one since the last confirmation, never confirm at or above the target.
 */
class SkillLevelFlow(private val panel: SkillLevelPanel, private val log: (String) -> Unit = {}) {
    private val done = mutableListOf<SlotResult>()
    /** Results so far, also valid after an abort. */
    val results: List<SlotResult> get() = done.toList()

    fun runAll(targets: List<Int>): List<SlotResult> {
        for ((slot, target) in targets.withIndex()) {
            val result = runSlot(slot, target)
            done += result
            log("slot=${slot + 1} target=$target start=${result.startLevel ?: "-"} end=${result.endLevel ?: "-"} stop=${result.stop}")
            if (result.stop == SlotStop.Stuck) break
        }
        return results
    }

    fun runSlot(slot: Int, target: Int): SlotResult {
        if (target <= 0) return SlotResult(slot, target, null, null, SlotStop.Off)
        var start: Int? = null
        var previous: Int? = null
        var known: Int? = null
        fun finish(stop: SlotStop) = SlotResult(slot, target, start, known, stop)
        fun reachedByLastConfirm() = known?.let { it >= target } == true
        // A level cannot be confirmed more than nine times; one more pass reads the final state.
        repeat(MAX_PASSES) {
            if (panel.isLocked(slot)) return finish(SlotStop.Locked)
            panel.select(slot)
            val band = panel.band()
            if (band == SkillLevelBand.Unlock || panel.unlockButtonVisible()) return finish(SlotStop.Locked)
            if (band == SkillLevelBand.Shortage) return finish(if (reachedByLastConfirm()) SlotStop.Reached else SlotStop.Shortage)
            panel.pressEnhance()
            if (!panel.awaitDialog()) {
                return finish(when {
                    reachedByLastConfirm() -> SlotStop.Reached
                    panel.maxButtonVisible() -> SlotStop.Max
                    else -> SlotStop.CannotEnhance
                })
            }
            if (!panel.dialogIsEnhance()) return finish(closeDialog(SlotStop.UnexpectedDialog))
            val reading = panel.readLevels()
            val current = reading.current
            if (current == null || current !in 1..9) return finish(closeDialog(SlotStop.Unreadable))
            // Both values must be unambiguous, and next must be exactly current + 1.
            if (reading.next != current + 1) return finish(closeDialog(SlotStop.Unreadable))
            if (start == null) start = current
            known = current
            if (previous != null && current != previous + 1) return finish(closeDialog(SlotStop.NoIncrease))
            if (current >= target) return finish(closeDialog(SlotStop.Reached))
            panel.confirm()
            previous = current
            known = current + 1
            if (!panel.skipToScreen()) return finish(SlotStop.Stuck)
        }
        return finish(SlotStop.CannotEnhance)
    }

    /** Cancels; when the dialog does not close the screen is stuck and the run stops. */
    private fun closeDialog(stop: SlotStop) = if (panel.cancel()) stop else SlotStop.Stuck

    companion object {
        const val MAX_PASSES = 11
    }
}
