package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.CapturedBattle
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class CaptureBattleFeedTest {
    @BeforeTest fun reset() {
        CaptureTracker.discardRun()
        CaptureTracker.setRunning(false)
        CaptureTracker.delete()
        CaptureTracker.battleInbox.clear()
    }
    @AfterTest fun clean() = reset()
    private val feed = CaptureBattleFeed()
    private fun send(path: String, json: String, host: String = "yfgo-game.netmarble.com") =
        CaptureTracker.onGameResponse("https://$host$path", json.toByteArray(), null)
    private fun login(id: Int, host: String = "yfgo-game.netmarble.com") = send("/login/top", """{"response":[{"nid":"login","resCode":"00"}],
        "cache":{"replaced":{"userGame":[{"userId":$id,"name":"Fictional"}]}}}""", host)
    private fun result(id: Int, num: Int, host: String = "yfgo-game.netmarble.com") = send("/battle/result", """{"response":[{"success":{
        "resultDropInfos":[{"type":2,"objectId":6516,"num":$num}]}}],
        "cache":{"updated":{"userItem":[{"userId":$id,"itemId":6516,"num":$num}]}}}""", host)

    @Test fun `first result after token belongs only to the script account`() {
        login(7001); CaptureTracker.setRunning(true); CaptureTracker.startRun()
        result(7001, 1)
        val token = feed.battleToken()
        login(7002); result(7002, 99)
        assertNull(feed.awaitBattle(token, Duration.ZERO))
        result(7001, 3); result(7001, 4)
        assertEquals(mapOf(6516L to 3L), feed.awaitBattle(token, Duration.ZERO)!!.drops)
    }

    @Test fun `accountless script binds to first routed response and VPN stop rejects later results`() {
        CaptureTracker.setRunning(true); CaptureTracker.startRun()
        val token = feed.battleToken()
        result(7001, 2); result(7002, 99)
        assertEquals(mapOf(6516L to 2L), feed.awaitBattle(token, Duration.ZERO)!!.drops)
        val next = feed.battleToken()
        CaptureTracker.setRunning(false)
        result(7001, 4)
        assertNull(feed.awaitBattle(next, Duration.ZERO))
    }

    @Test fun `parses setup before reduction and returns public servant id not result local id`() {
        login(7001); CaptureTracker.setRunning(true); CaptureTracker.startRun()
        val token = feed.battleToken()
        send("/battle/setup", """{"cache":{"replaced":{"battle":[{"id":1,"battleInfo":{"myDeck":{"svts":[
            {"id":11,"userSvtId":101,"svtId":901,"initPos":1,"isFollowerSvt":false},
            {"id":12,"userSvtId":102,"svtId":902,"initPos":2,"isFollowerSvt":true}]}}}]}}}""")
        send("/battle/result", """{"response":[{"success":{"myDeck":{"svts":[{"id":11,"userSvtId":101}]},
            "oldUserSvtCollection":[{"svtId":901,"friendship":100,"friendshipRank":9,"friendshipExceedCount":0}],
            "resultDropInfos":[]}}],"cache":{"updated":{"userSvtCollection":[
            {"userId":7001,"svtId":901,"friendship":150,"friendshipRank":10,"friendshipExceedCount":0}]}}}""")
        val row = feed.awaitBattle(token, Duration.ZERO)!!.bond!!.single()
        assertEquals(901L, row.svtId); assertEquals(9, row.oldRank); assertEquals(10, row.newRank)
        assertEquals(10, row.cap); assertEquals(50L, row.gained)
    }

    @Test fun `new script clears prior results and ended script rejects results`() {
        login(7001); CaptureTracker.setRunning(true); CaptureTracker.startRun()
        result(7001, 3)
        val oldToken = feed.battleToken() - 1
        CaptureTracker.startRun()
        assertNull(feed.awaitBattle(oldToken, Duration.ZERO))
        val token = feed.battleToken()
        CaptureTracker.endRunCollection(); result(7001, 4)
        assertNull(feed.awaitBattle(token, Duration.ZERO))
    }

    @Test fun `inbox wakes a waiter and missing or invalid first response remains unknown`() {
        val inbox = CaptureBattleInbox()
        val token = inbox.token()
        val future = CompletableFuture.supplyAsync { inbox.await(token, 1.seconds) }
        val battle = CapturedBattle(mapOf(6516L to 2L), null)
        inbox.add(battle)
        assertSame(battle, future.get(2, TimeUnit.SECONDS))
        val next = inbox.token()
        inbox.add(null); inbox.add(battle)
        assertNull(inbox.await(next, Duration.ZERO))
        assertNull(inbox.await(inbox.token(), Duration.ZERO))
    }

    @Test fun `bridge follows actual VPN state and exposes only the startup fallback flag`() {
        assertFalse(feed.isCollecting()); assertFalse(feed.isStarting())
        CaptureTracker.starting = true
        assertTrue(feed.isStarting())
        CaptureTracker.setRunning(true)
        assertTrue(feed.isCollecting()); assertFalse(feed.isStarting())
        CaptureTracker.setRunning(false)
        assertFalse(feed.isCollecting())
    }

    @Test fun `krScriptFeedReceivesKrResultsWhileJpIsMostRecentAndReverse`() {
        login(7001, "yfgo-game.netmarble.com")
        login(7002, "game.fate-go.jp")
        assertEquals("7002", CaptureTracker.currentId.value)
        CaptureTracker.setRunning(true)

        CaptureTracker.startRun("KR")
        val krToken = feed.battleToken()
        result(7002, 99, "game.fate-go.jp")
        assertNull(feed.awaitBattle(krToken, Duration.ZERO))
        result(7001, 5, "yfgo-game.netmarble.com")
        assertEquals(mapOf(6516L to 5L), feed.awaitBattle(krToken, Duration.ZERO)!!.drops)

        login(7001, "yfgo-game.netmarble.com")
        assertEquals("7001", CaptureTracker.currentId.value)
        CaptureTracker.startRun("JP")
        val jpToken = feed.battleToken()
        result(7001, 88, "yfgo-game.netmarble.com")
        assertNull(feed.awaitBattle(jpToken, Duration.ZERO))
        result(7002, 7, "game.fate-go.jp")
        assertEquals(mapOf(6516L to 7L), feed.awaitBattle(jpToken, Duration.ZERO)!!.drops)
    }
}
