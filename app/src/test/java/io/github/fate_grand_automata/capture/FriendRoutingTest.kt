package io.github.fate_grand_automata.capture

import kotlin.test.*

/** Exercises the actual hook, including roster publication when normal account reduction is skipped. */
class FriendRoutingTest {
    @BeforeTest fun reset() { CaptureTracker.delete() }
    @AfterTest fun clean() { CaptureTracker.delete() }
    private fun feed(path: String, body: String, host: String = "yfgo-game.netmarble.com") = CaptureTracker.onGameResponse(
        "https://$host/$path", body.toByteArray(), null)
    private fun login(id: Int, host: String = "yfgo-game.netmarble.com") = feed("login/top", """{"response":[{"nid":"login","resCode":"00"}],
        "cache":{"replaced":{"userGame":[{"userId":$id,"name":"Master $id"}]}}}""", host)
    private fun top(owner: Int, own: Int? = null, friend: Int = 11, host: String = "yfgo-game.netmarble.com") = feed("friend/top", """{"cache":{
        "replaced":{"tblFriend":[{"userId":$owner,"friendId":$friend},{"userId":12,"friendId":$owner}]},
        "updated":{"otherUserGame":[{"userId":$friend,"userName":"Friend $friend"}]
        ${own?.let { ",\"userGame\":[{\"userId\":$it}]" }.orEmpty()}},"serverTime":1000}}""", host)
    private fun followers(path: String, own: Int? = null, host: String = "yfgo-game.netmarble.com") = feed(path, """{"cache":{"updated":{
        "userFollower":[{"followerInfo":[{"type":1,"userId":11,"userName":"Renamed"},
            {"type":1,"userId":99,"userName":"New friend"}]}]
        ${own?.let { ",\"userItem\":[{\"userId\":$it,\"itemId\":1,\"num\":1}]" }.orEmpty()}}}}""", host)

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

    @Test fun friendRoutingUsesPerServerCurrentAndCorrectsWrongServerLabel() {
        // KR account 7001 has a roster; JP account 7002 logs in after KR.
        login(7001, "yfgo-game.netmarble.com")
        top(7001, host = "yfgo-game.netmarble.com")
        login(7002, "game.fate-go.jp")
        assertEquals("7002", CaptureTracker.currentId.value)

        // Id-less KR follower/list routes to KR's current account (7001), not JP (7002).
        followers("follower/list", host = "yfgo-game.netmarble.com")
        assertEquals("Renamed", CaptureTracker.rosters.value.getValue("7001").friends.getValue("11").name)

        // Id-less follower/list on a server without a current account is dropped.
        CaptureTracker.delete()
        login(7001, "yfgo-game.netmarble.com")
        top(7001, host = "yfgo-game.netmarble.com")
        followers("follower/list", host = "game.fate-go.jp")
        assertEquals("Friend 11", CaptureTracker.rosters.value.getValue("7001").friends.getValue("11").name)

        // Identified friend/top from JP host is accepted even when 7001 was labelled KR, and corrects its label to JP.
        top(7001, friend = 22, host = "game.fate-go.jp")
        assertEquals("JP", CaptureTracker.accounts.value.single { it.id == "7001" }.server)
        assertEquals(setOf("22"), CaptureTracker.rosters.value.getValue("7001").friends.keys)
    }
}
