package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.ScriptLog
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
    val enabled get() = prefs.bondTrackingFeatureEnabled && (config.trackBond || config.stopOnBondMax) &&
        (prefs.gameServer is GameServer.Kr || prefs.gameServer is GameServer.Jp)

    fun isScreen() = enabled && reader.isScreen()
    fun summary(): BondSummary? = if (enabled && config.trackBond) ledger.summary() else null

    fun beginBattle() { if (enabled) battleRun = state.runs }

    fun finishResult() {
        if (enabled && battleRun == state.runs) ledger.ensure(state.runs)
    }

    /** The last two confirmed gains must agree; unknown OCR frames supply no new value. */
    fun process(): BondFrame {
        if (!enabled) return BondFrame.Other
        // Wait for the result animation before capturing a fresh frame. Keep the user's
        // requested 1.2 second delay independent of the general wait multiplier.
        1200.milliseconds.wait(applyMultiplier = false)
        val readGains = config.trackBond
        fun read(number: Int): BondFrame = reader.read(readGains).also { frame ->
            messages.log(ScriptLog.Bond("run=${state.runs} read=$number ${frame::class.simpleName}"))
            reader.lastColumns.forEach { column ->
                messages.log(ScriptLog.Bond("run=${state.runs} read=$number ${column.compact()}"))
            }
        }
        data class ConfirmedRead(val number: Int, val value: BondValue, val rule: String?)
        val agreements = MutableList<Pair<Int, Int>?>(5) { null }
        val agreedRules = MutableList<String?>(5) { null }
        fun logStable(stable: List<BondValue>, latest: BondFrame.Normal?) {
            stable.forEachIndexed { index, value ->
                val reason = if (value != BondValue.Unknown) {
                    val reads = checkNotNull(agreements[index])
                    "agreed(reads=${reads.first},${reads.second})"
                } else when {
                    !readGains -> "gain reading disabled"
                    latest?.values?.getOrNull(index) == BondValue.Unknown -> "OCR consensus failed"
                    else -> "no agreement between reads"
                }
                val rule = agreedRules[index]
                    ?.takeIf { it in setOf("consensus", "majority", "majority+base", "unanimous+base") }
                messages.log(ScriptLog.Bond("run=${state.runs} final c${index + 1}=$value reason=$reason rule=${rule ?: "none"}"))
            }
        }
        var frame = read(0)
        if (frame is BondFrame.Normal) {
            var previous: BondFrame.Normal = frame
            val readings = mutableListOf(frame to reader.lastColumns)
            val stable = MutableList<BondValue>(5) { BondValue.Unknown }
            repeat(3) {
                350.milliseconds.wait()
                frame = read(it + 1)
                val current = frame as? BondFrame.Normal
                if (current == null) {
                    logStable(stable, null)
                    ledger.normal(state.runs, stable)
                    return record(frame)
                }
                readings += current to reader.lastColumns
                current.values.forEachIndexed { index, value ->
                    val confirmed = readings.mapIndexedNotNull { number, (reading, columns) ->
                        reading.values.getOrNull(index)?.takeUnless { it == BondValue.Unknown }?.let {
                            ConfirmedRead(number, it, columns.getOrNull(index)?.reason)
                        }
                    }.takeLast(2)
                    // Unknown gain OCR cannot erase agreement, but a later different value can.
                    // MAX still requires two adjacent frames, exactly as before.
                    val agrees = confirmed.size == 2 && confirmed[0].value == confirmed[1].value
                    val eligible = confirmed.lastOrNull()?.value is BondValue.Gain ||
                        (value == BondValue.Max && previous.values.getOrNull(index) == BondValue.Max)
                    stable[index] = if (agrees && eligible) confirmed.last().value else BondValue.Unknown
                    agreements[index] = if (stable[index] != BondValue.Unknown)
                        confirmed[0].number to confirmed[1].number else null
                    agreedRules[index] = if (stable[index] != BondValue.Unknown) confirmed.last().rule else null
                }
                previous = current
            }
            logStable(stable, previous)
            ledger.normal(state.runs, stable)
            checkStop()
            return frame
        }
        if (frame is BondFrame.LevelUp) {
            350.milliseconds.wait()
            val confirmed = read(1)
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
