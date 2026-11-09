package io.github.fate_grand_automata.ui.log

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.GameNameMap
import io.github.fate_grand_automata.capture.CaptureLogItem
import io.github.fate_grand_automata.prefs.core.LogRetentionPrefsCore
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
internal fun timeFilterLabel(time: LogTimeFilter): String = stringResource(when (time) {
    LogTimeFilter.Today -> R.string.capture_log_today
    LogTimeFilter.Week -> R.string.capture_log_week
    LogTimeFilter.Month -> R.string.capture_log_month
    LogTimeFilter.All -> R.string.capture_log_all_time
    LogTimeFilter.Custom -> R.string.capture_log_custom_range
})

@Composable
internal fun resourceLabel(key: String, names: GameNameMap): String =
    resourceName(key.substringBefore(':').toInt(), key.substringAfter(':').toLong(), names)

internal fun customRangeLabel(start: Long?, end: Long?, locale: Locale = Locale.getDefault()): String {
    if (start == null || end == null) return ""
    val format = DateFormat.getDateInstance(DateFormat.SHORT, locale)
    return listOf(start, end).joinToString(" – ") { format.format(Date(pickerDateInZone(it, TimeZone.getDefault()))) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogFilterDialog(
    time: LogTimeFilter, resource: String?, start: Long?, end: Long?, retention: Int,
    battleCount: Int, outsideCount: Int, entries: List<CaptureLogItem>, names: GameNameMap, profile: String?,
    onDismiss: () -> Unit, onApply: (LogTimeFilter, String?, Long?, Long?, Int, String?) -> Unit,
) {
    var draftTime by rememberSaveable { mutableStateOf(time) }
    var draftResource by rememberSaveable { mutableStateOf(resource) }
    var draftProfile by rememberSaveable { mutableStateOf(profile) }
    var draftStart by rememberSaveable { mutableStateOf(start) }
    var draftEnd by rememberSaveable { mutableStateOf(end) }
    var draftRetention by rememberSaveable { mutableStateOf(retention) }
    var query by rememberSaveable { mutableStateOf("") }
    var picker by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val profiles = logProfiles(entries)
    val activeProfile = availableLogProfile(draftProfile, profiles)
    val resources = logResources(filterCaptureLogs(entries, draftTime, null, System.currentTimeMillis(),
        customStart = draftStart, customEnd = draftEnd, profile = activeProfile))
    val apply = { onApply(draftTime, draftResource, draftStart, draftEnd, draftRetention, activeProfile) }
    val screen = LocalConfiguration.current
    AlertDialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.width(minOf(screen.screenWidthDp * 0.9f, 900f).dp)
            .heightIn(max = (screen.screenHeightDp * 0.9f).dp).testTag("log-filter-dialog"),
        title = { Text(stringResource(R.string.capture_log_filter)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.capture_log_date), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LogTimeFilter.entries.forEach { value ->
                        FilterChip(selected = draftTime == value, onClick = {
                            if (value == LogTimeFilter.Custom) picker = true else draftTime = value
                        }, modifier = Modifier.testTag("log-filter-time:$value"), label = { Text(timeFilterLabel(value)) })
                    }
                }
                if (draftTime == LogTimeFilter.Custom) Text(customRangeLabel(draftStart, draftEnd), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.capture_log_profile), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = activeProfile == null, onClick = { draftProfile = null },
                        modifier = Modifier.testTag("log-filter-profile:all"),
                        label = { Text(stringResource(R.string.capture_log_all_profiles)) })
                    profiles.forEach { (id, name) ->
                        FilterChip(selected = activeProfile == id, onClick = { draftProfile = id },
                            modifier = Modifier.testTag("log-filter-profile:$id"),
                            label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
                Text(stringResource(R.string.capture_log_resource), style = MaterialTheme.typography.titleSmall)
                val labels = resources.map { it to resourceLabel(it, names) }.sortedBy { it.second }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("log-filter-search"), singleLine = true,
                    placeholder = { Text(stringResource(R.string.capture_log_resource_search)) })
                val shown = labels.filter { (key, label) -> key == draftResource || label.contains(query.trim(), ignoreCase = true) }
                Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).testTag("log-filter-resources")) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(selected = draftResource == null, onClick = { draftResource = null },
                            label = { Text(stringResource(R.string.capture_log_all_resources)) })
                        shown.forEach { (key, label) ->
                            FilterChip(selected = draftResource == key, onClick = { draftResource = key }, modifier = Modifier.testTag("log-filter-resource:$key"),
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        ResourceIcon(key.substringBefore(':').toInt(), key.substringAfter(':').toLong(), names)
                                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                })
                        }
                    }
                }
                Text(stringResource(R.string.capture_log_retention), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LogRetentionPrefsCore.choices.forEach { limit ->
                        FilterChip(selected = draftRetention == limit, onClick = { draftRetention = limit }, label = { Text("$limit") })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            if (draftRetention < retention && (battleCount > draftRetention || outsideCount > draftRetention)) confirm = true else apply()
        }) { Text(stringResource(R.string.capture_log_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } })
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.capture_log_retention)) },
        text = { Text(stringResource(R.string.capture_log_retention_confirm, draftRetention)) },
        confirmButton = { TextButton(onClick = apply) { Text(stringResource(R.string.capture_log_apply)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(android.R.string.cancel)) } })
    if (picker) {
        val state = rememberDateRangePickerState(initialSelectedStartDateMillis = draftStart, initialSelectedEndDateMillis = draftEnd)
        // DatePickerDialog hard-codes a 360 dp Surface, ignoring the requested content width.
        BasicAlertDialog(onDismissRequest = { picker = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.width(minOf(600, maxOf(360, screen.screenWidthDp - 32), screen.screenWidthDp).dp).testTag("log-date-range-dialog")) {
            Surface(shape = DatePickerDefaults.shape, tonalElevation = DatePickerDefaults.TonalElevation,
                color = DatePickerDefaults.colors().containerColor) {
                Column {
                    DateRangePicker(state, Modifier.fillMaxWidth()
                        .heightIn(max = (screen.screenHeightDp * 0.7f).coerceAtMost(500f).dp)
                        .testTag("log-date-range-picker"),
                        headline = {
                            Text(customRangeLabel(state.selectedStartDateMillis, state.selectedEndDateMillis, screen.locales[0])
                                .ifEmpty { stringResource(R.string.capture_log_custom_range) },
                                Modifier.padding(horizontal = 16.dp).testTag("log-date-range-headline"),
                                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }, showModeToggle = true)
                    Row(Modifier.align(Alignment.End).padding(end = 8.dp, bottom = 8.dp)) {
                        TextButton(onClick = { picker = false }) { Text(stringResource(android.R.string.cancel)) }
                        TextButton(enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null,
                            onClick = {
                                draftStart = state.selectedStartDateMillis
                                draftEnd = state.selectedEndDateMillis
                                draftTime = LogTimeFilter.Custom
                                picker = false
                            }) { Text(stringResource(android.R.string.ok)) }
                    }
                }
            }
        }
    }
}
