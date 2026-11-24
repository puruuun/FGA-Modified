package io.github.fate_grand_automata.ui.more

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.SupportListLastResult
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.ui.OnResume
import io.github.fate_grand_automata.ui.pref_support.PreferredSupport
import io.github.fate_grand_automata.ui.pref_support.SupportListFilterUi
import io.github.fate_grand_automata.ui.pref_support.SupportViewModel
import io.github.fate_grand_automata.ui.pref_support.rememberCaptureAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ManualSupportFilterViewModel @Inject constructor(val prefsCore: PrefsCore) : ViewModel()

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
 * More > 서포트 목록 필터 (#685): the filter for manual play. The same items as a battle config's support screen,
 * stored apart from every battle config, with a button that copies a battle config's conditions in.
 */
@Composable
fun ManualSupportFilterScreen(prefs: PrefsCore, supportVm: SupportViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    OnResume {
        scope.launch(Dispatchers.IO) {
            if (supportVm.shouldExtractSupportImages) supportVm.performSupportImageExtraction(context)
            else supportVm.refresh(context)
        }
    }
    ManualSupportFilter(prefs, supportVm)
}

@Composable
internal fun ManualSupportFilter(
    prefs: PrefsCore,
    supportVm: SupportViewModel,
    filter: SupportListFilterUi = SupportListFilterUi(
        summary = R.string.support_list_filter_summary_manual,
        extra = { ImportFromBattleConfig(prefs) },
    ),
    captureAvailable: Boolean = rememberCaptureAvailable(prefs),
) {
    PreferredSupport(
        config = prefs.manualSupportFilter,
        vm = supportVm,
        filter = filter,
        captureAvailable = captureAvailable,
        heading = stringResource(R.string.support_list_filter_manual_title),
    )
}

@Composable
internal fun ImportFromBattleConfig(prefs: PrefsCore) {
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
                    val name = config.name.get()
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                prefs.manualSupportFilter.copyConditionsFrom(config.support)
                                open = false
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.support_list_filter_imported, name),
                                    Toast.LENGTH_SHORT,
                                ).show()
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
