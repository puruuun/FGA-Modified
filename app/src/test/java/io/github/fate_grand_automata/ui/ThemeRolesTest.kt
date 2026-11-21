package io.github.fate_grand_automata.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeRolesTest {
    private fun roles(s: ColorScheme): Map<String, Color> = mapOf(
        "primary" to s.primary, "onPrimary" to s.onPrimary,
        "primaryContainer" to s.primaryContainer, "onPrimaryContainer" to s.onPrimaryContainer,
        "inversePrimary" to s.inversePrimary,
        "secondary" to s.secondary, "onSecondary" to s.onSecondary,
        "secondaryContainer" to s.secondaryContainer, "onSecondaryContainer" to s.onSecondaryContainer,
        "tertiary" to s.tertiary, "onTertiary" to s.onTertiary,
        "tertiaryContainer" to s.tertiaryContainer, "onTertiaryContainer" to s.onTertiaryContainer,
        "error" to s.error, "onError" to s.onError,
        "errorContainer" to s.errorContainer, "onErrorContainer" to s.onErrorContainer,
        "background" to s.background, "onBackground" to s.onBackground,
        "surface" to s.surface, "onSurface" to s.onSurface,
        "surfaceVariant" to s.surfaceVariant, "onSurfaceVariant" to s.onSurfaceVariant,
        "surfaceTint" to s.surfaceTint,
        "inverseSurface" to s.inverseSurface, "inverseOnSurface" to s.inverseOnSurface,
        "outline" to s.outline, "outlineVariant" to s.outlineVariant, "scrim" to s.scrim,
        "surfaceBright" to s.surfaceBright, "surfaceDim" to s.surfaceDim,
        "surfaceContainerLowest" to s.surfaceContainerLowest, "surfaceContainerLow" to s.surfaceContainerLow,
        "surfaceContainer" to s.surfaceContainer, "surfaceContainerHigh" to s.surfaceContainerHigh,
        "surfaceContainerHighest" to s.surfaceContainerHighest,
    )

    /**
     * Roles whose palette value is the same as Material's baseline on purpose: pure black and white
     * carry no hue, and the owner's red for errors is Material's own red. The expected values pin them.
     */
    private val sameAsBaselineOnPurpose = mapOf(
        "light" to mapOf(
            "onPrimary" to Color.White, "onSecondary" to Color.White, "onTertiary" to Color.White,
            "onError" to Color.White, "scrim" to Color.Black, "surfaceContainerLowest" to Color.White,
            "error" to Color(0xFFB3261E), "errorContainer" to Color(0xFFF9DEDC),
            "onErrorContainer" to Color(0xFF410E0B),
        ),
        "dark" to mapOf(
            "scrim" to Color.Black,
            "error" to Color(0xFFF2B8B5), "onError" to Color(0xFF601410),
            "errorContainer" to Color(0xFF8C1D18), "onErrorContainer" to Color(0xFFF9DEDC),
        ),
    )

    private fun check(name: String, scheme: ColorScheme, baseline: ColorScheme) {
        val allowed = sameAsBaselineOnPurpose.getValue(name)
        val base = roles(baseline)
        val fallbacks = mutableListOf<String>()
        roles(scheme).forEach { (role, value) ->
            if (role in allowed) {
                assertEquals("$name $role", allowed.getValue(role), value)
            } else if (value == base.getValue(role)) {
                fallbacks += role
            }
        }
        assertTrue("$name roles still at Material's baseline value: $fallbacks", fallbacks.isEmpty())
    }

    @Test fun lightSchemeSetsEveryRole() = check("light", LightColors, lightColorScheme())

    @Test fun darkSchemeSetsEveryRole() = check("dark", DarkColors, darkColorScheme())
}
