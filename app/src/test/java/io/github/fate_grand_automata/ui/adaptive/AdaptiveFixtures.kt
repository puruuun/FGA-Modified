package io.github.fate_grand_automata.ui.adaptive

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import io.github.fate_grand_automata.prefs.core.BattleConfigCore
import io.github.fate_grand_automata.prefs.core.PrefMaker
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.scripts.enums.BraveChainEnum
import io.github.fate_grand_automata.scripts.enums.CardPriorityStyle
import io.github.fate_grand_automata.scripts.enums.SupportClass
import io.github.fate_grand_automata.scripts.enums.SupportSelectionModeEnum
import io.github.fate_grand_automata.scripts.models.ServantCardKey
import io.github.fate_grand_automata.scripts.models.TeamSlot
import io.github.fate_grand_automata.ui.card_priority.CardPriorityListItem

/*
 * Sample data shared by the adaptive layout tests. Real prefs classes over Robolectric's in-memory
 * SharedPreferences stand in for user data, so no real prefs file or DataStore is touched.
 */

internal fun sampleConfig(context: Context, id: String) = BattleConfigCore(id, context).apply {
    name.set("Sample farming config")
    notes.set("Wave 3 boss: use NP chain")
    support.selectionMode.set(SupportSelectionModeEnum.Preferred)
    support.supportClass.set(SupportClass.Caster)
}

internal fun sampleConfigList(context: Context, prefix: String): List<BattleConfigCore> =
    listOf("Farming AP20", "Event lottery", "Grail run", "Dailies").mapIndexed { i, name ->
        BattleConfigCore("${prefix}_$i", context).apply {
            this.name.set(name)
            if (i >= 2) folder.set("Events")
        }
    }

internal fun sampleCardPriorityItems(config: BattleConfigCore): SnapshotStateList<CardPriorityListItem> =
    config.cardPriority.get()
        .take(3)
        .map { priority ->
            CardPriorityListItem(
                scores = priority.toMutableStateList(),
                servantPriority = emptyList<TeamSlot>().toMutableStateList(),
                servantCards = emptyList<ServantCardKey>().toMutableStateList(),
                rearrangeCards = mutableStateOf(false),
                braveChains = mutableStateOf(BraveChainEnum.None),
                style = mutableStateOf(CardPriorityStyle.Affinity),
                useServantPriority = mutableStateOf(false)
            )
        }
        .toMutableStateList()

internal fun samplePrefs(context: Context) = PrefsCore(
    PrefMaker(context.getSharedPreferences("adaptive_prefs", Context.MODE_PRIVATE)),
    context
)
