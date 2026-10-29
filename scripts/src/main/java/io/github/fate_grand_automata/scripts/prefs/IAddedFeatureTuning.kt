package io.github.fate_grand_automata.scripts.prefs

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Immutable settings captured once by the script component, shared by all readers in that run. */
interface IAddedFeatureTuning {
    val weekly: FeatureTuning
    val fp: FeatureTuning
    val skill: FeatureTuning
    val ce: FeatureTuning
    val quest: FeatureTuning
    val bond: FeatureTuning
    val support: FeatureTuning
    val questTapSettle: Duration
    val questPollInterval: Duration

    companion object {
        val Default: IAddedFeatureTuning = AddedFeatureTuning()
    }
}

data class FeatureTuning(val delayPercent: Int = 100, val similarityOffsetPercent: Int = 0) {
    fun delay(base: Duration): Duration =
        if (delayPercent == 100) base else base * (delayPercent.coerceIn(50, 300) / 100.0)

    fun similarity(base: Double): Double =
        (base + similarityOffsetPercent.coerceIn(-5, 5) / 100.0).coerceIn(0.40, 0.99)

    /** Preserve the original nullable matcher argument at the defaults. */
    fun defaultSimilarity(base: () -> Double): Double? =
        if (similarityOffsetPercent == 0) null else similarity(base())
}

data class AddedFeatureTuning(
    override val weekly: FeatureTuning = FeatureTuning(),
    override val fp: FeatureTuning = FeatureTuning(),
    override val skill: FeatureTuning = FeatureTuning(),
    override val ce: FeatureTuning = FeatureTuning(),
    override val quest: FeatureTuning = FeatureTuning(),
    override val bond: FeatureTuning = FeatureTuning(),
    override val support: FeatureTuning = FeatureTuning(),
    override val questTapSettle: Duration = 100.milliseconds,
    override val questPollInterval: Duration = 300.milliseconds
) : IAddedFeatureTuning
