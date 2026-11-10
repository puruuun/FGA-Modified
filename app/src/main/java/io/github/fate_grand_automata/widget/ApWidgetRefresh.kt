package io.github.fate_grand_automata.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Refresh every 15 minutes while an AP widget exists, and after any account summary is saved. */
object ApWidgetRefresh {
    private const val WORK_NAME = "ap_widget_refresh"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Replaced in tests, which have no widget host. */
    internal var launcher: (Context) -> Unit = { context ->
        scope.launch { runCatching { updateAll(context) } }
    }

    fun requestUpdate(context: Context) = launcher(context.applicationContext)

    /** Redraws every placed AP widget from the stored summaries. */
    internal suspend fun updateAll(context: Context) {
        ApWidgetRenderer.updateAll(context)
    }

    /** The existing save hook covers every account now. The check runs outside the capture lock. */
    @Suppress("UNUSED_PARAMETER")
    fun onAccountSaved(context: Context, accountId: String?) {
        val app = context.applicationContext
        scope.launch { if (WidgetRefresh.hasApWidget(app)) requestUpdate(app) }
    }

    /** Several log changes in a burst (a period updates on every response) become one redraw. */
    private const val LOGS_DEBOUNCE_MS = 1_500L
    private val logsPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private var openWatch: Job? = null
    private var watched: Flow<*>? = null

    /** Replaced in tests: redraws the account info + recent logs widgets only. */
    internal var logsUpdater: suspend (Context) -> Unit = { ApWidgetRenderer.updateLogsWidgets(it) }
    internal var logsDebounceMs = LOGS_DEBOUNCE_MS

    /**
     * A battle log or an outside log entry was added, deleted or trimmed, or an open period changed. Only the
     * AP + logs widgets are redrawn, and only when one is placed; it never throws into the caller.
     */
    fun onLogsChanged(context: Context) {
        val app = context.applicationContext
        if (!logsPending.compareAndSet(false, true)) return
        scope.launch {
            try {
                delay(logsDebounceMs)
            } finally {
                logsPending.set(false)
            }
            runCatching { if (WidgetRefresh.hasLogsWidget(app)) logsUpdater(app) }
        }
    }

    /** Redraws when the open outside periods change. StateFlow already drops equal values. Started once. */
    @Synchronized
    fun watchOpenPeriods(context: Context, flow: Flow<List<*>>) {
        if (openWatch?.isActive == true && watched === flow) return
        openWatch?.cancel()
        watched = flow
        val app = context.applicationContext
        openWatch = scope.launch { flow.drop(1).collect { onLogsChanged(app) } }
    }

    fun schedule(context: Context) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ApWidgetWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
    }

    fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
    }
}

class ApWidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!WidgetRefresh.hasApWidget(applicationContext)) {
            ApWidgetRefresh.cancel(applicationContext)
            return Result.success()
        }
        ApWidgetRefresh.updateAll(applicationContext)
        return Result.success()
    }
}