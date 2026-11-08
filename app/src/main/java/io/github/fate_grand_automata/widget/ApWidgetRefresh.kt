package io.github.fate_grand_automata.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.fate_grand_automata.capture.CaptureTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Refresh of the account AP widgets: every 15 minutes while one exists, and after the tracker saves bound data. */
object ApWidgetRefresh {
    private const val WORK_NAME = "ap_widget_refresh"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Replaced in tests, which have no widget host. */
    internal var launcher: (Context) -> Unit = { context ->
        scope.launch { runCatching { ApWidget().updateAll(context) } }
    }

    fun requestUpdate(context: Context) = launcher(context.applicationContext)

    /**
     * The one hook after the tracker saved account data. It only reads the small bindings file, so
     * it is cheap when no AP widget is placed. A null [accountId] means "everything was flushed".
     */
    fun onAccountSaved(context: Context, accountId: String?) {
        val bindings = ApWidgetBindings(context)
        val relevant = if (accountId == null) bindings.hasBindings() else bindings.isBound(accountId)
        if (relevant) requestUpdate(context)
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
        val manager = GlanceAppWidgetManager(applicationContext)
        if (manager.getGlanceIds(ApWidget::class.java).isEmpty()) {
            ApWidgetRefresh.cancel(applicationContext)
            return Result.success()
        }
        withContext(Dispatchers.IO) { CaptureTracker.init(applicationContext) }
        ApWidget().updateAll(applicationContext)
        return Result.success()
    }
}
