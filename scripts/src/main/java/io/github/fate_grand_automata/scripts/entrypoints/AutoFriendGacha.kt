package io.github.fate_grand_automata.scripts.entrypoints

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.lib_automata.EntryPoint
import io.github.lib_automata.ExitManager
import io.github.fate_grand_automata.scripts.modules.FpAutoSell
import io.github.fate_grand_automata.scripts.modules.FpBannerReturn
import io.github.fate_grand_automata.scripts.modules.FpSellPolicy
import io.github.fate_grand_automata.scripts.modules.FpSellReader
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

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
        object BannerNotFound : ExitReason()
        object SellScreenUnknown : ExitReason()
        class SellDryRunDone(val summary: FpSellPolicy.Summary) : ExitReason()
        class Limit(val count: Int) : ExitReason()
    }

    class ExitException(val reason: ExitReason) : Exception()

    private var returning = false
    private var previousSold: Int? = null
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
                if (previousSold == 0) throw ExitException(ExitReason.InventoryFullNothingToSell)
                previousSold = autoSell.run()
                banner.restore()
                returning = true
            },
            { isFirstSummon() } to {
                if (prefs.fpAutoSell) banner.remember()
                if (returning) { countNext(); returning = false }
                // First 10x or 100x Summon
                locations.fp.first10SummonClick.click()
                0.3.seconds.wait()
                locations.fp.okClick.click()

                initialScreen = true
            },
            { isDailyFree() } to {
                if (prefs.fpAutoSell) banner.remember()
                if (returning) { countNext(); returning = false }
                // Daily Free Summon
                locations.fp.initialSummonClick.click()
                0.3.seconds.wait()
                locations.fp.initialSummonContinueClick.click()

                initialScreen = true
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
                0.5.seconds.wait()
            }
        } finally { banner.release() }
    }

    private fun rollFPAgain() {
        if (!isInLoop && initialScreen) {
            countNext()
            isInLoop = true
        }
        countNext()

        locations.fp.continueSummonClick.click()
        0.3.seconds.wait()
        locations.fp.okClick.click()
        3.seconds.wait()
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
