package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.modules.*
import io.github.fate_grand_automata.scripts.prefs.*
import io.github.fate_grand_automata.scripts.supportSelection.*
import io.github.fate_grand_automata.scripts.weekly.ListScrollbar
import io.github.fate_grand_automata.scripts.weekly.WeeklyPanelFlow
import io.github.lib_automata.*
import io.mockk.*
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AddedFeatureTuningTest {
    private val defaults = IAddedFeatureTuning.Default

    @Test fun `every routed wait constant retains its exact old duration at defaults`() {
        fun check(tuning: FeatureTuning, values: List<Pair<Duration, Long>>) {
            values.forEach { (base, oldMs) -> assertEquals(oldMs.milliseconds, tuning.delay(base)) }
        }
        check(defaults.weekly, listOf(
            700.milliseconds to 700L, 2.seconds to 2000L, 3.seconds to 3000L,
            1.seconds to 1000L, 500.milliseconds to 500L, 300.milliseconds to 300L,
            WeeklyPanelFlow.enterMapWait to 3000L, WeeklyPanelFlow.leaveMapWait to 3000L
        ))
        check(defaults.fp, listOf(
            FpSellTiming.AFTER_ACTION to 1000L, FpSellTiming.SELL_SCREEN_LOAD to 2000L,
            FpSellTiming.BUTTON_SETTLE to 1000L, FpSellTiming.SELECTION_SCROLL to 700L,
            FpSellTiming.AFTER_CLOSE to 4000L, FpSellTiming.SUMMON_LOAD_WAIT to 5000L,
            FpSellTiming.AFTER_ARROW to 1200L, FpSellTiming.SUMMON_CONFIRM to 300L,
            FpSellTiming.SUMMON_ANIMATION to 3000L
        ))
        check(defaults.skill, listOf(
            SkillLevelTiming.SELECT_SETTLE to 1000L, SkillLevelTiming.AFTER_ENHANCE to 400L,
            SkillLevelTiming.AFTER_CANCEL to 400L, SkillLevelTiming.AFTER_SKIP to 500L
        ))
        check(defaults.ce, listOf(0.5.seconds to 500L, 1.seconds to 1000L))
        check(defaults.quest, listOf(defaults.questTapSettle to 100L, defaults.questPollInterval to 300L,
            1.seconds to 1000L, 2.seconds to 2000L))
        check(defaults.bond, listOf(1200.milliseconds to 1200L, 350.milliseconds to 350L))
        check(defaults.support, listOf(0.2.seconds to 200L, 0.3.seconds to 300L))
    }

    @Test fun `every routed template constant retains its old double bits at defaults`() {
        fun check(tuning: FeatureTuning, values: List<Pair<Double, Double>>) {
            values.forEach { (base, old) -> assertEquals(old.toBits(), tuning.similarity(base).toBits()) }
        }
        check(defaults.weekly, listOf(
            WeeklyRouteDriver.BANNER_SIMILARITY to 0.85, WeeklyRouteDriver.BUTTON_SIMILARITY to 0.80,
            WeeklyVaultReader.ANCHOR_SIMILARITY to 0.85, ListScrollbar.SIMILARITY to 0.60,
            QuestPanelReader.FREE_SIMILARITY to 0.75, 0.85 to 0.85, 0.7 to 0.7, 0.88 to 0.88
        ))
        check(defaults.fp, listOf(FpSellReader.SCROLLBAR_SIMILARITY to 0.72,
            FpSellReader.LOCK_SIMILARITY to 0.80, 0.9 to 0.9, 0.85 to 0.85))
        check(defaults.skill, listOf(
            SkillLevelScreens.LABEL_SIMILARITY to 0.9, SkillLevelScreens.RESELECT_SIMILARITY to 0.85,
            SkillLevelScreens.UNLOCK_BUTTON_SIMILARITY to 0.7, SkillLevelScreens.MAX_BUTTON_SIMILARITY to 0.8,
            SkillLevelScreens.DIALOG_SIMILARITY to 0.8, SkillLevelScreens.LOCK_SIMILARITY to 0.85,
            SkillLevelScreens.DIALOG_TITLE_SIMILARITY to 0.8, SkillLevelScreens.RESULT_SIMILARITY to 0.7,
            PanelOutlineMatcher.MIN_SCORE to 0.70
        ))
        check(defaults.ce, listOf(0.85 to 0.85))
        check(defaults.quest, listOf(QuestItemReader.STATE_SIMILARITY to 0.88,
            QuestItemReader.ICON_SIMILARITY to 0.88, QuestItemReader.SCREEN_SIMILARITY to 0.9))
        check(defaults.bond, listOf(BondReader.IMAGE_SIMILARITY to 0.85))
        check(defaults.support, listOf(
            NoblePhantasmSelection.NP_NAME_SIMILARITY to 0.85, SupportNpLevelReader.PREFIX_SIMILARITY to 0.80,
            SupportNpLevelReader.RECOVERY_PREFIX_SIMILARITY to 0.75, SupportNpLevelReader.KR_RECOVERY_PREFIX_SIMILARITY to 0.72,
            SupportScoreSelection.SCORE_SIMILARITY to 0.9, SupportScoreBlocks.SIMILARITY to 0.9,
            SupportScoreReader.COMPACT_FIELD_SIMILARITY to 0.94, SupportScoreReader.GRAND_FIELD_SIMILARITY to 0.90,
            SupportSkillDisplay.LABEL_SIMILARITY to 0.9, SupportSkillLevelReader.MAX_LEVEL_SIMILARITY to 0.68,
            CESelection.BOND_EFFECT_SIMILARITY to 0.55
        ))
    }

    @Test fun `global multiplier composes once and bypass waits use feature multiplier only`() {
        val platform = mockk<PlatformImpl>()
        val exit = mockk<ExitManager>(relaxed = true)
        every { platform.prefs.waitMultiplier } returns 1.5
        val waiter = RealWaiter(platform, exit)
        val tuning = FeatureTuning(delayPercent = 200)
        waiter(tuning.delay(300.milliseconds))
        waiter(tuning.delay(100.milliseconds), applyMultiplier = false)
        verifySequence { exit.wait(900.milliseconds); exit.wait(200.milliseconds) }
    }

    @Test fun `similarity offset adds clamps and preserves the original nullable default argument`() {
        assertEquals(0.99, FeatureTuning(similarityOffsetPercent = 5).similarity(0.98))
        assertEquals(0.40, FeatureTuning(similarityOffsetPercent = -5).similarity(0.41))
        assertEquals(0.88 + 0.03, FeatureTuning(similarityOffsetPercent = 3).similarity(0.88))
        assertEquals(0.88 - 0.03, FeatureTuning(similarityOffsetPercent = -3).similarity(0.88))
        assertNull(FeatureTuning().defaultSimilarity { error("Default must keep the matcher argument null") })
        assertEquals(0.8 + 0.03, FeatureTuning(similarityOffsetPercent = 3).defaultSimilarity { 0.8 })
    }

    @Test fun `feature changes do not affect other sections or safety timings`() {
        val tuned = AddedFeatureTuning(weekly = FeatureTuning(300, -5))
        assertEquals(3.seconds, tuned.weekly.delay(1.seconds))
        for (feature in listOf(tuned.fp, tuned.skill, tuned.ce, tuned.quest, tuned.bond, tuned.support)) {
            assertEquals(1.seconds, feature.delay(1.seconds))
            assertEquals(0.85, feature.similarity(0.85))
        }
        assertEquals(20.seconds, FpSellTiming.SCREEN_POLL * FpSellTiming.SCREEN_POLLS)
        assertEquals(2.seconds, FpSellTiming.FIND_POLL * FpSellTiming.FIND_POLLS)
        assertEquals(20.seconds, SkillLevelTiming.SKIP_INTERVAL * SkillLevelTiming.SKIP_POLLS)
        assertEquals(2.seconds, SkillLevelTiming.FIND_POLL * SkillLevelTiming.DIALOG_POLLS)
    }

    @Test fun `classification thresholds change without changing score gaps`() {
        assertEquals(CEEnhanceScreens.Recommended.Absent, CEEnhanceScreens.recommended(0.83, 0.4))
        assertEquals(CEEnhanceScreens.Recommended.Active,
            CEEnhanceScreens.recommended(0.83, 0.4, FeatureTuning(similarityOffsetPercent = -3)))
        assertFalse(ListScrollbar.read(0.58, null).top)
        assertTrue(ListScrollbar.read(0.58, null, FeatureTuning(similarityOffsetPercent = -3)).top)
        assertEquals(io.github.fate_grand_automata.scripts.weekly.RouteScreen.Unknown,
            WeeklyRouteScreens.classify(0.86, 0.84, 0.3, FeatureTuning(similarityOffsetPercent = -3)))
    }
}
