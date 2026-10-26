package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.modules.SkillLevelDigits
import io.github.fate_grand_automata.scripts.modules.SkillLevelStep
import io.github.fate_grand_automata.scripts.modules.SkillLevelFlow
import io.github.fate_grand_automata.scripts.modules.SkillLevelPanel
import io.github.fate_grand_automata.scripts.modules.SkillLevelReading
import io.github.fate_grand_automata.scripts.modules.SlotResult
import io.github.fate_grand_automata.scripts.modules.SlotStop
import io.github.lib_automata.OcrText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A game that levels up one level per confirmed enhancement; every knob models one way the real screen can misbehave. */
private class FakePanel(
    val levels: MutableList<Int>,
    private val locked: Set<Int> = emptySet(),
    private val iconLockVisible: Set<Int> = emptySet(),
    private val unlockLabel: Set<Int> = emptySet(),
    private val maxLabel: Boolean = false,
    /** Whether the game takes a confirmed enhancement. False models a swallowed tap. */
    private val confirmWorks: Boolean = true,
    private val dialogOpens: Boolean = true,
    private val enhanceTitle: Boolean = true,
    private val skipReturns: Boolean = true,
    private val cancelCloses: Boolean = true,
    private val panelRead: (trueLevel: Int) -> Int? = { null },
    private val read: (trueLevel: Int) -> SkillLevelReading = { SkillLevelReading(it, it + 1) }
) : SkillLevelPanel {
    var selected = -1
    var dialogOpen = false
    var confirms = 0
    var cancels = 0
    var panelReads = 0
    var enhances = 0
    var unlockPressed = false
    val peak = levels.toMutableList()

    override fun isLocked(slot: Int) = slot in iconLockVisible
    override fun select(slot: Int) { selected = slot }
    override fun unlockButtonVisible() = selected in unlockLabel || selected in locked
    override fun maxButtonVisible() = maxLabel
    override fun readPanelLevel(slot: Int): Int? {
        check(slot == selected)
        panelReads++
        return panelRead(levels[slot])
    }
    override fun pressEnhance() {
        enhances++
        if (selected in locked) unlockPressed = true
        else if (levels[selected] < 10 && dialogOpens) dialogOpen = true
    }
    override fun awaitDialog() = dialogOpen
    override fun dialogIsEnhance() = enhanceTitle
    override fun readLevels() = read(levels[selected])
    override fun confirm() {
        check(dialogOpen)
        confirms++
        dialogOpen = false
        if (confirmWorks) {
            levels[selected]++
            peak[selected] = maxOf(peak[selected], levels[selected])
        }
    }
    override fun cancel(): Boolean {
        cancels++
        if (cancelCloses) dialogOpen = false
        return !dialogOpen
    }
    override fun skipToScreen() = skipReturns
}

class SkillLevelFlowTest {
    private fun run(panel: FakePanel, vararg targets: Int): List<SlotResult> = SkillLevelFlow(panel).runAll(targets.toList())

    @Test fun `unlocked slot reads panel before unlock guard and waits for dialog before max check`() {
        val base = FakePanel(mutableListOf(3), dialogOpens = false)
        val actions = mutableListOf<String>()
        val panel = object : SkillLevelPanel by base {
            override fun isLocked(slot: Int): Boolean { actions += "padlock"; return base.isLocked(slot) }
            override fun select(slot: Int) { actions += "select"; base.select(slot) }
            override fun readPanelLevel(slot: Int): Int? { actions += "panel"; return base.readPanelLevel(slot) }
            override fun unlockButtonVisible(): Boolean { actions += "unlock"; return base.unlockButtonVisible() }
            override fun pressEnhance() { actions += "enhance"; base.pressEnhance() }
            override fun awaitDialog(): Boolean { actions += "dialog"; return base.awaitDialog() }
            override fun maxButtonVisible(): Boolean { actions += "max"; return base.maxButtonVisible() }
        }
        assertEquals(SlotStop.CannotEnhance, SkillLevelFlow(panel).runSlot(0, 8).stop)
        assertEquals(listOf("padlock", "select", "panel", "unlock", "enhance", "dialog", "max"), actions)
        assertEquals(0, base.confirms)
    }

