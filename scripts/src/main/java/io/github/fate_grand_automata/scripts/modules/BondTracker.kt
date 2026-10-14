package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.models.*
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@ScriptScope
class BondTracker @Inject constructor(api: IFgoAutomataApi, private val reader: BondReader, private val state: BattleState) : IFgoAutomataApi by api {
    private val ledger = BondLedger()
    private var party: BondParty? = null
    private var battleRun: Int? = null
    val enabled get() = (prefs.selectedServerConfigPref.trackBond || prefs.selectedServerConfigPref.stopOnBondMax) &&
        (prefs.gameServer is GameServer.Kr || prefs.gameServer is GameServer.Jp)

    fun isScreen() = enabled && reader.isScreen()
    fun summary(): BondSummary? = if (enabled && prefs.selectedServerConfigPref.trackBond) ledger.summary() else null

    fun captureParty() {
        if (!enabled) return
        var previous: BondParty? = null
        repeat(4) {
            val current = reader.readParty()
            if (current != null && current == previous) { party = current; return }
            previous = current
            300.milliseconds.wait()
        }
        throw AutoBattle.BattleExitException(AutoBattle.ExitReason.BondPartyUnavailable)
    }

    fun beginBattle() { if (enabled) battleRun = state.runs }

    fun finishResult() {
        if (enabled && battleRun == state.runs) party?.let { ledger.ensure(state.runs, it) }
    }

    /** Two agreeing fresh frames per value prevent reading the result counting animation. */
    fun process(): BondFrame {
        if (!enabled) return BondFrame.Other
        val positions = party ?: throw AutoBattle.BattleExitException(AutoBattle.ExitReason.BondPartyUnavailable)
        val readGains = prefs.selectedServerConfigPref.trackBond
        var frame = reader.read(readGains)
        if (frame is BondFrame.Normal) {
            var previous: BondFrame.Normal = frame
            val stable = MutableList<BondValue>(5) { BondValue.Unknown }
            repeat(3) {
                350.milliseconds.wait()
                frame = reader.read(readGains)
                val current = frame as? BondFrame.Normal
                if (current == null) {
                    ledger.normal(state.runs, positions, stable)
                    return record(frame, positions)
                }
                current.values.forEachIndexed { index, value ->
                    // Keep only the latest agreement, not an intermediate animated number.
                    stable[index] = if (previous.values.getOrNull(index) == value) value else BondValue.Unknown
                }
                previous = current
            }
            ledger.normal(state.runs, positions, stable)
            checkStop()
            return frame
        }
        if (frame is BondFrame.LevelUp) {
            350.milliseconds.wait()
            val confirmed = reader.read(readGains)
            if (frame != confirmed) {
                ledger.levelUp(state.runs, positions, null, null)
                return confirmed
            }
        }
        return record(frame, positions)
    }

    private fun record(frame: BondFrame, party: BondParty): BondFrame {
        if (frame is BondFrame.LevelUp) {
            ledger.levelUp(state.runs, party, frame.reachedLevel, frame.resultIndex)
            checkStop()
        }
        return frame
    }

    private fun checkStop() {
        if (!prefs.selectedServerConfigPref.stopOnBondMax) return
        val ignored = prefs.selectedServerConfigPref.bondMaxIgnoredPositions
        val watchedMax = ledger.maxPositions(state.runs) - ignored
        if (watchedMax.isNotEmpty()) {
            state.nextRun() // This quest has cleared even though we stop before the reward clicks.
            throw AutoBattle.BattleExitException(AutoBattle.ExitReason.BondMax(watchedMax.sorted()))
        }
    }
}
