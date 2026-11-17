package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.entrypoints.SkillLevelScript
import io.github.fate_grand_automata.scripts.locations.SkillLevelLocations
import io.github.fate_grand_automata.scripts.modules.*
import io.github.lib_automata.*
import io.mockk.*
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class SkillLevelDeviceTest {
    private class Run {
        val api = mockk<IFgoAutomataApi>(relaxed=true)
        val loc = mockk<SkillLevelLocations>(relaxed=true)
        val screens = mockk<SkillLevelScreens>(relaxed=true)
        val enhance = Location(100,200)
        val confirm = Location(300,400)
        val skipClick = Location(720, 72)
        val retryRegion = Region(700, 800, 100, 50)
        val retryPattern = mockk<Pattern>(relaxed = true)
        val panelReader = mockk<SkillLevelPanelReader>()
        val device = SkillLevelDevice(api,SkillLevelMode.Skill,loc,screens,mockk(relaxed=true),panelReader)
        init {
            every { api.locations.enhancementClick } returns enhance
            every { api.locations.retryRegion } returns retryRegion
            every { api.images[Images.Retry] } returns retryPattern
            every { loc.dialogConfirm(SkillLevelMode.Skill) } returns confirm
            every { loc.skipClick } returns skipClick
            every { screens.dialogVisible() } returns true
            every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
        }
    }
    @Test fun `enhance waits four tenths of a second after the tap before the unchanged dialog search`() {
        val run=Run()
        run.device.pressEnhance()
        assertTrue(run.device.awaitDialog())
        assertEquals(0.4.seconds,SkillLevelTiming.AFTER_ENHANCE)
        assertEquals(2.seconds,SkillLevelTiming.FIND_POLL*SkillLevelTiming.DIALOG_POLLS)
        assertEquals(20.seconds,SkillLevelTiming.SKIP_INTERVAL*SkillLevelTiming.SKIP_POLLS)
        verifyOrder { run.screens.unlockButtonVisible();with(run.api) { run.enhance.click(1);SkillLevelTiming.AFTER_ENHANCE.wait() };run.screens.dialogVisible() }
        verify(exactly=1) { with(run.api) { any<Duration>().wait(any()) } }
    }
    @Test fun `device forwards selected slot and mode to panel reader and retains evidence`() {
        val run = Run()
        val evidence = PanelLevelEvidence(6, false, 6, 0.9, 0.2, "matched")
        every { run.panelReader.read(SkillLevelMode.Skill, 1) } returns evidence
        assertEquals(6, run.device.readPanelLevel(1))
        assertEquals(evidence, run.device.panelLevelEvidence)
        verify(exactly = 1) { run.panelReader.read(SkillLevelMode.Skill, 1) }
    }
    @Test fun `real dialog reader preserves confident zero even in conflicting primary OCR`() {
        val run = Run()
        val current = Region(1, 2, 3, 4)
        val next = Region(5, 6, 7, 8)
        every { run.loc.currentLevel(SkillLevelMode.Skill) } returns current
        every { run.loc.nextLevel(SkillLevelMode.Skill) } returns next
        val digits = spyk(SkillLevelDigits(run.api, run.loc, mockk()))
        every { digits.readings(current, SkillLevelMode.Skill) } returns listOf(OcrText("0", 95), OcrText("3", 95))
        every { digits.readings(next, SkillLevelMode.Skill) } returns listOf(OcrText("4", 95), OcrText("4", 95))
        assertEquals(SkillLevelReading(0, 4), digits.read(SkillLevelMode.Skill))
        every { digits.readings(current, SkillLevelMode.Skill) } returns listOf(OcrText("3", 95), OcrText("3", 95))
        every { digits.readings(next, SkillLevelMode.Skill) } returns listOf(OcrText("0", 95), OcrText("4", 95))
        assertEquals(SkillLevelReading(3, 0), digits.read(SkillLevelMode.Skill))
    }
    @Test fun `confirm taps once with no added wait before the skip flow`() {
        val run=Run()
        run.device.confirm()
        verify(exactly=1) { with(run.api) { run.confirm.click(1) } }
        verify(exactly=0) { with(run.api) { any<Duration>().wait(any()) } }
    }
    @Test fun `real dialog reader retains next one for inferred zero without relaxing consensus`() {
        val run = Run()
        val current = Region(1, 2, 3, 4)
        val next = Region(5, 6, 7, 8)
        every { run.loc.currentLevel(SkillLevelMode.Skill) } returns current
        every { run.loc.nextLevel(SkillLevelMode.Skill) } returns next
        val digits = spyk(SkillLevelDigits(run.api, run.loc, mockk()))
        every { digits.readings(current, SkillLevelMode.Skill) } returns listOf(OcrText("", 0))
        every { digits.templateScores(current, any()) } returns emptyList()
        every { digits.readings(next, SkillLevelMode.Skill) } returns listOf(OcrText("1", 95), OcrText("1", 95))
        val inferred = digits.read(SkillLevelMode.Skill).inferMissing()
        assertEquals(0, inferred.current)
        assertTrue(inferred.currentInferred)
        assertNull(inferred.dialogLevel)
        every { digits.readings(next, SkillLevelMode.Skill) } returns listOf(OcrText("1", 95))
        assertEquals(SkillLevelReading(null, null), digits.read(SkillLevelMode.Skill).inferMissing())
        every { digits.readings(next, SkillLevelMode.Skill) } returns listOf(OcrText("1", 95), OcrText("2", 95))
        assertEquals(SkillLevelReading(null, null), digits.read(SkillLevelMode.Skill).inferMissing())
    }
    @Test fun `the last unlock guard still prevents both the enhance tap and its delay`() {
        val run=Run()
        every { run.screens.unlockButtonVisible() } returns true
        assertFailsWith<IllegalStateException> { run.device.pressEnhance() }
        verify(exactly=0) { with(run.api) { run.enhance.click(any());any<Duration>().wait(any()) } }
    }
    @Test fun `cancel waits four tenths of a second after the tap before the close check`() {
        val run=Run()
        val cancel=Location(500,600)
        every { run.loc.dialogCancel(SkillLevelMode.Skill) } returns cancel
        every { run.screens.dialogVisible() } returns false
        assertTrue(run.device.cancel())
        assertEquals(0.4.seconds,SkillLevelTiming.AFTER_CANCEL)
        verifyOrder { with(run.api) { cancel.click(1);SkillLevelTiming.AFTER_CANCEL.wait() };run.screens.dialogVisible() }
    }
    @Test fun `exit reasons log readable names`() {
        val names=listOf(SkillLevelScript.ExitReason.Done(emptyList()) to "Done",SkillLevelScript.ExitReason.NoServantSelected to "NoServantSelected",
            SkillLevelScript.ExitReason.WrongScreen to "WrongScreen",SkillLevelScript.ExitReason.UnsupportedServer to "UnsupportedServer",
            SkillLevelScript.ExitReason.NothingSelected to "NothingSelected",SkillLevelScript.ExitReason.Abort(emptyList()) to "Abort",
            SkillLevelScript.ExitReason.Unexpected(Exception(),emptyList()) to "Unexpected")
        names.forEach { (reason,name) -> assertEquals(name,reason.logName) }
    }

    /** One skip poll per character: B = back screen, D = label only (deselected), R = Retry dialog, O = something else (animation). */
    private data class SkipOutcome(
        val result: SkipResult,
        val polls: Int,
        val retries: Int,
        val retryTaps: Int,
        val skipTaps: Int,
        val retryCallbacks: List<Int>
    )

    private fun skipWithFrames(
        frames: String,
        mode: SkillLevelMode = SkillLevelMode.Skill
    ): SkipOutcome {
        val run = Run()
        val device = SkillLevelDevice(run.api, mode, run.loc, run.screens, mockk(relaxed = true), run.panelReader)
        var poll = 0
        var retryTaps = 0
        var skipTaps = 0
        fun frame() = frames.getOrElse(poll - 1) { 'O' }
        every { with(run.api) { run.retryPattern in run.retryRegion } } answers { frame() == 'R' }
        every { run.screens.onLabel(any()) } answers { frame() == 'B' || frame() == 'D' }
        every { run.screens.servantSelected() } answers { frame() == 'B' }
        every { run.screens.dialogVisible() } returns false
        every { with(run.api) { run.retryRegion.click(any()) } } answers { retryTaps++ }
        every { with(run.api) { run.skipClick.click(any()) } } answers { skipTaps++ }
        every { with(run.api) { SkillLevelTiming.SKIP_INTERVAL.wait(any()) } } answers { poll++ }
        val callbacks = mutableListOf<Int>()
        val result = device.skipToScreen { callbacks += it }
        return SkipOutcome(result, poll, device.lastSkipRetries, retryTaps, skipTaps, callbacks)
    }

    private fun skip(frames: String, mode: SkillLevelMode = SkillLevelMode.Skill): Pair<SkipResult, Int> =
        skipWithFrames(frames, mode).let { it.result to it.polls }

    @Test fun `one deselected frame followed by the back screen is back`() {
        assertEquals(SkipResult.Back, skip("OODB").first)
    }
    @Test fun `two consecutive deselected frames end the skip as deselected without waiting further`() {
        val (result, polls) = skip("OODD")
        assertEquals(SkipResult.Deselected, result)
        assertEquals(4, polls)
    }
    @Test fun `a different frame between two deselected frames resets the count`() {
        assertEquals(SkipResult.Back, skip("OODODB").first)
        assertEquals(SkipResult.Timeout, skip("OODOD" + "O".repeat(40)).first)
    }
    @Test fun `deselected frames before the animation was seen to leave or the minimum polls do not count`() {
        assertEquals(SkipResult.Back, skip("DDB").first)
        assertEquals(SkipResult.Deselected, skip("DDDD").first)
    }
    @Test fun `append mode never reports a deselection`() {
        assertEquals(SkipResult.Timeout, skip("D".repeat(60), SkillLevelMode.Append).first)
    }
    @Test fun `retry showing once taps retry without tapping skip on that poll then returns back`() {
        // Polls: 1=O (skip tap), 2=R (retry tap, no skip tap), 3=O (skip tap), 4=O (skip tap), 5=B (back)
        val outcome = skipWithFrames("OROOB")
        assertEquals(SkipResult.Back, outcome.result)
        assertEquals(1, outcome.retries)
        assertEquals(listOf(1), outcome.retryCallbacks)
        assertEquals(1, outcome.retryTaps)
        assertEquals(3, outcome.skipTaps)
    }
    @Test fun `retry showing four times returns connection failed with three retry taps`() {
        val outcome = skipWithFrames("OROROROR")
        assertEquals(SkipResult.ConnectionFailed, outcome.result)
        assertEquals(3, outcome.retries)
        assertEquals(listOf(1, 2, 3), outcome.retryCallbacks)
        assertEquals(3, outcome.retryTaps)
        assertEquals(4, outcome.skipTaps)
    }
    @Test fun `retry restarts the twenty second poll count from zero`() {
        val frames = "O".repeat(38) + "R" + "O".repeat(19) + "B"
        val outcome = skipWithFrames(frames)
        assertEquals(SkipResult.Back, outcome.result)
        assertEquals(1, outcome.retries)
        assertEquals(1, outcome.retryTaps)
    }
    @Test fun `retry resets left and deselected counters so the screen is judged fresh`() {
        // Without reset on R, D before R and D after R would trigger Deselected before B.
        val outcome = skipWithFrames("OODRDDB")
        assertEquals(SkipResult.Back, outcome.result)
        assertEquals(1, outcome.retries)
    }
    @Test fun `retry is checked before back and deselected on the same poll`() {
        val run = Run()
        val device = SkillLevelDevice(run.api, SkillLevelMode.Skill, run.loc, run.screens, mockk(relaxed = true), run.panelReader)
        var poll = 0
        every { with(run.api) { run.retryPattern in run.retryRegion } } answers { poll == 3 }
        every { run.screens.onLabel(any()) } answers { poll >= 3 }
        every { run.screens.servantSelected() } answers { poll >= 3 }
        every { run.screens.dialogVisible() } returns false
        every { with(run.api) { SkillLevelTiming.SKIP_INTERVAL.wait(any()) } } answers { poll++ }
        assertEquals(SkipResult.Back, device.skipToScreen())
        assertEquals(1, device.lastSkipRetries)
        verify(exactly = 1) { with(run.api) { run.retryRegion.click(1) } }
    }
    @Test fun `retry is never checked outside skipToScreen`() {
        val run = Run()
        every { with(run.api) { run.retryPattern in run.retryRegion } } returns true
        every { run.screens.unlockButtonVisible() } returns false
        every { run.screens.dialogVisible() } returns true
        every { run.panelReader.read(SkillLevelMode.Skill, 0) } returns PanelLevelEvidence(3, false, 3, 0.9, 0.2, "ok")
        run.device.select(0)
        run.device.readPanelLevel(0)
        run.device.pressEnhance()
        run.device.awaitDialog()
        run.device.confirm()
        every { run.screens.dialogVisible() } returns false
        run.device.cancel()
        verify(exactly = 0) { with(run.api) { run.retryPattern in run.retryRegion } }
        verify(exactly = 0) { with(run.api) { run.retryRegion.click(any()) } }
    }
}
