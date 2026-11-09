package io.github.fate_grand_automata.ui.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.prefs.core.WidgetTheme
import io.github.fate_grand_automata.ui.CappedWidth
import io.github.fate_grand_automata.ui.FGASliderColors
import io.github.fate_grand_automata.ui.Heading
import io.github.fate_grand_automata.ui.prefs.ListPreference
import io.github.fate_grand_automata.ui.prefs.Preference
import io.github.fate_grand_automata.ui.prefs.SwitchPreference
import io.github.fate_grand_automata.ui.prefs.remember
import io.github.fate_grand_automata.widget.WidgetSettings
import io.github.fate_grand_automata.widget.normalizeTransparency

/** Theme and card opacity of the home-screen widgets. Both widgets redraw as soon as a value changes. */
@Composable
fun WidgetSettingsScreen(prefs: PrefsCore) {
    CappedWidth {
        Column(Modifier.fillMaxSize()) {
            Heading(stringResource(R.string.widget_settings))
            prefs.widgetShowLogButton.SwitchPreference(
                title = stringResource(R.string.widget_settings_show_log_button),
            )
            prefs.widgetTheme.ListPreference(
                title = stringResource(R.string.widget_settings_theme),
                entries = mapOf(
                    WidgetTheme.Dark to stringResource(R.string.widget_settings_theme_dark),
                    WidgetTheme.Light to stringResource(R.string.widget_settings_theme_light),
                ),
            )
            var transparency by prefs.widgetBackgroundTransparency.remember()
            Preference(
                title = { Text(stringResource(R.string.widget_settings_transparency)) },
                summary = {
                    Column {
                        Text("${normalizeTransparency(transparency)} %")
                        Slider(
                            value = normalizeTransparency(transparency).toFloat(),
                            onValueChange = { transparency = normalizeTransparency(it.toInt()) },
                            valueRange = 0f..100f,
                            steps = 100 / WidgetSettings.TRANSPARENCY_STEP - 1,
                            colors = FGASliderColors(),
                        )
                    }
                },
            )
        }
    }
}
