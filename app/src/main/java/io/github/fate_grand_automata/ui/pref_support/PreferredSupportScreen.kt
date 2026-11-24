package io.github.fate_grand_automata.ui.pref_support

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.SupportImageKind
import io.github.fate_grand_automata.prefs.core.Pref
import io.github.fate_grand_automata.prefs.core.SupportPrefsCore
import io.github.fate_grand_automata.scripts.enums.BondCEEffectEnum
import io.github.fate_grand_automata.ui.CappedWidth
import io.github.fate_grand_automata.ui.Heading
import io.github.fate_grand_automata.ui.OnResume
import io.github.fate_grand_automata.ui.dialog.FaceThumbnails
import io.github.fate_grand_automata.ui.icon
import io.github.fate_grand_automata.ui.prefs.ListPreference
import io.github.fate_grand_automata.ui.prefs.Preference
import io.github.fate_grand_automata.ui.prefs.PreferenceGroupHeader
import io.github.fate_grand_automata.ui.prefs.SwitchPreference
import io.github.fate_grand_automata.ui.prefs.remember
import io.github.fate_grand_automata.util.SupportNameResources.getLocalizedCEName
import io.github.fate_grand_automata.util.SupportNameResources.getLocalizedServantName
import io.github.fate_grand_automata.util.portraits.SupportCatalogKind
import io.github.fate_grand_automata.util.stringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.res.AssetManager
import io.github.fate_grand_automata.ui.dialog.ImageDelete
import io.github.fate_grand_automata.util.StorageProvider
import io.github.fate_grand_automata.util.SupportImageExtractor
import io.github.fate_grand_automata.util.SupportThumbnailIndex

@Composable
fun PreferredSupportScreen(
    vm: PreferredSupportViewModel = viewModel(),
    supportVm: SupportViewModel = viewModel(),
    autoClassVm: SupportClassAutoViewModel = viewModel()
) {
    PreferredSupport(
        config = vm.supportPrefs,
        vm = supportVm,
        filter = SupportListFilterUi(R.string.support_list_filter_summary_config),
        captureAvailable = rememberCaptureAvailable(vm.prefsCore)
    )

    val context = LocalContext.current

    val scope = rememberCoroutineScope()

    // The first composition only reflects the stored selection; just later changes pick the support class.
    val servants by vm.supportPrefs.preferredServants.remember()
    var lastServants by remember { mutableStateOf(servants) }
    LaunchedEffect(servants) {
        if (servants == lastServants) return@LaunchedEffect
        val previous = lastServants
        lastServants = servants
        autoClassVm.onServantsChanged(vm.supportPrefs, previous, servants)
    }

    OnResume {
        scope.launch(Dispatchers.IO) {
            if (supportVm.shouldExtractSupportImages) {
                supportVm.performSupportImageExtraction(context)
            } else supportVm.refresh(context)
        }
    }
}

/**
 * Deleting for one picker. Built-in images are protected because the extractor writes them back after an app
 * update and from the More options screen, so a deleted one would reappear on its own.
 */
internal fun supportImageDelete(
    storage: StorageProvider,
    kind: SupportImageKind,
    assets: AssetManager,
    index: SupportThumbnailIndex,
    afterDelete: () -> Unit
): ImageDelete<String> {
    val builtIn = SupportImageExtractor.builtInEntries(assets, kind)
    return ImageDelete(
        isProtected = { it in builtIn },
        delete = { names ->
            val removable = names.filterNot { it in builtIn }.toSet()
            val removed = withContext(Dispatchers.IO) { storage.deleteSupportEntries(kind, removable) }
            index.forget(removed)
            afterDelete()
            removed
        }
    )
}

/** Turns off the checks the screen hides for this state. Only writes a check that is on. */
internal fun SupportPrefsCore.clearHiddenChecks(hasServants: Boolean, grandServant: Boolean) {
    if (!hasServants) {
        if (npLevelCheck.get()) npLevelCheck.set(false)
        if (score62Check.get()) score62Check.set(false)
    }
    if (!hasServants || !grandServant) {
        if (grandScore16Check.get()) grandScore16Check.set(false)
    }
}

/**
 * [filter] adds the support list filter (#685): its switch on top, the displayed ascension item and, next to each
 * condition, whether the filter applies it.
 */
