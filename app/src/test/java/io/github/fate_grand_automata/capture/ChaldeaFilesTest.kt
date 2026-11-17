package io.github.fate_grand_automata.capture

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import kotlin.test.*

class ChaldeaFilesTest {
    private val old = "chaldea-kr-7001-20261104-0901.json"
    private val latest = "chaldea-kr-7001-20261104-0902.json"

    private fun localTime(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        GregorianCalendar(year, month - 1, day, hour, minute, second).timeInMillis

    @Test fun filenameHasZeroPaddedLocalMinute() {
        assertEquals("chaldea-account-7001-20261104-0901.json", ChaldeaFiles.name("7001", localTime(2026, 11, 4, 9, 1, 59)))
    }

    @Test fun filenameDoesNotDependOnDefaultLocale() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"))
            assertEquals("chaldea-account-7001-20261104-0901.json", ChaldeaFiles.name("7001", localTime(2026, 11, 4, 9, 1)))
        } finally { Locale.setDefault(before) }
    }

    @Test fun keepsNewestOfUnsortedNames() {
        assertEquals(listOf(old), ChaldeaFiles.toDelete(listOf(latest, old)))
    }

    @Test fun neverDeletesForeignNames() {
        val foreign = listOf("account.json", "fga-capture-ca.cer", "chaldea-jp-20261104-0901.json",
            "chaldea-kr-20261104-0901.json.bak", "chaldea-kr-20261104-0901 (1).json", "chaldea-kr-bad.json")
        assertEquals(listOf(old), ChaldeaFiles.toDelete(foreign + old + latest))
    }

    @Test fun emptyAndSingleExportNeedNoDeletion() {
        assertTrue(ChaldeaFiles.toDelete(emptyList()).isEmpty())
        assertTrue(ChaldeaFiles.toDelete(listOf(latest)).isEmpty())
    }

    @Test fun keepsFreshExportWhenClockMovedBackwards() {
        assertEquals(listOf(latest), ChaldeaFiles.toDelete(listOf(latest, old), newest = old))
    }

    @Test fun minuteCollisionIsRetainedByNameForStoreToResolveById() {
        assertEquals(listOf(old), ChaldeaFiles.toDelete(listOf(latest, old, latest), latest))
    }

    @Test fun keepsNewestForEachAccountAndCleansEveryLegacyFile() {
        val otherOld = "chaldea-kr-7002-20261104-0801.json"
        val otherNew = "chaldea-kr-7002-20261104-0802.json"
        val legacy = listOf("chaldea-kr-20261104-0901.json", "chaldea-kr-20261104-0902.json")
        val names = listOf(old, latest, otherNew, otherOld) + legacy
        assertEquals(listOf(old, otherOld) + legacy, ChaldeaFiles.toDelete(names, latest))
        assertEquals(listOf(old, otherOld) + legacy, ChaldeaFiles.toDelete(names))
        assertEquals("7001", ChaldeaFiles.accountId(latest))
        assertNull(ChaldeaFiles.accountId(legacy.first()))
    }

    @Test fun exportingAnotherAccountPreservesTheFreshFileOfTheFirstAccount() {
        val other = "chaldea-kr-7002-20261104-0801.json"
        assertEquals(listOf(old), ChaldeaFiles.toDelete(listOf(old, latest, other), other))
        assertTrue(ChaldeaFiles.toDelete(listOf(old, other), other).isEmpty())
    }

    @Test fun neutralAndOldKrNamesAreCleanedByAccountAndTimestamp() {
        val newer = "chaldea-account-7001-20261104-0903.json"
        val older = "chaldea-account-7001-20261104-0900.json"
        val jp = "chaldea-account-7002-20261104-0904.json"
        assertEquals(listOf(old, latest, older), ChaldeaFiles.toDelete(listOf(old, latest, older, newer, jp)))
        assertEquals("7002", ChaldeaFiles.accountId(jp))
        assertTrue(ChaldeaFiles.isExport(jp))
        assertEquals("kr-test", ChaldeaFiles.accountId("chaldea-account-kr-test-20261104-0904.json"))
    }

    @Test fun fallbackNameAddsSecondsAndIsStillOwnedAndNewest() {
        val fallback = ChaldeaFiles.fallbackName("7001", localTime(2026, 11, 4, 9, 1, 7))
        assertEquals("chaldea-account-7001-20261104-0901-07.json", fallback)
        assertEquals("7001", ChaldeaFiles.accountId(fallback))
        assertTrue(ChaldeaFiles.isExport(fallback))
        // Same minute: the second-stamped name sorts after the fixed one, so the fixed one is cleaned up.
        assertEquals(listOf(old), ChaldeaFiles.toDelete(listOf(old, fallback)))
    }

    @Test fun captureFileSaverTimestampedFormatsLocalMinuteWithoutLocaleDrift() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"))
            assertEquals(
                "fga-capture-ca-20261104-0901.crt",
                CaptureFileSaver.timestamped("fga-capture-ca.crt", localTime(2026, 11, 4, 9, 1, 59)),
            )
        } finally { Locale.setDefault(before) }
    }

    @Test fun certStorePemWrapsAt64CharsAndRoundTripsX509() {
        val cert = io.github.fate_grand_automata.scripts.capture.SingleUseCa.issue("yfgo-game.netmarble.com").caCert
        val pem = CertStore.pem(cert)
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----\n"))
        assertTrue(pem.endsWith("\n-----END CERTIFICATE-----\n"))
        val bodyLines = pem.lines().drop(1).dropLast(2)
        assertTrue(bodyLines.size > 1)
        assertTrue(bodyLines.dropLast(1).all { it.length == 64 })
        assertTrue(bodyLines.last().length in 1..64)
        val parsed = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(pem.byteInputStream()) as java.security.cert.X509Certificate
        assertContentEquals(cert.encoded, parsed.encoded)
    }
}
