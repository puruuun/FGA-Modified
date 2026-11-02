package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@ScriptScope
class Withdraw @Inject constructor(
    api: IFgoAutomataApi,
    private val turnLimitRetreat: TurnLimitRetreat
) : IFgoAutomataApi by api {
    /**
     * Checks if the window for withdrawing from the battle exists.
     */
    fun needsToWithdraw() =
        images[Images.Withdraw] in locations.withdrawRegion

    /**
     * Handles withdrawing from battle after a party wipe. The selected battle config's
     * "retreat and restart" switch decides whether the script withdraws or stops completely.
     *
     * @return true when the withdraw was done and counted as a retreat; the caller must then reset the run
     * without counting it.
     */
    fun withdraw(): Boolean {
        if (!turnLimitRetreat.isEnabled) {
            throw AutoBattle.BattleExitException(AutoBattle.ExitReason.WithdrawDisabled)
        }

        // Withdraw Region can vary depending on if you have Command Spells/Quartz
        val withdrawRegion = locations.withdrawRegion.find(images[Images.Withdraw])
            ?: return false

        withdrawRegion.region.click()

        0.5.seconds.wait()

        // Click the "Accept" button after choosing to withdraw
        locations.withdrawAcceptClick.click()

        1.seconds.wait()

        // Click the "Close" button after accepting the withdrawal
        locations.withdrawCloseClick.click()

        turnLimitRetreat.recordWipeWithdraw()
        return true
    }
}