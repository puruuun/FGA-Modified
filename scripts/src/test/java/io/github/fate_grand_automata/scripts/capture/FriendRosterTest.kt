package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

/** Synthetic bodies only: made-up ids, names and codes. */
class FriendRosterTest {
    @Test fun grandFlagAcceptsPositiveNumbersAndDefaultsToFalse() {
        for ((extra, expected) in listOf(
            "" to false, ""","grandSvt":1""" to true, ""","grandSvt":2""" to true,
            ""","grandSvt":0""" to false, ""","grandSvt":-1""" to false,
            ",\"grandSvt\":\"unreadable\"" to false, ""","grandSvt":null""" to false,
        )) {
            val body = top(listOf(friendRow("11", null, listOf(slot(101, 1, 500, 1, extra = extra)))))
            val roster = assertNotNull(FriendRoster.fromFriendTop(body))
            assertEquals(expected, roster.friends.getValue("11").slots.single().grand, extra)
            assertEquals(roster, FriendRoster.fromJson(roster.toJson()))
        }
    }

    @Test fun followerRefreshReplacesGrandFlagAndKeepsUntouchedEventSlot() {
        val grand = slot(101, 1, 500, 1, extra = ""","grandSvt":1""")
        val event = slot(201, 1, 500, 1, extra = ""","grandSvt":1""")
        val roster = FriendRoster.fromFriendTop(top(listOf(friendRow("11", null, listOf(grand), listOf(event)))))!!
        assertTrue(roster.friends.getValue("11").slots.all { it.grand })
        for (extra in listOf("", ""","grandSvt":0""")) {
            val fresh = slot(101, 1, 500, 2, extra = extra)
            val merged = FriendRoster.mergeFollowers(roster, followers(info(1, "11", listOf(fresh))))
            assertFalse(merged.friends.getValue("11").slots.single { it.deckId == 101L }.grand)
            assertTrue(merged.friends.getValue("11").slots.single { it.deckId == 201L }.grand)
            val restored = FriendRoster.mergeFollowers(merged, followers(info(1, "11", listOf(grand))))
            assertTrue(restored.friends.getValue("11").slots.all { it.grand })
        }
    }

    @Test fun oldJsonAndKrFixtureWithoutGrandBehaveAsBefore() {
        val old = """{"friends":{"11":{"userId":"11","slots":[{"deckId":101,"classId":1,"svtId":500}]}}}"""
        assertFalse(FriendRoster.fromJson(old).friends.getValue("11").slots.single().grand)
        val kr = FriendRoster.fromFriendTop(twoFriends)!!
        assertTrue(kr.friends.values.flatMap { it.slots }.none { it.grand })
        assertEquals(2, FriendList.select(kr, FriendFilter(), 5000).size)
        assertTrue(kr.friends.values.all { it.grandSlots.isEmpty() })
        assertTrue(FriendRoster.fromJson(old).friends.getValue("11").grandSlots.isEmpty())
    }

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun slot(deck: Int, cls: Int, svt: Long, updatedAt: Long, ce: Long = 0, limit: Int = 0,
        appends: String = "[]", extra: String = "") =
        """{"supportDeckId":$deck,"classId":$cls,"svtId":$svt,"lv":90,"treasureDeviceLv":5,"skillLv1":10,"skillLv2":9,"skillLv3":8,
           "appendPassiveSkill":$appends,"equipTarget1":{"svtId":$ce,"limitCount":$limit,"lv":${if (ce == 0L) 0 else 100}},
           "updatedAt":$updatedAt$extra}"""

    private fun friendRow(id: String, code: String?, main: List<String>, event: List<String> = emptyList()) =
        """{"userId":"$id","userName":"Friend $id","userLv":120,"friendCode":${code?.let { "\"$it\"" } ?: "null"},
           "message":"PRIVATE-MESSAGE","mainSupportDeckIds":[101,102,103],"eventSupportDeckIds":[201,202,203],
           "userSvtLeaderHash":[${main.joinToString(",")}],"eventUserSvtLeaderHash":[${event.joinToString(",")}]}"""

    private fun top(rows: List<String>, requests: String = "[]", serverTime: Long = 5000) = obj(
        """{"cache":{"updated":{"otherUserGame":[${rows.joinToString(",")}]},"replaced":{"tblFriend":$requests},"serverTime":$serverTime}}""",
    )

    private val twoFriends = top(listOf(
        friendRow("11", "111222333", listOf(slot(101, 1, 500, 1000, ce = 9000, limit = 4), slot(102, 2, 0, 4000)), listOf(slot(201, 5, 600, 2000))),
        friendRow("12", "444555666", listOf(slot(101, 3, 700, 900))),
    ))

