package io.github.fate_grand_automata.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * Palette "F": neutral grey surfaces and text, navy only on interactive elements, gold only for
 * "maxed / complete" highlights, red only for warnings and errors. Every role is set, including
 * the surface container levels, because Material fills an unset role from its purple baseline.
 */
val md_theme_light_primary = Color(0xFF3755A3)
val md_theme_light_onPrimary = Color(0xFFFFFFFF)
val md_theme_light_primaryContainer = Color(0xFFDAE2FF)
val md_theme_light_onPrimaryContainer = Color(0xFF0F1F4F)
val md_theme_light_secondary = Color(0xFF5D5E66)
val md_theme_light_onSecondary = Color(0xFFFFFFFF)
val md_theme_light_secondaryContainer = Color(0xFFE4E4E8)
val md_theme_light_onSecondaryContainer = Color(0xFF1A1B1F)
val md_theme_light_tertiary = Color(0xFF7A5900)
val md_theme_light_onTertiary = Color(0xFFFFFFFF)
val md_theme_light_tertiaryContainer = Color(0xFFFFDEA6)
val md_theme_light_onTertiaryContainer = Color(0xFF3D2C00)
val md_theme_light_error = Color(0xFFB3261E)
val md_theme_light_onError = Color(0xFFFFFFFF)
val md_theme_light_errorContainer = Color(0xFFF9DEDC)
val md_theme_light_onErrorContainer = Color(0xFF410E0B)
val md_theme_light_background = Color(0xFFFAFAFB)
val md_theme_light_onBackground = Color(0xFF1A1B1F)
val md_theme_light_surface = Color(0xFFFAFAFB)
val md_theme_light_onSurface = Color(0xFF1A1B1F)
val md_theme_light_surfaceVariant = Color(0xFFE4E4E8)
val md_theme_light_onSurfaceVariant = Color(0xFF5D5E66)
val md_theme_light_outline = Color(0xFF76777F)
val md_theme_light_outlineVariant = Color(0xFFC6C6CC)
val md_theme_light_inverseSurface = Color(0xFF2F3034)
val md_theme_light_inverseOnSurface = Color(0xFFF1F0F4)
val md_theme_light_inversePrimary = Color(0xFFB1C5FF)
val md_theme_light_surfaceTint = Color(0xFF5D5E66)
val md_theme_light_scrim = Color(0xFF000000)
val md_theme_light_surfaceDim = Color(0xFFDADADD)
val md_theme_light_surfaceBright = Color(0xFFFAFAFB)
val md_theme_light_surfaceContainerLowest = Color(0xFFFFFFFF)
val md_theme_light_surfaceContainerLow = Color(0xFFF6F6F8)
val md_theme_light_surfaceContainer = Color(0xFFF3F3F5)
val md_theme_light_surfaceContainerHigh = Color(0xFFF0F0F2)
val md_theme_light_surfaceContainerHighest = Color(0xFFEEEEF0)

val md_theme_dark_primary = Color(0xFFB1C5FF)
val md_theme_dark_onPrimary = Color(0xFF002B6F)
val md_theme_dark_primaryContainer = Color(0xFF2A3F7A)
val md_theme_dark_onPrimaryContainer = Color(0xFFDAE2FF)
val md_theme_dark_secondary = Color(0xFFA5A5AD)
val md_theme_dark_onSecondary = Color(0xFF1A1B1F)
val md_theme_dark_secondaryContainer = Color(0xFF2E2E33)
val md_theme_dark_onSecondaryContainer = Color(0xFFE4E4E7)
val md_theme_dark_tertiary = Color(0xFFF2BF48)
val md_theme_dark_onTertiary = Color(0xFF402D00)
val md_theme_dark_tertiaryContainer = Color(0xFF5C4200)
val md_theme_dark_onTertiaryContainer = Color(0xFFFFDEA6)
val md_theme_dark_error = Color(0xFFF2B8B5)
val md_theme_dark_onError = Color(0xFF601410)
val md_theme_dark_errorContainer = Color(0xFF8C1D18)
val md_theme_dark_onErrorContainer = Color(0xFFF9DEDC)
val md_theme_dark_background = Color(0xFF121214)
val md_theme_dark_onBackground = Color(0xFFE4E4E7)
val md_theme_dark_surface = Color(0xFF121214)
val md_theme_dark_onSurface = Color(0xFFE4E4E7)
val md_theme_dark_surfaceVariant = Color(0xFF2E2E33)
val md_theme_dark_onSurfaceVariant = Color(0xFFA5A5AD)
val md_theme_dark_outline = Color(0xFF8E8E96)
val md_theme_dark_outlineVariant = Color(0xFF3E3E44)
val md_theme_dark_inverseSurface = Color(0xFFE4E4E7)
val md_theme_dark_inverseOnSurface = Color(0xFF2F3034)
val md_theme_dark_inversePrimary = Color(0xFF3755A3)
val md_theme_dark_surfaceTint = Color(0xFFA5A5AD)
val md_theme_dark_scrim = Color(0xFF000000)
val md_theme_dark_surfaceDim = Color(0xFF121214)
val md_theme_dark_surfaceBright = Color(0xFF38383D)
val md_theme_dark_surfaceContainerLowest = Color(0xFF0D0D0F)
val md_theme_dark_surfaceContainerLow = Color(0xFF1A1A1D)
val md_theme_dark_surfaceContainer = Color(0xFF1C1C1F)
val md_theme_dark_surfaceContainerHigh = Color(0xFF222226)
val md_theme_dark_surfaceContainerHighest = Color(0xFF26262A)

