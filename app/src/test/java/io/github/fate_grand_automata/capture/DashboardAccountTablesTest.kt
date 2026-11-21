package io.github.fate_grand_automata.capture

import kotlinx.serialization.json.*
import kotlin.test.*

/** Login counts, parties, command codes and card boosts as read from synthetic stored states (#615). */
class DashboardAccountTablesTest {
    private fun rows(json: String) = Json.parseToJsonElement(json).jsonArray.map { it.jsonObject }

    // Login cells

    @Test fun loginCountsAndTheNextMilestoneAreRead() {
        val login = projectLogin(
            rows("""[{"totalLoginCount":2774,"seqLoginCount":1672,"lastLoginAt":1,"createdAt":2}]"""),
            rows("""[{"eventId":40001,"day":2800,"script":"a string on KR"}]"""),
        )!!
        assertEquals(DashboardLogin(2774, 1672, 2800), login)
        assertEquals(26L, login.daysLeft)
        // JP stores the script as an object; it is never read, so the shape does not matter.
        assertEquals(26L, projectLogin(rows("""[{"totalLoginCount":2774,"seqLoginCount":1}]"""), rows("""[{"day":2800,"script":{"banner":"x"}}]"""))!!.daysLeft)
    }

    @Test fun theNextRewardIsTheFirstMilestoneAheadAndIsHiddenWhenThereIsNone() {
        assertEquals(2850L, projectLogin(rows("""[{"totalLoginCount":2800}]"""), rows("""[{"day":2800},{"day":2850}]"""))!!.nextRewardDay)
        // Reached or past: no days left, so no cell.
        assertNull(projectLogin(rows("""[{"totalLoginCount":2800}]"""), rows("""[{"day":2800}]"""))!!.daysLeft)
        assertNull(projectLogin(rows("""[{"totalLoginCount":2900}]"""), rows("""[{"day":2800}]"""))!!.daysLeft)
    }

    @Test fun aMissingTableHidesItsCellsAndNeverInventsAZero() {
        val onlyMilestone = projectLogin(null, rows("""[{"day":2800}]"""))!!
        assertNull(onlyMilestone.total)
        assertNull(onlyMilestone.seq)
        assertNull(onlyMilestone.daysLeft)
        val onlyLogin = projectLogin(rows("""[{"totalLoginCount":5}]"""), null)!!
        assertEquals(5L, onlyLogin.total)
        assertNull(onlyLogin.seq)
        assertNull(onlyLogin.nextRewardDay)
        assertNull(projectLogin(null, null))
        assertNull(projectLogin(emptyList(), emptyList()))
    }

    // Parties

    private val svts = rows(
        """[{"id":901,"svtId":100,"lv":90,"treasureDeviceLv1":2},{"id":902,"svtId":200,"lv":80,"treasureDeviceLv1":5},
            {"id":903,"svtId":300,"lv":70,"treasureDeviceLv1":1},
            {"id":951,"svtId":9000,"lv":100,"limitCount":4},{"id":952,"svtId":9001,"lv":1,"limitCount":0}]""",
    )
    private val equips = rows("""[{"id":701,"equipId":30},{"id":702,"equipId":40}]""")

    private fun slot(id: Int, userSvtId: Long, pos: Int, ces: String = "[0]", follower: Boolean = false) =
        """{"id":$id,"userSvtId":$userSvtId,"svtId":0,"userSvtEquipIds":$ces,"isFollowerSvt":$follower,"initPos":$pos}"""

    private fun deck(no: Int, name: String?, equip: Long, vararg slots: String) =
        """{"id":$no,"deckNo":$no,"name":${name?.let { "\"$it\"" }},"cost":0,"deckInfo":{"svts":[${slots.joinToString(",")}],"userEquipId":$equip,"waveSvts":[]}}"""

    @Test fun servantsAreFoundThroughUserSvtIdBecauseDeckInfoSvtIdIsAlwaysZero() {
        val parties = projectParties(rows("[" + deck(1, "주력", 702,
            slot(1, 902, 2, "[951]"), slot(2, 901, 1, "[952]"), slot(3, 0, 3, follower = true), slot(4, 0, 4)) + "]"), svts, equips)
        val party = parties.single()
        assertEquals(1L, party.deckNo)
        assertEquals("주력", party.name)
        // Slots follow initPos, empty slots are left out, the support slot is marked.
        assertEquals(listOf(1, 2, 3), party.slots.map { it.position })
        assertEquals(listOf<Long?>(100, 200, null), party.slots.map { it.svtId })
        assertEquals(listOf(false, false, true), party.slots.map { it.support })
        assertEquals(90L, party.slots[0].level)
        assertEquals(2L, party.slots[0].np)
        // CEs come from userSvt through userSvtEquipIds; the first slot's CE is level 1, the second slot's level 100.
        assertEquals(listOf(DashboardPartyCe(9001, 1, 0)), party.slots[0].ces)
        assertEquals(listOf(DashboardPartyCe(9000, 100, 4)), party.slots[1].ces)
        assertEquals(40L, party.mysticCodeId)
    }

