package io.github.fate_grand_automata

import android.util.Log
import io.github.fate_grand_automata.util.*
import kotlin.test.*

class SkillLevelLogPolicyTest {
    @Test fun `skill level info is loggable in the non debug CI and beta policy`() {
        assertFalse(BuildConfig.DEBUG)
        assertTrue(isFgaLoggable(SKILL_LEVEL_TAG,Log.INFO,debug=false))
        assertFalse(isFgaLoggable(SKILL_LEVEL_TAG,Log.DEBUG,debug=false))
    }
    @Test fun `the existing tag allowlist and warning policy remain unchanged`() {
        assertTrue(isFgaLoggable(FP_SELL_TAG,Log.INFO,debug=false))
        assertTrue(isFgaLoggable("FGA.SupportScore",Log.INFO,debug=false))
        assertFalse(isFgaLoggable("Other",Log.INFO,debug=false))
        assertFalse(isFgaLoggable(null,Log.INFO,debug=false))
        assertTrue(isFgaLoggable("Other",Log.WARN,debug=false))
    }
    @Test fun `debug builds retain all logs`() {
        assertTrue(isFgaLoggable("Other",Log.DEBUG,debug=true))
    }
}
