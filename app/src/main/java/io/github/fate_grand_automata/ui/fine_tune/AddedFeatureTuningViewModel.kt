package io.github.fate_grand_automata.ui.fine_tune

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.prefs.core.AddedFeatureTuningPrefsCore
import io.github.fate_grand_automata.prefs.core.Pref
import io.github.fate_grand_automata.prefs.core.PrefsCore
import io.github.fate_grand_automata.ui.icon
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class AddedFeatureTuningViewModel @Inject constructor(prefs: PrefsCore) : ViewModel() {
    private val tuning = prefs.addedFeatureTuning

    private fun section(name: Int, prefs: AddedFeatureTuningPrefsCore.Section, extras: List<FineTuneItem> = emptyList()) =
        FineTuneGroup(name, listOf(
            FineTuneItem(
                pref = prefs.delayPercent, name = R.string.added_tuning_delay,
                icon = icon(R.drawable.ic_time), valueRange = 50..300, step = 10,
                valueRepresentation = { "$it%" }, hint = R.string.added_tuning_delay_hint
            ),
            FineTuneItem(
                pref = prefs.similarityOffset, name = R.string.added_tuning_similarity,
                icon = icon(R.drawable.ic_image_search), valueRange = -5..5,
                valueRepresentation = { String.format(Locale.ROOT, "%+.2f", it / 100.0) },
                hint = R.string.added_tuning_similarity_hint
            )
        ) + extras)

    private fun milliseconds(pref: Pref<Int>, name: Int, range: IntRange, step: Int) = FineTuneItem(
        pref = pref, name = name, icon = icon(R.drawable.ic_time), valueRange = range, step = step,
        valueRepresentation = { "${it}ms" }, hint = R.string.added_tuning_quest_hint
    )

    val groups = listOf(
        section(R.string.added_tuning_weekly, tuning.weekly),
        section(R.string.added_tuning_fp, tuning.fp),
        section(R.string.added_tuning_skill, tuning.skill),
        section(R.string.added_tuning_ce, tuning.ce),
        section(R.string.added_tuning_quest, tuning.quest, listOf(
            milliseconds(tuning.questTapSettle, R.string.added_tuning_tap_settle, 50..1000, 50),
            milliseconds(tuning.questPollInterval, R.string.added_tuning_poll_interval, 100..2000, 100)
        )),
        section(R.string.added_tuning_bond, tuning.bond),
        section(R.string.added_tuning_support, tuning.support)
    )

    init {
        groups.forEach { group -> group.items.forEach { it.normalizeStoredValue() } }
    }

    fun reset(group: FineTuneGroup) = group.items.forEach { it.reset() }
    fun resetAll() = groups.forEach { reset(it) }
}
