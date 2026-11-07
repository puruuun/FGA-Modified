package io.github.fate_grand_automata.scripts.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureNamesTest {
    @Test fun hostsIdentifyBothServers() {
        assertEquals("game.fate-go.jp", CaptureNames.JP_HOST)
        assertEquals("JP", CaptureNames.server("https://game.fate-go.jp/login/top?_userId=7002"))
        assertEquals("KR", CaptureNames.server("https://yfgo-game.netmarble.com/login/top"))
        assertEquals(null, CaptureNames.server("https://example.org/login/top"))
    }
    @Test fun jpGamePathsAreNeverNetmarbleAccountPaths() {
        for (path in listOf("login/top", "home/top", "battle/setup", "battle/result", "externalPayment/reflect")) {
            val target = "https://game.fate-go.jp/$path?_userId=7002"
            assertFalse(CaptureNames.isAccountPath(target))
            assertEquals(if (path == "login/top") CaptureNames.ApiPath.Login else CaptureNames.ApiPath.Game,
                CaptureNames.classify(target))
            assertEquals("https://game.fate-go.jp/$path", CaptureNames.stripQuery(target))
        }
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
