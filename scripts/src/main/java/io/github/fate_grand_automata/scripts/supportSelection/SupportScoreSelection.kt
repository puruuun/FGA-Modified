package io.github.fate_grand_automata.scripts.supportSelection

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.locations.SupportScoreLayout
import io.github.fate_grand_automata.scripts.prefs.ISupportPreferences
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

@ScriptScope
class SupportScoreSelection @Inject constructor(
    api: IFgoAutomataApi,
    private val supportPrefs: ISupportPreferences
) : IFgoAutomataApi by api {
    companion object {
        const val SCORE_SIMILARITY = 0.90
    }

    /** Pure read within the caller's snapshot, restricted to this candidate's score number. */
    fun check(bounds: SupportBounds): Boolean {
        if (!supportPrefs.score62Check) return true
        val matched = SupportScoreLayout.numberRegion(bounds.region)
            .exists(images[Images.SupportScore62], similarity = SCORE_SIMILARITY)
        messages.log(ScriptLog.SupportScore62(matched))
        return matched
    }
}
