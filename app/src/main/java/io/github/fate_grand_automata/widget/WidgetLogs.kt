package io.github.fate_grand_automata.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.battlelog.FileBattleLogStorage
import io.github.fate_grand_automata.capture.CaptureLogItem
import io.github.fate_grand_automata.capture.CaptureTracker
import io.github.fate_grand_automata.capture.OutsideLogRepository
import io.github.fate_grand_automata.capture.battleLogSummary
import io.github.fate_grand_automata.capture.captureAccountName
import io.github.fate_grand_automata.capture.mixedCaptureLogs
import io.github.fate_grand_automata.capture.outsideLogSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Height of the log-card row, including its bottom padding. The top row gets the rest of the widget. */
const val WIDGET_LOGS_ROW_HEIGHT_DP = 54

/** Narrowest a log card may be: two fit a 3x2 and a 4x2 phone widget, five a 560 dp tablet. */
const val WIDGET_LOG_CARD_MIN_WIDTH_DP = 96

/** Row insets (8 + 10), and the 4 dp gap each card carries after it. */
private const val LOGS_ROW_INSETS_DP = 8 + 10
private const val LOG_CARD_GAP_DP = 4

/** Never read or draw more cards than this, however wide the widget is. */
const val WIDGET_LOG_CARD_MAX = 8

/** Cards narrower than this drop the small account name so the title gets the room. */
const val WIDGET_LOG_CARD_ACCOUNT_MIN_WIDTH_DP = 130

/** Width of one card when [count] share [widthDp]: the row's inner width split evenly, less each card's gap. */
fun widgetLogCardWidthDp(widthDp: Int, count: Int): Int =
    (widthDp - LOGS_ROW_INSETS_DP) / count.coerceAtLeast(1) - LOG_CARD_GAP_DP

/** How many cards fit in [widthDp]: as many as the width allows at the minimum card width, at least one. */
fun widgetLogCardCount(widthDp: Int): Int =
    ((widthDp - LOGS_ROW_INSETS_DP + LOG_CARD_GAP_DP) / (WIDGET_LOG_CARD_MIN_WIDTH_DP + LOG_CARD_GAP_DP)).coerceIn(1, WIDGET_LOG_CARD_MAX)

/** What one card shows. Built from the same entries and summary functions as the log list. */
data class WidgetLogCard(
    /** The log screen's entry id: the battle id, or "outside:<id>". */
    val id: String,
    val outside: Boolean,
    val title: String,
    val account: String?,
    val summary: String,
)

/**
 * The newest [limit] items, mixed and ordered exactly like the log screen: open periods first, then newest end
 * time ([mixedCaptureLogs]).
 */
fun widgetLogItems(
    battles: List<io.github.fate_grand_automata.battlelog.BattleLogEntry>,
    outside: List<io.github.fate_grand_automata.capture.OutsideLogEntry>,
    open: List<io.github.fate_grand_automata.capture.OutsideLogEntry>,
    limit: Int = WIDGET_LOG_CARD_MAX,
): List<CaptureLogItem> = mixedCaptureLogs(battles, outside, open).take(limit)

fun widgetLogCard(context: Context, item: CaptureLogItem): WidgetLogCard {
    item.battle?.let {
        return WidgetLogCard(item.id, false, it.configName, it.accountName, battleLogSummary(context, it))
    }
    val outside = item.outside!!
    return WidgetLogCard(
        item.id, true, context.getString(R.string.capture_outside_title), outside.accountName,
        outsideLogSummary(context, outside, item.inProgress),
    )
}

/**
 * Reads the stored logs for the widget: the battle log index (never the detail files), the outside log (from memory
 * when the app already loaded it, otherwise read-only from its file) and the open periods the tracker holds.
 * Any failure gives an empty list, which the widget shows as "로그 없음". It never writes, moves or deletes a file.
 */
