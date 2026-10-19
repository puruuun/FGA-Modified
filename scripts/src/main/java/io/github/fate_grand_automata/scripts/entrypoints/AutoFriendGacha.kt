package io.github.fate_grand_automata.scripts.entrypoints

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.lib_automata.EntryPoint
import io.github.lib_automata.ExitManager
import io.github.fate_grand_automata.scripts.modules.FpSellTiming
import io.github.fate_grand_automata.scripts.modules.FpSellButtons
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
    private val reader: FpSellReader
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
        /** The sell button, heading or servant tab was not found. */
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

    private val returnButtons = FpSellButtons(api)
    private var saleStarted = false
    private var returning = false
    private var previousSale: FpAutoSell.Result? = null
    private var awaitingDryFull = false
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
                previousSale?.let { previous ->
                    if (previous.summary.total == 0) throw ExitException(ExitReason.InventoryFullNothingToSell)
                    if (previous.dryRun) {
                        if (!awaitingDryFull) throw ExitException(ExitReason.SellFlowFailed(true, previous.summary.total, previous.summary.unknownLock))
                        messages.log(ScriptLog.FpSell("stop reason=dry-run-flow-banner-resummon-verified selected=${previous.summary.total}"))
                        throw ExitException(ExitReason.SellDryRunDone(previous.summary))
                    }
                }
                saleStarted = true
                previousSale = autoSell.run()
                banner.restore()
                returning = true
            },
            { isFirstSummon() } to {
                if (prefs.fpAutoSell) banner.remember()
                if (returning) resumeSummon() else {
                    // Initial summon behavior is unchanged when auto-sell is off.
                    locations.fp.first10SummonClick.click()
                    FpSellTiming.summonConfirm(prefs.fpAutoSell).wait()
                    locations.fp.okClick.click()
                    initialScreen = true
                }
            },
            { isDailyFree() } to {
                if (prefs.fpAutoSell) banner.remember()
                if (returning) resumeSummon() else {
                    locations.fp.initialSummonClick.click()
                    FpSellTiming.summonConfirm(prefs.fpAutoSell).wait()
                    locations.fp.initialSummonContinueClick.click()
                    initialScreen = true
                }
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
                    if (awaitingDryFull && unknownFrames >= 40) {
                        val summary = checkNotNull(previousSale).summary
                        messages.log(ScriptLog.FpSell("stop reason=second-full-warning-not-seen selected=${summary.total} dryRun=true"))
                        throw ExitException(ExitReason.SellFlowFailed(true, summary.total, summary.unknownLock))
                    }
                    if (unknownFrames >= 120) throw ExitException(ExitReason.SellScreenUnknown)
                }
                val action = actor ?: { if (!awaitingDryFull) locations.fp.skipRapidClick.click(15) }
                action.invoke()
                FpSellTiming.summonLoop(prefs.fpAutoSell).wait()
            }
        } finally {
            if (saleStarted) autoSell.logFinalSummary()
            banner.release()
        }
    }

    private fun resumeSummon() {
        countNext()
        banner.summonAgain()
        isInLoop = true
        returning = false
        initialScreen = true
        val previous = checkNotNull(previousSale)
        if (previous.dryRun) {
            // The full-box warning appears without spending FP. Never acknowledge another summon dialog in dry run.
            awaitingDryFull = true
            return
        }
        // Real mode preserves the ordinary summon confirmation, but matches the button before tapping it.
        var ok: io.github.lib_automata.Match? = null
        FpSellTiming.BUTTON_SETTLE.wait()
        val reached = returnButtons.awaitSignal(FpSellTiming.FIND_POLLS) {
            if (isInventoryFull() || reader.servantFullWarning() || isSummonButtonVisible()) true else {
                ok = reader.loc.summonOk.find(images[Images.Ok], similarity = 0.9)
                ok != null
            }
        }
        if (!reached) throw ExitException(ExitReason.SellFlowFailed(false, previous.summary.total, previous.summary.unknownLock))
        ok?.region?.click()
    }

    private fun rollFPAgain() {
        if (!isInLoop && initialScreen) {
            countNext()
            isInLoop = true
        }
        countNext()

        locations.fp.continueSummonClick.click()
        FpSellTiming.summonConfirm(prefs.fpAutoSell).wait()
        locations.fp.okClick.click()
        FpSellTiming.summonAnimation(prefs.fpAutoSell).wait()
    }

    private fun isSummonButtonVisible() = !awaitingDryFull && findImage(locations.fp.continueSummonRegion, Images.FPSummonContinue)

    private fun isFirstSummon(): Boolean {
        if (awaitingDryFull || (isInLoop && !returning)) return false

        return images[Images.FriendSummon] in locations.fp.summonCheck
    }

    private fun isDailyFree(): Boolean {
        if (awaitingDryFull || (isInLoop && !returning)) return false

        return images[Images.FriendSummon] in locations.fp.initialSummonCheck
    }
}
