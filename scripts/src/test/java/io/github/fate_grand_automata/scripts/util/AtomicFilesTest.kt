package io.github.fate_grand_automata.scripts.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtomicFilesTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun createsTargetWhenMissing() {
        val temp = File(dir, "data.tmp").apply { writeText("fresh") }
        val target = File(dir, "data.json")

        replaceFile(temp, target)

        assertTrue(target.exists())
        assertEquals("fresh", target.readText())
        assertFalse(temp.exists())
    }

    @Test
    fun replacesExistingTargetContent() {
        val target = File(dir, "data.json").apply { writeText("old") }
        val temp = File(dir, "data.tmp").apply { writeText("new") }

        replaceFile(temp, target)

        assertEquals("new", target.readText())
        assertFalse(temp.exists())
        assertEquals(listOf("data.json"), dir.list()?.toList())
    }

    @Test
    fun throwsIOExceptionWhenParentMissing() {
        val temp = File(dir, "data.tmp").apply { writeText("fresh") }
        val target = File(dir, "missing/data.json")

        val ex = assertThrows<IOException> {
            replaceFile(temp, target)
        }
        assertTrue(ex.message.orEmpty().contains(temp.path))
        assertTrue(ex.message.orEmpty().contains(target.path))
    }
}