@Composable
internal fun PreferredSupport(
    config: SupportPrefsCore,
    vm: SupportViewModel,
    filter: SupportListFilterUi? = null,
    captureAvailable: Boolean = true,
    heading: String = stringResource(R.string.p_support_mode_preferred)
) {
    @Composable
    fun scope(applied: Boolean) {
        if (filter != null) FilterScopeLabel(applied)
    }

    val prefServants by config.preferredServants.remember()
    val prefCEs by config.preferredCEs.remember()
    val grandServant by config.grandServant.remember()

    // Runs when the screen opens, which repairs a stored config that breaks the rule, and again whenever a
    // condition stops holding. A hidden check must not stay active, so it is stored off (#613).
    // It reads the stored values: the remembered states above start at the defaults until their flows
    // arrive, and clearing from those would wipe the checks of a valid config on every open.
    LaunchedEffect(config, prefServants.isNotEmpty(), grandServant) {
        config.clearHiddenChecks(
            hasServants = config.preferredServants.get().isNotEmpty(),
            grandServant = config.grandServant.get()
        )
    }

    val context = LocalContext.current
    val configuration = LocalConfiguration.current

    // Localizing the entries builds a new map, and `Map` is unstable to the Compose compiler,
    // so doing it inline re-ran on every recomposition of this screen — including on every
    // toggle in the multi-select dialogs. Keyed on the configuration so that a locale change
    // still re-resolves the names.
    val servantEntries = remember(vm.servants, configuration) {
        vm.servants.mapValues { (name, _) -> getLocalizedServantName(context, name) }
    }
    val ceEntries = remember(vm.ces, configuration) {
        vm.ces.mapValues { (_, displayName) -> getLocalizedCEName(context, displayName) }
    }

    // A new index per refresh, so images saved by "이미지 받기" show up in the reopened list.
    val servantIndex = remember(vm.servants) { vm.storageProvider.supportThumbnails(SupportImageKind.Servant) }
    val ceIndex = remember(vm.ces) { vm.storageProvider.supportThumbnails(SupportImageKind.CE) }
    val servantFaces = remember(servantIndex) { FaceThumbnails(servantIndex::thumbnail) }
    val ceFaces = remember(ceIndex) { FaceThumbnails(ceIndex::thumbnail) }

    val servantDelete = remember(vm, servantIndex) {
        supportImageDelete(vm.storageProvider, SupportImageKind.Servant, context.assets, servantIndex) { vm.refresh(context) }
    }
    val ceDelete = remember(vm, ceIndex) {
        supportImageDelete(vm.storageProvider, SupportImageKind.CE, context.assets, ceIndex) { vm.refresh(context) }
    }

    CappedWidth {
        LazyColumn {
            item {
                Heading(heading)
            }

            if (filter != null) {
                item {
                    Card(
                        modifier = Modifier.padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        SupportListFilterHeader(config, filter, captureAvailable)
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .padding(bottom = 16.dp)
                    ) {
                        PreferenceGroupHeader(
                            title = stringResource(R.string.p_battle_config_support_pref_servants)
                        )

                        config.preferredServants.SupportSelectPreference(
                            title = stringResource(R.string.p_battle_config_support_pref_servants),
                            entries = servantEntries,
                            thumbnails = servantFaces,
                            imageDelete = servantDelete,
                            imageDownload = { select ->
                                SupportImageDownload(SupportCatalogKind.Servant, select, { vm.refresh(context) })
                            }
                        )
                        scope(applied = true)

                        if (filter != null && prefServants.isNotEmpty()) {
                            DisplayedAscensionPreference(config, servantEntries)
                            scope(applied = true)
                        }

                        // Re-ascension by Noble Phantasm name (0006 D-8): right under the servant choice.
                        config.preferredNoblePhantasms.SupportSelectPreference(
                            title = stringResource(R.string.p_battle_config_support_pref_nps),
                            entries = vm.noblePhantasms
                        )
                        Text(
                            text = stringResource(R.string.p_battle_config_support_pref_nps_hint),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        scope(applied = false)

                        if (prefServants.isNotEmpty()) {
                            config.maxAscended.SwitchPreference(
                                title = stringResource(R.string.p_battle_config_support_max_ascended)
                            )
                            scope(applied = true)

                            config.grandServant.SwitchPreference(
                                title = stringResource(R.string.p_battle_config_support_grand_servant)
                            )
                            scope(applied = false)

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    stringResource(R.string.p_max_skills),
                                    modifier = Modifier.weight(1f)
                                )

                                MaxSkills(
                                    skills = listOf(
                                        config.skill1Max,
                                        config.skill2Max,
                                        config.skill3Max
                                    )
                                )
                            }
                            scope(applied = true)
                        }
                        if (prefServants.isNotEmpty()) {
                            config.npLevelCheck.SwitchPreference(
                                title = stringResource(R.string.p_np_level_check)
                            )
                            val npEnabled by config.npLevelCheck.remember()
                            AnimatedVisibility(npEnabled) {
                                config.minimumNpLevel.ListPreference(
                                    title = stringResource(R.string.p_np_min_level),
                                    entries = (2..5).associateWith { stringResource(R.string.p_np_min_level_entry, it) }
                                )
                            }
                            scope(applied = true)
                            config.score62Check.SwitchPreference(
                                title = stringResource(R.string.p_score_62_check)
                            )
                            scope(applied = true)
                            // The game shows +16 only for Grand servants.
                            if (grandServant) {
                                config.grandScore16Check.SwitchPreference(
                                    title = stringResource(R.string.p_grand_score_16_check)
                                )
                                scope(applied = false)
                            }
                            config.appendSkillCheck.SwitchPreference(
                                title = stringResource(R.string.p_append_skill_check)
                            )
                            val appendEnabled by config.appendSkillCheck.remember()
                            AnimatedVisibility(appendEnabled) {
                                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                                    Row(modifier = Modifier.padding(top = 8.dp)) {
                                        MaxSkills(config.appendSkillsMax, compact = true, tagPrefix = MaxAppendSkillTagPrefix)
                                    }
                                }
                            }
                            scope(applied = true)
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .padding(bottom = 16.dp)
                    ) {
                        PreferenceGroupHeader(
                            title = stringResource(R.string.p_battle_config_support_pref_ces)
                        )

                        config.preferredCEs.SupportSelectPreference(
                            title = stringResource(R.string.p_battle_config_support_pref_ces),
                            entries = ceEntries,
                            thumbnails = ceFaces,
                            imageDelete = ceDelete,
                            imageDownload = { select ->
                                SupportImageDownload(SupportCatalogKind.CE, select, { vm.refresh(context) })
                            }
                        )
                        scope(applied = true)

                        if (prefCEs.isNotEmpty()) {
                            config.mlb.SwitchPreference(
                                title = stringResource(R.string.p_battle_config_support_mlb)
                            )
                            scope(applied = true)

                            // These options only apply to Grand Servants
                            AnimatedVisibility(grandServant) {
                                Column {
                                    config.bondCEEffect.ListPreference(
                                        title = stringResource(R.string.p_battle_config_support_bond_ce_effect_preference),
                                        entries = BondCEEffectEnum.entries
                                            .associateWith { stringResource(it.stringRes) }
                                    )

                                    config.requireBothNormalAndRewardMatch.SwitchPreference(
                                        title = stringResource(R.string.p_battle_config_support_require_both_normal_and_reward_match)
                                    )
                                    scope(applied = false)
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Column {
                        PreferenceGroupHeader(
                            title = stringResource(R.string.p_support_mode_friend)
                        )

                        val friendsOnly by config.friendsOnly.remember()
                        val autoFriendRequest by config.autoFriendRequest.remember()

                        LaunchedEffect(config, friendsOnly, autoFriendRequest) {
                            if (friendsOnly && autoFriendRequest) config.autoFriendRequest.set(false)
                        }

                        config.friendsOnly.SwitchPreference(
                            title = stringResource(R.string.p_battle_config_support_friends_only)
                        )
                        scope(applied = true)

                        // The manual filter has no script, and so nothing that sends friend requests.
                        if (filter?.script != false) {
                            config.autoFriendRequest.SwitchPreference(
                                title = stringResource(R.string.p_battle_config_support_auto_friend_request),
                                enabled = !friendsOnly
                            )
                        }

                        AnimatedVisibility (friendsOnly) {
                            if (vm.friends.isNotEmpty()) {
                                Column {
                                    config.friendNames.SupportSelectPreference(
                                        title = stringResource(R.string.p_battle_config_support_friend_names),
                                        entries = vm.friends
                                    )
                                    scope(applied = false)
                                }
                            } else {
                                Preference(
                                    icon = icon(R.drawable.ic_info),
                                    title = stringResource(R.string.p_battle_config_support_friend_names),
                                    summary = stringResource(R.string.p_battle_config_support_friend_name_hint)
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    PreferredSupportHelp()
                }
            }
        }
    }
}

@Composable
private fun PreferredSupportHelp() {
    Column(
        modifier = Modifier
            .padding(16.dp)
    ) {
        Text(
            """
                1. You can add more images using 'Support Image Maker' script by clicking the PLAY button on support or friend list screens.
                
                2. For event CEs, it is better to use the in-game filters in FGO for CEs and MLB setting.
                """.trimIndent(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Light
        )
    }
}

internal const val MaxSkillTagPrefix = "maxSkill:"
internal const val MaxAppendSkillTagPrefix = "maxAppendSkill:"

@Composable
internal fun MaxSkills(
    skills: List<Pref<Boolean>>,
    compact: Boolean = false,
    tagPrefix: String = MaxSkillTagPrefix
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        fun skillText(max: Boolean) = if (max) "10" else "x"

        skills.forEachIndexed { index, pref ->
            if (index != 0) {
                Text(
                    "/",
                    modifier = Modifier
                        .padding(horizontal = if (compact) 4.dp else 8.dp)
                )
            }

            var max by pref.remember()

            // No check icon on purpose: the owner wants the gold fill alone to mean "maxed".
            FilterChip(
                selected = max,
                onClick = { max = !max },
                label = {
                    Text(
                        skillText(max),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = if (compact) 16.dp else 24.dp)
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = max,
                    borderColor = MaterialTheme.colorScheme.outline,
                    selectedBorderColor = Color.Transparent
                ),
                modifier = Modifier.testTag("$tagPrefix$index")
            )
        }
    }
}