    @Test fun `no dialog after the last confirm preserves reached below level ten`() {
        val base = FakePanel(mutableListOf(3))
        val panel = object : SkillLevelPanel by base {
            override fun pressEnhance() { if (base.confirms == 0) base.pressEnhance() }
        }
        val result = SkillLevelFlow(panel).runSlot(0, 4)
        assertEquals(SlotResult(0, 4, 3, 4, SlotStop.Reached, SkillLevelStep.Dialog), result)
        assertEquals(1, base.confirms)
        assertEquals(0, base.cancels)
    }

    @Test fun `equal panel and dialog levels proceed using both sources`() {
        val logs = mutableListOf<String>()
        val panel = FakePanel(mutableListOf(3), panelRead = { it })
        val result = SkillLevelFlow(panel, logs::add).runAll(listOf(4)).single()
        assertEquals(SlotStop.Reached, result.stop)
        assertEquals(1, panel.confirms)
        assertEquals(1, panel.enhances)
        assertEquals(0, panel.cancels)
        assertTrue(logs.any { "panel=3 dialog=3 source=both" in it })
    }

    @Test fun `different panel and dialog levels cancel without confirming`() {
        val logs = mutableListOf<String>()
        val panel = FakePanel(mutableListOf(3), panelRead = { 4 })
        val result = SkillLevelFlow(panel, logs::add).runAll(listOf(8)).single()
        assertEquals(SlotStop.PanelMismatch, result.stop)
        assertEquals(1, panel.cancels)
        assertEquals(0, panel.confirms)
        assertFalse(panel.dialogOpen)
        assertTrue(logs.any { "panel=4 dialog=3 source=both" in it })
    }

    @Test fun `mismatch cancellation failure stops all slots as stuck`() {
        val panel = FakePanel(mutableListOf(3, 3), panelRead = { 4 }, cancelCloses = false)
        assertEquals(SlotStop.Stuck, run(panel, 8, 8).single().stop)
        assertEquals(1, panel.enhances)
        assertEquals(0, panel.confirms)
    }

    @Test fun `unread dialog progresses across several panel-only passes`() {
        val logs = mutableListOf<String>()
        val panel = FakePanel(mutableListOf(3), panelRead = { it }, read = { SkillLevelReading(null, null) })
        val result = SkillLevelFlow(panel, logs::add).runAll(listOf(7)).single()
        assertEquals(SlotResult(0, 7, 3, 7, SlotStop.Reached, SkillLevelStep.Enhance), result)
        assertEquals(4, panel.confirms)
        assertEquals(4, panel.enhances)
        assertEquals(5, panel.panelReads)
        assertEquals(0, panel.cancels)
        for (level in 3..7) assertTrue(logs.any { "panel=$level dialog=unread source=panel" in it })
    }

    @Test fun `panel catches no increase before another enhance tap`() {
        val panel = FakePanel(mutableListOf(3), panelRead = { it }, confirmWorks = false,
            read = { SkillLevelReading(null, null) })
        val result = run(panel, 8).single()
        assertEquals(SlotResult(0, 8, 3, 3, SlotStop.NoIncrease, SkillLevelStep.Enhance), result)
        assertEquals(1, panel.confirms)
        assertEquals(1, panel.enhances)
        assertEquals(2, panel.panelReads)
        assertEquals(0, panel.cancels)
    }

    @Test fun `panel catches a jump by more than one before enhance`() {
        var reads = 0
        val panel = FakePanel(mutableListOf(3), panelRead = { if (reads++ == 0) 3 else 5 })
        assertEquals(SlotStop.NoIncrease, run(panel, 8).single().stop)
        assertEquals(1, panel.enhances)
        assertEquals(1, panel.confirms)
    }

