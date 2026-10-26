package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.modules.*
import kotlin.test.*

class SkillLevelCheckFlowTest {
    @Test fun `issue 38 table applies to check reports logs and the end dialog line`() {
        val cases = listOf(
            SkillLevelReading(8, 9) to "8",
            SkillLevelReading(8, 10) to "unread",
            SkillLevelReading(null, 9) to "8(inferred)",
            SkillLevelReading(8, null) to "8(next-inferred)",
            SkillLevelReading(null, null) to "unread",
            SkillLevelReading(null, 10) to "9(inferred)",
            SkillLevelReading(null, 1) to "unread"
        )
        for ((reading, text) in cases) for (panelLevel in listOf(null, 8, 9)) {
            val panel = Panel(level = panelLevel, reading = reading)
            val logs = mutableListOf<String>()
            val results = SkillLevelCheckFlow(panel, logs::add).runAll(SkillLevelMode.Skill)
            val report = checkNotNull(results.first().check)
            assertEquals(text, report.dialog, "$reading")
            val dialogLevel = when (text) { "unread" -> null; "9(inferred)" -> 9; else -> 8 }
            val verdict = when {
                panelLevel != null && dialogLevel != null -> if (panelLevel == dialogLevel) SkillLevelCheckVerdict.Match else SkillLevelCheckVerdict.Mismatch
                panelLevel != null -> SkillLevelCheckVerdict.PanelOnly
                dialogLevel != null -> SkillLevelCheckVerdict.DialogOnly
                else -> SkillLevelCheckVerdict.BothUnread
            }
            assertEquals(verdict, report.verdict, "$reading panel=$panelLevel")
            assertTrue("dialog=$text verdict=" in report.line(0))
            assertTrue("check ${report.line(0)}" in logs)
            assertEquals(3, panel.cancels)
            assertEquals(0, panel.confirms)
        }
    }

    private class Panel(
        var level: Int? = 3,
        var reading: SkillLevelReading = SkillLevelReading(3, 4),
        var locked: Set<Int> = emptySet(),
        var unlock: Boolean = false,
        var max: Boolean = false,
        var dialog: Boolean = true,
        var enhanceTitle: Boolean = true,
        var cancelWorks: Boolean = true,
        var readThrows: Boolean = false,
        var cancelThrows: Boolean = false,
        override val panelLevelEvidence: PanelLevelEvidence? = null
    ) : SkillLevelPanel {
        val selected = mutableListOf<Int>()
        val actions = mutableListOf<String>()
        var enhances = 0
        var cancels = 0
        var dialogReads = 0
        var confirms = 0
        override fun isLocked(slot: Int): Boolean { actions += "padlock"; return slot in locked }
        override fun select(slot: Int) { selected += slot; actions += "select" }
        override fun readPanelLevel(slot: Int): Int? { actions += "panel"; return level }
        override fun unlockButtonVisible(): Boolean { actions += "unlock"; return unlock }
        override fun maxButtonVisible(): Boolean { actions += "max"; return max }
        override fun pressEnhance() { enhances++; actions += "enhance" }
        override fun awaitDialog(): Boolean { actions += "dialog"; return dialog }
        override fun dialogIsEnhance() = enhanceTitle
        override fun readLevels(): SkillLevelReading {
            dialogReads++
            check(!readThrows) { "reader failed" }
            return reading
        }
        override fun cancel(): Boolean { cancels++; check(!cancelThrows); return cancelWorks }
        override fun confirm() { confirms++; fail("check mode must never confirm") }
        override fun skipToScreen(): Boolean = fail("check mode must never skip enhancement results")
    }

    private fun run(panel: Panel): List<SlotResult> = SkillLevelCheckFlow(panel).runAll(SkillLevelMode.Skill).also {
        assertEquals(0, panel.confirms)
    }
    private fun report(panel: Panel) = checkNotNull(run(panel).first().check)

