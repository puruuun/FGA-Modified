package io.github.fate_grand_automata.ui.log

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import io.github.fate_grand_automata.scripts.capture.GameDay
import io.github.fate_grand_automata.scripts.capture.gameDay
import java.text.SimpleDateFormat
import java.util.GregorianCalendar

@Composable
internal fun dayLabel(day: GameDay, now: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val skeleton = if (day.year == gameDay(now).year) "MMMdEEE" else "yMMMdEEE"
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
    // Local calendar on purpose: only the numeric date is formatted, so no zone shifts it.
    return SimpleDateFormat(pattern, locale).format(GregorianCalendar(day.year, day.month - 1, day.day).time)
}