// Set of Material typography styles to start with
val typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp
    )
)

internal val LightColors = lightColorScheme(
    primary = md_theme_light_primary,
    onPrimary = md_theme_light_onPrimary,
    primaryContainer = md_theme_light_primaryContainer,
    onPrimaryContainer = md_theme_light_onPrimaryContainer,
    secondary = md_theme_light_secondary,
    onSecondary = md_theme_light_onSecondary,
    secondaryContainer = md_theme_light_secondaryContainer,
    onSecondaryContainer = md_theme_light_onSecondaryContainer,
    tertiary = md_theme_light_tertiary,
    onTertiary = md_theme_light_onTertiary,
    tertiaryContainer = md_theme_light_tertiaryContainer,
    onTertiaryContainer = md_theme_light_onTertiaryContainer,
    error = md_theme_light_error,
    onError = md_theme_light_onError,
    errorContainer = md_theme_light_errorContainer,
    onErrorContainer = md_theme_light_onErrorContainer,
    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
    surfaceVariant = md_theme_light_surfaceVariant,
    onSurfaceVariant = md_theme_light_onSurfaceVariant,
    outline = md_theme_light_outline,
    outlineVariant = md_theme_light_outlineVariant,
    inverseSurface = md_theme_light_inverseSurface,
    inverseOnSurface = md_theme_light_inverseOnSurface,
    inversePrimary = md_theme_light_inversePrimary,
    surfaceTint = md_theme_light_surfaceTint,
    scrim = md_theme_light_scrim,
    surfaceDim = md_theme_light_surfaceDim,
    surfaceBright = md_theme_light_surfaceBright,
    surfaceContainerLowest = md_theme_light_surfaceContainerLowest,
    surfaceContainerLow = md_theme_light_surfaceContainerLow,
    surfaceContainer = md_theme_light_surfaceContainer,
    surfaceContainerHigh = md_theme_light_surfaceContainerHigh,
    surfaceContainerHighest = md_theme_light_surfaceContainerHighest,
)


internal val DarkColors = darkColorScheme(
    primary = md_theme_dark_primary,
    onPrimary = md_theme_dark_onPrimary,
    primaryContainer = md_theme_dark_primaryContainer,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer,
    secondary = md_theme_dark_secondary,
    onSecondary = md_theme_dark_onSecondary,
    secondaryContainer = md_theme_dark_secondaryContainer,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer,
    tertiary = md_theme_dark_tertiary,
    onTertiary = md_theme_dark_onTertiary,
    tertiaryContainer = md_theme_dark_tertiaryContainer,
    onTertiaryContainer = md_theme_dark_onTertiaryContainer,
    error = md_theme_dark_error,
    onError = md_theme_dark_onError,
    errorContainer = md_theme_dark_errorContainer,
    onErrorContainer = md_theme_dark_onErrorContainer,
    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    surfaceVariant = md_theme_dark_surfaceVariant,
    onSurfaceVariant = md_theme_dark_onSurfaceVariant,
    outline = md_theme_dark_outline,
    outlineVariant = md_theme_dark_outlineVariant,
    inverseSurface = md_theme_dark_inverseSurface,
    inverseOnSurface = md_theme_dark_inverseOnSurface,
    inversePrimary = md_theme_dark_inversePrimary,
    surfaceTint = md_theme_dark_surfaceTint,
    scrim = md_theme_dark_scrim,
    surfaceDim = md_theme_dark_surfaceDim,
    surfaceBright = md_theme_dark_surfaceBright,
    surfaceContainerLowest = md_theme_dark_surfaceContainerLowest,
    surfaceContainerLow = md_theme_dark_surfaceContainerLow,
    surfaceContainer = md_theme_dark_surfaceContainer,
    surfaceContainerHigh = md_theme_dark_surfaceContainerHigh,
    surfaceContainerHighest = md_theme_dark_surfaceContainerHighest,
)

@Composable
fun FGAListItemColors() = ListItemDefaults.colors(
    containerColor = MaterialTheme.colorScheme.surfaceVariant
)

/**
 * Sliders default their inactive track to `secondaryContainer`, which is the same grey as the
 * `surfaceVariant` cards they sit on, so the empty half of the track would vanish. The thumb and
 * active track already default to `primary`.
 */
@Composable
fun FGASliderColors() = SliderDefaults.colors(
    inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
)

@Composable
fun FGATheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    background: Color = Color.Unspecified,
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) {
        DarkColors
    } else {
        LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = typography
    ) {
        Surface(
            color = if (background == Color.Unspecified) MaterialTheme.colorScheme.background else background
        ) {
            PreventRtl {
                content()
            }
        }
    }
}

@Composable
fun FgaScreen(
    content: @Composable BoxScope.() -> Unit
) {
    FGATheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            content()
        }
    }
}