package io.github.fate_grand_automata.scripts.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

/** Synthetic bodies only: made-up ids, names and codes. */
class FriendRosterTest {
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
        val followers = obj("""{"cache":{"updated":{"userFollower":[{"followerInfo":[
            {"type":1,"userId":"11","message":"PRIVATE-MESSAGE",${slot(101, 1, 500, 3000).removePrefix("{")}]}]}}}""")
        assertFalse(FriendRoster.mergeFollowers(roster, followers).toJson().contains("PRIVATE-MESSAGE"))
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

    @Test fun followerUpdateKeepsFriendCodeAndNewFriendIsAdded() {
        val roster = FriendRoster.fromFriendTop(twoFriends)!!
        val merged = FriendRoster.mergeFollowers(roster, followers(
            """{"type":1,"userId":"11","userName":"Renamed","userLv":121,${slot(101, 1, 501, 4500).removePrefix("{")}""",
            """{"type":1,"userId":"99","userName":"Newcomer","userLv":50,${slot(101, 4, 800, 4400).removePrefix("{")}""",
        ))
        val old = merged.friends.getValue("11")
        assertEquals("111222333", old.friendCode)
        assertEquals("Renamed", old.name)
        assertEquals(121, old.lv)
        assertEquals(4500, old.lastLogin)
        // The slot with the same deck and class is replaced, the others stay.
        assertEquals(setOf(501L, 600L), old.slots.map { it.svtId }.toSet())
        val added = merged.friends.getValue("99")
        assertNull(added.friendCode)
        assertEquals(listOf(800L), added.slots.map { it.svtId })
        assertEquals(roster.updatedAt, merged.updatedAt)
    }

    @Test fun otherFollowerTypesAreIgnored() {
        val roster = FriendRoster.fromFriendTop(twoFriends)!!
        val merged = FriendRoster.mergeFollowers(roster, followers(
            """{"type":2,"userId":"11","userName":"Stranger",${slot(101, 1, 999, 9999).removePrefix("{")}""",
            """{"type":2,"userId":"77","userName":"Stranger",${slot(101, 1, 999, 9999).removePrefix("{")}""",
        ))
        assertSame(roster, merged)
    }

    @Test fun secondFriendTopRemovesGoneFriends() {
        val first = FriendRoster.update(null, "friend/top", twoFriends, 1)
        val second = FriendRoster.update(first, "friend/top", top(listOf(friendRow("12", "444555666", listOf(slot(101, 3, 700, 900)))), serverTime = 6000), 2)!!
        assertEquals(setOf("12"), second.friends.keys)
        assertEquals(6000, second.updatedAt)
    }

    @Test fun supportListsNeedAnExistingRosterAndOtherPathsAreIgnored() {
        val body = followers("""{"type":1,"userId":"11",${slot(101, 1, 500, 3000).removePrefix("{")}""")
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
}