suspend fun loadWidgetLogItems(context: Context): List<CaptureLogItem> = withContext(Dispatchers.IO) {
    runCatching {
        val accounts = CaptureTracker.accounts.value
        val battles = runCatching { FileBattleLogStorage(context.filesDir).readIndex() }
            .onFailure { Timber.w(it, "Widget: battle log index unreadable") }
            .getOrNull().orEmpty()
            .map { it.copy(accountName = it.captureAccountName(accounts)) }
        val outside = runCatching { OutsideLogRepository.loadedEntries() ?: OutsideLogRepository.readStored(context.filesDir) }
            .onFailure { Timber.w(it, "Widget: outside log unreadable") }
            .getOrDefault(emptyList())
        val open = runCatching { CaptureTracker.openOutsideEntriesIfLoaded() }.getOrDefault(emptyList())
        widgetLogItems(battles, outside, open)
    }.onFailure { Timber.w(it, "Widget: log load failed") }.getOrDefault(emptyList())
}

/** Fills the log row of the top-level [views]: as many cards as fit [widthDp], or "로그 없음". */
fun addLogsRow(
    context: Context,
    views: RemoteViews,
    items: List<CaptureLogItem>,
    palette: WidgetPalette,
    widthDp: Int,
) {
    fun color(res: Int) = ContextCompat.getColor(context, res)
    val count = widgetLogCardCount(widthDp)
    val shown = items.take(count)
    // The cards share the row evenly, so with fewer items each one is wider.
    val showAccount = widgetLogCardWidthDp(widthDp, shown.size) >= WIDGET_LOG_CARD_ACCOUNT_MIN_WIDTH_DP
    views.removeAllViews(R.id.ap_logs_cards)
    views.setViewVisibility(R.id.ap_logs_cards, if (shown.isEmpty()) View.GONE else View.VISIBLE)
    views.setViewVisibility(R.id.ap_logs_empty, if (shown.isEmpty()) View.VISIBLE else View.GONE)
    views.setTextColor(R.id.ap_logs_empty, color(palette.secondary))
    // A tap anywhere in the row that is not a card opens the log screen.
    views.setOnClickPendingIntent(R.id.ap_logs, activityPendingIntent(context, WidgetIntents.openLogs(context)))
    shown.forEach { views.addView(R.id.ap_logs_cards, buildLogCardViews(context, widgetLogCard(context, it), palette, showAccount)) }
}

internal fun buildLogCardViews(context: Context, card: WidgetLogCard, palette: WidgetPalette, showAccount: Boolean = true): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_ap_log_card)
    fun color(res: Int) = ContextCompat.getColor(context, res)
    views.setInt(
        R.id.ap_log_card, "setBackgroundResource",
        if (palette.light) R.drawable.widget_log_card_background_light else R.drawable.widget_log_card_background,
    )
    views.setImageViewResource(R.id.ap_log_card_icon, if (card.outside) R.drawable.ic_widget_log_outside else R.drawable.ic_swords)
    views.setInt(R.id.ap_log_card_icon, "setColorFilter", color(palette.onSurface))
    views.setTextViewText(R.id.ap_log_card_title, card.title)
    views.setTextColor(R.id.ap_log_card_title, color(palette.onSurface))
    views.setTextViewText(R.id.ap_log_card_summary, card.summary)
    views.setTextColor(R.id.ap_log_card_summary, color(palette.secondary))
    val account = card.account.takeIf { showAccount && !it.isNullOrEmpty() }
    views.setViewVisibility(R.id.ap_log_card_account, if (account == null) View.GONE else View.VISIBLE)
    if (account != null) {
        views.setTextViewText(R.id.ap_log_card_account, account)
        views.setTextColor(R.id.ap_log_card_account, color(palette.secondary))
    }
    views.setOnClickPendingIntent(R.id.ap_log_card, activityPendingIntent(context, WidgetIntents.openLogEntry(context, card.id)))
    return views
}
