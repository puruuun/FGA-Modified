package io.github.fate_grand_automata

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LicenseAssetTest {
    private val bundled = File(System.getProperty("fga.licenseAsset")!!)

    @Test fun `bundled license asset exists and holds both copyright lines`() {
        assertTrue(bundled.isFile, "LICENSE asset was not generated: $bundled")
        val text = bundled.readText()
        assertTrue("Copyright (c) 2017 Jerry Ho" in text)
        assertTrue("Copyright (c) 2026 puruuun" in text)
        assertTrue(text.startsWith("MIT License"))
    }

    @Test fun `bundled license is identical to the root LICENSE`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "LICENSE") }.first { it.isFile }
        assertEquals(root.readText(), bundled.readText())
    }
}