    @Test fun friendTopKeepsFriendsAndDropsEmptySlots() {
        val roster = assertNotNull(FriendRoster.fromFriendTop(twoFriends))
        assertEquals(5000, roster.updatedAt)
        assertEquals(setOf("11", "12"), roster.friends.keys)
        val friend = roster.friends.getValue("11")
        assertEquals("111222333", friend.friendCode)
        assertEquals(listOf(101L, 102L, 103L), friend.mainDeckIds)
        assertEquals(listOf(201L, 202L, 203L), friend.eventDeckIds)
        // svtId 0 is dropped; the main and event slots stay.
        assertEquals(listOf(500L, 600L), friend.slots.map { it.svtId })
        assertEquals(listOf(10, 9, 8), friend.slots.first().skills)
        assertEquals(5, friend.slots.first().np)
        assertEquals(4, friend.slots.first().ceLimitCount)
    }

    @Test fun requestsAreDroppedAndUsersMissingFromTblFriendAreKept() {
        val body = top(
            listOf(friendRow("11", "1", listOf(slot(101, 1, 500, 1))), friendRow("12", "2", listOf(slot(101, 1, 500, 1))),
                friendRow("13", "3", listOf(slot(101, 1, 500, 1))), friendRow("14", "4", listOf(slot(101, 1, 500, 1)))),
            requests = """[{"friendId":"11","status":1},{"friendId":"12","status":2},{"friendId":"13","status":3}]""",
        )
        assertEquals(setOf("13", "14"), FriendRoster.fromFriendTop(body)!!.friends.keys)
    }

    @Test fun messageIsNeverStored() {
        val roster = FriendRoster.fromFriendTop(twoFriends)!!
        assertFalse(roster.toJson().contains("PRIVATE-MESSAGE"))
        val merged = FriendRoster.mergeFollowers(roster, followers(info(1, "11", listOf(slot(101, 1, 500, 3000)))))
        assertFalse(merged.toJson().contains("PRIVATE-MESSAGE"))
        assertNotEquals(roster, merged)
    }

    @Test fun lastLoginIsMaximumOverMainAndEventAndIgnoresZero() {
        val roster = FriendRoster.fromFriendTop(twoFriends)!!
        // The empty slot (svtId 0) carries the newest time and still counts as a sign of activity.
        assertEquals(4000, roster.friends.getValue("11").lastLogin)
        assertEquals(900, roster.friends.getValue("12").lastLogin)
        val zero = FriendRoster.fromFriendTop(top(listOf(friendRow("13", "1", listOf(slot(101, 1, 500, 0)), listOf(slot(201, 1, 500, 0))))))!!
        assertEquals(0, zero.friends.getValue("13").lastLogin)
        val mixed = FriendRoster.fromFriendTop(top(listOf(friendRow("13", "1", listOf(slot(101, 1, 500, 0)), listOf(slot(201, 1, 500, 70))))))!!
        assertEquals(70, mixed.friends.getValue("13").lastLogin)
    }

    @Test fun appendSlotsFollowSkillIds() {
        val appends = """[{"skillId":3001000,"skillLv":1},{"skillId":3002000,"skillLv":2},{"skillId":3033000,"skillLv":4},
            {"skillId":3034000,"skillLv":5},{"skillId":3011000,"skillLv":3}]"""
        val roster = FriendRoster.fromFriendTop(top(listOf(friendRow("11", "1", listOf(slot(101, 1, 500, 1, appends = appends))))))!!
        assertEquals(listOf(1, 2, 3, 4, 5), roster.friends.getValue("11").slots.single().appends)
        assertEquals(3, FriendRoster.appendSlot(3999999))
        val locked = FriendRoster.fromFriendTop(top(listOf(friendRow("11", "1", listOf(slot(101, 1, 500, 1, appends = """[{"skillId":3002000,"skillLv":7}]"""))))))!!
        assertEquals(listOf(null, 7, null, null, null), locked.friends.getValue("11").slots.single().appends)
    }

    private fun followers(vararg infos: String) =
        obj("""{"cache":{"updated":{"userFollower":[{"followerInfo":[${infos.joinToString(",")}]}]}}}""")

    /** The real shape: a followerInfo is a user whose slots sit in nested leader hashes. No friendCode. */
    private fun info(type: Int, id: String, main: List<String>, event: List<String> = emptyList(), name: String = "Friend $id",
        lv: Int = 120, mainIds: String = "[101,102,103]", eventIds: String = "[201,202,203]") =
        """{"type":$type,"userId":"$id","userName":"$name","userLv":$lv,"message":"PRIVATE-MESSAGE",
           "mainSupportDeckIds":$mainIds,"eventSupportDeckIds":$eventIds,
           "userSvtLeaderHash":[${main.joinToString(",")}],"eventUserSvtLeaderHash":[${event.joinToString(",")}],
           "userSvtGrandHash":[],"userClassBoardInfo":[],"followerClassStatistics":{},"pushUserSvtId":0,"tutorial1":0,"tutorial2":0}"""

