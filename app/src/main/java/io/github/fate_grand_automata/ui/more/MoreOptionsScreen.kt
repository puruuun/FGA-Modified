package io.github.fate_grand_automata.ui.more

import io.github.fate_grand_automata.capture.captureFeaturesAvailable
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.ui.CappedWidth
import io.github.fate_grand_automata.ui.Heading
import io.github.fate_grand_automata.ui.Tabbed
import io.github.fate_grand_automata.ui.icon
import io.github.fate_grand_automata.util.OpenDocTreePersistable

@Composable
fun MoreOptionsScreen(
    vm: MoreOptionsViewModel = viewModel(),
    navigateToFineTune: () -> Unit,
    navigateToAddedFeatureTuning: () -> Unit,
    navigateToLicense: () -> Unit,
    navigateToWeeklySettings: () -> Unit = {},
    navigateToWidgetSettings: () -> Unit = {},
    navigateToGainsTracker: () -> Unit = {},
    navigateToFriendManager: () -> Unit = {}
) {
    GameAreaPreviewEffect(vm.prefsCore, vm.gameAreaPreview)
    val pickDirectory = rememberLauncherForActivityResult(OpenDocTreePersistable()) {
        vm.pickedDirectory(it)
    }
    val storageSummary by vm.storageSummary
    val context = LocalContext.current
    val extractionState by vm.extractionState.collectAsState()
    val iconState by vm.iconState.collectAsState()

    MoreOptionsContent(
        prefs = vm.prefsCore,
        storageSummary = storageSummary,
        extractSummary = extractionSummary(context, extractionState),
        extractSupportImages = vm::performSupportImageExtraction,
        downloadSummary = iconDownloadSummary(context, iconState),
        downloadIcons = vm::downloadLogIcons,
        additionalFeatures = {
            additionalFeaturesGroup(vm.prefsCore, vm.gameAreaPreview)
        },
        goToFineTune = navigateToFineTune,
        goToAddedFeatureTuning = navigateToAddedFeatureTuning,
        goToLicense = navigateToLicense,
        goToWeeklySettings = navigateToWeeklySettings,
        goToWidgetSettings = navigateToWidgetSettings,
        goToGainsTracker = navigateToGainsTracker,
        goToFriendManager = navigateToFriendManager,
        additionalTools = { AdditionalTools(vm) },
        pickDirectory = { pickDirectory.launch(Uri.EMPTY) }
    )
}

@Composable
internal fun MoreOptionsContent(
    prefs: PrefsCore,
    storageSummary: String?,
    extractSummary: String,
    extractSupportImages: () -> Unit,
    additionalFeatures: LazyListScope.() -> Unit,
    goToFineTune: () -> Unit,
    goToAddedFeatureTuning: () -> Unit,
    goToLicense: () -> Unit,
    goToWeeklySettings: () -> Unit,
    goToGainsTracker: () -> Unit = {},
    pickDirectory: () -> Unit,
    additionalTools: @Composable () -> Unit = {},
    goToFriendManager: () -> Unit = {},
    downloadSummary: String = "",
    downloadIcons: () -> Unit = {},
    goToWidgetSettings: () -> Unit = {},
) {
    val captureEnabled by prefs.captureTrackerEnabled.asFlow()
        .collectAsState(initial = prefs.captureTrackerEnabled.get())
    val captureAvailable = captureFeaturesAvailable(captureEnabled, android.os.Build.VERSION.SDK_INT)
    CappedWidth {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Heading(stringResource(R.string.p_more_options))

            Tabbed(
                items = MoreSettingsGroup.entries.toList(),
                heading = { Text(stringResource(it.stringRes)) },
                content = {
                    Card(
                        modifier = Modifier
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        LazyColumn(
                            modifier = Modifier.testTag("more-tab:$it"),
                            contentPadding = PaddingValues(bottom = 16.dp)
                        ) {
                            when (it) {
                                MoreSettingsGroup.Battle -> {
                                    battleGroup(prefs)
                                }

                                MoreSettingsGroup.Storage -> {
                                    item {
                                        StorageGroup(
                                            directoryName = storageSummary ?: "",
                                            onPickDirectory = pickDirectory,
                                            extractSupportImages = extractSupportImages,
                                            extractSummary = extractSummary,
                                            downloadIcons = downloadIcons,
                                            downloadSummary = downloadSummary,
                                            showIconDownload = captureAvailable
                                        )
                                    }
                                }

                                MoreSettingsGroup.AdditionalFeatures -> {
                                    item { additionalTools() }
                                    // The VPN proxy hand-off needs Android 10 (API 29).
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) item {
                                        io.github.fate_grand_automata.ui.prefs.Preference(
                                            title = stringResource(R.string.capture_title),
                                            icon = icon(Icons.Default.VpnKey),
                                            onClick = goToGainsTracker
                                        )
                                        if (captureAvailable) io.github.fate_grand_automata.ui.prefs.Preference(
                                            title = stringResource(R.string.friends_title),
                                            icon = icon(Icons.Default.People),
                                            onClick = goToFriendManager
                                        )
                                    }
                                    additionalFeatures()
                                }

                                MoreSettingsGroup.Advanced -> {
                                    advancedGroup(
                                        prefs,
                                        goToFineTune = goToFineTune,
                                        goToAddedFeatureTuning = goToAddedFeatureTuning,
                                        goToLicense = goToLicense,
                                        goToWeeklySettings = goToWeeklySettings,
                                        goToWidgetSettings = goToWidgetSettings,
                                    )
                                }
                            }
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
            )
        }
    }
}

private enum class MoreSettingsGroup {
    Battle, Storage, Advanced, AdditionalFeatures;

    val stringRes
        get() = when (this) {
            Battle -> R.string.p_script_mode_battle
            Storage -> R.string.p_storage
            Advanced -> R.string.p_advanced
            AdditionalFeatures -> R.string.p_additional_features
        }
}
