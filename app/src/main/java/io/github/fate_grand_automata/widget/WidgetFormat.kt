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

/**
 * Type sizes by widget width. The StackView draws each card at 90 % of the stack, so the narrow tiers are
 * smaller than a plain list would need; each still shows AP and the complete Korean 12-hour clock.
 */
data class ApWidgetFields(val apTextSize: Int, val fullTextSize: Int)

const val AP_WIDGET_MEDIUM_TEXT_MIN_DP = 200f
const val AP_WIDGET_LARGE_TEXT_MIN_DP = 225f
const val AP_WIDGET_XLARGE_TEXT_MIN_DP = 250f

fun apWidgetFields(widthDp: Float): ApWidgetFields =
    when {
        widthDp >= AP_WIDGET_XLARGE_TEXT_MIN_DP -> ApWidgetFields(18, 11)
        widthDp >= AP_WIDGET_LARGE_TEXT_MIN_DP -> ApWidgetFields(14, 10)
        widthDp >= AP_WIDGET_MEDIUM_TEXT_MIN_DP -> ApWidgetFields(11, 9)
        else -> ApWidgetFields(9, 8)
    }

/** Strings shown by the AP widget, built apart from Glance for formatting tests. */
data class ApWidgetTexts(
    val name: String,
    val ap: String,
    val fullLine: String,
    val age: String,
)

fun apWidgetTexts(context: Context, model: ApWidgetModel, now: Long = System.currentTimeMillis() / 1000): ApWidgetTexts {
    val clock = android.text.format.DateFormat.getTimeFormat(context)
    val fullAt = model.fullAt
    return ApWidgetTexts(
        name = model.name,
        ap = context.getString(R.string.widget_ap_value, model.ap, model.apMax),
        fullLine = if (fullAt == null) context.getString(R.string.widget_ap_full)
        else context.getString(R.string.widget_ap_full_at, clock.format(Date(fullAt * 1000))),
        age = dataAgeText(context, dataAge(model.dataTime, now)),
    )
}