    @Test fun `panel max target and above target stop without tapping enhance`() {
        for ((level, target, stop) in listOf(Triple(10, 8, SlotStop.Max), Triple(10, 10, SlotStop.Max),
            Triple(6, 6, SlotStop.Reached), Triple(7, 6, SlotStop.Reached))) {
            val panel = FakePanel(mutableListOf(level), panelRead = { it })
            assertEquals(SlotResult(0, target, level, level, stop, SkillLevelStep.Enhance), run(panel, target).single())
            assertEquals(0, panel.enhances)
            assertEquals(0, panel.confirms)
            assertEquals(0, panel.cancels)
        }
    }

    @Test fun `panel max takes precedence over previous level check after reaching ten`() {
        val panel = FakePanel(mutableListOf(9), panelRead = { it }, read = { SkillLevelReading(null, null) })
        assertEquals(SlotResult(0, 10, 9, 10, SlotStop.Max, SkillLevelStep.Enhance), run(panel, 10).single())
        assertEquals(1, panel.enhances)
        assertEquals(1, panel.confirms)
    }

    @Test fun `abstaining panel preserves dialog-only progress and log`() {
        val logs = mutableListOf<String>()
        val panel = FakePanel(mutableListOf(3))
        assertEquals(SlotStop.Reached, SkillLevelFlow(panel, logs::add).runAll(listOf(4)).single().stop)
        assertEquals(1, panel.confirms)
        assertTrue(logs.any { "panel=abstain:unread dialog=3 source=dialog" in it })
    }

    @Test fun `both sources unread cancel and log their abstention`() {
        val logs = mutableListOf<String>()
        val panel = FakePanel(mutableListOf(3), read = { SkillLevelReading(null, null) })
        assertEquals(SlotStop.Unreadable, SkillLevelFlow(panel, logs::add).runAll(listOf(8)).single().stop)
        assertEquals(0, panel.confirms)
        assertEquals(1, panel.cancels)
        assertTrue(logs.any { "panel=abstain:unread dialog=unread" in it })
    }

    @Test fun `invalid dialog pairs are unread and may use a confident panel`() {
        for (reading in listOf(SkillLevelReading(3, 5), SkillLevelReading(10, 11))) {
            val panel = FakePanel(mutableListOf(3), panelRead = { it }, read = { reading })
            assertEquals(SlotStop.Reached, run(panel, 4).single().stop)
            assertEquals(1, panel.confirms)
        }
    }

    @Test fun `panel fallback never overrides a zero or a non enhancement dialog`() {
        for (reading in listOf(SkillLevelReading(0, 1), SkillLevelReading(null, 0), SkillLevelReading(3, 0))) {
            val panel = FakePanel(mutableListOf(3), panelRead = { it }, read = { reading })
            assertEquals(SlotStop.Unreadable, run(panel, 8).single().stop)
            assertEquals(0, panel.confirms)
            assertEquals(1, panel.cancels)
        }
        val panel = FakePanel(mutableListOf(3), panelRead = { it }, enhanceTitle = false)
        assertEquals(SlotStop.UnexpectedDialog, run(panel, 8).single().stop)
        assertEquals(0, panel.confirms)
        assertEquals(1, panel.cancels)
    }

    @Test fun `a panel value never bypasses the requirement for a dialog`() {
        val panel = FakePanel(mutableListOf(3), panelRead = { it }, dialogOpens = false)
        assertEquals(SlotStop.CannotEnhance, run(panel, 8).single().stop)
        assertEquals(0, panel.confirms)
    }

    @Test fun `invalid panel values abstain instead of confirming or stopping early`() {
        for (bad in listOf(0, -1, 11)) {
            val panel = FakePanel(mutableListOf(3), panelRead = { bad }, read = { SkillLevelReading(null, null) })
            assertEquals(SlotStop.Unreadable, run(panel, 8).single().stop)
            assertEquals(0, panel.confirms)
        }
    }