    @Test fun followerUpdateKeepsFriendCodeAndNewFriendIsAdded() {
        val roster = FriendRoster.fromFriendTop(twoFriends)!!
        val merged = FriendRoster.mergeFollowers(roster, followers(
            info(1, "11", listOf(slot(101, 1, 501, 4500)), name = "Renamed", lv = 121),
            info(1, "99", listOf(slot(101, 4, 800, 4400)), name = "Newcomer", lv = 50),
        ))
        val old = merged.friends.getValue("11")
        assertEquals("111222333", old.friendCode)
        assertEquals("Renamed", old.name)
        assertEquals(121, old.lv)
        assertEquals(4500, old.lastLogin)
        // The listed main decks are replaced; the stored event slot stays because the event hash was empty.
        assertEquals(setOf(501L, 600L), old.slots.map { it.svtId }.toSet())
        val added = merged.friends.getValue("99")
        assertNull(added.friendCode)
        assertEquals(listOf(800L), added.slots.map { it.svtId })
        assertEquals(roster.updatedAt, merged.updatedAt)
    }

    @Test fun otherFollowerTypesAreIgnored() {
        val roster = FriendRoster.fromFriendTop(twoFriends)!!
        val merged = FriendRoster.mergeFollowers(roster, followers(
            info(2, "11", listOf(slot(101, 1, 999, 9999)), name = "Stranger"),
            info(2, "77", listOf(slot(101, 1, 999, 9999)), name = "Stranger"),
        ))
        assertSame(roster, merged)
    }

    @Test fun followerUpdateChangesASlotAndRemovesAMissingOneOfTheSameMainDeck() {
        // Friend 11 stores deck 101 with class 1 (svt 500) and class 3 (svt 510); the new list only has class 1 changed.
        val roster = FriendRoster.fromFriendTop(top(listOf(friendRow("11", "111222333",
            listOf(slot(101, 1, 500, 1000), slot(101, 3, 510, 1000), slot(102, 2, 520, 1000)), listOf(slot(201, 5, 600, 2000))))))!!
        val merged = FriendRoster.mergeFollowers(roster, followers(info(1, "11", listOf(slot(101, 1, 501, 5000, ce = 9000, limit = 4)))))
        val friend = merged.friends.getValue("11")
        // Deck 101 now holds only the new servant; the other main deck and the event deck are replaced/kept as listed.
        assertEquals(listOf(501L), friend.slots.filter { it.deckId == 101L }.map { it.svtId })
        assertEquals(4, friend.slots.first { it.svtId == 501L }.ceLimitCount)
        assertEquals(5000, friend.lastLogin)
        assertEquals("111222333", friend.friendCode)
    }

    @Test fun emptyEventHashKeepsStoredEventSlotsAndNonEmptyOneReplacesThem() {
        val roster = FriendRoster.fromFriendTop(twoFriends)!!
        val kept = FriendRoster.mergeFollowers(roster, followers(info(1, "11", listOf(slot(101, 1, 500, 3000)))))
        assertEquals(listOf(600L), kept.friends.getValue("11").slots.filter { it.deckId == 201L }.map { it.svtId })
        val replaced = FriendRoster.mergeFollowers(roster, followers(info(1, "11", listOf(slot(101, 1, 500, 3000)), listOf(slot(201, 5, 650, 3000)))))
        assertEquals(listOf(650L), replaced.friends.getValue("11").slots.filter { it.deckId == 201L }.map { it.svtId })
    }

    @Test fun deckIdsAndLastLoginComeFromTheInfo() {
        val roster = FriendRoster.fromFriendTop(twoFriends)!!
        val merged = FriendRoster.mergeFollowers(roster, followers(
            info(1, "11", listOf(slot(111, 1, 500, 7000), slot(112, 2, 0, 8000)), mainIds = "[111,112,113]", eventIds = "[]"),
            info(1, "99", listOf(slot(121, 1, 800, 100)), mainIds = "[121,122,123]", eventIds = "[221,222,223]"),
        ))
        val friend = merged.friends.getValue("11")
        assertEquals(listOf(111L, 112L, 113L), friend.mainDeckIds)
        assertEquals(listOf(201L, 202L, 203L), friend.eventDeckIds) // an empty list never replaces
        assertEquals(8000, friend.lastLogin) // the empty slot's time counts too
        assertEquals(listOf(121L, 122L, 123L), merged.friends.getValue("99").mainDeckIds)
        assertEquals(listOf(221L, 222L, 223L), merged.friends.getValue("99").eventDeckIds)
        // Deck 101's old slot is not among the listed decks, so it stays.
        assertTrue(friend.slots.any { it.deckId == 101L && it.svtId == 500L })
    }

