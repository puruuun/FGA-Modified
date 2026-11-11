package io.github.fate_grand_automata.capture

import kotlin.test.*

/** Exercises the actual response hook, using handwritten JSON and no files or Android services. */
class CaptureTrackerTest {
    @Test fun jpLoginTagsItsOwnAccountAndKeepsKrSeparate() {
        fun loginAt(host: String, id: Int, name: String) = feed("https://$host/login/top", """{
            "response":[{"nid":"login","resCode":"00","success":{}},{"nid":"userdata","resCode":"00","success":{}}],
            "cache":{"replaced":{"userGame":[{"userId":$id,"name":"$name","exp":100}],
            "userItem":[{"userId":$id,"itemId":100,"num":5}]},"serverTime":1800000000}}""")
        loginAt("yfgo-game.netmarble.com", 7001, "Synthetic KR")
        loginAt("game.fate-go.jp", 7002, "Synthetic JP")
        val accounts = CaptureTracker.accounts.value.associateBy { it.id }
        assertEquals(setOf("7001", "7002"), accounts.keys)
        assertEquals("KR", accounts.getValue("7001").server)
        assertEquals("JP", accounts.getValue("7002").server)
        assertEquals("7002", CaptureTracker.currentId.value)
        val jp = assertNotNull(CaptureTracker.snapshot("7002")).second
        val body = kotlinx.serialization.json.Json.parseToJsonElement(
            io.github.fate_grand_automata.scripts.capture.ChaldeaExport.body(jp)) as kotlinx.serialization.json.JsonObject
        val cache = body["cache"] as kotlinx.serialization.json.JsonObject
        assertEquals(kotlinx.serialization.json.JsonObject(jp.tables), cache["replaced"])
        assertEquals(jp.loginResponse, body["response"])
        assertFalse(body.containsKey("region"))
    }

    @BeforeTest fun reset() {
        CaptureTracker.discardRun()
        CaptureTracker.setRunning(false)
        CaptureTracker.delete()
    }

    @AfterTest fun clean() = reset()

    private fun feed(path: String, body: String) = CaptureTracker.onGameResponse(
        if (path.startsWith("https://")) path else "https://yfgo-game.netmarble.com$path",
        body.toByteArray(), null,
    )

    private fun login(id: Int = 1, host: String = "yfgo-game.netmarble.com") = feed("https://$host/login/top",
        """{"response":[{"nid":"login","resCode":"00","success":{}}],
        "cache":{"replaced":{"userGame":[{"userId":$id,"name":"Master $id","exp":100}],
        "userItem":[{"itemId":100,"num":5}],"userSvtCollection":[{"svtId":10,"friendship":200}]}}}""")

    private fun battle(id: Int = 1, host: String = "yfgo-game.netmarble.com", qp: Int = 1000) =
        feed("https://$host/battle/result", """{"response":[{"success":{"phaseClearQp":$qp}}],
        "cache":{"updated":{"userGame":[{"userId":$id,"exp":250}],
        "userSvtCollection":[{"svtId":10,"friendship":500}]}}}""")

    @Test fun parsesAgainstStateBeforeReductionAndSavesLatestHoldings() {
        login()
        CaptureTracker.setRunning(true)
        CaptureTracker.startRun()
        battle()
        feed("/item/use", """{"cache":{"updated":{"userItem":[{"itemId":100,"num":3}]}}}""")
        CaptureTracker.endRunCollection()
        val gains = assertNotNull(CaptureTracker.finishRun())
        assertEquals(150, gains.masterExp)
        assertEquals(mapOf(10L to 300L), gains.bond)
        assertEquals(2, gains.fruitUsed[100])
        assertEquals(1, gains.capturedBattles)
        assertNull(CaptureTracker.finishRun())
    }

    @Test fun stoppedVpnKeepsCapturedPrefixUntilRecordedExit() {
        CaptureTracker.startRun()
        CaptureTracker.setRunning(true)
        battle()
        CaptureTracker.setRunning(false, CaptureStopReason.Revoked)
        battle()
        CaptureTracker.endRunCollection()
        assertEquals(1, assertNotNull(CaptureTracker.finishRun()).capturedBattles)
    }

    @Test fun unrecordedExitDiscardsAndStoppedTrackerHasNoGains() {
        CaptureTracker.startRun()
        battle()
        assertNull(CaptureTracker.finishRun())
        CaptureTracker.setRunning(true)
        CaptureTracker.startRun()
        battle()
        CaptureTracker.discardRun()
        assertNull(CaptureTracker.finishRun())
    }

    @Test fun scriptEndClosesRunCollectionButKeepsVpnRunning() {
        login()
        CaptureTracker.setRunning(true)
        CaptureTracker.startRun()
        battle()
        CaptureAutoStart.onScriptEnd()
        assertTrue(CaptureTracker.running.value)
        battle()
        assertEquals(1, assertNotNull(CaptureTracker.finishRun()).capturedBattles)
    }

    @Test fun runStaysOnOpeningAccountAndFinishesWithItsHoldingsAfterSwitch() {
        login(7001)
        CaptureTracker.setRunning(true)
        CaptureTracker.startRun()
        battle(7001)
        feed("/item/use", """{"cache":{"updated":{"userItem":[{"userId":7001,"itemId":100,"num":3}]}}}""")
        login(7002)
        battle(7002)
        assertEquals("7002", CaptureTracker.currentId.value)
        val gains = assertNotNull(CaptureTracker.finishRun())
        assertEquals(1, gains.capturedBattles)
        assertEquals(2, gains.fruitUsed[100])
        assertEquals(1000, gains.qp)
    }