    @Test fun `append panel evidence includes abstention reason score and margin on each pass`() {
        val base = FakePanel(mutableListOf(3))
        val panel = object : SkillLevelPanel by base {
            override val panelLevelEvidence = io.github.fate_grand_automata.scripts.modules.PanelLevelEvidence(
                null, false, 3, 0.65, 0.12, "low-score")
        }
        val logs = mutableListOf<String>()
        SkillLevelFlow(panel, logs::add).runAll(listOf(4))
        assertEquals(2, logs.count { "panel=abstain:low-score" in it && "source=dialog score=0.65 margin=0.12" in it })
    }

    @Test fun `a lock discovered by the panel reader also prevents the enhance tap`() {
        val base = FakePanel(mutableListOf(3))
        val panel = object : SkillLevelPanel by base {
            override val panelLevelEvidence = io.github.fate_grand_automata.scripts.modules.PanelLevelEvidence(
                null, true, null, 0.0, 0.0, "locked")
        }
        assertEquals(SlotStop.Locked, SkillLevelFlow(panel).runAll(listOf(8)).single().stop)
        assertEquals(0, base.enhances)
        assertEquals(0, base.confirms)
    }

    @Test fun `wide KR append capture with four locks only enhances slot two`() {
        // Capture 33: slots 1, 3, 4, 5 locked; slot 2 is Lv.6/10. Requesting all slots must never unlock one.
        val locks = setOf(0, 2, 3, 4)
        val panel = FakePanel(mutableListOf(0, 6, 0, 0, 0), locked = locks, iconLockVisible = locks)
        val results = run(panel, 7, 7, 7, 7, 7)
        assertEquals(listOf(0, 7, 0, 0, 0), panel.levels)
        assertEquals(1, panel.confirms)
        assertFalse(panel.unlockPressed)
        assertEquals(listOf(SlotStop.Locked, SlotStop.Reached, SlotStop.Locked, SlotStop.Locked, SlotStop.Locked), results.map { it.stop })
    }

    @Test fun `enhances to the target, then cancels the next dialog and reports the target as reached`() {
        val panel = FakePanel(mutableListOf(3))
        val result = run(panel, 5).single()
        assertEquals(2, panel.confirms)
        assertEquals(1, panel.cancels)
        assertEquals(5, panel.levels[0])
        assertEquals(SlotResult(0, 5, 3, 5, SlotStop.Reached, SkillLevelStep.Ocr), result)
    }

    @Test fun `already at the target level cancels without a single confirmation`() {
        val panel = FakePanel(mutableListOf(6))
        val result = run(panel, 6).single()
        assertEquals(0, panel.confirms)
        assertEquals(SlotStop.Reached, result.stop)
        assertEquals(6, result.startLevel)
    }

    @Test fun `the target is never exceeded for any start level and target`() {
        for (start in 1..10) for (target in 1..10) {
            val panel = FakePanel(mutableListOf(start))
            run(panel, target)
            assertTrue(panel.peak[0] <= maxOf(start, target), "start=$start target=$target peak=${panel.peak[0]}")
            if (start < target) assertEquals(target, panel.levels[0], "start=$start target=$target")
            else assertEquals(start, panel.levels[0], "start=$start target=$target")
        }
    }

    @Test fun `a level that did not go up stops the slot after one confirmation and cancels`() {
        val panel = FakePanel(mutableListOf(3), confirmWorks = false)
        val result = run(panel, 8).single()
        assertEquals(1, panel.confirms)
        assertEquals(1, panel.cancels)
        assertEquals(SlotStop.NoIncrease, result.stop)
        assertEquals(3, result.endLevel)
    }

    @Test fun `an unread level is never confirmed`() {
        val panel = FakePanel(mutableListOf(3), read = { SkillLevelReading(null, null) })
        val result = run(panel, 8).single()
        assertEquals(0, panel.confirms)
        assertEquals(1, panel.cancels)
        assertEquals(SlotStop.Unreadable, result.stop)
        assertNull(result.startLevel)
    }

