package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.weekly.*
import kotlin.test.*

class WeeklyLauncherPolicyTest {
    @Test fun `empty route never falls through to an ordinary battle`() {
        assertEquals(WeeklyLauncherPolicy.Action.Empty, WeeklyLauncherPolicy.action(true, emptyList()))
        assertEquals(WeeklyLauncherPolicy.Action.Empty, WeeklyLauncherPolicy.action(true, listOf(0, 0)))
        assertEquals(WeeklyLauncherPolicy.Action.Weekly, WeeklyLauncherPolicy.action(true, listOf(1)))
        assertEquals(WeeklyLauncherPolicy.Action.Battle, WeeklyLauncherPolicy.action(false, emptyList()))
    }

    @Test fun `finding lower content moves finger up and reverse search moves it down`() {
        assertEquals(1180 to 540, ListSearchDirection.Down.fingerY(1180, 540))
        assertEquals(540 to 1180, ListSearchDirection.Up.fingerY(1180, 540))
        assertEquals(1180 to 540, ListSearchDirection.Down.fingerY(540, 1180))
        assertEquals(listOf(ListSearchDirection.Down, ListSearchDirection.Up), ListSearchDirection.entries.toList())
    }

    @Test fun `shared OK only starts weekly when panel is open and a positive count remains`() {
        assertFalse(WeeklyLauncherPolicy.weekly(false, listOf(1, 3)))
        assertFalse(WeeklyLauncherPolicy.weekly(true, emptyList()))
        assertFalse(WeeklyLauncherPolicy.weekly(true, listOf(0, 0)))
        assertTrue(WeeklyLauncherPolicy.weekly(true, listOf(0, 2)))
        assertFalse(WeeklyLauncherPolicy.weekly(false, emptyList()))
    }

    @Test fun `chapter switches preserve quest exclusions and separate server candidates`() {
        val quests = listOf(100, 101, 311).mapIndexed { index, war ->
            RouteQuest(index + 1, war, war, "chapter", "spot", "quest", index + 1, "free", "repeatLast")
        }
        val chapter = WeeklyCandidateSettings.chapterKey("JP", 100)
        val quest = WeeklyCandidateSettings.questKey("JP", 2)
        val disabled = setOf(chapter, quest)
        assertEquals(emptyList(), WeeklyCandidateSettings.candidates("JP", quests, disabled))
        assertEquals(listOf(1, 2), WeeklyCandidateSettings.candidates("KR", quests, disabled).map { it.id })
        assertEquals(listOf(1), WeeklyCandidateSettings.candidates("JP", quests, disabled - chapter).map { it.id })
    }
}
