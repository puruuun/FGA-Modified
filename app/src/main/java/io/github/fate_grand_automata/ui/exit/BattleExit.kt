package io.github.fate_grand_automata.ui.exit

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.MaterialEnum
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.fate_grand_automata.scripts.models.BondSummary
import io.github.fate_grand_automata.ui.DimmedIcon
import io.github.fate_grand_automata.ui.FGATheme
import io.github.fate_grand_automata.ui.FgaScreen
import io.github.fate_grand_automata.ui.VectorIcon
import io.github.fate_grand_automata.ui.battle_config_item.Material
import io.github.fate_grand_automata.ui.icon
import io.github.fate_grand_automata.ui.prefs.remember
import io.github.fate_grand_automata.util.KnownException
import io.github.fate_grand_automata.util.displayText
import io.github.fate_grand_automata.util.stringRes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import java.text.NumberFormat

private val Duration.stringify: String
    get() =
        toComponents { hours, minutes, seconds, _ ->
            (if (hours > 0)
                listOf(hours, minutes, seconds)
            else listOf(minutes, seconds))
                .joinToString(":") { "%02d".format(it) }
        }

@Composable
private fun Refill(
    limit: Int,
    timesRefilled: Int
) {
    if (limit > 0) {
        SmallChip(
            text = "$timesRefilled / $limit",
            icon = icon(R.drawable.ic_apple)
        )
    }
}

fun LazyListScope.battleSummaryContent(
    reasonText: @Composable () -> String,
    state: AutoBattle.ExitState,
    refillEnabled: Boolean,
    ceLimitReached: Boolean
) {
    item {
        Text(
            reasonText(),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp, top = 5.dp)
        )
    }

    item {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(16.dp, 5.dp)
        ) {
            if (refillEnabled) {
                Refill(
                    limit = state.refillLimit,
                    timesRefilled = state.timesRefilled
                )
            }

            SmallChip(
                text = state.totalTime.stringify,
                icon = icon(R.drawable.ic_time)
            )

            Runs(
                runLimit = state.runLimit,
                timesRan = state.timesRan
            )
        }
    }

    if (!ceLimitReached && state.ceDropCount > 0) {
        item {
            Text(
                stringResource(R.string.ces_dropped, state.ceDropCount),
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .padding(16.dp, 5.dp)
            )
        }
    }

    if (state.timesRan > 1) {
        item {
            Text(
                stringResource(
                    R.string.avg_time_per_run,
                    state.averageTimePerRun.stringify
                ),
                modifier = Modifier
                    .padding(16.dp, 5.dp)
            )
        }

        item {
            Text(
                stringResource(
                    R.string.turns_stats,
                    state.minTurnsPerRun,
                    state.averageTurnsPerRun,
                    state.maxTurnsPerRun
                ),
                modifier = Modifier
                    .padding(16.dp, 5.dp)
            )
        }
    } else if (state.timesRan == 1) {
        item {
            Text(
                stringResource(R.string.turns_count, state.minTurnsPerRun),
                modifier = Modifier
                    .padding(16.dp, 5.dp)
            )
        }
    }

    if (state.materials.isNotEmpty()) {
        item {
            MaterialSummary(
                materials = state.materials
            )
        }
    }

    state.bond?.let { bond ->
        item { BondSummaryContent(bond) }
    }

    val retreatTotal = state.wipeWithdrawCount + state.turnLimitRetreatCount
    if (retreatTotal > 0) {
        item {
            Text(
                stringResource(
                    R.string.retreat_count,
                    retreatTotal,
                    state.wipeWithdrawCount,
                    state.turnLimitRetreatCount
                ),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(16.dp, 5.dp)
            )
        }
    }
}

