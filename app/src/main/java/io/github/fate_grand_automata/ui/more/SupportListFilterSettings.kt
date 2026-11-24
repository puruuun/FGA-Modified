package io.github.fate_grand_automata.ui.more

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.SupportFilterSettings
import io.github.fate_grand_automata.capture.SupportListLastResult
import io.github.fate_grand_automata.capture.loadSupportFilter
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.prefs.core.SupportFilterProfileCore
import io.github.fate_grand_automata.prefs.core.SupportFilterSource
import io.github.fate_grand_automata.ui.CappedWidth
import io.github.fate_grand_automata.ui.FGASwitchColors
import io.github.fate_grand_automata.ui.Heading
import io.github.fate_grand_automata.ui.OnResume
import io.github.fate_grand_automata.ui.launcher.supportFilterSummary
import io.github.fate_grand_automata.ui.pref_support.PreferredSupport
import io.github.fate_grand_automata.ui.pref_support.SupportListFilterUi
import io.github.fate_grand_automata.ui.pref_support.SupportViewModel
import io.github.fate_grand_automata.ui.pref_support.rememberCaptureAvailable
import io.github.fate_grand_automata.ui.prefs.Preference
import io.github.fate_grand_automata.ui.prefs.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SupportListFilterViewModel @Inject constructor(val prefsCore: PrefsCore) : ViewModel()

/** The last follower/list result of this app process, counts only; nothing is shown before the first list. */
@Composable
internal fun SupportListLastResultLine(modifier: Modifier = Modifier) {
    val last by SupportListLastResult.last.collectAsStateWithLifecycle()
    val text = when (val result = last) {
        null -> return
        is SupportListLastResult.Result.Filtered -> result.counts.let {
            stringResource(R.string.capture_support_filter_last, it.followersBefore, it.followersAfter, it.slotsBefore, it.slotsAfter)
        }
        SupportListLastResult.Result.Unchanged -> stringResource(R.string.capture_support_filter_last_unchanged)
    }
    Text(text, modifier, style = MaterialTheme.typography.bodySmall)
}

/**
 * More > 서포트 목록 필터 (#695): the feature switch and the profiles. The filter itself is switched and filled in
 * the play button menu.
 */
@Composable
fun SupportListFilterScreen(prefs: PrefsCore, editProfile: (String) -> Unit) {
    var feature by prefs.supportFilterFeature.remember()
    val ids by prefs.supportFilterProfiles.remember()
    var adding by remember { mutableStateOf(false) }
    // Read again when the list changes, so a rename made on this screen shows.
    var names by remember { mutableIntStateOf(0) }
    val profiles = remember(ids, names) { ids.map(prefs::forSupportFilterProfile).sortedBy { it.name.get().lowercase() } }
    CappedWidth {
        LazyColumn {
            item { Heading(stringResource(R.string.support_list_filter)) }
            item {
                Preference(
                    title = stringResource(R.string.support_filter_feature),
                    summary = stringResource(R.string.support_filter_feature_summary),
                    onClick = { feature = !feature },
                    modifier = Modifier.testTag(SupportFilterFeatureTag),
                ) {
                    Switch(checked = feature, onCheckedChange = { feature = it }, colors = FGASwitchColors())
                }
            }
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp),
                ) {
                    Text(
                        stringResource(R.string.support_filter_profiles),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { adding = true }, modifier = Modifier.testTag(SupportFilterAddProfileTag)) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text(stringResource(R.string.support_filter_profile_add))
                    }
                }
            }
            if (profiles.isEmpty()) item {
                Text(
                    stringResource(R.string.support_filter_profiles_none),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(profiles, key = { it.id }) { profile ->
                ProfileRow(prefs, profile, edit = { editProfile(profile.id) }, renamed = { names++ })
            }
        }
    }
    if (adding) ProfileNameDialog(
        title = stringResource(R.string.support_filter_profile_add),
        initial = "",
        onDismiss = { adding = false },
        onConfirm = { name ->
            adding = false
            editProfile(prefs.addSupportFilterProfile(name).id)
        },
    )
}

