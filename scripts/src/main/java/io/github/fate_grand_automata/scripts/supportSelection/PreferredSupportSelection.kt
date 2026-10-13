package io.github.fate_grand_automata.scripts.supportSelection

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.prefs.ISupportPreferences
import io.github.lib_automata.Region
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

@ScriptScope
class PreferredSupportSelection @Inject constructor(
    private val supportPrefs: ISupportPreferences,
    api: IFgoAutomataApi,
    private val boundsFinder: SupportBoundsFinder,
    private val friendChecker: SupportFriendChecker,
    private val servantSelection: ServantSelection,
    private val ceSelection: CESelection,
    private val friendSelection: FriendSelection,
    private val appendSelection: AppendSkillSelection,
    private val scoreSelection: SupportScoreSelection,
    private val npSelection: SupportNpSelection
) : SupportSelectionProvider, IFgoAutomataApi by api {
    private val servants = supportPrefs.preferredServants
    private val ces = supportPrefs.preferredCEs
    private val friendNames = supportPrefs.friendNames

    private data class ScrollBarState(
        val topScrollbar: Boolean = false,
        val movedScrollbar: Boolean = false,
        val bottomScrollbar: Boolean = false
    ) {
        fun resultWhenNoMatch(): SupportSelectionResult = when {
            topScrollbar -> SupportSelectionResult.ScrollDown
            movedScrollbar && !bottomScrollbar -> SupportSelectionResult.ScrollDown
            else -> SupportSelectionResult.EarlyRefresh
        }
    }

    override fun select(): SupportSelectionResult {
        if (servants.isEmpty() && ces.isEmpty() && !supportPrefs.score62Check &&
            !supportPrefs.grandScore16Check && !supportPrefs.npLevelCheck) {
            throw AutoBattle.BattleExitException(AutoBattle.ExitReason.SupportSelectionPreferredNotSet)
        }

        if (appendSelection.enabled) return selectWithAppendSkills()

        return useSameSnapIn {
            if (supportPrefs.friendsOnly && !friendChecker.isFriend()) {
                // no friends on screen, so there's no point in scrolling anymore
                return@useSameSnapIn SupportSelectionResult.Refresh
            }

            val matched = boundsFinder.all()
                .firstNotNullOfOrNull { isMatch(it) }

            if (matched != null) {
                matched.click()
                SupportSelectionResult.Done
            } else {
                val scrollBarState = readScrollBarState()
                scrollBarState.resultWhenNoMatch()
            }
        }
    }

    private fun selectWithAppendSkills(): SupportSelectionResult {
        appendSelection.ensureNormal()
        val bounds = useSameSnapIn {
            if (supportPrefs.friendsOnly && !friendChecker.isFriend()) null else boundsFinder.all()
        } ?: return SupportSelectionResult.Refresh

        for (bound in bounds) {
            // Pure normal-screen checks finish before any display mutation; refresh the next candidate's frame.
            val matched = useSameSnapIn { isMatch(bound) } ?: continue
            if (appendSelection.check(bound)) {
                matched.click()
                return SupportSelectionResult.Done
            }
        }
        return useSameSnapIn { readScrollBarState().resultWhenNoMatch() }
    }

    private fun readScrollBarState(): ScrollBarState {
        val topScrollbar = images[Images.SupportScrollBarTop] in locations.support.topScrollbarRegion

        if (topScrollbar) return ScrollBarState(topScrollbar = true)

        return ScrollBarState(
            movedScrollbar = images[Images.SupportScrollBarMoved] in locations.support.topScrollbarRegion,
            bottomScrollbar = images[Images.SupportScrollBarBottom] in locations.support.bottomScrollbarRegion
        )
    }

    private fun isMatch(bounds: SupportBounds): Region? {
        if (supportPrefs.friendsOnly && !friendChecker.isFriend(bounds)) {
            return null
        }

        return if (
            servantSelection.check(servants, bounds) &&
            scoreSelection.check(bounds) &&
            ceSelection.check(ces, bounds) &&
            (!supportPrefs.friendsOnly || friendSelection.check(friendNames, bounds)) &&
            // All snapshot-only conditions must finish before the slow Append display cycle.
            npSelection.check(bounds)
        ) {
            bounds.region
        } else null
    }
}
