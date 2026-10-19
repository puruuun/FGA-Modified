package io.github.fate_grand_automata.scripts

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
        val device = SkillLevelDevice(api,SkillLevelMode.Skill,loc,screens,mockk(relaxed=true))
        init {
            every { api.locations.enhancementClick } returns enhance
            every { loc.dialogConfirm(SkillLevelMode.Skill) } returns confirm
            every { screens.band() } returns SkillLevelBand.Enhance
            every { screens.dialogVisible() } returns true
            every { api.useSameSnapIn<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
        }
    }
    @Test fun `enhance waits half a second after the tap before the unchanged dialog search`() {
        val run=Run()
        run.device.pressEnhance()
        assertTrue(run.device.awaitDialog())
        assertEquals(0.5.seconds,SkillLevelTiming.AFTER_ENHANCE)
        assertEquals(2.seconds,SkillLevelTiming.FIND_POLL*SkillLevelTiming.DIALOG_POLLS)
        assertEquals(20.seconds,SkillLevelTiming.SKIP_INTERVAL*SkillLevelTiming.SKIP_POLLS)
        verifyOrder { with(run.api) { run.enhance.click(1);SkillLevelTiming.AFTER_ENHANCE.wait() };run.screens.dialogVisible() }
        verify(exactly=1) { with(run.api) { any<Duration>().wait(any()) } }
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
}