    @Test fun `level zero and out of range levels are never confirmed, the unlock dialog reads 0 to 1`() {
        for (bad in listOf(0, 10, 11, -1)) {
            val panel = FakePanel(mutableListOf(2), read = { SkillLevelReading(bad, bad + 1) })
            assertEquals(SlotStop.Unreadable, run(panel, 8).single().stop)
            assertEquals(0, panel.confirms, "read $bad")
            assertEquals(1, panel.cancels)
        }
    }

    @Test fun `a second reading that disagrees with the first is not trusted`() {
        val panel = FakePanel(mutableListOf(3), read = { SkillLevelReading(it, it + 3) })
        assertEquals(SlotStop.Unreadable, run(panel, 8).single().stop)
        assertEquals(0, panel.confirms)
    }

    @Test fun `a dialog that is not the enhancement dialog is canceled`() {
        val panel = FakePanel(mutableListOf(3), enhanceTitle = false)
        assertEquals(SlotStop.UnexpectedDialog, run(panel, 8).single().stop)
        assertEquals(0, panel.confirms)
        assertEquals(1, panel.cancels)
    }

    @Test fun `locked slots are skipped by every guard and unlock is never pressed`() {
        val cases = listOf(
            FakePanel(mutableListOf(1), iconLockVisible = setOf(0)),
            FakePanel(mutableListOf(1), unlockLabel = setOf(0)),
            FakePanel(mutableListOf(1), locked = setOf(0)),
        )
        for (panel in cases) {
            val result = run(panel, 5).single()
            assertEquals(SlotStop.Locked, result.stop)
            assertEquals(if (panel.selected == -1) 0 else 1, panel.panelReads)
            assertEquals(0, panel.enhances)
            assertFalse(panel.unlockPressed)
            assertEquals(0, panel.confirms)
            assertFalse(panel.dialogOpen)
        }
    }

    @Test fun `a material shortage ends the slot after no dialog without confirming`() {
        val panel = FakePanel(mutableListOf(3), dialogOpens = false)
        val result = run(panel, 5).single()
        assertEquals(SlotStop.CannotEnhance, result.stop)
        assertEquals(1, panel.enhances)
        assertEquals(1, panel.panelReads)
        assertFalse(panel.dialogOpen)
        assertEquals(0, panel.confirms)
    }

    @Test fun `no dialog means skip, with the reason when it can be told`() {
        val none = FakePanel(mutableListOf(3), dialogOpens = false)
        assertEquals(SlotStop.CannotEnhance, run(none, 8).single().stop)
        val max = FakePanel(mutableListOf(10), maxLabel = true)
        assertEquals(SlotStop.Max, run(max, 10).single().stop)
        assertEquals(0, max.confirms)
    }

    @Test fun `the last confirmed enhancement reaching 10 counts as reached, not as cannot enhance`() {
        val panel = FakePanel(mutableListOf(9), maxLabel = true)
        val result = run(panel, 10).single()
        assertEquals(1, panel.confirms)
        assertEquals(10, panel.levels[0])
        assertEquals(SlotResult(0, 10, 9, 10, SlotStop.Reached, SkillLevelStep.Dialog), result)
    }

    @Test fun `a slot with target 0 is off and is never touched`() {
        val panel = FakePanel(mutableListOf(3, 3, 3))
        val results = run(panel, 0, 0, 0)
        assertTrue(results.all { it.stop == SlotStop.Off })
        assertEquals(-1, panel.selected)
    }

    @Test fun `every slot is run in order with its own target`() {
        val panel = FakePanel(mutableListOf(3, 1, 8))
        val results = run(panel, 4, 0, 10)
        assertEquals(listOf(SlotStop.Reached, SlotStop.Off, SlotStop.Reached), results.map { it.stop })
        assertEquals(listOf(4, 1, 10), panel.levels)
    }

