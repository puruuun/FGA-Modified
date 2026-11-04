package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.*
import kotlin.test.*

class RunGainsTest {
    private fun state(items: String) = AccountState(mapOf("userItem" to Json.parseToJsonElement(items).jsonArray),
        JsonArray(emptyList()), snapshotTime = 1, lastUpdateTime = 1)
    private val battle = BattleGains(100, 99, listOf(GainedItem(2, 100, 1), GainedItem(2, 6510, 3), GainedItem(3, 8, 1)),
        qp = 1000, masterExp = 200, bond = mapOf(10L to 500L))

    @Test fun totalsIncludeFruitGainedAndAbsoluteEndHoldings() {
        val run = RunGains.start(state("""[{"itemId":100,"num":5},{"itemId":101,"num":4}]"""))
            .add(battle).add(battle).finish(state("""[{"itemId":100,"num":4},{"itemId":101,"num":3},{"itemId":6510,"num":30}]"""))
        assertEquals(2, run.capturedBattles)
        assertEquals(2000, run.qp)
        assertEquals(400, run.masterExp)
        assertEquals(mapOf(10L to 1000L), run.bond)
        assertEquals(GainedItem(2, 6510, 6, 30), run.items.first { it.objectId == 6510L })
        assertNull(run.items.first { it.type == 3 }.holdings)
        assertEquals(mapOf(100L to 3L, 101L to 1L, 102L to 0L, 104L to 0L), run.fruitUsed)
    }

    @Test fun noStartSnapshotKeepsGainsAndEndHoldingsButFruitIsUnknown() {
        val run = RunGains.start(null).add(battle).finish(state("""[{"itemId":6510,"num":30}]"""))
        assertEquals(3, run.items.first { it.objectId == 6510L }.gained)
        assertEquals(30, run.items.first { it.objectId == 6510L }.holdings)
        assertEquals(RunGains.FRUIT_IDS.toSet(), run.fruitUsed.keys)
        assertTrue(run.fruitUsed.values.all { it == null })
    }

    @Test fun noEndSnapshotMakesHoldingsAndFruitUnknown() {
        val run = RunGains.start(state("[]")).add(battle).finish(null)
        assertTrue(run.items.all { it.holdings == null })
        assertTrue(run.fruitUsed.values.all { it == null })
    }

    @Test fun emptyRunDoesNotInventExpOrQp() {
        val run = RunGains.start(null).finish(null)
        assertEquals(0, run.capturedBattles)
        assertNull(run.qp)
        assertNull(run.masterExp)
    }

    @Test fun serializationRoundTripKeepsBreakdownAndOmitsStartHoldings() {
        val run = RunGains.start(state("""[{"itemId":100,"num":9999}]"""))
            .add(battle).finish(null).withRunCount(3)
        val encoded = Json.encodeToString(run)
        assertEquals(run, Json.decodeFromString<RunGains>(encoded))
        assertFalse(encoded.contains("startHoldings"))
        assertFalse(encoded.contains("9999"))
        assertEquals(2, run.missingBattles)
    }

    @Test fun extraCapturesNeverProduceNegativeMissingBattles() {
        assertEquals(0, RunGains.start(null).add(battle).withRunCount(0).missingBattles)
    }
}
