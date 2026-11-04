package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.AccountState
import io.github.fate_grand_automata.scripts.capture.BattleGains
import io.github.fate_grand_automata.scripts.capture.RunGains
import kotlinx.serialization.json.*

/** Called under CaptureTracker's lock. VPN stop closes collection, retaining the completed prefix. */
internal class CaptureRunSession {
    private var scriptActive = false
    private var running = false
    private var run: RunGains? = null
    private var endState: AccountState? = null
    private var questId: Long? = null

    fun start(vpnRunning: Boolean, state: AccountState?) {
        discard()
        scriptActive = true
        setRunning(vpnRunning, state)
    }

    fun setRunning(value: Boolean, state: AccountState?) {
        running = value
        if (scriptActive && value && run == null) run = RunGains.start(state)
        if (scriptActive && !value) endState = state
    }

    fun collect(target: String, body: JsonObject, previous: AccountState?, next: AccountState?) {
        if (!scriptActive || !running) return
        val path = io.github.fate_grand_automata.scripts.capture.CaptureNames.path(target)
        if (path == "battle/setup") {
            val updated = (body["cache"] as? JsonObject)?.get("updated") as? JsonObject
            questId = listOf("userQuest", "battle", "userBattle").firstNotNullOfOrNull { table ->
                ((updated?.get(table) as? JsonArray)?.firstOrNull() as? JsonObject)?.get("questId")
                    ?.let { (it as? JsonPrimitive)?.longOrNull }
            }
        }
        if (path == "battle/result") {
            BattleGains.parse(body, previous)?.let { battle ->
                run = run?.add(battle.copy(questId = battle.questId ?: questId))
            }
        }
        endState = next
    }

    fun finish(state: AccountState?): RunGains? {
        val result = run?.finish(if (scriptActive && running) state else endState)
        discard()
        return result
    }

    fun stopCollection(state: AccountState?) {
        if (running) endState = state
        scriptActive = false
        running = false
    }

    fun discard() {
        scriptActive = false
        running = false
        run = null
        endState = null
        questId = null
    }
}