    @Test fun aCeThatIsNotInTheOwnedListIsMarkedMissingAndNoCeIsNot() {
        val decks = rows("[" + deck(1, "a", 701, slot(1, 901, 1, "[999999]"), slot(2, 902, 2, "[0]"), slot(3, 903, 3, "[951, 999999]")) + "]")
        val slots = projectParties(decks, svts, equips).single().slots.associateBy { it.position }
        assertTrue(slots.getValue(1).ces.isEmpty() && slots.getValue(1).ceMissing)
        assertTrue(slots.getValue(2).ces.isEmpty() && !slots.getValue(2).ceMissing)
        assertEquals(1, slots.getValue(3).ces.size)
        assertTrue(slots.getValue(3).ceMissing)
    }

    @Test fun partiesWithoutAServantOfTheOwnersAreHiddenAndTheRestAreOrderedByNumber() {
        val decks = rows("[" + listOf(
            deck(5, "다섯", 701, slot(1, 903, 1)),
            deck(2, null, 701, slot(1, 0, 1), slot(2, 0, 2)),
            deck(3, "서포트뿐", 701, slot(1, 0, 1, follower = true)),
            deck(4, "없는 서번트", 701, slot(1, 999999, 1)),
            deck(1, "  ", 999, slot(1, 901, 1)),
        ).joinToString(",") + "]")
        val parties = projectParties(decks, svts, equips)
        assertEquals(listOf(1L, 5L), parties.map { it.deckNo })
        // A blank name is no name; a mystic code that is not in userEquip is left out rather than guessed.
        assertNull(parties[0].name)
        assertNull(parties[0].mysticCodeId)
        assertEquals(30L, parties[1].mysticCodeId)
    }

    @Test fun missingTablesGiveNoParties() {
        assertTrue(projectParties(null, svts, equips).isEmpty())
        assertTrue(projectParties(rows("[" + deck(1, "a", 701, slot(1, 901, 1)) + "]"), null, equips).isEmpty())
        // Without userEquip the party stays and only the mystic code is dropped.
        assertNull(projectParties(rows("[" + deck(1, "a", 701, slot(1, 901, 1)) + "]"), svts, null).single().mysticCodeId)
    }

    @Test fun partiesReachTheDashboardDataThroughTheStoredState() {
        val state = dashboardState(
            "userSvt" to """[{"id":901,"svtId":10,"lv":90,"treasureDeviceLv1":2}]""",
            "userDeck" to "[" + deck(3, "세 번째", 0, slot(1, 901, 1)) + "]",
        )
        assertEquals(listOf(10L), dashboardData(state, dashboardNames()).parties.single().slots.map { it.svtId })
        assertTrue(dashboardData(dashboardState("userSvt" to "[]"), dashboardNames()).parties.isEmpty())
    }

    // Command codes and card boost

    @Test fun commandCodesCountFilledSlotsAgainstUnlockedOnes() {
        val codes = projectServantCodes(rows(
            """[{"svtId":1,"userCommandCodeIds":[501,0,502,-1,-1]},{"svtId":2,"userCommandCodeIds":[0,0,0,0,0]},
                {"svtId":3,"userCommandCodeIds":[-1,-1,-1,-1,-1]},{"svtId":4,"userCommandCodeIds":[7,8,9,10,11]},{"svtId":5}]""",
        ))
        assertEquals(DashboardServantCodes(2, 3), codes[1L])
        assertEquals(DashboardServantCodes(0, 5), codes[2L])
        assertEquals(DashboardServantCodes(5, 5), codes[4L])
        // Nothing is unlocked, or there is no list: nothing to show.
        assertNull(codes[3L])
        assertNull(codes[5L])
    }

    @Test fun cardBoostIsTheSumAndTheNumberOfEnhancedCards() {
        val boosts = projectCardBoosts(rows(
            """[{"svtId":1,"commandCardParam":[200,200,0,500,100]},{"svtId":2,"commandCardParam":[0,0,0,0,0]},{"svtId":3,"commandCardParam":[]},{"svtId":4}]""",
        ))
        assertEquals(DashboardCardBoost(1000, 4), boosts[1L])
        // A zero sum, an empty list and a missing list are all hidden.
        assertNull(boosts[2L])
        assertNull(boosts[3L])
        assertNull(boosts[4L])
    }

    @Test fun servantsGetTheirOwnCodesAndBoostByServantId() {
        val state = dashboardState(
            "userSvtCollection" to """[{"svtId":10,"status":2,"maxLv":90},{"svtId":13,"status":2,"maxLv":90}]""",
            "userSvtCommandCode" to """[{"svtId":10,"userCommandCodeIds":[501,0,0,-1,-1]}]""",
            "userSvtCommandCard" to """[{"svtId":13,"commandCardParam":[500,0,0,0,0]}]""",
        )
        val servants = dashboardData(state, dashboardNames()).servants!!.associateBy { it.id }
        assertEquals(DashboardServantCodes(1, 3), servants.getValue(10).commandCodes)
        assertNull(servants.getValue(10).cardBoost)
        assertEquals(DashboardCardBoost(500, 1), servants.getValue(13).cardBoost)
        assertNull(servants.getValue(13).commandCodes)
    }
}
