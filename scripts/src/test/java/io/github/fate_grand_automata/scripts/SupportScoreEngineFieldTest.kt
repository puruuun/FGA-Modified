package io.github.fate_grand_automata.scripts

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.github.fate_grand_automata.scripts.supportSelection.EngineFieldRefusal
import io.github.fate_grand_automata.scripts.supportSelection.SupportBounds
import io.github.fate_grand_automata.scripts.supportSelection.SupportScoreEngineField
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.test.Test

class SupportScoreEngineFieldTest {
    private fun bounds(y: Int) = SupportBounds(Region(76, y, 2356, 428))

    private fun field(height: Int) = mockk<Pattern>().also {
        every { it.width } returns 60
        every { it.height } returns height
    }

    @Test
    fun `the engine is asked up to bounds y 1056 and not from 1058`() {
        for (y in listOf(200, 1048, 1050, 1054, 1056)) {
            assertThat(SupportScoreEngineField.refusal(bounds(y), field(30)), "$y").isNull()
        }
        for (y in listOf(1058, 1060, 1066, 1068, 1070)) {
            assertThat(SupportScoreEngineField.refusal(bounds(y), field(30)), "$y").isEqualTo(EngineFieldRefusal.BottomBand)
        }
    }

    @Test
    fun `a field of another size is refused for its size first, also under the band`() {
        for (y in listOf(408, 1072, 1100)) {
            assertThat(SupportScoreEngineField.refusal(bounds(y), field(22)), "$y").isEqualTo(EngineFieldRefusal.CropSize)
        }
    }

    @Test
    fun `the recognised rows end where the shared row trim says`() {
        // Set by scripts/build.gradle.kts, which also declares the file as an input of the test task.
        val path = checkNotNull(System.getProperty("fga.scoreRowTrim")) {
            "fga.scoreRowTrim is not set: run this test through Gradle (:scripts:test)"
        }
        val table = File(path).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.trim().split(' ') }
            .associate { (name, value) -> name to value.toInt() }
        assertThat(table.keys).isEqualTo(setOf("crop_height", "top", "bottom"))
        assertThat(SupportScoreEngineField.HEIGHT).isEqualTo(table.getValue("crop_height"))
        assertThat(SupportScoreEngineField.RECOGNISED_ROWS_END).isEqualTo(table.getValue("crop_height") - table.getValue("bottom"))
    }
}
