package io.github.fate_grand_automata.widget

import android.content.Context
import android.content.SharedPreferences

/** The account each AP widget instance (appWidgetId) is bound to. Nothing here is ever removed automatically except by [remove]. */
class ApWidgetBindings(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    fun bind(appWidgetId: Int, accountId: String) {
        prefs.edit().putString(key(appWidgetId), accountId).apply()
    }

    fun accountFor(appWidgetId: Int): String? = prefs.getString(key(appWidgetId), null)

    fun remove(appWidgetId: Int) {
        prefs.edit().remove(key(appWidgetId)).apply()
    }

    fun boundAccountIds(): Set<String> = prefs.all.filterKeys { it.startsWith(PREFIX) }.values.filterIsInstance<String>().toSet()

    fun hasBindings() = prefs.all.keys.any { it.startsWith(PREFIX) }

    fun isBound(accountId: String) = accountId in boundAccountIds()

    private fun key(appWidgetId: Int) = "$PREFIX$appWidgetId"

    private companion object {
        const val FILE = "fga_widget_bindings"
        const val PREFIX = "ap_"
    }
}
