package io.github.fate_grand_automata.scripts.modules

import io.github.lib_automata.ScriptAbortException

/** Diagnostic tokens are shared verbatim by the end dialog and the SkillLevel log. */
enum class SkillLevelCheckVerdict(val token: String) {
    Match("match"), Mismatch("mismatch"), PanelOnly("panel-only"), DialogOnly("dialog-only"),
    BothUnread("both-unread"), NoDialog("no-dialog"), Locked("locked")
}

data class SkillLevelCheckReport(
    val panel: String, val dialog: String, val verdict: SkillLevelCheckVerdict,
    val reason: String, val evidence: PanelLevelEvidence? = null
) {
    fun line(slot: Int): String = "slot=${slot + 1} panel=$panel dialog=$dialog verdict=${verdict.token} reason=$reason" +
        (evidence?.let { " score=${it.score} margin=${it.margin}" } ?: "")
}

/** A second safety boundary: even an accidental call from the check flow cannot reach the device confirm tap. */
class SkillLevelCheckPanel(panel: SkillLevelPanel) : SkillLevelPanel by panel {
    override fun confirm(): Nothing = error("confirm is forbidden in level reading check mode")
    override fun skipToScreen(): Nothing = error("enhancement results are unreachable in level reading check mode")
}

/** One read per slot, independent of enhancement targets. Every opened dialog is cancelled in finally. */
class SkillLevelCheckFlow(panel: SkillLevelPanel, private val log: (String) -> Unit = {}) : SkillLevelRun {
    private val panel = SkillLevelCheckPanel(panel)
    private val done = mutableListOf<SlotResult>()
    override val results: List<SlotResult> get() = done.toList()
    override var activeSlot = -1
        private set
    override var activeStep = SkillLevelStep.Select
        private set
    private var report = SkillLevelCheckReport("abstain:not-read", "-", SkillLevelCheckVerdict.NoDialog, "not-read")
    private var panelLevel: Int? = null
    private var stop = SlotStop.CannotEnhance

    fun runAll(mode: SkillLevelMode): List<SlotResult> {
        for (slot in 0 until mode.slots) {
            activeSlot = slot
            activeStep = SkillLevelStep.Select
            panelLevel = null
            stop = SlotStop.CannotEnhance
            report = SkillLevelCheckReport("abstain:not-read", "-", SkillLevelCheckVerdict.NoDialog, "not-read")
            try {
                checkSlot(slot)
            } catch (e: Exception) {
                if (stop != SlotStop.Stuck) stop = if (e is ScriptAbortException) SlotStop.Aborted else SlotStop.Error
                report = report.copy(reason = if (stop == SlotStop.Stuck) "cancel-failed" else "${stop.name.lowercase()}:${activeStep.token}")
                finish(slot)
                throw e
            }
            finish(slot)
            if (stop == SlotStop.Stuck) break
        }
        return results
    }

    private fun finish(slot: Int) {
        done += SlotResult(slot, 0, panelLevel, panelLevel, stop, activeStep, report)
        log("check ${report.line(slot)}")
    }

    private fun locked() {
        report = report.copy(panel = "locked", dialog = "-", verdict = SkillLevelCheckVerdict.Locked, reason = "locked")
        stop = SlotStop.Locked
    }

    private fun checkSlot(slot: Int) {
        if (panel.isLocked(slot)) return locked()
        panel.select(slot)
        panelLevel = panel.readPanelLevel(slot)?.takeIf { it in 1..10 }
        val evidence = panel.panelLevelEvidence
        report = report.copy(panel = panelLevel?.toString() ?: "abstain:${evidence?.reason ?: "unread"}", evidence = evidence)
        activeStep = SkillLevelStep.Enhance
        if (panel.unlockButtonVisible() || evidence?.locked == true) return locked()
        // The panel itself is under test: even a reading of 10 must not suppress a dialog comparison.
        panel.pressEnhance() // Retains the device's last-moment unlock guard.
        activeStep = SkillLevelStep.Dialog
        if (!panel.awaitDialog()) {
            val reason = when {
                panel.maxButtonVisible() -> "max"
                else -> "no-response"
            }
            report = report.copy(reason = reason)
            stop = if (reason == "max") SlotStop.Max else SlotStop.CannotEnhance
            return
        }
        report = report.copy(dialog = "unread", verdict = if (panelLevel != null) SkillLevelCheckVerdict.PanelOnly else SkillLevelCheckVerdict.BothUnread)
        try {
            if (!panel.dialogIsEnhance()) {
                report = report.copy(reason = "unexpected-dialog")
                stop = SlotStop.UnexpectedDialog
                return
            }
            activeStep = SkillLevelStep.Ocr
            val reading = panel.readLevels().inferMissing()
            val dialogLevel = reading.dialogLevel
            val verdict = when {
                panelLevel != null && dialogLevel != null -> if (panelLevel == dialogLevel) SkillLevelCheckVerdict.Match else SkillLevelCheckVerdict.Mismatch
                panelLevel != null -> SkillLevelCheckVerdict.PanelOnly
                dialogLevel != null -> SkillLevelCheckVerdict.DialogOnly
                else -> SkillLevelCheckVerdict.BothUnread
            }
            report = report.copy(dialog = reading.dialogText, verdict = verdict, reason = "cancelled")
            stop = SlotStop.Reached
        } finally {
            val cause = activeStep
            activeStep = SkillLevelStep.Confirm // Existing UI label is decision/cancel.
            stop = SlotStop.Stuck // A thrown cancellation also preserves the stopped state.
            if (panel.cancel()) {
                activeStep = cause
                stop = if (report.reason == "unexpected-dialog") SlotStop.UnexpectedDialog else SlotStop.Reached
            } else {
                report = report.copy(reason = "cancel-failed")
            }
        }
    }
}