@Composable
private fun ProfileRow(prefs: PrefsCore, profile: SupportFilterProfileCore, edit: () -> Unit, renamed: () -> Unit) {
    val context = LocalContext.current
    val name by profile.name.remember()
    val changes = remember(profile) { SupportFilterSettings.changes(profile.support).map { SupportFilterSettings.of(profile.support) } }
    val settings by changes.collectAsState(initial = SupportFilterSettings.of(profile.support))
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val used = stringResource(R.string.support_filter_profile_used, name)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("$SupportFilterProfileRowTag${profile.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = edit)) {
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp)
            ) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(supportFilterSummary(settings), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(
                onClick = {
                    prefs.loadSupportFilter(profile.support, SupportFilterSource.Profile, name)
                    Toast.makeText(context, used, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.testTag("$SupportFilterUseProfileTag${profile.id}"),
            ) { Text(stringResource(R.string.support_filter_profile_use)) }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("$SupportFilterProfileMenuTag${profile.id}")) {
                    Icon(Icons.Default.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.support_filter_profile_rename)) },
                        onClick = { menu = false; renaming = true },
                        modifier = Modifier.testTag(SupportFilterRenameTag),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.support_filter_profile_delete)) },
                        onClick = { menu = false; deleting = true },
                        modifier = Modifier.testTag(SupportFilterDeleteTag),
                    )
                }
            }
        }
    }
    if (renaming) ProfileNameDialog(
        title = stringResource(R.string.support_filter_profile_rename),
        initial = name,
        onDismiss = { renaming = false },
        onConfirm = {
            renaming = false
            profile.name.set(it)
            renamed()
        },
    )
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text(stringResource(R.string.support_filter_profile_delete)) },
        text = { Text(stringResource(R.string.support_filter_profile_delete_confirm, name)) },
        confirmButton = {
            TextButton(
                onClick = { deleting = false; prefs.removeSupportFilterProfile(profile.id) },
                modifier = Modifier.testTag(SupportFilterDeleteConfirmTag),
            ) { Text(stringResource(R.string.support_filter_profile_delete)) }
        },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
internal fun ProfileNameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.support_filter_profile_name)) },
                modifier = Modifier.testTag(SupportFilterNameFieldTag),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag(SupportFilterNameConfirmTag),
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** One profile's conditions, on the battle config's support screen without the items only a script uses. */
@Composable
fun SupportFilterProfileScreen(prefs: PrefsCore, id: String, supportVm: SupportViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    OnResume {
        scope.launch(Dispatchers.IO) {
            if (supportVm.shouldExtractSupportImages) supportVm.performSupportImageExtraction(context)
            else supportVm.refresh(context)
        }
    }
    SupportFilterProfile(prefs, prefs.forSupportFilterProfile(id), supportVm)
}

@Composable
internal fun SupportFilterProfile(
    prefs: PrefsCore,
    profile: SupportFilterProfileCore,
    supportVm: SupportViewModel,
    filter: SupportListFilterUi = SupportListFilterUi(
        summary = R.string.support_list_filter_summary_profile,
        extra = { ImportFromBattleConfig(prefs, profile) },
        script = false,
    ),
    captureAvailable: Boolean = rememberCaptureAvailable(prefs),
) {
    val name by profile.name.remember()
    PreferredSupport(
        config = profile.support,
        vm = supportVm,
        filter = filter,
        captureAvailable = captureAvailable,
        heading = name,
    )
}

/** Fills a profile from a battle config's support conditions, a copy like every other. */
@Composable
internal fun ImportFromBattleConfig(prefs: PrefsCore, profile: SupportFilterProfileCore) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { open = true },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(ImportFromBattleConfigTag),
    ) { Text(stringResource(R.string.support_list_filter_import)) }
    if (!open) return
    val configs = remember {
        prefs.battleConfigList.get().map { prefs.forBattleConfig(it) }.sortedBy { it.name.get().lowercase() }
    }
    AlertDialog(
        onDismissRequest = { open = false },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) } },
        title = { Text(stringResource(R.string.support_list_filter_import)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (configs.isEmpty()) Text(stringResource(R.string.support_list_filter_no_configs))
                for (config in configs) {
                    val configName = config.name.get()
                    val imported = stringResource(R.string.support_list_filter_imported, configName)
                    Text(
                        configName,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                profile.support.copyConditionsFrom(config.support)
                                open = false
                                Toast.makeText(context, imported, Toast.LENGTH_SHORT).show()
                            }
                            .padding(vertical = 12.dp)
                            .testTag("$ImportConfigTag${config.id}"),
                    )
                }
            }
        },
    )
}

internal const val ImportFromBattleConfigTag = "supportListFilter:import"
internal const val ImportConfigTag = "supportListFilter:import:"
internal const val SupportFilterFeatureTag = "supportListFilter:feature"
internal const val SupportFilterAddProfileTag = "supportListFilter:addProfile"
internal const val SupportFilterProfileRowTag = "supportListFilter:profile:"
internal const val SupportFilterUseProfileTag = "supportListFilter:use:"
internal const val SupportFilterProfileMenuTag = "supportListFilter:menu:"
internal const val SupportFilterRenameTag = "supportListFilter:rename"
internal const val SupportFilterDeleteTag = "supportListFilter:delete"
internal const val SupportFilterDeleteConfirmTag = "supportListFilter:deleteConfirm"
internal const val SupportFilterNameFieldTag = "supportListFilter:name"
internal const val SupportFilterNameConfirmTag = "supportListFilter:nameConfirm"
