package io.github.fate_grand_automata.ui.card_priority

import androidx.compose.ui.graphics.Color
import io.github.fate_grand_automata.scripts.enums.CardAffinityEnum
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum

/**
 * One hue per card type, in the two themes. [full] is the weak tile as it is; the normal and
 * resist tiles are [full] mixed over the panel, so the three steps stay one family in either theme.
 * [resistText] is the label on the resist tile: the dark shade of the hue on the light theme and a
 * light tint on the dark theme, where the mix over a dark panel is itself dark.
 */
internal data class CardTileHue(val full: Color, val resistText: Color)

internal data class CardTileColors(val background: Color, val content: Color)

/*
 * The light hues are darker than the usual card colours because the 72% mix over a near-white panel
 * lightens them, and white text on the normal tile still has to reach 4.5:1 (WCAG AA).
 * CardTileColorsTest checks every tile against its label.
 */
internal val LightBusterHue = CardTileHue(Color(0xFFA21B1B), Color(0xFF5E0B0B))
internal val LightArtsHue = CardTileHue(Color(0xFF0B3F91), Color(0xFF072B66))
internal val LightQuickHue = CardTileHue(Color(0xFF14521A), Color(0xFF0A300E))
internal val DarkBusterHue = CardTileHue(Color(0xFFC62828), Color(0xFFFFD6D2))
internal val DarkArtsHue = CardTileHue(Color(0xFF1565C0), Color(0xFFD2E6FF))
internal val DarkQuickHue = CardTileHue(Color(0xFF2E7D32), Color(0xFFD4F2D6))

internal const val NormalTileMix = 0.72f
internal const val ResistTileMix = 0.45f

internal fun cardTileHue(type: CardTypeEnum, dark: Boolean): CardTileHue? = when (type) {
    CardTypeEnum.Buster -> if (dark) DarkBusterHue else LightBusterHue
    CardTypeEnum.Arts -> if (dark) DarkArtsHue else LightArtsHue
    CardTypeEnum.Quick -> if (dark) DarkQuickHue else LightQuickHue
    CardTypeEnum.Unknown -> null
}

/** Colours of a tile on [panel]; null for [CardTypeEnum.Unknown], which has no hue. */
internal fun cardTileColors(
    type: CardTypeEnum,
    affinity: CardAffinityEnum,
    panel: Color,
    dark: Boolean
): CardTileColors? {
    val hue = cardTileHue(type, dark) ?: return null

    return when (affinity) {
        CardAffinityEnum.Weak -> CardTileColors(hue.full, Color.White)
        CardAffinityEnum.Normal -> CardTileColors(mixOver(hue.full, panel, NormalTileMix), Color.White)
        CardAffinityEnum.Resist -> CardTileColors(mixOver(hue.full, panel, ResistTileMix), hue.resistText)
    }
}

/**
 * [hue] at [share] opacity over [panel], per sRGB channel like a translucent layer. Compose's `lerp`
 * mixes in Oklab and lands on different colours than the ones the contrast check was sized for.
 */
private fun mixOver(hue: Color, panel: Color, share: Float) = Color(
    red = hue.red * share + panel.red * (1 - share),
    green = hue.green * share + panel.green * (1 - share),
    blue = hue.blue * share + panel.blue * (1 - share)
)
