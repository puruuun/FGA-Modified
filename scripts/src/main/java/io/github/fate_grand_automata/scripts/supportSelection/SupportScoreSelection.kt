package io.github.fate_grand_automata.scripts.supportSelection

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.enums.SupportScoreMatchMode
import io.github.fate_grand_automata.scripts.prefs.ISupportPreferences
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

@ScriptScope
class SupportScoreSelection @Inject constructor(
    api: IFgoAutomataApi,
    private val supportPrefs: ISupportPreferences,
    private val reader: SupportScoreReader
) : IFgoAutomataApi by api {
    companion object {
        const val SCORE_SIMILARITY = 0.90

        fun matches(scores: SupportScores, checkClass: Boolean, checkGrand: Boolean, mode: SupportScoreMatchMode): Boolean {
            val conditions = buildList {
                if (checkClass) add(scores.classScore == 62)
                if (checkGrand) add(scores.grandScore == 16)
            }
            return conditions.isEmpty() || if (mode == SupportScoreMatchMode.Any) conditions.any { it }
            else conditions.all { it }
        }
    }

    /** Pure read within the caller's snapshot, restricted to this candidate's score number. */
    fun check(bounds: SupportBounds): Boolean {
        val checkClass = supportPrefs.score62Check
        val checkGrand = supportPrefs.grandScore16Check
        if (!checkClass && !checkGrand) return true
        val scores = reader.read(bounds, checkClass, checkGrand)
        val matched = matches(scores, checkClass, checkGrand, supportPrefs.scoreMatchMode)
        messages.log(ScriptLog.SupportScoreConditions(
            scores.classScore, scores.grandScore, checkClass, checkGrand,
            supportPrefs.scoreMatchMode == SupportScoreMatchMode.All, matched
        ))
        return matched
    }
}