    @Test fun slotFieldsDirectlyOnFollowerInfoAreNotSlots() {
        val roster = FriendRoster.fromFriendTop(twoFriends)!!
        val flat = followers("""{"type":1,"userId":"11",${slot(101, 1, 999, 9999).removePrefix("{")}""")
        assertEquals(roster.friends.getValue("11").slots, FriendRoster.mergeFollowers(roster, flat).friends.getValue("11").slots)
    }

    @Test fun secondFriendTopRemovesGoneFriends() {
        val first = FriendRoster.update(null, "friend/top", twoFriends, 1)
        val second = FriendRoster.update(first, "friend/top", top(listOf(friendRow("12", "444555666", listOf(slot(101, 3, 700, 900)))), serverTime = 6000), 2)!!
        assertEquals(setOf("12"), second.friends.keys)
        assertEquals(6000, second.updatedAt)
    }

    @Test fun supportListsNeedAnExistingRosterAndOtherPathsAreIgnored() {
        val body = followers(info(1, "11", listOf(slot(101, 1, 500, 3000))))
        assertNull(FriendRoster.update(null, "follower/list", body, 1))
        val roster = FriendRoster.update(null, "friend/top", twoFriends, 1)
        assertSame(roster, FriendRoster.update(roster, "shop/purchase", twoFriends, 2))
        // An older slot time never moves the last login backwards.
        assertEquals(4000, FriendRoster.update(roster, "battle/result", body, 2)!!.friends.getValue("11").lastLogin)
    }

    @Test fun malformedBodiesLeaveTheRosterUnchanged() {
        val roster = FriendRoster.update(null, "friend/top", twoFriends, 1)
        for (bad in listOf("""{}""", """{"cache":5}""", """{"cache":{"updated":{"otherUserGame":"x"}}}""", """{"cache":{"updated":{}}}""")) {
            assertSame(roster, FriendRoster.update(roster, "friend/top", obj(bad), 9), bad)
        }
        assertSame(roster, FriendRoster.update(roster, "follower/list", obj("""{"cache":{"updated":{"userFollower":7}}}"""), 9))
        val broken: JsonObject = obj("""{"cache":{"updated":{"otherUserGame":[1,"a",null,{"userId":{"x":1}}]}}}""")
        assertSame(roster, FriendRoster.update(roster, "friend/top", broken, 9))
        // A genuinely empty friend list is a valid replacement.
        assertTrue(FriendRoster.update(roster, "friend/top", top(emptyList()), 9)!!.friends.isEmpty())
    }

    @Test fun rosterRoundTripsThroughJson() {
        val roster = FriendRoster.fromFriendTop(twoFriends)!!
        assertEquals(roster, FriendRoster.fromJson(roster.toJson()))
    }

    @Test fun secondCeIsParsedFromEquipTarget2() {
        val roster = FriendRoster.fromFriendTop(top(listOf(friendRow("21", null, listOf(
            slot(101, 1, 500, 1000, ce = 9000, limit = 4, extra = ""","equipTarget2":{"svtId":9100,"limitCount":3,"lv":80}"""),
        )))))!!
        val slot = roster.friends.getValue("21").slots.single()
        assertEquals(9100L, slot.ce2Id)
        assertEquals(3, slot.ce2LimitCount)
        assertEquals(80, slot.ce2Lv)
        assertEquals(9000L, slot.ceId)
    }

    @Test fun missingOrEmptyEquipTarget2IsIgnored() {
        val roster = FriendRoster.fromFriendTop(top(listOf(friendRow("22", null, listOf(
            slot(101, 1, 500, 1000, ce = 9000, limit = 4),
            slot(102, 2, 501, 1000, ce = 9000, limit = 4, extra = ""","equipTarget2":{"svtId":0,"limitCount":0,"lv":0}"""),
            slot(103, 3, 502, 1000, ce = 9000, limit = 4, extra = ""","equipTarget2":null"""),
        )))))!!
        val slots = roster.friends.getValue("22").slots
        assertEquals(3, slots.size)
        slots.forEach { assertNull(it.ce2Id); assertNull(it.ce2LimitCount); assertNull(it.ce2Lv) }
    }

    @Test fun oldSavedRosterWithoutSecondCeFieldsStillLoads() {
        val old = """{"updatedAt":5,"friends":{"1":{"userId":"1","slots":[{"deckId":101,"classId":1,"svtId":500,"ceId":9000,"ceLimitCount":4,"ceLv":100}]}}}"""
        val slot = FriendRoster.fromJson(old).friends.getValue("1").slots.single()
        assertEquals(9000L, slot.ceId)
        assertNull(slot.ce2Id)
    }
}
