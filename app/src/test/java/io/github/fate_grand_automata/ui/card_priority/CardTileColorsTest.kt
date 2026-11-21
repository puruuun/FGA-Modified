package io.github.fate_grand_automata.ui.card_priority

import androidx.compose.ui.graphics.Color
import io.github.fate_grand_automata.scripts.enums.CardAffinityEnum
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.ui.md_theme_dark_surfaceContainer
import io.github.fate_grand_automata.ui.md_theme_light_surfaceContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardTileColorsTest {
    private val types = listOf(CardTypeEnum.Buster, CardTypeEnum.Arts, CardTypeEnum.Quick)

    private fun channel(c: Float): Double = if (c <= 0.04045f) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)

    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun panel(dark: Boolean) = if (dark) md_theme_dark_surfaceContainer else md_theme_light_surfaceContainer

    @Test
    fun `every label reaches 4_5 to 1 against its tile in both themes`() {
        for (dark in listOf(false, true)) {
            for (type in types) {
                for (affinity in CardAffinityEnum.entries) {
                    val colors = cardTileColors(type, affinity, panel(dark), dark)!!
                    val ratio = contrast(colors.content, colors.background)
                    assertTrue("$type $affinity dark=$dark ratio=$ratio", ratio >= 4.5)
                }
            }
        }
    }

    @Test
    fun `weak is the full hue and normal and resist are mixed over the panel`() {
        for (dark in listOf(false, true)) {
            val hue = cardTileHue(CardTypeEnum.Arts, dark)!!
            val weak = cardTileColors(CardTypeEnum.Arts, CardAffinityEnum.Weak, panel(dark), dark)!!
            val normal = cardTileColors(CardTypeEnum.Arts, CardAffinityEnum.Normal, panel(dark), dark)!!
            val resist = cardTileColors(CardTypeEnum.Arts, CardAffinityEnum.Resist, panel(dark), dark)!!

            assertEquals(hue.full, weak.background)
            // A larger share of the hue sits further from the panel colour.
            assertTrue(contrast(weak.background, panel(dark)) > contrast(normal.background, panel(dark)))
            assertTrue(contrast(normal.background, panel(dark)) > contrast(resist.background, panel(dark)))
            assertEquals(Color.White, weak.content)
            assertEquals(Color.White, normal.content)
            assertEquals(hue.resistText, resist.content)
        }
    }

    @Test
    fun `an unknown card type has no hue`() {
        assertNull(cardTileColors(CardTypeEnum.Unknown, CardAffinityEnum.Normal, panel(false), false))
    }
}
