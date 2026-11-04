package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.AccountState
import kotlinx.serialization.json.*
import kotlin.test.*

class CaptureRunSessionTest {
    private val result = Json.parseToJsonElement("""{"response":[{"success":{"phaseClearQp":100,
        "oldUserGame":[{"exp":10}],"oldUserSvtCollection":[{"svtId":1,"friendship":10}]}}],
        "cache":{"updated":{"userGame":[{"exp":20}],"userSvtCollection":[{"svtId":1,"friendship":50}]}}}""").jsonObject
    private fun state(num: Int) = AccountState(mapOf("userItem" to
        Json.parseToJsonElement("""[{"itemId":100,"num":$num}]""").jsonArray),
        JsonArray(emptyList()), snapshotTime = 1, lastUpdateTime = 1)

    @Test fun runningVpnAtScriptStartOpensAndFinishConsumesRun() {
        val session = CaptureRunSession()
        session.start(true, state(5))
        session.collect("/battle/result?discard=query", result, state(5), state(4))
        val run = assertNotNull(session.finish(state(4)))
        assertEquals(1, run.capturedBattles)
        assertEquals(100, run.qp)
        assertEquals(10, run.masterExp)
        assertEquals(mapOf(1L to 40L), run.bond)
        assertEquals(1, run.fruitUsed[100])
        assertNull(session.finish(state(4)))
    }

    @Test fun inactiveVpnDoesNotCreateRunButStartingItDuringScriptDoes() {
        val session = CaptureRunSession()
        session.start(false, null)
        session.collect("/battle/result", result, null, null)
        assertNull(session.finish(null))
        session.start(false, null)
        session.setRunning(true, null)
        session.collect("/battle/result", result, null, null)
        assertEquals(1, assertNotNull(session.finish(null)).capturedBattles)
    }

    @Test fun manualBattlesOutsideScriptAreIgnored() {
        val session = CaptureRunSession()
        session.setRunning(true, state(5))
        session.collect("/battle/result", result, state(5), state(4))
        assertNull(session.finish(state(4)))
    }

    @Test fun discardClearsPausedOrZeroRunExitAndNextRunStartsClean() {
        val session = CaptureRunSession()
        session.start(true, null)
        session.collect("/battle/result", result, null, null)
        session.discard()
        assertNull(session.finish(null))
        session.start(true, null)
        assertEquals(0, assertNotNull(session.finish(null)).capturedBattles)
    }

    @Test fun vpnStopKeepsPrefixAndIgnoresLaterResponses() {
        val session = CaptureRunSession()
        session.start(true, state(5))
        session.collect("/battle/result", result, state(5), state(4))
        session.setRunning(false, state(4))
        session.collect("/battle/result", result, state(4), state(1))
        session.stopCollection(state(1))
        val run = assertNotNull(session.finish(state(1)))
        assertEquals(1, run.capturedBattles)
        assertEquals(1, run.fruitUsed[100])
        assertEquals(2, run.withRunCount(3).missingBattles)
    }

    @Test fun scriptEndFreezesRunEvenWhenManuallyStartedVpnStaysRunning() {
        val session = CaptureRunSession()
        session.start(true, null)
        session.collect("/battle/result", result, null, null)
        session.stopCollection(null)
        session.setRunning(true, null)
        session.collect("/battle/result", result, null, null)
        assertEquals(1, assertNotNull(session.finish(null)).capturedBattles)
    }

    @Test fun otherResponsesUpdateHoldingsButAreNotBattles() {
        val session = CaptureRunSession()
        session.start(true, state(5))
        session.collect("/item/use", result, state(5), state(3))
        val run = assertNotNull(session.finish(state(3)))
        assertEquals(0, run.capturedBattles)
        assertEquals(2, run.fruitUsed[100])
    }

    @Test fun setupQuestIsKeptWithoutSnapshotAndResultQuestTakesPrecedence() {
        val session = CaptureRunSession()
        session.start(true, null)
        session.collect("/battle/setup", Json.parseToJsonElement("""{"cache":{"updated":{"userQuest":[{"questId":7}]}}}""").jsonObject, null, null)
        session.collect("/battle/result", result, null, null)
        val withQuest = Json.parseToJsonElement("""{"response":[{"success":{}}],"cache":{"updated":{"userQuest":[{"questId":8}]}}}""").jsonObject
        session.collect("/battle/result", withQuest, null, null)
        assertEquals(listOf(7L, 8L), assertNotNull(session.finish(null)).battles.map { it.questId })
    }
}
