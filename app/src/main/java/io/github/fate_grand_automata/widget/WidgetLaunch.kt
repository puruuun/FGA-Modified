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
    const val EXTRA_WIDGET_CAPTURE_START = "io.github.fate_grand_automata.extra.WIDGET_CAPTURE_START"
    const val EXTRA_OPEN_ACCOUNT = "io.github.fate_grand_automata.extra.OPEN_ACCOUNT"
    const val EXTRA_OPEN_LOGS = "io.github.fate_grand_automata.extra.OPEN_LOGS"
    const val EXTRA_OPEN_LOG_ENTRY = "io.github.fate_grand_automata.extra.OPEN_LOG_ENTRY"
    private const val ACTION_WIDGET_START = "io.github.fate_grand_automata.action.WIDGET_START"
    private const val ACTION_OPEN_ACCOUNT = "io.github.fate_grand_automata.action.OPEN_ACCOUNT"
    private const val ACTION_OPEN_LOGS = "io.github.fate_grand_automata.action.OPEN_LOGS"
    private const val ACTION_OPEN_LOG_ENTRY = "io.github.fate_grand_automata.action.OPEN_LOG_ENTRY"
    private const val ACTION_OPEN_ACCOUNT_ITEM = "io.github.fate_grand_automata.action.OPEN_ACCOUNT_ITEM"

    /** Distinct actions and data keep the PendingIntents of the widgets apart; extras do not count for intent equality. */
    fun startService(context: Context) = Intent(context, MainActivity::class.java)
        .setAction(ACTION_WIDGET_START)
        .putExtra(EXTRA_WIDGET_START, true)

    fun startCapture(context: Context) = Intent(context, MainActivity::class.java)
        .setAction("io.github.fate_grand_automata.action.WIDGET_CAPTURE_START")
        .putExtra(EXTRA_WIDGET_CAPTURE_START, true)

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

    /** Log screen, no entry selected. Its own action and data keep it apart from the entry intents. */
    fun openLogs(context: Context) = Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN_LOGS)
        .setData(Uri.Builder().scheme("fga").authority("logs").build())
        .putExtra(EXTRA_OPEN_LOGS, true)

    /** Log screen with entry [entryId] selected; the id is in the data URI so each card has its own PendingIntent. */
    fun openLogEntry(context: Context, entryId: String) = Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN_LOG_ENTRY)
        .setData(Uri.Builder().scheme("fga").authority("log").appendPath(entryId).build())
        .putExtra(EXTRA_OPEN_LOG_ENTRY, entryId)

    fun openApp(context: Context) = Intent(context, MainActivity::class.java)
}

/**
 * Requests carried by widget taps into the main activity. Held by the view model so rotation or
 * recreation never replays one: each request is handed out once by [takeStart] / [takeAccount].
 */
class WidgetLaunchRequests {
    var captureStartPending by mutableStateOf(false)
        private set
    var startPending by mutableStateOf(false)
        private set
    var logPending by mutableStateOf<LogRequest?>(null)
        private set
    var accountPending by mutableStateOf<String?>(null)
        private set

    fun requestStart() { startPending = true }
    fun requestCaptureStart() { captureStartPending = true }
    fun requestAccount(id: String) { accountPending = id }
    fun requestLog(entryId: String?) { logPending = LogRequest(entryId) }

    fun takeStart(): Boolean = startPending.also { startPending = false }
    fun takeCaptureStart(): Boolean = captureStartPending.also { captureStartPending = false }
    fun takeAccount(): String? = accountPending.also { accountPending = null }
    fun takeLog(): LogRequest? = logPending.also { logPending = null }

    /**
     * Reads the widget extras of a freshly delivered [intent] and removes them, so the same intent
     * is never read twice. A [restored] activity (recreated from saved state) keeps the original
     * intent, which already ran once, so it requests nothing.
     */
    fun consume(intent: Intent?, restored: Boolean = false) {
        intent ?: return
        val start = intent.getBooleanExtra(WidgetIntents.EXTRA_WIDGET_START, false)
        val captureStart = intent.getBooleanExtra(WidgetIntents.EXTRA_WIDGET_CAPTURE_START, false)
        val account = intent.getStringExtra(WidgetIntents.EXTRA_OPEN_ACCOUNT)
        val logs = intent.getBooleanExtra(WidgetIntents.EXTRA_OPEN_LOGS, false)
        val logEntry = intent.getStringExtra(WidgetIntents.EXTRA_OPEN_LOG_ENTRY)
        intent.removeExtra(WidgetIntents.EXTRA_WIDGET_START)
        intent.removeExtra(WidgetIntents.EXTRA_WIDGET_CAPTURE_START)
        intent.removeExtra(WidgetIntents.EXTRA_OPEN_ACCOUNT)
        intent.removeExtra(WidgetIntents.EXTRA_OPEN_LOGS)
        intent.removeExtra(WidgetIntents.EXTRA_OPEN_LOG_ENTRY)
        if (restored) return
        if (start) requestStart()
        if (captureStart) requestCaptureStart()
        if (account != null) requestAccount(account)
        if (logEntry != null) requestLog(logEntry) else if (logs) requestLog(null)
    }
}

@Composable
fun WidgetCaptureNavigationEffect(requests: WidgetLaunchRequests, onboardingRequired: Boolean, showMain: () -> Unit) {
    val pending = requests.captureStartPending
    LaunchedEffect(pending) {
        if (pending) {
            if (onboardingRequired) requests.takeCaptureStart() else showMain()
        }
    }
}

@Composable
fun WidgetCaptureStartEffect(requests: WidgetLaunchRequests, running: Boolean, onStart: () -> Unit) {
    val pending = requests.captureStartPending
    LaunchedEffect(pending) {
        if (pending && requests.takeCaptureStart() && !running) onStart()
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

/** A widget tap on the log row: [entryId] is the card to select, or null for the log screen alone. */
data class LogRequest(val entryId: String?)
