package io.github.fate_grand_automata.scripts.capture

import java.util.Calendar
import java.util.TimeZone

/** A game day: the game's daily reset is 04:00 JST/KST, so the day runs 04:00 to the next 04:00. */
data class GameDay(val year: Int, val month: Int, val day: Int) : Comparable<GameDay> {
    override fun compareTo(other: GameDay) =
        compareValuesBy(this, other, GameDay::year, GameDay::month, GameDay::day)
}

/**
 * The game day of [epochMs]. KR and JP share UTC+9 with no DST, so the offset is fixed and independent of the device
 * time zone. java.time is avoided because minSdk 24 has no desugaring.
 */
fun gameDay(epochMs: Long): GameDay {
    val calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT+09:00"))
    calendar.timeInMillis = epochMs - 4L * 60 * 60 * 1000
    return GameDay(calendar[Calendar.YEAR], calendar[Calendar.MONTH] + 1, calendar[Calendar.DAY_OF_MONTH])
}
