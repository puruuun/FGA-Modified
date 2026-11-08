package io.github.fate_grand_automata.widget

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.WidgetTheme

/** What the widget settings screen stores. Widgets read it straight from the preference file. */
data class WidgetSettings(
    val theme: WidgetTheme = WidgetTheme.Dark,
    /** 0 (opaque, the original look) to 100, in steps of [TRANSPARENCY_STEP]. */
    val transparencyPercent: Int = 0,
) {
    companion object {
        const val TRANSPARENCY_STEP = 10
    }
}

/** Same lightweight read as [captureEnabled]: no Hilt, so a widget session can call it anywhere. */
fun loadWidgetSettings(context: Context): WidgetSettings =
    readWidgetSettings(context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE))

internal fun readWidgetSettings(prefs: SharedPreferences): WidgetSettings {
    val maker = PrefMaker(prefs)
    return WidgetSettings(
        theme = maker.enum("widget_theme", WidgetTheme.Dark).get(),
        transparencyPercent = normalizeTransparency(maker.int("widget_background_transparency", 0).get()),
    )
}

/** Snaps any stored value to the 0..100 range in steps of 10. */
fun normalizeTransparency(percent: Int): Int =
    (percent.coerceIn(0, 100) + WidgetSettings.TRANSPARENCY_STEP / 2) / WidgetSettings.TRANSPARENCY_STEP * WidgetSettings.TRANSPARENCY_STEP

/** Alpha of the card background: 0 % see-through is 255, 50 % is 128, 100 % is 0. */
fun cardAlpha(transparencyPercent: Int): Int =
    (255 * (100 - normalizeTransparency(transparencyPercent)) + 50) / 100

/** Colours and drawables of one theme. Only the card is see-through; every other part stays opaque. */
data class WidgetPalette(
    @param:ColorRes val onSurface: Int,
    @param:ColorRes val secondary: Int,
    @param:ColorRes val fullAt: Int,
    @param:ColorRes val accent: Int,
    @param:ColorRes val on: Int,
    @param:DrawableRes val tileBackground: Int,
    @param:DrawableRes val toggleCircle: Int,
    @param:DrawableRes val toggleCircleOn: Int,
    /** Opaque card of one stack item, so the cards behind the top one stay hidden. */
    @param:DrawableRes val stackCard: Int,
    val light: Boolean,
)

fun widgetPalette(theme: WidgetTheme): WidgetPalette = when (theme) {
    WidgetTheme.Dark -> WidgetPalette(
        onSurface = R.color.widget_on_surface,
        secondary = R.color.widget_secondary,
        fullAt = R.color.widget_full_at,
        accent = R.color.widget_accent,
        on = R.color.widget_on,
        tileBackground = R.drawable.widget_tile_background,
        toggleCircle = R.drawable.widget_toggle_circle,
        toggleCircleOn = R.drawable.widget_toggle_circle_on,
        stackCard = R.drawable.widget_stack_card_dark,
        light = false,
    )
    WidgetTheme.Light -> WidgetPalette(
        onSurface = R.color.widget_light_on_surface,
        secondary = R.color.widget_light_secondary,
        fullAt = R.color.widget_light_full_at,
        accent = R.color.widget_light_accent,
        on = R.color.widget_light_on,
        tileBackground = R.drawable.widget_tile_background_light,
        toggleCircle = R.drawable.widget_toggle_circle_light,
        toggleCircleOn = R.drawable.widget_toggle_circle_on_light,
        stackCard = R.drawable.widget_stack_card_light,
        light = true,
    )
}

private val darkCards = intArrayOf(
    R.drawable.widget_card_dark_0, R.drawable.widget_card_dark_1, R.drawable.widget_card_dark_2, R.drawable.widget_card_dark_3,
    R.drawable.widget_card_dark_4, R.drawable.widget_card_dark_5, R.drawable.widget_card_dark_6, R.drawable.widget_card_dark_7,
    R.drawable.widget_card_dark_8, R.drawable.widget_card_dark_9, R.drawable.widget_card_dark_10,
)
private val lightCards = intArrayOf(
    R.drawable.widget_card_light_0, R.drawable.widget_card_light_1, R.drawable.widget_card_light_2, R.drawable.widget_card_light_3,
    R.drawable.widget_card_light_4, R.drawable.widget_card_light_5, R.drawable.widget_card_light_6, R.drawable.widget_card_light_7,
    R.drawable.widget_card_light_8, R.drawable.widget_card_light_9, R.drawable.widget_card_light_10,
)
private val darkTileCards = intArrayOf(
    R.drawable.widget_tile_card_dark_0, R.drawable.widget_tile_card_dark_1, R.drawable.widget_tile_card_dark_2, R.drawable.widget_tile_card_dark_3,
    R.drawable.widget_tile_card_dark_4, R.drawable.widget_tile_card_dark_5, R.drawable.widget_tile_card_dark_6, R.drawable.widget_tile_card_dark_7,
    R.drawable.widget_tile_card_dark_8, R.drawable.widget_tile_card_dark_9, R.drawable.widget_tile_card_dark_10,
)
private val lightTileCards = intArrayOf(
    R.drawable.widget_tile_card_light_0, R.drawable.widget_tile_card_light_1, R.drawable.widget_tile_card_light_2, R.drawable.widget_tile_card_light_3,
    R.drawable.widget_tile_card_light_4, R.drawable.widget_tile_card_light_5, R.drawable.widget_tile_card_light_6, R.drawable.widget_tile_card_light_7,
    R.drawable.widget_tile_card_light_8, R.drawable.widget_tile_card_light_9, R.drawable.widget_tile_card_light_10,
)

/**
 * The card background for a theme and see-through level. One shape drawable per step, so the corners
 * stay round on Android 10 and 11 too: the `cornerRadius` modifier is API 31+.
 */
@DrawableRes
fun widgetCardRes(settings: WidgetSettings): Int {
    val step = normalizeTransparency(settings.transparencyPercent) / WidgetSettings.TRANSPARENCY_STEP
    return (if (settings.theme == WidgetTheme.Light) lightCards else darkCards)[step]
}

/** The 1x1 widget is only the start/stop tile, so there the tile itself is the card. */
@DrawableRes
fun widgetTileCardRes(settings: WidgetSettings): Int {
    val step = normalizeTransparency(settings.transparencyPercent) / WidgetSettings.TRANSPARENCY_STEP
    return (if (settings.theme == WidgetTheme.Light) lightTileCards else darkTileCards)[step]
}
