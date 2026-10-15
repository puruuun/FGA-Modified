package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.models.*
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@ScriptScope
class BondTracker @Inject constructor(
    api: IFgoAutomataApi,
    private val reader: BondReader,
    private val state: BattleState,
    private val config: IBattleConfig
) : IFgoAutomataApi by api {
    private val ledger = BondLedger()
    private var battleRun: Int? = null
    val enabled get() = (config.trackBond || config.stopOnBondMax) &&
        (prefs.gameServer is GameServer.Kr || prefs.gameServer is GameServer.Jp)

    fun isScreen() = enabled && reader.isScreen()
    fun summary(): BondSummary? = if (enabled && config.trackBond) ledger.summary() else null

    fun beginBattle() { if (enabled) battleRun = state.runs }

    fun finishResult() {
        if (enabled && battleRun == state.runs) ledger.ensure(state.runs)
    }

    /** Two agreeing fresh frames per value prevent reading the result counting animation. */
    fun process(): BondFrame {
        if (!enabled) return BondFrame.Other
        // Wait for the result animation before capturing a fresh frame. Keep the user's
        // requested 1.2 second delay independent of the general wait multiplier.
        1200.milliseconds.wait(applyMultiplier = false)
        val readGains = config.trackBond
        var frame = reader.read(readGains)
        if (frame is BondFrame.Normal) {
            var previous: BondFrame.Normal = frame
            val stable = MutableList<BondValue>(5) { BondValue.Unknown }
            repeat(3) {
                350.milliseconds.wait()
                frame = reader.read(readGains)
                val current = frame as? BondFrame.Normal
                if (current == null) {
                    ledger.normal(state.runs, stable)
                    return record(frame)
                }
                current.values.forEachIndexed { index, value ->
                    // Keep only the latest agreement, not an intermediate animated number.
                    stable[index] = if (previous.values.getOrNull(index) == value) value else BondValue.Unknown
                }
                previous = current
            }
            ledger.normal(state.runs, stable)
            checkStop()
            return frame
        }
        if (frame is BondFrame.LevelUp) {
            350.milliseconds.wait()
            val confirmed = reader.read(readGains)
            if (frame != confirmed) {
                ledger.levelUp(state.runs, null, null)
                return confirmed
            }
        }
        return record(frame)
    }

    private fun record(frame: BondFrame): BondFrame {
        if (frame is BondFrame.LevelUp) {
            ledger.levelUp(state.runs, frame.reachedLevel, frame.resultIndex)
        }
        checkStop()
        return frame
    }

    private fun checkStop() {
        if (!config.stopOnBondMax) return
        val watchedMax = ledger.maxPositions(state.runs) - config.bondMaxIgnoredCharacters
        if (watchedMax.isNotEmpty()) {
            state.nextRun() // This quest has cleared even though we stop before the reward clicks.
            throw AutoBattle.BattleExitException(AutoBattle.ExitReason.BondMax(watchedMax.sorted()))
        }
    }
}
