package io.github.fate_grand_automata.ui.more

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.ui.Heading
import io.github.fate_grand_automata.ui.Tabbed
import io.github.fate_grand_automata.util.OpenDocTreePersistable

@Composable
fun MoreOptionsScreen(
    vm: MoreOptionsViewModel = viewModel(),
    navigateToFineTune: () -> Unit,
    navigateToLicense: () -> Unit,
    navigateToWeeklySettings: () -> Unit = {}
) {
    val pickDirectory = rememberLauncherForActivityResult(OpenDocTreePersistable()) {
        vm.pickedDirectory(it)
    }
    val originalImport by vm.originalImport.collectAsStateWithLifecycle()
    val portraitImport by vm.portraitImport.collectAsStateWithLifecycle()
    val portraitContext = LocalContext.current
    if (portraitImport.open) {
        val destinationName by vm.storageSummary
        ServantPortraitImportDialog(
            state = portraitImport,
            destinationName = destinationName,
            onNumber = vm::changePortraitNumber,
            onKind = vm::changePortraitKind,
            onSelect = vm::selectPortrait,
            onSearch = { vm.searchPortraits(portraitContext.resources.configuration.locales[0].toLanguageTag()) },
            onStart = vm::importPortraits,
            onPickDirectory = { pickDirectory.launch(Uri.EMPTY) },
            onDismiss = vm::closePortraitImport
        )
    }
    val importScripts = rememberLauncherForActivityResult(OpenDocTreePersistable()) {
        vm.selectImportFolder(scripts = true, uri = it)
    }
    val importImages = rememberLauncherForActivityResult(OpenDocTreePersistable()) {
        vm.selectImportFolder(scripts = false, uri = it)
    }
    if (originalImport.open) {
        OriginalFgaImportDialog(
            state = originalImport,
            destinationName = vm.storageProvider.rootDirName,
            onDismiss = vm::closeOriginalImport,
            onSelection = vm::selectImportMode,
            onPickScripts = { importScripts.launch(originalImport.scripts ?: Uri.EMPTY) },
            onPickImages = { importImages.launch(originalImport.images ?: Uri.EMPTY) },
            onOverwrite = vm::overwriteImportedImages,
            onImport = vm::importOriginalData
        )
    }

    MoreOptionsContent(
        vm = vm,
        goToFineTune = navigateToFineTune,
        goToLicense = navigateToLicense,
        goToWeeklySettings = navigateToWeeklySettings,
        pickDirectory = { pickDirectory.launch(Uri.EMPTY) }
    )
}

@Composable
private fun MoreOptionsContent(
    vm: MoreOptionsViewModel,
    goToFineTune: () -> Unit,
    goToLicense: () -> Unit,
    goToWeeklySettings: () -> Unit,
    pickDirectory: () -> Unit
) {
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
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        when (it) {
                            MoreSettingsGroup.Battle -> {
                                battleGroup(vm.prefsCore)
                            }

                            MoreSettingsGroup.Storage -> {
                                item {
                                    val summary by vm.storageSummary
                                    val extractSummary by vm.extractSummary
                                    val context = LocalContext.current

                                    StorageGroup(
                                        directoryName = summary ?: "",
                                        onPickDirectory = pickDirectory,
                                        extractSupportImages = { vm.performSupportImageExtraction(context) },
                                        extractSummary = extractSummary
                                    )
                                }
                            }

                            MoreSettingsGroup.AdditionalFeatures -> {
                                additionalFeaturesGroup(vm.prefsCore, vm::openOriginalImport, vm::openPortraitImport)
                                item {
                                    io.github.fate_grand_automata.ui.prefs.Preference(
                                        title = stringResource(R.string.weekly_route_settings),
                                        onClick = goToWeeklySettings
                                    )
                                }
                            }

                            MoreSettingsGroup.Advanced -> {
                                advancedGroup(
                                    vm.prefsCore,
                                    goToFineTune = goToFineTune,
                                    goToLicense = goToLicense
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
