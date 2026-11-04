package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountSummaryTest {
    private fun state(game: String, items: String = "[]") = AccountState(
        mapOf(
            "userGame" to Json.parseToJsonElement("[$game]").jsonArray,
            "userItem" to Json.parseToJsonElement(items).jsonArray,
        ),
        Json.parseToJsonElement("[]").jsonArray, null, 10, 20,
    )

    private fun game(recoverAt: Long, max: Long = 100) =
        """{"userId":"1","name":"Master","lv":120,"actMax":$max,"actRecoverAt":$recoverAt,"stone":7,"freeStone":3,"chargeStone":4,"mana":11,"rarePri":12,"qp":13}"""

    @Test fun readsFieldsAndItems() {
        val items = """[{"itemId":16,"num":1},{"itemId":100,"num":2},{"itemId":101,"num":3},{"itemId":104,"num":4},
            {"itemId":102,"num":5},{"itemId":103,"num":6},{"itemId":4001,"num":7},{"itemId":999,"num":9}]"""
        val s = AccountSummary.from(state(game(0), items), now = 1000)
        assertEquals("Master", s.masterName)
        assertEquals(120, s.level)
        assertEquals(100, s.ap)
        assertEquals(100, s.apMax)
        assertEquals(7, s.stone)
        assertEquals(3, s.freeStone)
        assertEquals(4, s.chargeStone)
        assertEquals(
            listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L),
            listOf(s.fragments, s.goldenApples, s.silverApples, s.bronzeApples, s.copperApples, s.bronzeSaplings, s.tickets),
        )
        assertEquals(11, s.manaPrisms)
        assertEquals(12, s.rarePrisms)
        assertEquals(13, s.qp)
        assertEquals(10, s.snapshotTime)
        assertEquals(20, s.lastUpdateTime)
    }

    @Test fun apEdgeCases() {
        assertEquals(98, AccountSummary.from(state(game(1301)), 1000).ap)
        assertEquals(99, AccountSummary.from(state(game(1300)), 1000).ap)
        assertEquals(99, AccountSummary.from(state(game(1001)), 1000).ap)
        assertEquals(100, AccountSummary.from(state(game(1000)), 1000).ap)
        assertEquals(100, AccountSummary.from(state(game(500)), 1000).ap)
        assertEquals(0, AccountSummary.from(state(game(1000 + 300 * 150)), 1000).ap)
        assertEquals(0, AccountSummary.from(state(game(5000, max = 0)), 1000).ap)
    }

    @Test fun carryOverAndMissingData() {
        val s = AccountSummary.from(state("""{"userId":"1","actMax":50,"actRecoverAt":0,"carryOverActPoint":12}"""), 1)
        assertEquals(12, s.carryOverActPoint)
        val empty = AccountSummary.from(AccountState(emptyMap(), Json.parseToJsonElement("[]").jsonArray, null, 1, 1), 1)
        assertEquals(0, empty.ap)
        assertEquals("", empty.masterName)
        assertEquals(0, empty.carryOverActPoint)
    }
}
