package io.github.fate_grand_automata.scripts.modules

import io.github.lib_automata.ScriptAbortException

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

enum class SkillLevelStep(val token: String) {
    Select("select"), Enhance("enhance"), Dialog("dialog"), Ocr("ocr"), Confirm("confirm"), Skip("skip")
}

enum class SlotStop {
    /** Target 0. */
    Off,
    /** The slot is at the target level (read on the panel/dialog, or reached by the last confirmation). */
    Reached,
    /** Level 10: nothing left to enhance. */
    Max,
    /** Material, QP or coin shortage. */
    Shortage,
    /** No dialog opened and no reason could be told. */
    CannotEnhance,
    /** Locked append slot, never unlocked. */
    Locked,
    /** Neither source supplied a usable level, or the dialog contained zero. */
    Unreadable,
    /** The panel and enhancement dialog read different current levels. */
    PanelMismatch,
    /** The level did not go up by exactly one after a confirmed enhancement. */
    NoIncrease,
    /** The dialog was not an enhancement dialog. */
    UnexpectedDialog,
    /** The game did not return to the skill screen. */
    Stuck,
    Aborted,
    Error
}

/**
 * [startLevel] is the first accepted level; [endLevel] the last known one (null when never read).
 */
data class SlotResult(val slot: Int, val target: Int, val startLevel: Int?, val endLevel: Int?, val stop: SlotStop,
    val step: SkillLevelStep = SkillLevelStep.Select, val check: SkillLevelCheckReport? = null)

/** Common progress exposed to the end-of-run dialog, including interrupted runs. */
interface SkillLevelRun {
    val results: List<SlotResult>
    val activeSlot: Int
    val activeStep: SkillLevelStep
}

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
    /** Confident selected-slot level, or null when the panel reader abstains. */
    fun readPanelLevel(slot: Int): Int?
    /** Diagnostics from the last panel read; fakes may omit them. */
    val panelLevelEvidence: PanelLevelEvidence? get() = null
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
 * never tap unlock (padlock, band, button label, dialog level 0), never confirm without a trusted level, never confirm
 * when the level did not go up by one since the last confirmation, never confirm at or above the target.
 */
class SkillLevelFlow(private val panel: SkillLevelPanel, private val log: (String) -> Unit = {}) : SkillLevelRun {
    private val done = mutableListOf<SlotResult>()
    override val results: List<SlotResult> get() = done.toList()
    override var activeSlot = -1
        private set
    override var activeStep = SkillLevelStep.Select
        private set
    private var partial: SlotResult? = null

    private fun line(fields: String) = log("slot=${activeSlot + 1} step=${activeStep.token} $fields")
    private fun readings(panel: String, dialog: Int? = null, source: String = "panel", evidence: PanelLevelEvidence? = null) {
        line("panel=$panel dialog=${dialog ?: "unread"} source=$source" +
            (evidence?.let { " score=${it.score} margin=${it.margin}" } ?: ""))
    }
    private fun summary(result: SlotResult) = log("summary slot=${result.slot + 1} step=${result.step.token} " +
        "target=${result.target} start=${result.startLevel ?: "-"} end=${result.endLevel ?: "-"} reason=${result.stop}")

    fun runAll(targets: List<Int>): List<SlotResult> {
        for ((slot, target) in targets.withIndex()) {
            val result = try {
                runSlot(slot, target)
            } catch (e: Exception) {
                val reason = if (e is ScriptAbortException) SlotStop.Aborted else SlotStop.Error
                val stopped = checkNotNull(partial).copy(stop = reason, step = activeStep)
                done += stopped
                log("stop slot=${slot + 1} step=${activeStep.token} reason=$reason error=${e::class.simpleName}:${e.message?.replace("\n", " ")}")
                summary(stopped)
                throw e
            }
            done += result
            summary(result)
            if (result.stop == SlotStop.Stuck) break
        }
        return results
    }

