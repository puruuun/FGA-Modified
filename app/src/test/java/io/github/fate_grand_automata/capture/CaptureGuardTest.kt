package io.github.fate_grand_automata.capture

import kotlin.test.*

class CaptureGuardTest {
    @Test fun outOfMemoryIsLoggedNotRethrownAndLaterCallsRun() {
        guardedCapture("friend/top", 1000) { throw OutOfMemoryError("synthetic") }
        guardedCapture("friend/top", 1000) { throw IllegalStateException("synthetic") }
        var ran = false
        guardedCapture("friend/top", 1000) { ran = true }
        assertTrue(ran)
    }

    @Test fun trackerSurvivesAMalformedFriendTop() {
        CaptureTracker.delete()
        CaptureTracker.onGameResponse("https://yfgo-game.netmarble.com/friend/top", """{"cache":{"updated":""".toByteArray(), null)
        CaptureTracker.onGameResponse("https://yfgo-game.netmarble.com/friend/top", ByteArray(10), "gzip")
        assertTrue(CaptureTracker.rosters.value.isEmpty())
        CaptureTracker.delete()
    }
}