    @Test fun `matching levels cancel every dialog and inspect all slots left to right`() {
        val panel = Panel()
        val results = run(panel)
        assertTrue(results.all { it.check?.verdict == SkillLevelCheckVerdict.Match })
        assertEquals(listOf(0, 1, 2), panel.selected)
        assertEquals(3, panel.enhances)
        assertEquals(3, panel.cancels)
        assertEquals(listOf("padlock", "select", "panel", "unlock", "enhance", "dialog"), panel.actions.take(6))
    }
    @Test fun `mismatch is reported and cancelled`() {
        val panel = Panel(level = 4)
        val result = report(panel)
        assertEquals(SkillLevelCheckVerdict.Mismatch, result.verdict)
        assertEquals("4", result.panel)
        assertEquals("3", result.dialog)
        assertEquals(3, panel.cancels)
    }
    @Test fun `read panel with unread dialog is panel-only`() {
        assertEquals(SkillLevelCheckVerdict.PanelOnly, report(Panel(reading = SkillLevelReading(null, null))).verdict)
    }
    @Test fun `unread panel with read dialog is dialog-only`() {
        val result = report(Panel(level = null))
        assertEquals(SkillLevelCheckVerdict.DialogOnly, result.verdict)
        assertEquals("abstain:unread", result.panel)
    }
    @Test fun `both unread is reported`() {
        val result = report(Panel(level = null, reading = SkillLevelReading(null, null)))
        assertEquals(SkillLevelCheckVerdict.BothUnread, result.verdict)
        assertEquals("unread", result.dialog)
    }
    @Test fun `no response reports panel with no dialog`() {
        val panel = Panel(dialog = false)
        val result = report(panel)
        assertEquals(SkillLevelCheckVerdict.NoDialog, result.verdict)
        assertEquals("-", result.dialog)
        assertEquals("no-response", result.reason)
        assertEquals(0, panel.cancels)
    }
    @Test fun `padlock is reported without selecting reading or enhancing locked slot`() {
        val panel = Panel(locked = setOf(0, 2))
        val results = run(panel)
        assertEquals(listOf(1), panel.selected)
        assertEquals(1, panel.enhances)
        assertEquals(1, panel.dialogReads)
        for (index in listOf(0, 2)) {
            assertEquals(SkillLevelCheckVerdict.Locked, results[index].check?.verdict)
            assertEquals("locked", results[index].check?.panel)
            assertEquals("-", results[index].check?.dialog)
        }
    }
    @Test fun `unlock button never presses enhance`() {
        val panel = Panel(unlock = true)
        assertEquals(SkillLevelCheckVerdict.Locked, report(panel).verdict)
        assertEquals(0, panel.enhances)
    }
    @Test fun `reader lock evidence never presses enhance`() {
        val panel = Panel(panelLevelEvidence = PanelLevelEvidence(null, true, null, 0.0, 0.0, "locked"))
        assertEquals(SkillLevelCheckVerdict.Locked, report(panel).verdict)
        assertEquals(0, panel.enhances)
    }
    @Test fun `max panel is read and max button checked only after no dialog`() {
        val panel = Panel(level = 10, max = true, dialog = false)
        val result = report(panel)
        assertEquals("10", result.panel)
        assertEquals("max", result.reason)
        assertEquals(SkillLevelCheckVerdict.NoDialog, result.verdict)
        assertEquals(3, panel.enhances)
        assertEquals(listOf("padlock", "select", "panel", "unlock", "enhance", "dialog", "max"), panel.actions.take(7))
    }
    @Test fun `max button with unread panel reports reason`() {
        val panel = Panel(level = null, max = true, dialog = false)
        assertEquals("max", report(panel).reason)
        assertEquals(3, panel.enhances)
    }
    @Test fun `material shortage retains panel reading after an unresponsive enhance tap`() {
        val panel = Panel(dialog = false)
        val result = report(panel)
        assertEquals("3", result.panel)
        assertEquals("no-response", result.reason)
        assertEquals(SkillLevelCheckVerdict.NoDialog, result.verdict)
        assertEquals(3, panel.enhances)
        assertEquals(0, panel.cancels)
    }
    @Test fun `panel ten must still be cross checked when a dialog opens`() {
        val panel = Panel(level = 10, reading = SkillLevelReading(9, 10))
        assertEquals(SkillLevelCheckVerdict.Mismatch, report(panel).verdict)
        assertEquals(3, panel.enhances)
        assertEquals(3, panel.cancels)
    }
    @Test fun `disabled max button gives no dialog reason after the enhance tap`() {
        val panel = Panel(level = null, max = true, dialog = false)
        val result = report(panel)
        assertEquals(SkillLevelCheckVerdict.NoDialog, result.verdict)
        assertEquals("max", result.reason)
        assertEquals(3, panel.enhances)
        assertEquals(0, panel.cancels)
    }
    @Test fun `visible max label cannot suppress an available dialog comparison`() {
        val panel = Panel(max = true)
        assertEquals(SkillLevelCheckVerdict.Match, report(panel).verdict)
        assertEquals(3, panel.enhances)
        assertEquals(3, panel.cancels)
        assertFalse("max" in panel.actions)
    }
    @Test fun `unexpected dialog is cancelled without reading`() {
        val panel = Panel(enhanceTitle = false)
        val result = report(panel)
        assertEquals("unexpected-dialog", result.reason)
        assertEquals("unread", result.dialog)
        assertEquals(0, panel.dialogReads)
        assertEquals(3, panel.cancels)
    }
    @Test fun `failed cancel stops run as stuck before next slot`() {
        val panel = Panel(cancelWorks = false)
        val result = run(panel).single()
        assertEquals(SlotStop.Stuck, result.stop)
        assertEquals("cancel-failed", result.check?.reason)
        assertEquals(listOf(0), panel.selected)
        assertEquals(1, panel.cancels)
    }
    @Test fun `unexpected dialog cancel failure is also stuck`() {
        val result = run(Panel(enhanceTitle = false, cancelWorks = false)).single()
        assertEquals(SlotStop.Stuck, result.stop)
        assertEquals("cancel-failed", result.check?.reason)
    }
    @Test fun `reader exception still cancels and preserves partial result`() {
        val panel = Panel(readThrows = true)
        val flow = SkillLevelCheckFlow(panel)
        assertFailsWith<IllegalStateException> { flow.runAll(SkillLevelMode.Skill) }
        assertEquals(1, panel.cancels)
        assertEquals(SlotStop.Error, flow.results.single().stop)
        assertEquals(0, panel.confirms)
    }
    @Test fun `throwing cancel preserves stuck result`() {
        val panel = Panel(cancelThrows = true)
        val flow = SkillLevelCheckFlow(panel)
        assertFailsWith<IllegalStateException> { flow.runAll(SkillLevelMode.Skill) }
        assertEquals(SlotStop.Stuck, flow.results.single().stop)
        assertEquals("cancel-failed", flow.results.single().check?.reason)
    }
    @Test fun `check wrapper blocks even a direct accidental confirm call`() {
        val panel = Panel()
        assertFailsWith<IllegalStateException> { SkillLevelCheckPanel(panel).confirm() }
        assertEquals(0, panel.confirms)
    }
    @Test fun `append checks five slots independent of targets and logs the same report line`() {
        val panel = Panel(panelLevelEvidence = PanelLevelEvidence(3, false, 3, 0.91, 0.23, "matched"))
        val logs = mutableListOf<String>()
        val results = SkillLevelCheckFlow(panel, logs::add).runAll(SkillLevelMode.Append)
        assertEquals(listOf(0, 1, 2, 3, 4), panel.selected)
        assertEquals(5, panel.cancels)
        results.forEach { result ->
            assertEquals(0, result.target)
            assertTrue("check ${checkNotNull(result.check).line(result.slot)}" in logs)
        }
        assertEquals("slot=1 panel=3 dialog=3 verdict=match reason=cancelled score=0.91 margin=0.23", results.first().check?.line(0))
        assertEquals(0, panel.confirms)
    }
    @Test fun `abstain reason and scores survive in report`() {
        val result = report(Panel(level = null, panelLevelEvidence = PanelLevelEvidence(null, false, 4, 0.5, 0.01, "ambiguous")))
        assertEquals("abstain:ambiguous", result.panel)
        assertTrue("score=0.5 margin=0.01" in result.line(0))
    }
    @Test fun `zero and non consecutive dialog readings stay unread and are cancelled`() {
        for (reading in listOf(SkillLevelReading(0, 1), SkillLevelReading(3, 0), SkillLevelReading(3, 5), SkillLevelReading(10, 11))) {
            val panel = Panel(reading = reading)
            assertEquals(SkillLevelCheckVerdict.PanelOnly, report(panel).verdict)
            assertEquals(3, panel.cancels)
        }
    }
}
