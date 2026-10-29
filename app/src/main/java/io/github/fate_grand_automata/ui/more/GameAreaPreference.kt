package io.github.fate_grand_automata.ui.more

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.accessibility.TapperService
import io.github.fate_grand_automata.prefs.core.GameAreaMode
import io.github.fate_grand_automata.prefs.core.Pref
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.ui.Stepper
import io.github.fate_grand_automata.ui.icon
import io.github.fate_grand_automata.ui.prefs.ListPreference
import io.github.fate_grand_automata.ui.prefs.Preference
import io.github.fate_grand_automata.ui.prefs.remember
import io.github.fate_grand_automata.util.stringRes

/** Owned by the screen, outside the lazy list: scrolling an item away must not orphan a window. */
@Composable
fun GameAreaPreviewEffect(prefs: PrefsCore, preview: GameAreaPreviewManager) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mode by prefs.gameAreaMode.remember()
    val left by prefs.gameOffsetLeft.remember()
    val top by prefs.gameOffsetTop.remember()
    val right by prefs.gameOffsetRight.remember()
    val bottom by prefs.gameOffsetBottom.remember()

    DisposableEffect(preview, lifecycle) {
        preview.hide()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) preview.hide()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            preview.hide()
        }
    }
    LaunchedEffect(mode, left, top, right, bottom) {
        if (mode != GameAreaMode.Custom) preview.hide() else preview.update()
    }
}

@Composable
fun GameAreaPreference(prefs: PrefsCore, preview: GameAreaPreviewManager) {
    val mode by prefs.gameAreaMode.remember()
    val serviceStarted by TapperService.serviceStarted
    val enabled = serviceStarted && !preview.scriptActive
    val screenSize = preview.screenSize

    Column {
        prefs.gameAreaMode.ListPreference(
            title = stringResource(R.string.p_game_area),
            icon = icon(Icons.Default.Fullscreen),
            entries = GameAreaMode.entries.associateWith { stringResource(it.stringRes) }
        )
        AnimatedVisibility(mode == GameAreaMode.Custom) {
            Card(
                modifier = Modifier.padding(5.dp),
                elevation = CardDefaults.cardElevation(5.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Column(modifier = Modifier.scale(0.9f)) {
                    prefs.gameOffsetLeft.customOffset(stringResource(R.string.p_game_area_custom_left), screenSize.width)
                    prefs.gameOffsetRight.customOffset(stringResource(R.string.p_game_area_custom_right), screenSize.width)
                    prefs.gameOffsetTop.customOffset(stringResource(R.string.p_game_area_custom_top), screenSize.height)
                    prefs.gameOffsetBottom.customOffset(stringResource(R.string.p_game_area_custom_bottom), screenSize.height)
                    Preference(
                        title = stringResource(R.string.p_game_area_preview),
                        summary = stringResource(R.string.p_game_area_preview_summary),
                        enabled = enabled,
                        onClick = { if (preview.visible) preview.hide() else preview.show() }
                    ) {
                        Switch(
                            checked = preview.visible,
                            enabled = enabled,
                            onCheckedChange = { if (it) preview.show() else preview.hide() }
                        )
                    }
                    if (!serviceStarted) {
                        Text(
                            stringResource(R.string.p_game_area_preview_disabled),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Pref<Int>.customOffset(text: String, screenDimension: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        var value by remember()
        Text(text)
        Stepper(
            value = value,
            onValueChange = { value = it },
            onValueChanging = { value = it },
            valueRange = 0 until screenDimension
        )
    }
}