@Composable
private fun BondSummaryContent(summary: BondSummary) {
    Column(Modifier.padding(16.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text(stringResource(R.string.bond_totals), color = MaterialTheme.colorScheme.secondary)
        if (summary.slots.isEmpty()) Text(stringResource(R.string.bond_no_results), style = MaterialTheme.typography.bodySmall)
        summary.slots.forEach { slot ->
            Text(stringResource(R.string.bond_slot_total, slot.position, NumberFormat.getIntegerInstance().format(slot.total)))
            Text(stringResource(R.string.bond_slot_reads, slot.readRuns, slot.missedRuns) +
                if (slot.maxObserved) " · " + stringResource(R.string.bond_max_observed) else "",
                style = MaterialTheme.typography.bodySmall)
        }
        if (summary.unmappedLevelUpRuns > 0) Text(stringResource(R.string.bond_unmapped_popups, summary.unmappedLevelUpRuns),
            style = MaterialTheme.typography.bodySmall)
        if (summary.levelUpMissedRuns > 0) Text(stringResource(R.string.bond_levelup_missed, summary.levelUpMissedRuns),
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Runs(
    runLimit: Int?,
    timesRan: Int
) {
    val runs = when {
        runLimit != null && runLimit > 0 -> "$timesRan / $runLimit"
        timesRan > 0 -> timesRan.toString()
        else -> ""
    }

    if (runs.isNotBlank()) {
        SmallChip(
            text = "${stringResource(R.string.p_runs)}: $runs"
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MaterialSummary(
    materials: Map<MaterialEnum, Int>
) {
    FlowRow(
        maxItemsInEachRow = 3,
        modifier = Modifier.padding(16.dp)
    ) {
        for ((mat, count) in materials.toList()) {
            Card(
                shape = CircleShape,
                modifier = Modifier
                    .padding(end = 5.dp, top = 5.dp, bottom = 5.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(16.dp, 5.dp)
                ) {
                    Material(mat = mat)

                    Text(
                        stringResource(mat.stringRes),
                        modifier = Modifier
                            .padding(horizontal = 5.dp)
                    )

                    Text(
                        "x$count",
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallChip(
    text: String,
    icon: VectorIcon? = null
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .padding(end = 7.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(5.dp, 3.dp)
        ) {
            if (icon != null) {
                DimmedIcon(
                    icon,
                    contentDescription = "icon",
                    modifier = Modifier
                        .padding(end = 5.dp)
                        .size(16.dp)
                )
            }

            Text(
                text,
                style = MaterialTheme.typography.titleSmall
            )
        }
    }
}

@Composable
fun BattleExit(
    exception: AutoBattle.ExitException,
    prefs: IPreferences,
    prefsCore: PrefsCore,
    onClose: () -> Unit,
    onCopy: () -> Unit
) {
    FgaScreen {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
            ) {
                battleSummaryContent(
                    reasonText = { exception.reason.displayText(LocalContext.current) },
                    state = exception.state,
                    refillEnabled = prefs.selectedServerConfigPref.resources.isNotEmpty(),
                    ceLimitReached = exception.reason is AutoBattle.ExitReason.LimitCEs
                )
            }

            if (exception.reason is AutoBattle.ExitReason.Paused) {
                HorizontalDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp, 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.stop_after_this_run),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    var stopAfterThisRun by prefsCore.stopAfterThisRun.remember()

                    Switch(
                        checked = stopAfterThisRun,
                        onCheckedChange = { stopAfterThisRun = it }
                    )
                }
            }

            HorizontalDivider()

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row {
                    val allowCopy = exception.reason.let { reason ->
                        reason is AutoBattle.ExitReason.Unexpected && reason.cause !is KnownException
                    }

                    if (allowCopy) {
                        TextButton(onClick = onCopy) {
                            Text(stringResource(R.string.unexpected_error_copy))
                        }
                    }
                }

                Row {
                    TextButton(
                        onClick = onClose
                    ) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            }
        }
    }
}

@Preview(name = "Light Mode", widthDp = 600, heightDp = 300)
@Preview(name = "Dark Mode", widthDp = 600, heightDp = 300, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun PreviewBattleExitContent() {
    FGATheme {
        LazyColumn {
            battleSummaryContent(
                reasonText = { AutoBattle.ExitReason.CEGet.displayText(LocalContext.current) },
                state = AutoBattle.ExitState(
                    timesRan = 5,
                    runLimit = 8,
                    timesRefilled = 3,
                    refillLimit = 6,
                    ceDropCount = 2,
                    materials = emptyMap(),
//                    materials = mapOf(
//                        MaterialEnum.ShellOfReminiscence to 2,
//                        MaterialEnum.Chain to 5,
//                        MaterialEnum.AmnestyBell to 1,
//                        MaterialEnum.AuroraSteel to 10
//                    ),
                    wipeWithdrawCount = 1,
                    totalTime = 1880.seconds,
                    averageTimePerRun = 75.seconds,
                    minTurnsPerRun = 3,
                    maxTurnsPerRun = 4,
                    averageTurnsPerRun = 3.45678
                ),
                refillEnabled = true,
                ceLimitReached = false
            )
        }
    }
}
