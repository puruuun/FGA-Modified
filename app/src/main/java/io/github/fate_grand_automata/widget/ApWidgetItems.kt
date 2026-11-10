package io.github.fate_grand_automata.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.widget.RemoteViewsCompat
import io.github.fate_grand_automata.R
import kotlin.math.roundToInt

/** Resolution of the AP bar; the item layout's ProgressBar has the same max. */
const val AP_BAR_MAX = 1000

/** Everything one stack card shows, apart from the colours. Pure, so it is unit tested directly. */
data class ApItemContent(
    val itemId: Long,
    val accountId: String,
    val texts: ApWidgetTexts,
    val progress: Int,
    /** Merged into the tap template, so the tap opens this account's dashboard. */
    val fillIn: Intent,
    val server: String = "",
)

fun apItemContent(context: Context, model: ApWidgetModel, now: Long = System.currentTimeMillis() / 1000) = ApItemContent(
    itemId = apWidgetItemId(model.accountId),
    accountId = model.accountId,
    texts = apWidgetTexts(context, model, now),
    progress = (model.fraction * AP_BAR_MAX).roundToInt().coerceIn(0, AP_BAR_MAX),
    fillIn = WidgetIntents.openAccountFillIn(model.accountId),
    server = model.server,
)

/** Order is the model order, which is the tracker's order: current account first, then latest update. */
fun apItemContents(context: Context, models: List<ApWidgetModel>, now: Long = System.currentTimeMillis() / 1000) =
    models.map { apItemContent(context, it, now) }

/** One account card. Text colours and the bar follow the theme. */
fun buildApItemViews(context: Context, content: ApItemContent, palette: WidgetPalette, fields: ApWidgetFields, showServer: Boolean = false): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_ap_item)
    fun color(res: Int) = ContextCompat.getColor(context, res)
    views.setTextViewText(R.id.ap_item_name, content.texts.name)
    views.setTextViewText(R.id.ap_item_age, content.texts.age)
    views.setTextViewText(R.id.ap_item_ap, content.texts.ap)
    views.setTextViewText(R.id.ap_item_full, content.texts.fullLine)
    views.setTextColor(R.id.ap_item_name, color(palette.onSurface))
    views.setTextColor(R.id.ap_item_age, color(palette.secondary))
    views.setTextColor(R.id.ap_item_ap, color(palette.onSurface))
    views.setTextColor(R.id.ap_item_full, color(palette.fullAt))
    val badge = serverBadgeText(showServer, content.server)
    views.setViewVisibility(R.id.ap_item_server, if (badge != null) View.VISIBLE else View.GONE)
    if (badge != null) {
        views.setTextViewText(R.id.ap_item_server, badge)
        views.setTextColor(R.id.ap_item_server, color(palette.secondary))
    }
    views.setTextViewTextSize(R.id.ap_item_ap, TypedValue.COMPLEX_UNIT_SP, fields.apTextSize.toFloat())
    views.setTextViewTextSize(R.id.ap_item_full, TypedValue.COMPLEX_UNIT_SP, fields.fullTextSize.toFloat())
    // Two bars with their own track colour; setProgressDrawable cannot be sent through RemoteViews.
    views.setViewVisibility(R.id.ap_item_bar_dark, if (palette.light) View.GONE else View.VISIBLE)
    views.setViewVisibility(R.id.ap_item_bar_light, if (palette.light) View.VISIBLE else View.GONE)
    views.setProgressBar(if (palette.light) R.id.ap_item_bar_light else R.id.ap_item_bar_dark, AP_BAR_MAX, content.progress, false)
    views.setOnClickFillInIntent(R.id.ap_item_root, content.fillIn)
    return views
}

/**
 * Fills the AdapterViewFlipper (R.id.ap_flipper) of the top-level [views] with one card per account. Items go
 * through [RemoteViewsCompat.setRemoteAdapter]: Android 12+ uses RemoteCollectionItems directly, older versions
 * fall back to androidx's own RemoteViewsCompatService. [views] must be the RemoteViews given to updateAppWidget.
 * [displayedIndex] is the account to show; it is set after the adapter so every full render keeps it.
 */
fun addApFlipper(
    context: Context,
    views: RemoteViews,
    appWidgetId: Int,
    models: List<ApWidgetModel>,
    palette: WidgetPalette,
    fields: ApWidgetFields,
    displayedIndex: Int = 0,
    now: Long = System.currentTimeMillis() / 1000,
    showServer: Boolean = false,
) {
    val items = RemoteViewsCompat.RemoteCollectionItems.Builder()
        .setHasStableIds(true)
        .setViewTypeCount(1)
    apItemContents(context, models, now).forEach { items.addItem(it.itemId, buildApItemViews(context, it, palette, fields, showServer)) }
    RemoteViewsCompat.setRemoteAdapter(context, views, appWidgetId, R.id.ap_flipper, items.build())
    views.setDisplayedChild(R.id.ap_flipper, displayedIndex.coerceIn(0, (models.size - 1).coerceAtLeast(0)))
    views.setPendingIntentTemplate(R.id.ap_flipper, openAccountTemplate(context))
}

/** Mutable on purpose: the system has to merge each card's fill-in intent into it (required from Android 12). */
internal fun openAccountTemplate(context: Context): PendingIntent {
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    return PendingIntent.getActivity(context, 0, WidgetIntents.openAccountTemplate(context), flags)
}
