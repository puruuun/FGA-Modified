package io.github.fate_grand_automata.scripts.supportSelection

import io.github.fate_grand_automata.SupportImageKind
import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.locations.SupportNpLayout
import io.github.lib_automata.Region
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

/**
 * Noble Phantasm (Re-ascension) condition (0006). Matches the saved NP name images against the NP
 * band of one support row. Empty list = no condition.
 */
@ScriptScope
class NoblePhantasmSelection @Inject constructor(
    api: IFgoAutomataApi
) : IFgoAutomataApi by api {
    companion object {
        // 0006 D-6. Not lower than 0.80: a threshold that only passes after lowering means the crop is wrong.
        const val NP_NAME_SIMILARITY = 0.85

        // Matches below this are too weak to log as evidence.
        const val LOG_FLOOR_SIMILARITY = 0.5
    }

    /** Pure read of the caller's candidate snapshot. True when the list is empty. */
    fun check(entries: List<String>, bounds: SupportBounds): Boolean {
        if (entries.isEmpty()) return true
        val band = SupportNpLayout.noblePhantasmBand(bounds.region)
        val best = bestScore(entries, band)
        val matched = best != null && best >= NP_NAME_SIMILARITY
        messages.log(ScriptLog.SupportNoblePhantasm(matched, best))
        return matched
    }

    /** Highest match score of any saved NP image inside [band], or null when nothing reaches the floor. */
    fun bestScore(entries: List<String>, band: Region): Double? =
        entries
            .flatMap { entry -> images.loadSupportPattern(SupportImageKind.NoblePhantasm, entry) }
            .mapNotNull { template -> band.findAll(template, LOG_FLOOR_SIMILARITY).maxOfOrNull { it.score } }
            .maxOrNull()
}