    fun runSlot(slot: Int, target: Int): SlotResult {
        activeSlot = slot
        activeStep = SkillLevelStep.Select
        var start: Int? = null
        var previous: Int? = null
        var known: Int? = null
        fun snapshot(stop: SlotStop) = SlotResult(slot, target, start, known, stop, activeStep)
        fun remember() { partial = snapshot(SlotStop.Error) }
        fun finish(stop: SlotStop): SlotResult {
            val result = snapshot(stop)
            log("stop slot=${slot + 1} step=${activeStep.token} reason=$stop")
            return result
        }
        fun reachedByLastConfirm() = known?.let { it >= target } == true
        remember()
        if (target <= 0) return finish(SlotStop.Off)
        repeat(MAX_PASSES) {
            activeStep = SkillLevelStep.Select
            if (panel.isLocked(slot)) {
                readings("locked")
                return finish(SlotStop.Locked)
            }
            panel.select(slot)
            line("action=select")
            activeStep = SkillLevelStep.Enhance
            val band = panel.band()
            line("band=$band")
            if (band == SkillLevelBand.Unlock || panel.unlockButtonVisible()) {
                readings("locked")
                return finish(SlotStop.Locked)
            }
            if (band == SkillLevelBand.Shortage) {
                readings("abstain:shortage")
                return finish(if (reachedByLastConfirm()) SlotStop.Reached else SlotStop.Shortage)
            }
            val panelLevel = panel.readPanelLevel(slot)?.takeIf { it in 1..10 }
            val evidence = panel.panelLevelEvidence
            val panelText = panelLevel?.toString() ?: if (evidence?.locked == true) "locked" else "abstain:${evidence?.reason ?: "unread"}"
            if (evidence?.locked == true) {
                readings("locked", evidence = evidence)
                return finish(SlotStop.Locked)
            }
            if (panelLevel != null) {
                val stop = when {
                    panelLevel == 10 -> SlotStop.Max
                    panelLevel >= target -> SlotStop.Reached
                    previous != null && panelLevel != previous + 1 -> SlotStop.NoIncrease
                    else -> null
                }
                if (stop != null) {
                    readings(panelText, evidence = evidence)
                    if (start == null) start = panelLevel
                    known = panelLevel
                    return finish(stop)
                }
            }
            line("action=enhance-tap")
            panel.pressEnhance()
            activeStep = SkillLevelStep.Dialog
            val seen = panel.awaitDialog()
            line("seen=$seen")
            if (!seen) {
                readings(panelText, evidence = evidence)
                return finish(when {
                    reachedByLastConfirm() -> SlotStop.Reached
                    panel.maxButtonVisible() -> SlotStop.Max
                    else -> SlotStop.CannotEnhance
                })
            }
            if (!panel.dialogIsEnhance()) {
                readings(panelText, evidence = evidence)
                return finish(closeDialog(SlotStop.UnexpectedDialog))
            }
            activeStep = SkillLevelStep.Ocr
            val reading = panel.readLevels()
            line("current=${reading.current ?: "unread"} next=${reading.next ?: "unread"}")
            val dialogLevel = reading.current?.takeIf { it in 1..9 && reading.next == it + 1 }
            val source = when {
                panelLevel != null && dialogLevel != null -> "both"
                dialogLevel != null -> "dialog"
                else -> "panel"
            }
            readings(panelText, dialogLevel, source, evidence)
            // A visible zero remains an unlock guard even with a confident panel reading.
            if (reading.current == 0 || reading.next == 0) return finish(closeDialog(SlotStop.Unreadable))
            if (panelLevel != null && dialogLevel != null && panelLevel != dialogLevel)
                return finish(closeDialog(SlotStop.PanelMismatch))
            val current = dialogLevel ?: panelLevel?.takeIf { it in 1..9 }
                ?: return finish(closeDialog(SlotStop.Unreadable))
            if (start == null) start = current
            known = current
            remember()
            if (previous != null && current != previous + 1) return finish(closeDialog(SlotStop.NoIncrease))
            if (current >= target) return finish(closeDialog(SlotStop.Reached))
            activeStep = SkillLevelStep.Confirm
            line("action=confirm")
            panel.confirm()
            previous = current
            known = current + 1
            remember()
            activeStep = SkillLevelStep.Skip
            val back = panel.skipToScreen()
            line("return=$back")
            if (!back) return finish(SlotStop.Stuck)
        }
        return finish(SlotStop.CannotEnhance)
    }

    /** Preserve the cause step after a successful cancellation; a failed cancellation is a confirm-step failure. */
    private fun closeDialog(stop: SlotStop): SlotStop {
        val cause = activeStep
        activeStep = SkillLevelStep.Confirm
        line("action=cancel")
        val closed = panel.cancel()
        line("cancelClosed=$closed")
        if (closed) activeStep = cause
        return if (closed) stop else SlotStop.Stuck
    }

    companion object {
        const val MAX_PASSES = 11
    }
}
