package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Retreats from the quest through the Battle Menu once the battle reaches the configured turn.
 *
 * Only the Kr and Jp servers have the templates, so the feature never touches an image on any other server.
 * The sequence runs from [AutoBattle] after the battle loop has handed control back, so `isInBattle` is
 * already cleared when the result window and quest list appear.
 */
@ScriptScope
class TurnLimitRetreat @Inject constructor(
    api: IFgoAutomataApi,
    private val config: IBattleConfig
) : IFgoAutomataApi by api {
    companion object {
        const val STREAK_LIMIT = 3
        const val MAX_ATTEMPTS_PER_RUN = 2
    }

    /** Retreats performed (party wipes and turn limits together). Retreated runs are not runs. */
    var count = 0
        private set

    /** Party wipe withdraws among [count]. */
    var wipeCount = 0
        private set

    /** Turn-limit retreats among [count]. */
    val turnLimitCount get() = count - wipeCount

    /** Consecutive retreats; a normally cleared run resets it. */
    var streak = 0
        private set

    /** Set by [AutoBattle.runSelectedQuest]: the weekly route never retreats. */
    var disabled = false

    /** A retreat was requested at the start of the current turn and has not run yet. */
    var pending = false
        private set

    private var attempts = 0

    val isSupportedServer get() = prefs.gameServer.let { it is GameServer.Kr || it is GameServer.Jp }

    /** The one switch of "retreat and restart": off stops on a party wipe, on withdraws and restarts. */
    val isEnabled get() = config.turnLimitRetreatEnabled

    /** The configured turn limit; 0 (wipe only) outside Kr and Jp. */
    val limitTurns get() = if (isSupportedServer) config.turnLimitRetreatTurns.coerceAtLeast(0) else 0

    /**
     * Decides at the start of a turn. [effectiveTurn] is the run's turn counter minus the card-count
     * waiting turns, so the limit counts only turns the user's setup actually played.
     */
    fun shouldRetreat(effectiveTurn: Int): Boolean {
        if (!isEnabled || disabled || !isSupportedServer) return false
        if (limitTurns < 1 || effectiveTurn < limitTurns) return false
        pending = true
        return true
    }

    /** Runs the whole retreat sequence, from the battle screen to the quest list. */
    fun execute() {
        pending = false
        check(isSupportedServer) { "Turn limit retreat is only available on Kr and Jp" }
        if (++attempts > MAX_ATTEMPTS_PER_RUN) fail()

        openMenu()
        confirmRetreat()
        acceptAndClose()

        ++count
        ++streak
    }

    /** A party wipe was withdrawn from: it counts like a turn-limit retreat and never resets the streak. */
    fun recordWipeWithdraw() {
        ++count
        ++wipeCount
        ++streak
    }

    /** Stops after [STREAK_LIMIT] retreats in a row when the user asked for it. Call after the retreat finished. */
    fun checkStreak() {
        if (config.turnLimitRetreatStopAfterStreak && streak >= STREAK_LIMIT) {
            throw AutoBattle.BattleExitException(AutoBattle.ExitReason.TurnLimitRetreatStreak)
        }
    }

    /** A run was cleared normally. */
    fun onRunCleared() {
        streak = 0
        attempts = 0
    }

    /** The quest list is on screen again: the previous retreat worked. */
    fun onQuestListSeen() {
        attempts = 0
    }

    private fun fail(): Nothing =
        throw AutoBattle.BattleExitException(AutoBattle.ExitReason.TurnLimitRetreatFailed)

    /** Opens the Battle Menu until the retreat button shows; one retry. */
    private fun openMenu() {
        repeat(2) {
            locations.battle.master.battleMenuSearchRegion
                .find(images[Images.BattleMenu])
                ?.region
                ?.click()
            0.5.seconds.wait()
            if (locations.turnLimitRetreatRegion.exists(images[Images.TurnLimitRetreat], 3.seconds)) return
        }
        fail()
    }

    /**
     * The retreat dialog sits over the menu, so the menu's own button still matches behind it.
     * Only the dialog's confirm button proves it is open; a blind tap at that spot would flip
     * the menu's skill-use confirmation toggle.
     */
    private fun confirmRetreat() {
        locations.turnLimitRetreatClick.click()
        0.5.seconds.wait()
        if (!locations.turnLimitRetreatOkRegion.exists(images[Images.TurnLimitRetreatConfirm], 3.seconds)) {
            locations.battleMenuCloseClick.click()
            fail()
        }
    }

    private fun acceptAndClose() {
        locations.withdrawAcceptClick.click()
        0.5.seconds.wait()
        if (closeAppears()) return closeWindow()

        // A connection error dialog: the main loop retries it. The retreat still counts.
        if (locations.retryRegion.exists(images[Images.Retry])) return

        // The OK tap did not register and the dialog is still up: tap it once more.
        if (locations.turnLimitRetreatOkRegion.exists(images[Images.TurnLimitRetreatConfirm])) {
            locations.withdrawAcceptClick.click()
            0.5.seconds.wait()
            if (closeAppears()) return closeWindow()
        }

        // Still in the battle: never reset the run state.
        fail()
    }

    private fun closeAppears() = locations.ordealCallOutOfPodsRegion.exists(images[Images.Close], 15.seconds)

    private fun closeWindow() {
        locations.ordealCallOutOfPodsClick.click()
        // The quest list takes a while to load.
        5.seconds.wait()
    }
}
