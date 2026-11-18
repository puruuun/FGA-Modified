package io.github.fate_grand_automata.prefs

import io.github.fate_grand_automata.prefs.core.MASTER_SKILL_MENU_DELAY_DEFAULT_MS
import io.github.fate_grand_automata.prefs.core.masterSkillMenuDelayOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class MasterSkillMenuDelayPrefsTest {
    @Test
    fun `default is 1500 ms and maps to a Duration`() {
        assertEquals(1500, MASTER_SKILL_MENU_DELAY_DEFAULT_MS)
        assertEquals(1500.milliseconds, masterSkillMenuDelayOf(MASTER_SKILL_MENU_DELAY_DEFAULT_MS))
        assertEquals(750.milliseconds, masterSkillMenuDelayOf(750))
    }

    @Test
    fun `stored values outside 300 to 3000 ms are clamped when read`() {
        assertEquals(300.milliseconds, masterSkillMenuDelayOf(300))
        assertEquals(3000.milliseconds, masterSkillMenuDelayOf(3000))
        assertEquals(300.milliseconds, masterSkillMenuDelayOf(0))
        assertEquals(300.milliseconds, masterSkillMenuDelayOf(-500))
        assertEquals(3000.milliseconds, masterSkillMenuDelayOf(60000))
    }
}
