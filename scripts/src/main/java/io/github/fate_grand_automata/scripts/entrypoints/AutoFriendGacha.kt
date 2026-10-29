package io.github.fate_grand_automata.scripts.entrypoints

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.prefs.IAddedFeatureTuning
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.lib_automata.EntryPoint
import io.github.lib_automata.ExitManager
import io.github.fate_grand_automata.scripts.modules.FpSellTiming
import io.github.fate_grand_automata.scripts.modules.FpAutoSell
import io.github.fate_grand_automata.scripts.modules.FpBannerReturn
import io.github.fate_grand_automata.scripts.modules.FpSellPolicy
import io.github.fate_grand_automata.scripts.modules.FpSellReader
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

/**
 * Continually triggers 10x Summon, intended for FP summons, but could also be used for SQ summons.
 * With auto-sell on, a servant-slot full warning sells the configured tiles and resumes the same banner (0007).
 * With auto-sell off, every full inventory warning stops the script, as before.
 */
@ScriptScope
class AutoFriendGacha @Inject constructor(
    exitManager: ExitManager,
    api: IFgoAutomataApi,
    private val autoSell: FpAutoSell,
    private val banner: FpBannerReturn,
    private val reader: FpSellReader,
    private val addedTuning: IAddedFeatureTuning = IAddedFeatureTuning.Default
) : EntryPoint(exitManager), IFgoAutomataApi by api {
    sealed class ExitReason {
        object InventoryFull : ExitReason()
        object InventoryFullNothingToSell : ExitReason()
        /** The full warning is not for the servant slots (Craft Essence, command code or an unknown dialog). */
        object InventoryFullNotServant : ExitReason()
        sealed class BannerReturnStopped(val label: String) : ExitReason()
        object WorkshopNotReached : BannerReturnStopped("workshop")
        object WorkshopMenuFailed : BannerReturnStopped("menu")
        object SummonMenuFailed : BannerReturnStopped("summon")
        object BannerTitleNotFound : BannerReturnStopped("title")
        /** The summon loop saw no known screen for too long (not a sell step). */
        object SellScreenUnknown : ExitReason()
        /** A sell step stopped without selling. Every one carries the dry-run state and the tiles selected so far (D-21). */
        sealed class SellStopped(val dryRun: Boolean, val selected: Int, val unknownLock: Int) : ExitReason()
        /** The sell button, card grid or servant tab was not found. */
        class SellEntryFailed(dryRun: Boolean, selected: Int, unknownLock: Int = 0) : SellStopped(dryRun, selected, unknownLock)
        /** The seven-column layout was not reached. */
        class ColumnSwitchFailed(dryRun: Boolean, selected: Int, unknownLock: Int = 0) : SellStopped(dryRun, selected, unknownLock)
        /** The sell screen or grid could not be read while selecting. */
        class SelectionScreenLost(dryRun: Boolean, selected: Int, unknownLock: Int = 0) : SellStopped(dryRun, selected, unknownLock)
        /** The confirm, convert or close step of the real sale did not appear. */
        class SellFlowFailed(dryRun: Boolean, selected: Int, unknownLock: Int = 0) : SellStopped(dryRun, selected, unknownLock)
        class SellDryRunDone(val summary: FpSellPolicy.Summary) : ExitReason()
        class Limit(val count: Int) : ExitReason()
    }

    class ExitException(val reason: ExitReason) : Exception()

    /** The steps of one sell run, in order. */
    enum class SellStep { Entry, Columns, Selection, Flow }

    companion object {
        fun sellStop(step: SellStep, dryRun: Boolean, selected: Int, unknownLock: Int = 0): ExitReason.SellStopped = when (step) {
            SellStep.Entry -> ExitReason.SellEntryFailed(dryRun, selected, unknownLock)
            SellStep.Columns -> ExitReason.ColumnSwitchFailed(dryRun, selected, unknownLock)
            SellStep.Selection -> ExitReason.SelectionScreenLost(dryRun, selected, unknownLock)
            SellStep.Flow -> ExitReason.SellFlowFailed(dryRun, selected, unknownLock)
        }
    }

    private var saleStarted = false
    private var returning = false
    private var count = 0
    private var isInLoop = false
    private var initialScreen = false

    private fun countNext() {
        if (prefs.shouldLimitFP && count >= prefs.limitFP) {
            throw ExitException(ExitReason.Limit(count))
        }

        ++count
    }

    override fun script(): Nothing {
        val screens: Map<() -> Boolean, () -> Unit> = mapOf(
            { isInventoryFull() || (prefs.fpAutoSell && reader.servantFullWarning()) } to {
                if (!prefs.fpAutoSell) throw ExitException(ExitReason.InventoryFull)
                if (!reader.servantFullWarning()) throw ExitException(ExitReason.InventoryFullNotServant)
                saleStarted = true
                autoSell.run()
                banner.restore()
                returning = true
            },
            { isFirstSummon() } to {
                if (prefs.fpAutoSell) banner.remember()
                if (returning) resumeSummon(dailyFree = false) else summon(dailyFree = false)
            },
            { isDailyFree() } to {
                if (prefs.fpAutoSell) banner.remember()
                if (returning) resumeSummon(dailyFree = true) else summon(dailyFree = true)
            },
            { isSummonButtonVisible() } to { rollFPAgain() }
        )

        try {
            var unknownFrames = 0
            while (true) {
                val actor = useSameSnapIn {
                    screens
                        .asSequence()
                        .filter { (validator, _) -> validator() }
                        .map { (_, actor) -> actor }
                        .firstOrNull()
                }
                if (prefs.fpAutoSell) {
                    unknownFrames = if (actor == null) unknownFrames + 1 else 0
                    if (unknownFrames >= 120) throw ExitException(ExitReason.SellScreenUnknown)
                }
                val action = actor ?: { locations.fp.skipRapidClick.click(15) }
                action.invoke()
                FpSellTiming.SUMMON_LOOP.wait()
            }
        } finally {
            if (saleStarted) autoSell.logFinalSummary()
            banner.release()
        }
    }

    /** D-48: first summon and return use exactly the same original FGA taps and delay. */
    private fun summon(dailyFree: Boolean) {
        if (dailyFree) locations.fp.initialSummonClick.click() else locations.fp.first10SummonClick.click()
        summonWait(FpSellTiming.SUMMON_CONFIRM)
        if (dailyFree) locations.fp.initialSummonContinueClick.click() else locations.fp.okClick.click()
        initialScreen = true
    }

    private fun resumeSummon(dailyFree: Boolean) {
        countNext()
        banner.verifySummon()
        isInLoop = true
        returning = false
        summon(dailyFree)
    }

    private fun rollFPAgain() {
        if (!isInLoop && initialScreen) {
            countNext()
            isInLoop = true
        }
        countNext()

        locations.fp.continueSummonClick.click()
        summonWait(FpSellTiming.SUMMON_CONFIRM)
        locations.fp.okClick.click()
        summonWait(FpSellTiming.SUMMON_ANIMATION)
    }

    private fun summonWait(base: kotlin.time.Duration) {
        (if (prefs.fpAutoSell) addedTuning.fp.delay(base) else base).wait()
    }

    private fun isSummonButtonVisible() = findImage(locations.fp.continueSummonRegion, Images.FPSummonContinue)

    private fun isFirstSummon(): Boolean {
        if (isInLoop && !returning) return false

        return images[Images.FriendSummon] in locations.fp.summonCheck
    }

    private fun isDailyFree(): Boolean {
        if (isInLoop && !returning) return false

        return images[Images.FriendSummon] in locations.fp.initialSummonCheck
    }
}
