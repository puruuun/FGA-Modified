package io.github.fate_grand_automata.util

import android.util.Log
import kotlin.test.*

class BondLogPolicyTest {
    @Test fun `bond evidence is emitted only in beta at INFO or higher`() {
        for (debug in listOf(false, true)) {
            assertFalse(isFgaLoggable(BOND_TAG, Log.INFO, debug, beta = false))
            assertFalse(isFgaLoggable(BOND_TAG, Log.WARN, debug, beta = false))
            assertFalse(isFgaLoggable(BOND_TAG, Log.DEBUG, debug, beta = true))
            assertTrue(isFgaLoggable(BOND_TAG, Log.INFO, debug, beta = true))
        }
    }
}
