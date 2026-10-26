package io.github.fate_grand_automata.prefs

import io.github.fate_grand_automata.scripts.modules.SkillLevelMode
import kotlin.test.Test
import kotlin.test.assertEquals

class SkillLevelPrefsTest {
    @Test fun `check flag is only an in memory handover and can be reset for the next run`() {
        val a = SkillLevelPrefs()
        try {
            a.checkOnly = true
            assertEquals(true, SkillLevelPrefs().checkOnly)
            SkillLevelPrefs().checkOnly = false
            assertEquals(false, a.checkOnly)
        } finally {
            a.checkOnly = false
        }
    }
    @Test
    fun `a fresh prefs object starts with every slot off and nothing is written to disk`() {
        // Only an in-memory hand-over exists (D-18); a different instance of the same process sees the same
        // values, a new process sees none.
        val a = SkillLevelPrefs()
        a.setTargets(SkillLevelMode.Skill, listOf(5, 0, 10))
        assertEquals(listOf(5, 0, 10), SkillLevelPrefs().targets(SkillLevelMode.Skill))
        assertEquals(List(5) { 0 }, SkillLevelPrefs().targets(SkillLevelMode.Append))
    }
}
