package io.github.fate_grand_automata.scripts.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureNamesTest {
    @Test fun jpHostIsReservedWithoutEnablingProxyRoutingYet() {
        assertEquals("game.fate-go.jp", CaptureNames.JP_HOST)
        assertEquals(mapOf(CaptureNames.GAME_HOST to "KR"), CaptureNames.HOST_SERVERS)
    }
    @Test fun accountPathsAreRecognisedWithAndWithoutQuery() {
        assertTrue(CaptureNames.isAccountPath("/NMAccount/login?token=x"))
        assertTrue(CaptureNames.isAccountPath("/NetmarbleService/foo"))
        assertTrue(CaptureNames.isAccountPath("NetmarbleUserDataService/x"))
        assertFalse(CaptureNames.isAccountPath("/login/top?userId=1"))
    }

    @Test fun classify() {
        assertEquals(CaptureNames.ApiPath.Account, CaptureNames.classify("/NMAccount/x"))
        assertEquals(CaptureNames.ApiPath.Login, CaptureNames.classify("/login/top?a=b"))
        assertEquals(CaptureNames.ApiPath.Game, CaptureNames.classify("/battle/result"))
    }

    @Test fun queryIsStripped() {
        assertEquals("/a/b", CaptureNames.stripQuery("/a/b?id=1#x"))
    }
}
