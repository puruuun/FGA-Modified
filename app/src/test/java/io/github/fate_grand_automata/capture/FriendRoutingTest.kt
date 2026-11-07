package io.github.fate_grand_automata.capture

import kotlin.test.*

/** Exercises the actual hook, including roster publication when normal account reduction is skipped. */
class FriendRoutingTest {
    @BeforeTest fun reset() { CaptureTracker.delete() }
    @AfterTest fun clean() { CaptureTracker.delete() }
    private fun feed(path: String, body: String) = CaptureTracker.onGameResponse(
        "https://yfgo-game.netmarble.com/$path", body.toByteArray(), null)
    private fun login(id: Int) = feed("login/top", """{"response":[{"nid":"login","resCode":"00"}],
        "cache":{"replaced":{"userGame":[{"userId":$id,"name":"Master $id"}]}}}""")
    private fun top(owner: Int, own: Int? = null, friend: Int = 11) = feed("friend/top", """{"cache":{
        "replaced":{"tblFriend":[{"userId":$owner,"friendId":$friend},{"userId":12,"friendId":$owner}]},
        "updated":{"otherUserGame":[{"userId":$friend,"userName":"Friend $friend"}]
        ${own?.let { ",\"userGame\":[{\"userId\":$it}]" }.orEmpty()}},"serverTime":1000}}""")
    private fun followers(path: String, own: Int? = null) = feed(path, """{"cache":{"updated":{
        "userFollower":[{"followerInfo":[{"type":1,"userId":11,"userName":"Renamed"},
            {"type":1,"userId":99,"userName":"New friend"}]}]
        ${own?.let { ",\"userItem\":[{\"userId\":$it,\"itemId\":1,\"num\":1}]" }.orEmpty()}}}}""")

    @Test fun nonCurrentOwnerGetsTheRosterAndCurrentMarkerDoesNotMove() {
        login(7001); login(7002)
        top(7001)
        assertEquals(setOf("11"), CaptureTracker.rosters.value.getValue("7001").friends.keys)
        assertFalse("7002" in CaptureTracker.rosters.value)
        assertEquals("7002", CaptureTracker.currentId.value)
    }

    @Test fun unknownOwnerAndDisagreeingOwnRowsLeaveEveryRosterUnchanged() {
        login(7001); top(7001); login(7002)
        val before = CaptureTracker.rosters.value
        top(7003)
        assertEquals(before, CaptureTracker.rosters.value)
        top(7001, own = 7002, friend = 88)
        assertEquals(before, CaptureTracker.rosters.value)
        assertEquals("7002", CaptureTracker.currentId.value)
        feed("friend/top", """{"cache":{"updated":{"otherUserGame":[{"userId":99}]}}}""")
        assertEquals(before, CaptureTracker.rosters.value)
    }

    @Test fun followerAndBattleFallbackOnlyUpdateKnownFriends() {
        for (path in listOf("follower/list", "battle/result")) {
            CaptureTracker.delete(); login(7001); top(7001)
            followers(path)
            val friends = CaptureTracker.rosters.value.getValue("7001").friends
            assertEquals(setOf("11"), friends.keys)
            assertEquals("Renamed", friends.getValue("11").name)
        }
    }

    @Test fun followerAndBattleWithOwnRowsCanAddFriendsToThatAccount() {
        for (path in listOf("follower/list", "battle/result")) {
            CaptureTracker.delete(); login(7001); top(7001); login(7002)
            followers(path, own = 7001)
            assertEquals(setOf("11", "99"), CaptureTracker.rosters.value.getValue("7001").friends.keys)
            assertFalse("7002" in CaptureTracker.rosters.value)
        }
    }
}
