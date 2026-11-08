package io.github.fate_grand_automata.widget

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.WidgetTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class WidgetStyleTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun color(res: Int) = ContextCompat.getColor(context, res)
    private fun contrast(a: Int, b: Int) = ColorUtils.calculateContrast(a, b)

    @Test fun opacityMapsToCardAlphaAt0And50And100Percent() {
        assertEquals(255, cardAlpha(0))
        assertEquals(128, cardAlpha(50))
        assertEquals(0, cardAlpha(100))
    }

    @Test fun storedValuesSnapToTenPercentStepsInsideTheRange() {
        assertEquals(0, normalizeTransparency(-20))
        assertEquals(100, normalizeTransparency(140))
        assertEquals(30, normalizeTransparency(33))
        assertEquals(40, normalizeTransparency(35))
        assertEquals(60, normalizeTransparency(60))
    }

    @Test fun defaultsAreTheOriginalDarkOpaqueLook() {
        val prefs = context.getSharedPreferences("widget_style_defaults", Context.MODE_PRIVATE)
        assertEquals(WidgetSettings(WidgetTheme.Dark, 0), readWidgetSettings(prefs))
    }

    @Test fun storedSettingsAreReadBackAndGarbageFallsBack() {
        val prefs = context.getSharedPreferences("widget_style_stored", Context.MODE_PRIVATE)
        prefs.edit().putString("widget_theme", "Light").putInt("widget_background_transparency", 70).commit()
        assertEquals(WidgetSettings(WidgetTheme.Light, 70), readWidgetSettings(prefs))
        prefs.edit().putString("widget_theme", "Neon").putInt("widget_background_transparency", 999).commit()
        assertEquals(WidgetSettings(WidgetTheme.Dark, 100), readWidgetSettings(prefs))
    }

    @Test fun everyThemeAndStepHasItsOwnCardAndTileDrawable() {
        val cards = WidgetTheme.entries.flatMap { theme ->
            (0..100 step 10).map { widgetCardRes(WidgetSettings(theme, it)) }
        }
        assertEquals(22, cards.toSet().size)
        val tiles = WidgetTheme.entries.flatMap { theme ->
            (0..100 step 10).map { widgetTileCardRes(WidgetSettings(theme, it)) }
        }
        assertEquals(22, tiles.toSet().size)
    }

    @Test fun cardDrawableAlphaMatchesTheOpacityStep() {
        // The XML colours are written out per step; read the first gradient colour back.
        fun startAlpha(res: Int): Int {
            val xml = context.resources.getXml(res)
            while (xml.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "gradient") {
                    return android.graphics.Color.alpha(
                        xml.getAttributeUnsignedIntValue("http://schemas.android.com/apk/res/android", "startColor", 0),
                    )
                }
                xml.next()
            }
            error("no gradient")
        }
        for (theme in WidgetTheme.entries) for (percent in listOf(0, 50, 100)) {
            assertEquals(cardAlpha(percent), startAlpha(widgetCardRes(WidgetSettings(theme, percent))), "$theme $percent")
        }
    }

    @Test fun darkAndLightPalettesDiffer() {
        val dark = widgetPalette(WidgetTheme.Dark)
        val light = widgetPalette(WidgetTheme.Light)
        assertNotEquals(color(dark.onSurface), color(light.onSurface))
        assertNotEquals(dark.tileBackground, light.tileBackground)
        assertTrue(!dark.light && light.light)
    }

    @Test fun lightThemeTextIsReadableOnTheWhiteCard() {
        val p = widgetPalette(WidgetTheme.Light)
        val card = color(R.color.widget_light_surface)
        assertEquals(android.graphics.Color.WHITE, card)
        assertTrue(contrast(color(p.onSurface), card) >= 7.0)
        assertTrue(contrast(color(p.secondary), card) >= 4.5)
        // The full-at line uses a darker green; the dark theme's mint would be unreadable on white.
        assertTrue(contrast(color(p.fullAt), card) >= 4.5)
        assertTrue(contrast(color(widgetPalette(WidgetTheme.Dark).fullAt), card) < 3.0)
    }

    @Test fun darkThemeKeepsTheOriginalColours() {
        val p = widgetPalette(WidgetTheme.Dark)
        assertEquals(android.graphics.Color.parseColor("#F2F5FA"), color(p.onSurface))
        assertEquals(android.graphics.Color.parseColor("#5FE3B0"), color(p.fullAt))
        assertTrue(contrast(color(p.secondary), color(R.color.widget_surface)) >= 4.5)
    }

    @Test fun lightTileIconsAreVisibleOnTheirCircles() {
        val p = widgetPalette(WidgetTheme.Light)
        assertTrue(contrast(color(p.accent), color(R.color.widget_light_tile_circle)) >= 3.0)
        assertTrue(contrast(color(p.on), color(R.color.widget_light_tile_circle_on)) >= 3.0)
    }
}
