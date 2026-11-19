package io.github.fate_grand_automata.sensing

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OcrModelInstallerTest {
    private val dir: File = Files.createTempDirectory("ocr-model").toFile()
    private val model = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val pin = OcrModelPin("m.onnx", sha256(model), model.size.toLong())
    private var opened = 0

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun asset(bytes: ByteArray = model) = { opened++; ByteArrayInputStream(bytes) }

    @AfterTest fun cleanUp() { dir.deleteRecursively() }

    @Test fun `copies once under a name holding the hash and reuses it`() {
        val file = OcrModelInstaller.install(pin, dir, asset())
        assertEquals("${pin.sha256}-m.onnx", file.name)
        assertContentEquals(model, file.readBytes())
        assertEquals(file, OcrModelInstaller.install(pin, dir, asset()))
        assertEquals(1, opened)
        assertEquals(listOf(file.name), dir.list()!!.toList())
    }

    @Test fun `a truncated copy is replaced`() {
        val file = File(dir, OcrModelInstaller.fileName(pin)).apply { writeBytes(model.copyOf(1000)) }
        OcrModelInstaller.install(pin, dir, asset())
        assertEquals(1, opened)
        assertContentEquals(model, file.readBytes())
    }

    @Test fun `stale copies and part files are removed`() {
        File(dir, "0000-old.onnx").writeBytes(byteArrayOf(1))
        File(dir, OcrModelInstaller.fileName(pin) + OcrModelInstaller.PART_SUFFIX).writeBytes(byteArrayOf(2))
        val file = OcrModelInstaller.install(pin, dir, asset())
        assertEquals(listOf(file.name), dir.list()!!.toList())
    }

    @Test fun `an asset with another hash leaves no file`() {
        val other = model.copyOf().also { it[5] = (it[5] + 1).toByte() }
        val error = assertFailsWith<IOException> { OcrModelInstaller.install(pin, dir, asset(other)) }
        assertTrue(error.message!!.contains("SHA-256"))
        assertEquals(emptyList(), dir.list()!!.toList())
    }

    @Test fun `a short or long asset leaves no file`() {
        assertFailsWith<IOException> { OcrModelInstaller.install(pin, dir, asset(model.copyOf(10))) }
        assertEquals(emptyList(), dir.list()!!.toList())
        assertFailsWith<IOException> { OcrModelInstaller.install(pin, dir, asset(model + 1)) }
        assertEquals(emptyList(), dir.list()!!.toList())
    }

    @Test fun `the pin file parses and refuses a bad one`() {
        val text = File(System.getProperty("fga.ocrModelPin")!!).readText()
        val parsed = OcrModelPin.parse(text.byteInputStream())
        assertEquals(OcrModelPin("PP-OCRv6_rec_small.onnx", "6f327246b50388f3c176ae304bd95767ea6dc0c9ae92153ef8cbe210b3c14884", 21234383), parsed)
        for (bad in listOf("file=a.onnx\nsha256=xyz\nsize=1", "file=../a.onnx\nsha256=${pin.sha256}\nsize=1", "file=a.onnx\nsha256=${pin.sha256}\nsize=0", "sha256=${pin.sha256}\nsize=1")) {
            assertFailsWith<IOException>(bad) { OcrModelPin.parse(bad.byteInputStream()) }
        }
    }
}
