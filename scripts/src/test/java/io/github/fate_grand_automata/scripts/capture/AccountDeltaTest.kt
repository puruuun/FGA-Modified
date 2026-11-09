package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.*
import kotlin.test.*

class AccountDeltaTest {
    @Test fun friendPointOnlyChangesAreNotEmptyAndAddAsLongs() {
        val spent = AccountDelta(friendPoint = -200)
        assertFalse(spent.isEmpty)
        assertFalse(AccountDelta(friendPoint = 25).isEmpty)
        assertEquals(AccountDelta(friendPoint = 3_000_000_000L), spent + AccountDelta(friendPoint = 3_000_000_200L))
        assertTrue((spent + AccountDelta(friendPoint = 200)).isEmpty)
    }

    @Test fun friendPointsComeFromBothGameTableNames() {
        val before = state(game = """{"friendPoint":"3000000200"}""")
        val after = state(game = """{"friendPoint":3000000000}""")
        assertEquals(AccountDelta(friendPoint = -200), AccountDelta.between(before, after))
        fun alias(state: AccountState) = state.copy(tables = mapOf("tblUserGame" to state.tables.getValue("userGame")))
        assertEquals(AccountDelta(friendPoint = -200), AccountDelta.between(alias(before), alias(after)))
    }

    @Test fun oldJsonWithoutFriendPointsDecodesWithZero() {
        val old = """{"items":{"100":-2},"qp":1000,"svts":{"10":1}}"""
        assertEquals(AccountDelta(items = mapOf(100L to -2L), qp = 1000, svts = mapOf(10L to 1L)),
            Json.decodeFromString<AccountDelta>(old))
    }

    private fun state(items: String = "[]", game: String = "{}") = AccountState(
        mapOf("userItem" to Json.parseToJsonElement(items).jsonArray,
            "userGame" to JsonArray(listOf(Json.parseToJsonElement(game)))),
        JsonArray(emptyList()), snapshotTime = 1, lastUpdateTime = 1,
    )

    @Test fun addedRemovedChangedAndUnchangedItemsUseAbsoluteCounts() {
        val before = state("""[{"itemId":1,"num":10},{"itemId":2,"num":7},{"itemId":3,"num":4},{"itemId":5,"num":9}]""")
        val after = state("""[{"itemId":1,"num":13},{"itemId":2,"num":5},{"itemId":3,"num":4},{"itemId":4,"num":8}]""")
        assertEquals(mapOf(1L to 3L, 2L to -2L, 4L to 8L, 5L to -9L), AccountDelta.between(before, after).items)
    }

    @Test fun missingItemTableOnEitherSideCountsAsZero() {
        val absent = state().copy(tables = emptyMap())
        val held = state("""[{"itemId":100,"num":2}]""")
        assertEquals(mapOf(100L to 2L), AccountDelta.between(absent, held).items)
        assertEquals(mapOf(100L to -2L), AccountDelta.between(held, absent).items)
    }

    @Test fun currenciesComeFromUserGameIncludingFreeAndPaidStoneSeparately() {
        val before = state(game = """{"qp":10000,"mana":12,"rarePri":3,"freeStone":90,"chargeStone":10}""")
        val after = state(game = """{"qp":7000,"mana":15,"rarePri":2,"freeStone":65,"chargeStone":5}""")
        assertEquals(AccountDelta(qp = -3000, mana = 3, rarePri = -1, freeStone = -25, chargeStone = -5),
            AccountDelta.between(before, after))
    }

    @Test fun missingSnapshotDoesNotInventGainsOrUsage() {
        assertTrue(AccountDelta.between(null, state()).isEmpty)
        assertTrue(AccountDelta.between(state(), null).isEmpty)
        assertTrue(AccountDelta.between(state(), state()).isEmpty)
    }

    @Test fun malformedFieldsNeverThrowAndValidItemsStillContribute() {
        val malformed = state("""[null,5,{}, {"itemId":[],"num":3},{"itemId":7,"num":"bad"},{"itemId":8,"num":3}]""",
            """{"qp":{},"mana":null,"freeStone":"bad"}""")
        assertEquals(mapOf(8L to 3L), AccountDelta.between(state(), malformed).items)
        assertEquals(0, AccountDelta.between(state(), malformed).qp)
    }

    @Test fun legacyGameAliasAndMissingCurrencyFieldsWork() {
        val next = state(game = """{"qp":"1500","mana":2}""")
        val alias = next.copy(tables = mapOf("tblUserGame" to next.tables.getValue("userGame")))
        assertEquals(AccountDelta(qp = 1500, mana = 2), AccountDelta.between(state(), alias))
    }
}
