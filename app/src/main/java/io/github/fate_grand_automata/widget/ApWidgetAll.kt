package io.github.fate_grand_automata.widget

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import io.github.fate_grand_automata.R

/** Narrowest a cell can be: the AP value at 14 sp bold and the Korean 12-hour full time at 10 sp bold. */
const val ALL_CELL_MIN_WIDTH_DP = 76

/** Card insets (8 + 22), the gap after the account area (8) and the row's own left padding (14). */
private const val ALL_FIXED_INSETS_DP = 8 + 22 + 8 + 14

/** Room kept for the one data-age label (a few Korean characters or "5h ago"). */
private const val ALL_AGE_LABEL_DP = 44

/** Width of one 40 dp tile and its 4 dp margin. */
private const val TILE_DP = 44

/** On phones the age sits above the buttons, sharing their width. */
internal fun compactAllAccountsAge(widthDp: Int, showLog: Boolean, showService: Boolean) =
    widthDp < 400 && (showLog || showService)

/**
 * How many accounts fit in the one-row style: as many as the width allows at [ALL_CELL_MIN_WIDTH_DP] each,
 * never fewer than one and never more than [accountCount].
 */
fun allAccountsCellCount(widthDp: Int, showLog: Boolean, showService: Boolean, accountCount: Int): Int {
    val tiles = (if (showLog) TILE_DP else 0) + (if (showService) TILE_DP else 0)
    val age = if (compactAllAccountsAge(widthDp, showLog, showService)) 0 else ALL_AGE_LABEL_DP
    val room = widthDp - ALL_FIXED_INSETS_DP - age - tiles
    return (room / ALL_CELL_MIN_WIDTH_DP).coerceAtLeast(1).coerceAtMost(accountCount.coerceAtLeast(0))
}

/** The accounts the row shows, in model order. */
fun shownAllAccounts(models: List<ApWidgetModel>, cells: Int): List<ApWidgetModel> = models.take(cells)

/** The label's age follows the stalest of the shown accounts. */
fun oldestDataTime(models: List<ApWidgetModel>): Long? = models.minOfOrNull { it.dataTime }

/** "KR" / "JP" badge text, or null when the badge is off. */
fun serverBadgeText(show: Boolean, server: String): String? = server.trim().uppercase().takeIf { show && it.isNotEmpty() }

private fun buildAllCell(
    context: Context,
    model: ApWidgetModel,
    palette: WidgetPalette,
    showServer: Boolean,
    now: Long,
    heightDp: Int,
): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_ap_all_cell)
    if (heightDp < 74) {
        // Allow for Korean font metrics and the user's font scale in all three lines.
        val scale = ((heightDp - 4) / (52f * context.resources.configuration.fontScale)).coerceIn(0.1f, 1f)
        views.setTextViewTextSize(R.id.ap_all_name, TypedValue.COMPLEX_UNIT_SP, 12f * scale)
        views.setTextViewTextSize(R.id.ap_all_ap, TypedValue.COMPLEX_UNIT_SP, 14f * scale)
        views.setTextViewTextSize(R.id.ap_all_full, TypedValue.COMPLEX_UNIT_SP, 10f * scale)
        views.setViewPadding(R.id.ap_all_ap, 0, 0, 0, 0)
        views.setViewPadding(R.id.ap_all_full, 0, 0, 0, 0)
    }
    fun color(res: Int) = ContextCompat.getColor(context, res)
    val texts = apWidgetTexts(context, model, now)
    views.setTextViewText(R.id.ap_all_name, texts.name)
    views.setTextViewText(R.id.ap_all_ap, texts.ap)
    views.setTextViewText(R.id.ap_all_full, texts.fullLine)
    views.setTextColor(R.id.ap_all_name, color(palette.onSurface))
    views.setTextColor(R.id.ap_all_ap, color(palette.onSurface))
    views.setTextColor(R.id.ap_all_full, color(palette.fullAt))
    val badge = serverBadgeText(showServer, model.server)
    views.setViewVisibility(R.id.ap_all_server, if (badge != null) View.VISIBLE else View.GONE)
    if (badge != null) {
        views.setTextViewText(R.id.ap_all_server, badge)
        views.setTextColor(R.id.ap_all_server, color(palette.secondary))
    }
    // The same tap as the stack card: open this account's dashboard.
    views.setOnClickPendingIntent(R.id.ap_all_cell, openAccountPendingIntent(context, model.accountId))
    return views
}

/** Fills the all-accounts row of the top-level [views] and shows it. */
fun addAllAccountsRow(
    context: Context,
    views: RemoteViews,
    models: List<ApWidgetModel>,
    palette: WidgetPalette,
    cells: Int,
    showServer: Boolean,
    now: Long = System.currentTimeMillis() / 1000,
    heightDp: Int = 74,
    compactAge: Boolean = false,
) {
    val shown = shownAllAccounts(models, cells)
    views.setViewVisibility(R.id.ap_all, View.VISIBLE)
    views.removeAllViews(R.id.ap_all_cells)
    shown.forEach { views.addView(R.id.ap_all_cells, buildAllCell(context, it, palette, showServer, now, heightDp)) }
    val oldest = oldestDataTime(shown)
    val ageId = if (compactAge) R.id.ap_compact_age else R.id.ap_all_age
    views.setViewVisibility(R.id.ap_all_age, if (compactAge) View.GONE else View.VISIBLE)
    views.setViewVisibility(R.id.ap_compact_age, if (compactAge) View.VISIBLE else View.GONE)
    views.setTextViewText(ageId, oldest?.let { dataAgeText(context, dataAge(it, now)) } ?: "")
    views.setTextColor(ageId, ContextCompat.getColor(context, palette.secondary))
}
