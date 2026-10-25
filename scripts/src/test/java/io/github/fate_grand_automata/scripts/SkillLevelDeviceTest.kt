package io.github.fate_grand_automata.scripts

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
        val panelReader = mockk<SkillLevelPanelReader>()
        val device = SkillLevelDevice(api,SkillLevelMode.Skill,loc,screens,mockk(relaxed=true),panelReader)
        init {
            every { api.locations.enhancementClick } returns enhance
            every { loc.dialogConfirm(SkillLevelMode.Skill) } returns confirm
            every { screens.band() } returns SkillLevelBand.Enhance
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
        verifyOrder { with(run.api) { run.enhance.click(1);SkillLevelTiming.AFTER_ENHANCE.wait() };run.screens.dialogVisible() }
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
}
