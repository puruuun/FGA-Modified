package io.github.fate_grand_automata.util

import androidx.appcompat.app.AppCompatDelegate
import io.github.fate_grand_automata.prefs.core.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals

class AppThemeTest {
    @Test
    fun `each AppTheme maps to its AppCompatDelegate night mode`() {
        val expected = mapOf(
            AppTheme.System to AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppTheme.Light to AppCompatDelegate.MODE_NIGHT_NO,
            AppTheme.Dark to AppCompatDelegate.MODE_NIGHT_YES,
        )
        assertEquals(AppTheme.entries.toSet(), expected.keys)
        for ((theme, mode) in expected) {
            assertEquals(mode, theme.nightMode(), theme.name)
        }
    }
}
