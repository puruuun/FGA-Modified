package io.github.fate_grand_automata.widget

import android.content.Context
import io.github.fate_grand_automata.R
import java.util.Date

/** How old the stored data is, in the unit the widget shows. Pure, so it is unit tested directly. */
sealed interface DataAge {
    /** Under one minute. */
    data object Now : DataAge
    data class Minutes(val value: Long) : DataAge
    data class Hours(val value: Long) : DataAge
    data class Days(val value: Long) : DataAge
}

fun dataAge(lastUpdate: Long, now: Long): DataAge {
    val seconds = (now - lastUpdate).coerceAtLeast(0)
    return when {
        seconds < 60 -> DataAge.Now
        seconds < 3600 -> DataAge.Minutes(seconds / 60)
        seconds < 86400 -> DataAge.Hours(seconds / 3600)
        else -> DataAge.Days(seconds / 86400)
    }
}

fun dataAgeText(context: Context, age: DataAge): String = when (age) {
    DataAge.Now -> context.getString(R.string.widget_age_now)
    is DataAge.Minutes -> context.getString(R.string.widget_age_minutes, age.value)
    is DataAge.Hours -> context.getString(R.string.widget_age_hours, age.value)
    is DataAge.Days -> context.getString(R.string.widget_age_days, age.value)
}

/** Remaining time as H:MM, the minutes padded to two digits ("3:23", "0:25"). */
fun remainingText(minutes: Long): String {
    val m = minutes.coerceAtLeast(0)
    return "${m / 60}:${(m % 60).toString().padStart(2, '0')}"
}

/** Which optional parts the widget shows at a given width. */
data class ApWidgetFields(
    /** The "(H:MM)" time left after the full-at clock time. */
    val showRemaining: Boolean,
    /** The refresh icon; also selects the larger tile and type sizes. */
    val showRefresh: Boolean,
    /** The small "AP" label in front of the current AP. */
    val showApLabel: Boolean,
    /** The "완충" word in front of the full-at clock time; the narrowest widget shows the time alone. */
    val showFullWord: Boolean,
)

/**
 * Width breakpoints, measured on the screenshot tests with a 12-hour Korean clock ("완충 오후 8:23 (3:23)").
 * A standard 4x1 cell is about 270 dp wide and a 3x1 about 200 dp; the widget resizes down to 180 dp.
 * Below each breakpoint the AP row would clip.
 */
const val AP_WIDGET_FULL_WORD_MIN_DP = 195f
const val AP_WIDGET_WIDE_MIN_DP = 230f
const val AP_WIDGET_REMAINING_MIN_DP = 255f
const val AP_WIDGET_LABEL_MIN_DP = 268f

fun apWidgetFields(widthDp: Float): ApWidgetFields = ApWidgetFields(
    showRemaining = widthDp >= AP_WIDGET_REMAINING_MIN_DP,
    showRefresh = widthDp >= AP_WIDGET_WIDE_MIN_DP,
    showApLabel = widthDp >= AP_WIDGET_LABEL_MIN_DP,
    showFullWord = widthDp >= AP_WIDGET_FULL_WORD_MIN_DP,
)

/** Strings shown by the AP widget; built apart from Glance so they can be unit tested. */
data class ApWidgetTexts(
    val name: String,
    val server: String,
    val apLabel: String,
    val ap: String,
    val apMax: String,
    /** "Full 9:41 PM (4:35)", or "AP full". */
    val fullLine: String,
    /** The narrow variant of [fullLine]: no time left. */
    val fullLineShort: String,
    /** The narrowest variant: the clock time alone, or "AP full". */
    val fullLineTime: String,
    val age: String,
)

fun apWidgetTexts(context: Context, model: ApWidgetModel, now: Long = System.currentTimeMillis() / 1000): ApWidgetTexts {
    val clock = android.text.format.DateFormat.getTimeFormat(context)
    fun time(epochSeconds: Long) = clock.format(Date(epochSeconds * 1000))
    val full = context.getString(R.string.widget_ap_full)
    val fullAt = model.fullAt
    return ApWidgetTexts(
        name = model.name,
        server = model.server,
        apLabel = context.getString(R.string.widget_ap_label),
        ap = model.ap.toString(),
        apMax = context.getString(R.string.widget_ap_max, model.apMax.toString()),
        fullLine = if (fullAt == null) full
        else context.getString(R.string.widget_ap_full_at, time(fullAt), remainingText(model.minutesLeft ?: 0)),
        fullLineShort = if (fullAt == null) full else context.getString(R.string.widget_ap_full_at_short, time(fullAt)),
        fullLineTime = if (fullAt == null) full else time(fullAt),
        age = dataAgeText(context, dataAge(model.dataTime, now)),
    )
}
