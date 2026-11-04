package io.github.fate_grand_automata.capture

import kotlin.test.*

/** Exercises the actual response hook, using handwritten JSON and no files or Android services. */
class CaptureTrackerTest {
    @BeforeTest fun reset() {
        CaptureTracker.discardRun()
        CaptureTracker.setRunning(false)
        CaptureTracker.delete()
    }

    @AfterTest fun clean() = reset()

    private fun feed(path: String, body: String) = CaptureTracker.onGameResponse(path, body.toByteArray(), null)

    private fun login() = feed("/login/top", """{"response":[{"nid":"login","resCode":"00","success":{}}],
        "cache":{"replaced":{"userGame":[{"userId":1,"exp":100}],
        "userItem":[{"itemId":100,"num":5}],"userSvtCollection":[{"svtId":10,"friendship":200}]}}}""")

    private fun battle() = feed("/battle/result", """{"response":[{"success":{"phaseClearQp":1000}}],
        "cache":{"updated":{"userGame":[{"userId":1,"exp":250}],
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
}
