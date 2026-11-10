package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.*
import kotlin.test.*

/** Synthetic account states only. */
class OwnFormationTest {
    private fun state(vararg tables: Pair<String, String>) = AccountState(
        tables.associate { (name, body) -> name to Json.parseToJsonElement(body).jsonArray },
        JsonArray(emptyList()), snapshotTime = 100, lastUpdateTime = 200,
    )

    private fun leader(deck: Long, cls: Int, svt: Long = 500, extra: String = "") =
        """{"supportDeckId":$deck,"classId":$cls,"svtId":$svt,"lv":90,"treasureDeviceLv":3,"skillLv1":10,"skillLv2":9,"skillLv3":8,
            "appendPassiveSkill":[{"skillId":3001000,"skillLv":5}],"equipTarget1":{"svtId":9000,"limitCount":4,"lv":100}$extra}"""

    private fun game(main: String?, event: String?) =
        """[{"userId":"1"${main?.let { ""","fixMainSupportDeckIds":$it""" } ?: ""}${event?.let { ""","fixEventSupportDeckIds":$it""" } ?: ""}}]"""

    private fun grand(seat: Int, bond: String = """"equipTarget2":{"svtId":9300000},"script":{"equipTarget2SkillChange":1}""") =
        """{"grandGraphId":$seat,"svtId":600,"lv":120,"treasureDeviceLv":5,"skillLv1":10,"skillLv2":10,"skillLv3":10,
            "equipTarget1":{"svtId":9000,"limitCount":4,"lv":100},"equipTarget3":{"svtId":9001,"limitCount":2,"lv":60}${if (bond.isEmpty()) "" else ",$bond"}}"""

    @Test fun decksAreSplitIntoNormalAndEventInTheGamesOrder() {
        val result = OwnFormations.from(state(
            "userGame" to game("[102,101]", "[201]"),
            "userSvtLeader" to "[${leader(101, 2)},${leader(101, 1)},${leader(102, 1, 501)},${leader(201, 3, 502)}]",
        ))!!
        assertEquals(listOf(102L, 101L), result.main.map { it.deckId })
        assertEquals(listOf(1, 2), result.main.map { it.index })
        assertEquals(listOf(201L), result.event.map { it.deckId })
        assertEquals(1, result.event.single().index)
        // Slots follow the class order, not the table order.
        assertEquals(listOf(1, 2), result.main[1].slots.map { it.classId })
        assertEquals(501L, result.main[0].slots.single().svtId)
        assertTrue(result.grand.isEmpty())
    }

    @Test fun missingDeckIdListsPutEveryDeckUnderNormal() {
        val rows = "[${leader(103, 1)},${leader(101, 1)},${leader(101, 2)}]"
        for (game in listOf(null, game(null, null), game("[]", "[]"), """[{"userId":"1","fixMainSupportDeckIds":"broken"}]""")) {
            val result = OwnFormations.from(state(*listOfNotNull(game?.let { "userGame" to it }, "userSvtLeader" to rows).toTypedArray()))!!
            assertEquals(listOf(101L, 103L), result.main.map { it.deckId })
            assertEquals(listOf(1, 2), result.main.map { it.index })
            assertTrue(result.event.isEmpty())
        }
    }

    @Test fun grandSvtMarksTheGrandBadgeAndSlotFieldsAreRead() {
        val result = OwnFormations.from(state(
            "userGame" to game("[101]", "[]"),
            "userSvtLeader" to "[${leader(101, 1, extra = ""","grandSvt":1""")},${leader(101, 2, extra = ""","grandSvt":0""")}]",
        ))!!
        val (first, second) = result.main.single().slots
        assertTrue(first.grand)
        assertFalse(second.grand)
        assertEquals(90, first.lv)
        assertEquals(3, first.np)
        assertEquals(listOf(10, 9, 8), first.skills)
        assertEquals(listOf(5, null, null, null, null), first.appends)
        assertEquals(9000L, first.ceId)
        assertEquals(4, first.ceLimitCount)
    }

    @Test fun grandSeatsAreInSeatOrderWithTheThreeBondEffects() {
        val result = OwnFormations.from(state(
            "userSvtGrand" to "[${grand(10003, "")},${grand(10001)},${grand(10002, """"equipTarget2":{"svtId":9300000}""")}]",
        ))!!
        assertTrue(result.main.isEmpty())
        assertEquals(listOf(10001, 10002, 10003), result.grand.map { it.seat })
        assertEquals(listOf(1, 2, 3), result.grand.map { it.seatNumber })
        assertEquals(
            listOf(FriendBondEffect.NpCharge, FriendBondEffect.Base, FriendBondEffect.None),
            result.grand.map { it.bondEffect },
        )
        assertEquals(listOf(9300000L, 9300000L, null), result.grand.map { it.bondCeId })
        val seat = result.grand.first()
        assertEquals(FriendCe(9000, 4, 100), seat.ce1)
        assertEquals(FriendCe(9001, 2, 60), seat.ce3)
        assertEquals(120, seat.lv)
    }

    @Test fun aKrShapedStateWithoutUserSvtGrandHasNoGrandSection() {
        val result = OwnFormations.from(state("userGame" to game("[101]", "[]"), "userSvtLeader" to "[${leader(101, 1)}]"))!!
        assertTrue(result.hasSupport)
        assertTrue(result.grand.isEmpty())
        val empty = OwnFormations.from(state("userGame" to game("[101]", "[]"), "userSvtLeader" to "[${leader(101, 1)}]", "userSvtGrand" to "[]"))!!
        assertTrue(empty.grand.isEmpty())
    }

    @Test fun brokenRowsAreSkippedAndNoDataIsNull() {
        val result = OwnFormations.from(state(
            "userGame" to game("[101]", "[]"),
            "userSvtLeader" to """[${leader(101, 1)},"text",{},{"supportDeckId":101,"classId":2},{"supportDeckId":101,"classId":3,"svtId":0},
                {"classId":4,"svtId":500},${leader(101, 1, 999)}]""",
            "userSvtGrand" to """[{"svtId":600},"x",${grand(10001)}]""",
        ))!!
        assertEquals(listOf(500L), result.main.single().slots.map { it.svtId })
        assertEquals(listOf(10001), result.grand.map { it.seat })
        assertNull(OwnFormations.from(state("userGame" to game("[101]", "[]"))))
        assertNull(OwnFormations.from(state()))
        assertNull(OwnFormations.from(state("userSvtLeader" to "[{}]", "userSvtGrand" to "[1]")))
    }
}
