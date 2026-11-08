package io.github.fate_grand_automata.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.fate_grand_automata.ui.main.MainActivity

/** The intents the home-screen widgets send to [MainActivity]. */
object WidgetIntents {
    const val EXTRA_WIDGET_START = "io.github.fate_grand_automata.extra.WIDGET_START"
    const val EXTRA_OPEN_ACCOUNT = "io.github.fate_grand_automata.extra.OPEN_ACCOUNT"
    private const val ACTION_WIDGET_START = "io.github.fate_grand_automata.action.WIDGET_START"
    private const val ACTION_OPEN_ACCOUNT = "io.github.fate_grand_automata.action.OPEN_ACCOUNT"
    private const val ACTION_OPEN_ACCOUNT_ITEM = "io.github.fate_grand_automata.action.OPEN_ACCOUNT_ITEM"

    /** Distinct actions and data keep the PendingIntents of the widgets apart; extras do not count for intent equality. */
    fun startService(context: Context) = Intent(context, MainActivity::class.java)
        .setAction(ACTION_WIDGET_START)
        .putExtra(EXTRA_WIDGET_START, true)

    fun openAccount(context: Context, accountId: String) = Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN_ACCOUNT)
        .setData(Uri.Builder().scheme("fga").authority("account").appendPath(accountId).build())
        .putExtra(EXTRA_OPEN_ACCOUNT, accountId)

    /**
     * The tap template of the account stack. Each card adds [openAccountFillIn]; extras of the fill-in
     * are merged into this intent when the card is tapped.
     */
    fun openAccountTemplate(context: Context) = Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN_ACCOUNT_ITEM)

    fun openAccountFillIn(accountId: String) = Intent().putExtra(EXTRA_OPEN_ACCOUNT, accountId)

    fun openApp(context: Context) = Intent(context, MainActivity::class.java)
}

/**
 * Requests carried by widget taps into the main activity. Held by the view model so rotation or
 * recreation never replays one: each request is handed out once by [takeStart] / [takeAccount].
 */
class WidgetLaunchRequests {
    var startPending by mutableStateOf(false)
        private set
    var accountPending by mutableStateOf<String?>(null)
        private set

    fun requestStart() { startPending = true }
    fun requestAccount(id: String) { accountPending = id }

    fun takeStart(): Boolean = startPending.also { startPending = false }
    fun takeAccount(): String? = accountPending.also { accountPending = null }

    /**
     * Reads the widget extras of a freshly delivered [intent] and removes them, so the same intent
     * is never read twice. A [restored] activity (recreated from saved state) keeps the original
     * intent, which already ran once, so it requests nothing.
     */
    fun consume(intent: Intent?, restored: Boolean = false) {
        intent ?: return
        val start = intent.getBooleanExtra(WidgetIntents.EXTRA_WIDGET_START, false)
        val account = intent.getStringExtra(WidgetIntents.EXTRA_OPEN_ACCOUNT)
        intent.removeExtra(WidgetIntents.EXTRA_WIDGET_START)
        intent.removeExtra(WidgetIntents.EXTRA_OPEN_ACCOUNT)
        if (restored) return
        if (start) requestStart()
        if (account != null) requestAccount(account)
    }
}

/** Runs [onStart] once for a pending widget start; a service that is already running is left alone. */
@Composable
fun WidgetStartEffect(
    requests: WidgetLaunchRequests,
    serviceStarted: () -> Boolean,
    onStart: () -> Unit,
) {
    val pending = requests.startPending
    LaunchedEffect(pending) {
        if (pending && requests.takeStart() && !serviceStarted()) onStart()
    }
}