    @Test fun `a game that does not return to the screen stops the whole run`() {
        val panel = FakePanel(mutableListOf(3, 3), skipReturns = false)
        val results = run(panel, 8, 8)
        assertEquals(1, results.size)
        assertEquals(SlotStop.Stuck, results.single().stop)
        assertEquals(1, panel.confirms)
    }

    @Test fun `a dialog that will not close stops the whole run`() {
        val panel = FakePanel(mutableListOf(6, 3), cancelCloses = false)
        val results = run(panel, 6, 8)
        assertEquals(listOf(SlotStop.Stuck), results.map { it.stop })
    }

    @Test fun `an append layout with locked slots only touches the unlocked ones`() {
        val panel = FakePanel(mutableListOf(1, 5, 1, 1, 10), iconLockVisible = setOf(0, 2, 3), locked = setOf(0, 2, 3), maxLabel = true)
        val results = run(panel, 3, 7, 3, 3, 10)
        assertEquals(listOf(SlotStop.Locked, SlotStop.Reached, SlotStop.Locked, SlotStop.Locked, SlotStop.Max), results.map { it.stop })
        assertFalse(panel.unlockPressed)
        assertEquals(listOf(1, 7, 1, 1, 10), panel.levels)
    }

    @Test fun `digit consensus needs two confident readings and no contradiction`() {
        val c = { text: String, conf: Int -> OcrText(text, conf) }
        assertEquals(4, SkillLevelDigits.consensus(listOf(c("4", 95), c("4", 90), c("", 10)), 1..9))
        assertNull(SkillLevelDigits.consensus(listOf(c("4", 95)), 1..9))
        assertNull(SkillLevelDigits.consensus(listOf(c("4", 95), c("1", 90), c("4", 90)), 1..9))
        assertNull(SkillLevelDigits.consensus(listOf(c("4", 40), c("4", 40)), 1..9))
        assertNull(SkillLevelDigits.consensus(listOf(c("0", 95), c("0", 95)), 1..9))
        assertNull(SkillLevelDigits.consensus(listOf(c("10", 95), c("10", 95)), 1..9))
        assertEquals(10, SkillLevelDigits.consensus(listOf(c("10", 95), c(" 10\n", 90)), 2..10))
    }

    @Test fun `nine to two digit ten confirms exactly once when the target is ten`() {
        val panel = FakePanel(mutableListOf(9), read = { SkillLevelReading(9, 10) })
        val result = run(panel, 10).single()
        assertEquals(1, panel.confirms)
        assertEquals(0, panel.cancels)
        assertEquals(10, panel.levels[0])
        assertEquals(SlotResult(0, 10, 9, 10, SlotStop.Reached, SkillLevelStep.Dialog), result)
    }

    @Test fun `nine to two digit ten cancels without confirming when the target is nine`() {
        val panel = FakePanel(mutableListOf(9), read = { SkillLevelReading(9, 10) })
        val result = run(panel, 9).single()
        assertEquals(0, panel.confirms)
        assertEquals(1, panel.cancels)
        assertEquals(9, panel.levels[0])
        assertEquals(SlotResult(0, 9, 9, 9, SlotStop.Reached, SkillLevelStep.Ocr), result)
    }

    @Test fun `an unread next level is inferred from an unambiguous current nine`() {
        val panel = FakePanel(mutableListOf(9), read = { SkillLevelReading(9, null) })
        val result = run(panel, 10).single()
        assertEquals(1, panel.confirms)
        assertEquals(0, panel.cancels)
        assertEquals(10, panel.levels[0])
        assertEquals(SlotStop.Reached, result.stop)
    }

