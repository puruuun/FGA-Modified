package io.github.fate_grand_automata.scripts.supportSelection

import io.github.fate_grand_automata.scripts.prefs.IAddedFeatureTuning
import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.locations.SupportSkillLayout
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

@ScriptScope
class SupportSkillLevelReader @Inject constructor(api: IFgoAutomataApi,
    private val addedTuning: IAddedFeatureTuning = IAddedFeatureTuning.Default
) : IFgoAutomataApi by api {
    companion object {
        const val MAX_LEVEL_SIMILARITY = 0.68

        fun matches(required: List<Boolean>, detected: List<Boolean>) =
            required.size == detected.size && required.zip(detected).all { (need, maxed) -> !need || maxed }
    }

    /** Caller supplies one snapshot for the row; do not replace an enclosing normal-check snapshot. */
    fun read(bounds: SupportBounds, append: Boolean): List<Boolean> =
        SupportSkillLayout.levelRegions(bounds.region, append).map { region ->
            region.exists(images[Images.SkillTen], similarity = addedTuning.support.similarity(MAX_LEVEL_SIMILARITY))
        }
}
