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
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Refresh every 15 minutes while an AP widget exists, and after any account summary is saved. */
object ApWidgetRefresh {
    private const val WORK_NAME = "ap_widget_refresh"
    /** Production always runs on Dispatchers.IO; tests swap in an inline scope. */
    internal var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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