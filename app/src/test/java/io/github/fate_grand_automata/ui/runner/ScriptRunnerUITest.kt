package io.github.fate_grand_automata.ui.runner

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class ScriptRunnerUITest {
    @Test
    fun baseColorUsesCollectingColorWhenCollecting() {
        val surface = Color(0xFF1C1B1F)
        assertEquals(scriptButtonCollectingColor, scriptButtonBaseColor(collecting = true, surface = surface))
        assertEquals(Color(0xFF7A4012), scriptButtonBaseColor(collecting = true, surface = surface))
    }

    @Test
    fun baseColorUsesSurfaceWhenNotCollecting() {
        val surface = Color(0xFF1C1B1F)
        assertEquals(surface, scriptButtonBaseColor(collecting = false, surface = surface))
    }
}
