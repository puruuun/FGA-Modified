package io.github.fate_grand_automata

import io.github.fate_grand_automata.util.readerDiagnosticsOffered
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderDiagnosticsAvailabilityTest {
    @Test fun `only the beta and the Rust test APK offer reader diagnostics`() {
        assertTrue(readerDiagnosticsOffered("beta"))
        assertTrue(readerDiagnosticsOffered("rust"))
        for (type in listOf("debug", "ci", "release", "deviceTest")) assertFalse(readerDiagnosticsOffered(type), type)
    }

    @Test fun `this build is ci and does not offer them`() {
        assertEquals("ci", BuildConfig.BUILD_TYPE)
        assertFalse(readerDiagnosticsOffered())
    }
}
