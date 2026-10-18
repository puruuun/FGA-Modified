package io.github.fate_grand_automata.scripts.supportSelection

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.ScriptNotify
import io.github.fate_grand_automata.scripts.enums.GameServer
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

        /**
         * Servers whose support list shows Grand scores such as "+62/+16". Elsewhere a +16
         * condition could never pass, so the reader ignores it there (0005 D-10). Add a server
         * here once it ships Grand scores.
         */
        fun showsGrandScore(server: GameServer): Boolean = server is GameServer.Jp

        fun matches(scores: SupportScores, checkClass: Boolean, checkGrand: Boolean): Boolean =
            (!checkClass || scores.classScore == 62) &&
                (!checkGrand || (scores.display == SupportScoreDisplay.Compound && scores.grandScore == 16))

    }

    // One notice per run: this selection is scoped to a single script run.
    private var grandIgnoredNoticeShown = false

    /** Pure read within the caller's snapshot, restricted to this candidate's score number. */
    fun check(bounds: SupportBounds, screen: Int = -1, row: Int = -1): Boolean {
        val checkClass = supportPrefs.score62Check
        val grandRequested = supportPrefs.grandScore16Check
        if (grandRequested && !showsGrandScore(prefs.gameServer)) noticeGrandIgnored()
        val checkGrand = grandRequested && showsGrandScore(prefs.gameServer)
        if (!checkClass && !checkGrand) return true
        val scores = reader.read(bounds, checkClass, checkGrand, screen, row)
        val matched = matches(scores, checkClass, checkGrand)
        messages.log(ScriptLog.SupportScoreConditions(
            scores.classScore, scores.grandScore, checkClass, checkGrand,
            scores.display.name, matched
        ))
        return matched
    }

    private fun noticeGrandIgnored() {
        if (grandIgnoredNoticeShown) return
        grandIgnoredNoticeShown = true
        messages.log(ScriptLog.GrandScoreIgnored(prefs.gameServer.simple))
        messages.notify(ScriptNotify.GrandScoreNotSupported)
    }
}
