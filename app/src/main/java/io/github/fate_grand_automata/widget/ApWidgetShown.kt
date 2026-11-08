package io.github.fate_grand_automata.widget

import android.content.Context

/**
 * Which account each placed AP widget shows. The account id is stored, not the position, so a reordered list
 * (the tracker sorts by latest update) keeps showing the same account. Plain SharedPreferences: the widget code
 * runs in receivers and must not need Hilt.
 */
object ApWidgetShown {
    private const val FILE = "ap_widget_shown"
    private fun key(appWidgetId: Int) = "shown_$appWidgetId"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(context: Context, appWidgetId: Int): String? = prefs(context).getString(key(appWidgetId), null)

    fun set(context: Context, appWidgetId: Int, accountId: String) {
        prefs(context).edit().putString(key(appWidgetId), accountId).apply()
    }

    fun remove(context: Context, appWidgetIds: IntArray) {
        val edit = prefs(context).edit()
        appWidgetIds.forEach { edit.remove(key(it)) }
        edit.apply()
    }

    /** Position of the shown account in [models], or 0 when it is not set or the account is gone. */
    fun indexOf(models: List<ApWidgetModel>, shownId: String?): Int =
        models.indexOfFirst { it.accountId == shownId }.coerceAtLeast(0)

    /** The account to show after a tap: +1 is the next one, -1 the previous one, wrapping around both ends. */
    fun step(models: List<ApWidgetModel>, shownId: String?, direction: Int): Int {
        if (models.isEmpty()) return 0
        val size = models.size
        return ((indexOf(models, shownId) + direction) % size + size) % size
    }
}
