package io.github.fate_grand_automata.scripts.capture

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone
import kotlin.test.*

class GameDayTest {
    private fun kst(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, s: Int = 0, ms: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi, s, ms * 1_000_000).toInstant(ZoneOffset.ofHours(9)).toEpochMilli()

    private fun check() {
        assertEquals(GameDay(2026, 11, 10), gameDay(kst(2026, 11, 11, 1)))
        assertEquals(GameDay(2026, 11, 11), gameDay(kst(2026, 11, 11, 4)))
        assertEquals(GameDay(2026, 11, 10), gameDay(kst(2026, 11, 11, 3, 59, 59, 999)))
        assertEquals(GameDay(2026, 11, 11), gameDay(kst(2026, 11, 11, 23, 59, 59, 999)))
        assertEquals(GameDay(2025, 12, 31), gameDay(kst(2026, 1, 1, 3, 59, 59, 999)))
        assertEquals(GameDay(2026, 1, 1), gameDay(kst(2026, 1, 1, 4)))
        assertTrue(GameDay(2025, 12, 31) < GameDay(2026, 1, 1))
        assertTrue(GameDay(2026, 2, 28) < GameDay(2026, 11, 1))
    }

    @Test fun boundariesInKst() = check()

    @Test fun independentOfDeviceTimeZone() {
        val saved = TimeZone.getDefault()
        try {
            for (zone in listOf("UTC", "America/New_York")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                check()
            }
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test fun independentOfDeviceLocaleCalendar() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale("th", "TH"))
            check()
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun leapDay() {
        assertEquals(GameDay(2028, 2, 28), gameDay(kst(2028, 2, 29, 3, 59, 59, 999)))
        assertEquals(GameDay(2028, 2, 29), gameDay(kst(2028, 2, 29, 4)))
        assertEquals(GameDay(2028, 2, 29), gameDay(kst(2028, 3, 1, 3)))
    }
}
