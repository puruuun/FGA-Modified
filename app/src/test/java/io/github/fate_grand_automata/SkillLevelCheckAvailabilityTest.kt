package io.github.fate_grand_automata

import io.github.fate_grand_automata.ui.launcher.ScriptLauncherResponse
import io.github.fate_grand_automata.util.skillLevelCheckAvailable
import kotlin.test.*

class SkillLevelCheckAvailabilityTest {
    @Test fun `only beta and deviceTest can enable check mode`() {
        assertTrue(skillLevelCheckAvailable("beta"))
        assertTrue(skillLevelCheckAvailable("deviceTest"))
        for (type in listOf("stable", "ci", "debug", "release")) assertFalse(skillLevelCheckAvailable(type))
    }

    @Test fun `launcher response defaults to enhancement mode`() {
        assertFalse(ScriptLauncherResponse.SkillLevel(listOf(0, 0, 0)).checkOnly)
        assertFalse(ScriptLauncherResponse.AppendLevel(List(5) { 0 }).checkOnly)
    }

    @Test fun `CI uses the same disabled gate as stable`() {
        assertFalse(skillLevelCheckAvailable()) // CI unit tests use the ci build.
    }
}