    @Test fun `issue 38 table applies to the regular flow without a panel fallback`() {
        val cases = listOf(
            SkillLevelReading(8, 9) to "8",
            SkillLevelReading(8, 10) to "unread",
            SkillLevelReading(null, 9) to "8(inferred)",
            SkillLevelReading(8, null) to "8(next-inferred)",
            SkillLevelReading(null, null) to "unread"
        )
        for ((reading, text) in cases) {
            val logs = mutableListOf<String>()
            val panel = FakePanel(mutableListOf(8), read = { reading })
            val result = SkillLevelFlow(panel, logs::add).runSlot(0, 8)
            assertEquals(if (text == "unread") SlotStop.Unreadable else SlotStop.Reached, result.stop, "$reading")
            assertEquals(0, panel.confirms)
            assertEquals(1, panel.cancels)
            assertTrue(logs.any { "dialog=$text source=" in it }, "$reading: $logs")
        }
    }

    @Test fun `one side inference reaches every target without exceeding it`() {
        for (missingCurrent in listOf(true, false)) for (start in 1..9) for (target in 1..10) {
            val panel = FakePanel(mutableListOf(start), read = {
                if (missingCurrent) SkillLevelReading(null, it + 1) else SkillLevelReading(it, null)
            })
            run(panel, target)
            assertEquals(maxOf(start, target), panel.levels[0])
            assertEquals(maxOf(start, target), panel.peak[0])
        }
    }

    @Test fun `inferred current zero cancels even with a confident panel`() {
        for (panelLevel in listOf(null, 3)) {
            val logs = mutableListOf<String>()
            val panel = FakePanel(mutableListOf(3), panelRead = { panelLevel }, read = { SkillLevelReading(null, 1) })
            assertEquals(SlotStop.Unreadable, SkillLevelFlow(panel, logs::add).runSlot(0, 8).stop)
            assertEquals(0, panel.confirms)
            assertEquals(1, panel.cancels)
            assertTrue(logs.any { "current=0(inferred) next=1" in it })
        }
    }

    @Test fun `inferred levels still pass through panel mismatch and no increase guards`() {
        for (missingCurrent in listOf(true, false)) {
            val read: (Int) -> SkillLevelReading = {
                if (missingCurrent) SkillLevelReading(null, it + 1) else SkillLevelReading(it, null)
            }
            val mismatch = FakePanel(mutableListOf(3), panelRead = { 4 }, read = read)
            assertEquals(SlotStop.PanelMismatch, run(mismatch, 8).single().stop)
            assertEquals(0, mismatch.confirms)
            assertEquals(1, mismatch.cancels)
            val noIncrease = FakePanel(mutableListOf(3), confirmWorks = false, read = read)
            assertEquals(SlotStop.NoIncrease, run(noIncrease, 8).single().stop)
            assertEquals(1, noIncrease.confirms)
            assertEquals(1, noIncrease.cancels)
        }
    }

    @Test fun `template fallback never replaces confident invalid or conflicting primary digits`() {
        assertTrue(SkillLevelDigits.needsTemplateFallback(listOf(OcrText("", 0), OcrText("9", 69))))
        assertFalse(SkillLevelDigits.needsTemplateFallback(listOf(OcrText("9", 95))))
        assertFalse(SkillLevelDigits.needsTemplateFallback(listOf(OcrText("0", 95))))
        assertFalse(SkillLevelDigits.needsTemplateFallback(listOf(OcrText("123", 95))))
        assertFalse(SkillLevelDigits.needsTemplateFallback(listOf(OcrText("9", 95), OcrText("6", 95))))
        assertNull(SkillLevelDigits.consensus(listOf(OcrText("9", 95), OcrText("6", 95)), 1..9))
    }

    @Test fun `nine template fallback requires a clear match and margin against competing digits`() {
        assertEquals(9, SkillLevelDigits.templateNine(listOf(9 to 0.99, 8 to 0.75, 0 to 0.8)))
        assertNull(SkillLevelDigits.templateNine(listOf(9 to 0.95, 8 to 0.7)))
        assertNull(SkillLevelDigits.templateNine(listOf(9 to 0.99, 8 to 0.92)))
        assertNull(SkillLevelDigits.templateNine(listOf(8 to 1.0, 9 to 0.98)))
        assertNull(SkillLevelDigits.templateNine(listOf(9 to 1.0)))
        assertNull(SkillLevelDigits.templateNine(listOf(9 to Double.NaN, 8 to 0.1)))
    }

