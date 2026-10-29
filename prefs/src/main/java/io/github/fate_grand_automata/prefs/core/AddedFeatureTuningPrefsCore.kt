package io.github.fate_grand_automata.prefs.core

import io.github.fate_grand_automata.scripts.prefs.AddedFeatureTuning
import io.github.fate_grand_automata.scripts.prefs.FeatureTuning
import io.github.fate_grand_automata.scripts.prefs.IAddedFeatureTuning
import kotlin.time.Duration.Companion.milliseconds

class AddedFeatureTuningPrefsCore(maker: PrefMaker) {
    class Section(maker: PrefMaker, key: String) {
        val delayPercent = maker.int("added_tuning_${key}_delay", 100)
        val similarityOffset = maker.int("added_tuning_${key}_similarity", 0)

        fun snapshot() = FeatureTuning(delayPercent.get(), similarityOffset.get())
    }

    val weekly = Section(maker, "weekly")
    val fp = Section(maker, "fp")
    val skill = Section(maker, "skill")
    val ce = Section(maker, "ce")
    val quest = Section(maker, "quest")
    val bond = Section(maker, "bond")
    val support = Section(maker, "support")
    val questTapSettle = maker.int("added_tuning_quest_tap_settle", 100)
    val questPollInterval = maker.int("added_tuning_quest_poll_interval", 300)

    fun snapshot(): IAddedFeatureTuning = AddedFeatureTuning(
        weekly = weekly.snapshot(), fp = fp.snapshot(), skill = skill.snapshot(),
        ce = ce.snapshot(), quest = quest.snapshot(), bond = bond.snapshot(), support = support.snapshot(),
        questTapSettle = questTapSettle.get().coerceIn(50, 1000).milliseconds,
        questPollInterval = questPollInterval.get().coerceIn(100, 2000).milliseconds
    )
}
