package io.github.fate_grand_automata.scripts.modules

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.capture.*
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** One decision per script and one response wait per battle for bond. */
@ScriptScope
class CaptureBattleTracker @Inject constructor(
    api: IFgoAutomataApi,
    private val state: BattleState,
    private val config: IBattleConfig,
    private val feed: ICaptureFeed = ICaptureFeed.None,
) : IFgoAutomataApi by api {
    var enabled = false
        private set
    private var started = false
    private var token = 0L
    private var awaited = false
    private var battle: CapturedBattle? = null
    private var bondChecked = false
    var battleNumber = 0
        private set

    fun start() {
        if (started) return
        started = true
        if (prefs.gameServer == GameServer.Kr || prefs.gameServer == GameServer.Jp.Original) {
            for (attempt in 1..30) {
                if (feed.isCollecting() || !feed.isStarting()) break
                100.milliseconds.wait(applyMultiplier = false)
            }
            enabled = feed.isCollecting()
        }
        messages.log(ScriptLog.CaptureMode(enabled))
        beginBattle()
    }

    /** Called before launching/repeating a quest, never on each turn. */
    fun beginBattle() {
        if (!enabled) return
        battleNumber++
        token = feed.battleToken()
        awaited = false
        battle = null
        bondChecked = false
    }

    /** The first response of this battle, or null when it did not arrive in time. Never stops the script. */
    fun response(): CapturedBattle? {
        if (!enabled) return null
        if (!awaited) {
            battle = feed.awaitBattle(token, 3.seconds)
            awaited = true
            battle?.let { messages.log(ScriptLog.Bond(slotLog(it))) }
        }
        return battle
    }

    fun checkBond() {
        if (!enabled || bondChecked) return
        if (!prefs.bondTrackingFeatureEnabled) return
        bondChecked = true
        val result = response() ?: return
        if (!config.stopOnBondMax) return
        // Diagnostic only: the stop itself comes from the screen reading in BondTracker.
        val decision = CaptureBondStop.check(result, config.bondMaxIgnoredCharacters)
        messages.log(ScriptLog.Bond("capture stop decision (comparison only)=${decision ?: "undecided"}"))
    }

    companion object {
        /** One line per battle: own slots in deck order, then the same servants sorted by setup initPos. */
        fun slotLog(battle: CapturedBattle): String {
            val rows = battle.bond ?: return "capture bond slots=none"
            val slots = rows.joinToString(" | ") {
                "${it.slot}:svt=${it.svtId} r=${it.oldRank}->${it.newRank} cap=${it.cap} +${it.gained}"
            }
            val byInit = rows.sortedBy { it.initPos ?: Int.MAX_VALUE }.map { it.svtId }
            return "capture bond slots=$slots | initPosOrder=$byInit"
        }
    }
}
