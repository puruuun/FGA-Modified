package io.github.fate_grand_automata.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
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
import io.github.fate_grand_automata.ui.dialog.FgaDialog
import io.github.fate_grand_automata.ui.icon
import io.github.fate_grand_automata.ui.prefs.Preference
import io.github.fate_grand_automata.ui.prefs.listDialog
import io.github.fate_grand_automata.ui.prefs.remember
import io.github.lib_automata.GameAreaOffsets

/** Owned by the screen, outside the lazy list: scrolling an item away must not apply settings. */
@Composable
fun GameAreaPreviewEffect(prefs: PrefsCore, preview: GameAreaPreviewManager) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mode by prefs.gameAreaMode.remember()
    val left by prefs.gameOffsetLeft.remember()
    val top by prefs.gameOffsetTop.remember()
    val right by prefs.gameOffsetRight.remember()
    val bottom by prefs.gameOffsetBottom.remember()
    val presetId by prefs.gameArea.presetId.remember()
    val presets by prefs.gameArea.presets.remember()

    DisposableEffect(preview, lifecycle) {
        preview.hide()
        preview.beginSettings()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> preview.beginSettings()
                Lifecycle.Event.ON_STOP -> preview.finishSettings()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            preview.finishSettings()
        }
    }
    LaunchedEffect(mode, left, top, right, bottom, presetId, presets) {
        if (mode == GameAreaMode.Default) preview.hide() else preview.update()
    }
}

@Composable
fun GameAreaPreference(prefs: PrefsCore, preview: GameAreaPreviewManager) {
    val settings = prefs.gameArea
    val mode by settings.mode.remember()
    val presetId by settings.presetId.remember()
    val presets by settings.presets.remember()
    val left by settings.left.remember()
    val top by settings.top.remember()
    val right by settings.right.remember()
    val bottom by settings.bottom.remember()
    val serviceStarted by TapperService.serviceStarted
    val editable = !preview.scriptActive
    val previewEnabled = serviceStarted && editable
    val screenSize = preview.screenSize
    val offsets = GameAreaOffsets(left, top, right, bottom)
    val maximums = offsets.maximums(screenSize)
    val preset = presets.firstOrNull { it.id == presetId }
    val entries = linkedMapOf(
        "Default" to stringResource(R.string.p_game_area_default),
        "Custom" to stringResource(R.string.p_game_area_custom)
    ).apply { presets.forEach { put(it.id, it.name) } }
    val selected = if (mode == GameAreaMode.Preset) presetId else mode.name
    val picker = listDialog(
        selected = selected,
        onSelectedChange = { choice ->
            when (choice) {
                "Default" -> settings.mode.set(GameAreaMode.Default)
                "Custom" -> {
                    settings.setCustomOffsets(settings.customOffsets().clamped(screenSize))
                    settings.mode.set(GameAreaMode.Custom)
                }
                else -> {
                    settings.presetId.set(choice)
                    settings.mode.set(GameAreaMode.Preset)
                }
            }
            preview.applyCaptureSettings()
        },
        entries = entries,
        title = stringResource(R.string.p_game_area)
    )
    // Normalize legacy offsets only while editing settings, never at script start or capture.
    LaunchedEffect(mode, screenSize) {
        if (mode == GameAreaMode.Custom && editable) {
            settings.setCustomOffsets(settings.customOffsets().clamped(screenSize))
        }
    }

    val addDialog = FgaDialog()
    var name by rememberSaveable { mutableStateOf("") }
    val defaultName = stringResource(R.string.p_game_area_preset_default_name, presets.size + 1)
    addDialog.build {
        title(stringResource(R.string.p_game_area_add))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.p_game_area_preset_name)) },
            singleLine = true,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        buttons(onSubmit = { settings.addPreset(name.trim().ifEmpty { defaultName }) })
    }

    Column {
        Preference(
            title = stringResource(R.string.p_game_area),
            summary = entries[selected] ?: stringResource(R.string.p_game_area_default),
            icon = icon(Icons.Default.Fullscreen),
            enabled = editable,
            onClick = { picker.show() }
        )
        if (mode != GameAreaMode.Default) {
            Card(
                modifier = Modifier.padding(5.dp),
                elevation = CardDefaults.cardElevation(5.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (mode == GameAreaMode.Custom) {
                        settings.left.customOffset(stringResource(R.string.p_game_area_custom_left), maximums.left, editable)
                        settings.right.customOffset(stringResource(R.string.p_game_area_custom_right), maximums.right, editable)
                        settings.top.customOffset(stringResource(R.string.p_game_area_custom_top), maximums.top, editable)
                        settings.bottom.customOffset(stringResource(R.string.p_game_area_custom_bottom), maximums.bottom, editable)
                        Text(stringResource(R.string.p_game_area_minimum), style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(enabled = editable, onClick = {
                                settings.setCustomOffsets(GameAreaOffsets())
                                preview.applyCaptureSettings()
                            }) { Text(stringResource(R.string.p_game_area_reset)) }
                            TextButton(enabled = editable, onClick = {
                                name = defaultName
                                addDialog.show()
                            }) { Text(stringResource(R.string.p_game_area_add)) }
                        }
                        GameAreaCalibrationPreference(prefs, preview)
                    } else if (preset != null) {
                        Text(stringResource(R.string.p_game_area_preset_read_only), style = MaterialTheme.typography.bodySmall)
                        Text("${stringResource(R.string.p_game_area_custom_left)}: ${preset.left}")
                        Text("${stringResource(R.string.p_game_area_custom_right)}: ${preset.right}")
                        Text("${stringResource(R.string.p_game_area_custom_top)}: ${preset.top}")
                        Text("${stringResource(R.string.p_game_area_custom_bottom)}: ${preset.bottom}")
                        TextButton(enabled = editable, onClick = {
                            settings.deleteSelectedPreset()
                            preview.applyCaptureSettings()
                        }) { Text(stringResource(R.string.p_game_area_delete)) }
                    }
                    Preference(
                        title = stringResource(R.string.p_game_area_preview),
                        summary = stringResource(R.string.p_game_area_preview_summary),
                        enabled = previewEnabled,
                        onClick = { if (preview.visible) preview.hide() else preview.show() }
                    ) {
                        Switch(
                            checked = preview.visible,
                            enabled = previewEnabled,
                            onCheckedChange = { if (it) preview.show() else preview.hide() }
                        )
                    }
                    if (!serviceStarted) {
                        Text(stringResource(R.string.p_game_area_preview_disabled),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun Pref<Int>.customOffset(text: String, maximum: Int, enabled: Boolean) {
    var value by remember()
    val dialog = FgaDialog()
    var input by rememberSaveable { mutableStateOf("") }
    dialog.build {
        title(text)
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        buttons(onSubmit = {
            value = input.toBigIntegerOrNull()?.coerceIn(0.toBigInteger(), maximum.toBigInteger())?.toInt()
                ?: value.coerceIn(0, maximum)
        })
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text)
        Stepper(
            value = value,
            onValueChange = { value = it.coerceIn(0, maximum) },
            onValueChanging = { value = it.coerceIn(0, maximum) },
            valueRange = 0..maximum,
            enabled = enabled,
            onValueClick = { input = value.toString(); dialog.show() }
        )
    }
}