    @Test fun `every successful stage and cancellation is visible in the step log`() {
        val logs = mutableListOf<String>()
        val result = SkillLevelFlow(FakePanel(mutableListOf(3)), logs::add).runAll(listOf(4)).single()
        for (field in listOf("action=select", "action=enhance-tap", "seen=true",
            "current=3 next=4", "action=confirm", "return=true", "action=cancel", "cancelClosed=true")) {
            assertTrue(logs.any { it.contains(field) }, field)
        }
        assertTrue(logs.any { it == "stop slot=1 step=ocr reason=Reached" })
        assertTrue(logs.last().startsWith("summary slot=1 step=ocr target=4 start=3 end=4 reason=Reached"))
        assertEquals(SkillLevelStep.Ocr, result.step)
    }

    @Test fun `exceptions at every step retain the active slot summary and exact failure step`() {
        for (step in SkillLevelStep.entries) {
            val base = FakePanel(mutableListOf(3, 3))
            fun fail(here: SkillLevelStep) { if (here == step) error("failed-${step.token}") }
            val panel = object : SkillLevelPanel by base {
                override fun select(slot: Int) { fail(SkillLevelStep.Select);base.select(slot) }
                override fun pressEnhance() { fail(SkillLevelStep.Enhance);base.pressEnhance() }
                override fun awaitDialog(): Boolean { fail(SkillLevelStep.Dialog);return base.awaitDialog() }
                override fun readLevels(): SkillLevelReading { fail(SkillLevelStep.Ocr);return base.readLevels() }
                override fun confirm() { fail(SkillLevelStep.Confirm);base.confirm() }
                override fun skipToScreen(): Boolean { fail(SkillLevelStep.Skip);return base.skipToScreen() }
            }
            val logs = mutableListOf<String>()
            val flow = SkillLevelFlow(panel, logs::add)
            kotlin.test.assertFailsWith<IllegalStateException> { flow.runAll(listOf(5, 5)) }
            assertEquals(step, flow.results.single().step)
            assertEquals(SlotStop.Error, flow.results.single().stop)
            assertTrue(logs.any { it.startsWith("stop slot=1 step=${step.token} reason=Error") })
            assertTrue(logs.any { it.startsWith("summary slot=1 step=${step.token}") })
        }
    }

    @Test fun `manual stop during skip retains the enhanced slot and writes its summary`() {
        val base = FakePanel(mutableListOf(3))
        val panel = object : SkillLevelPanel by base {
            override fun skipToScreen(): Boolean = throw io.github.lib_automata.ScriptAbortException()
        }
        val logs = mutableListOf<String>()
        val flow = SkillLevelFlow(panel, logs::add)
        kotlin.test.assertFailsWith<io.github.lib_automata.ScriptAbortException> { flow.runAll(listOf(4)) }
        assertEquals(SlotResult(0,4,3,4,SlotStop.Aborted,SkillLevelStep.Skip),flow.results.single())
        assertTrue(logs.any { it.startsWith("stop slot=1 step=skip reason=Aborted") })
        assertTrue(logs.last().startsWith("summary slot=1 step=skip"))
    }

    @Test fun `cancel failure and skip failure carry distinct steps with the same stuck safety stop`() {
        val cancel = SkillLevelFlow(FakePanel(mutableListOf(3), cancelCloses=false)).runAll(listOf(3)).single()
        val skip = SkillLevelFlow(FakePanel(mutableListOf(3), skipReturns=false)).runAll(listOf(4)).single()
        assertEquals(SlotStop.Stuck,cancel.stop)
        assertEquals(SkillLevelStep.Confirm,cancel.step)
        assertEquals(SlotStop.Stuck,skip.stop)
        assertEquals(SkillLevelStep.Skip,skip.step)
    }

}