    @Test fun accountlessRunBindsToFirstRoutedResponseAndRejectsOtherAccount() {
        CaptureTracker.setRunning(true)
        CaptureTracker.startRun()
        battle(7001) // No snapshot: gains can still be captured.
        battle(7002)
        val entry = CaptureTracker.finishRun(io.github.fate_grand_automata.battlelog.battleLogFixture())
        assertEquals("7001", entry.accountId)
        assertEquals("#7001", entry.accountName)
        assertEquals(1, entry.gains!!.capturedBattles)
    }

    @Test fun conflictResponseDoesNotChangeStateOrCollectBattle() {
        login(7001)
        CaptureTracker.setRunning(true)
        CaptureTracker.startRun()
        val before = CaptureTracker.snapshot()?.second
        feed("/battle/result", """{"response":[{"success":{"phaseClearQp":9000}}],"cache":{"updated":{
            "userItem":[{"userId":7001,"itemId":100,"num":1}],"userGame":[{"userId":7002,"exp":999}]}}}""")
        assertSame(before, CaptureTracker.snapshot()?.second)
        assertEquals("7001", CaptureTracker.currentId.value)
        assertEquals(0, assertNotNull(CaptureTracker.finishRun()).capturedBattles)
    }

    @Test fun battleLogStoresDisplayNameAtFinishOnlyWhenGainsExist() {
        val original = io.github.fate_grand_automata.battlelog.battleLogFixture()
        login(7001)
        CaptureTracker.startRun()
        val untracked = CaptureTracker.finishRun(original)
        assertNull(untracked.accountId)
        assertNull(untracked.accountName)
        CaptureTracker.setRunning(true)
        CaptureTracker.startRun()
        battle(7001)
        CaptureTracker.rename("7001", "Farming")
        val entry = CaptureTracker.finishRun(original)
        assertEquals("7001", entry.accountId)
        assertEquals("Farming", entry.accountName)
        assertEquals(original.timesRan - 1, entry.gains!!.missingBattles)
        CaptureTracker.rename("7001", "Later")
        assertEquals("Farming", entry.accountName)
        assertEquals("Later", CaptureTracker.summary()!!.masterName)
    }

    @Test fun krScriptCollectsKrBattleResultsWhileJpIsMostRecentAndReverse() {
        login(7001, "yfgo-game.netmarble.com")
        login(7002, "game.fate-go.jp")
        assertEquals("7002", CaptureTracker.currentId.value)
        CaptureTracker.setRunning(true)

        // Start a KR run while JP (7002) is the global current account.
        CaptureTracker.startRun("KR")
        battle(7002, "game.fate-go.jp", qp = 9000)
        battle(7001, "yfgo-game.netmarble.com", qp = 1200)
        val krEntry = CaptureTracker.finishRun(io.github.fate_grand_automata.battlelog.battleLogFixture())
        assertEquals("7001", krEntry.accountId)
        assertEquals(1, krEntry.gains!!.capturedBattles)
        assertEquals(1200, krEntry.gains!!.qp)

        // Reverse: make KR most recent, then start a JP run.
        login(7001, "yfgo-game.netmarble.com")
        assertEquals("7001", CaptureTracker.currentId.value)
        CaptureTracker.startRun("JP")
        battle(7001, "yfgo-game.netmarble.com", qp = 8000)
        battle(7002, "game.fate-go.jp", qp = 3400)
        val jpEntry = CaptureTracker.finishRun(io.github.fate_grand_automata.battlelog.battleLogFixture())
        assertEquals("7002", jpEntry.accountId)
        assertEquals(1, jpEntry.gains!!.capturedBattles)
        assertEquals(3400, jpEntry.gains!!.qp)
    }

    @Test fun accountlessRunBindsOnlyToExpectedServerResponse() {
        CaptureTracker.setRunning(true)
        CaptureTracker.startRun("JP")
        battle(7001, "yfgo-game.netmarble.com", qp = 5000)
        battle(7002, "game.fate-go.jp", qp = 2500)
        val entry = CaptureTracker.finishRun(io.github.fate_grand_automata.battlelog.battleLogFixture())
        assertEquals("7002", entry.accountId)
        assertEquals(1, entry.gains!!.capturedBattles)
        assertEquals(2500, entry.gains!!.qp)
    }

    @Test fun unknownHostResponseIsDroppedCompletely() {
        login(7001, "yfgo-game.netmarble.com")
        val before = CaptureTracker.snapshot("7001")?.second
        CaptureTracker.onGameResponse("https://example.com/login/top",
            """{"response":[{"nid":"login","resCode":"00"}],"cache":{"replaced":{"userGame":[{"userId":7002,"name":"Other"}]}}}""".toByteArray(), null)
        CaptureTracker.onGameResponse("https://example.com/item/use",
            """{"cache":{"updated":{"userItem":[{"userId":7001,"itemId":100,"num":1}]}}}""".toByteArray(), null)
        assertEquals(setOf("7001"), CaptureTracker.accounts.value.map { it.id }.toSet())
        assertSame(before, CaptureTracker.snapshot("7001")?.second)
    }
}
