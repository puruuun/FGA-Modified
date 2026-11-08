package io.github.fate_grand_automata.ui.more

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.capture.*
import io.github.fate_grand_automata.ui.log.LogIconLoader
import io.github.fate_grand_automata.util.StorageProvider
import io.github.fate_grand_automata.util.SupportImageExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupportImageExtraction @Inject constructor(
    @ApplicationContext context: Context,
    storageProvider: StorageProvider,
) {
    // This scope belongs to the application singleton, never to a screen or service.
    private val tasks = SupportImageTasks(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        extract = {
            try { SupportImageExtractor(context, storageProvider).extract()
            } catch (e: Exception) {
                Timber.e(e, "Support image extraction failed")
                throw e
            }
        },
        icons = IconPrefetchJob(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            lists = {
                CaptureTracker.init(context)
                GameNames(File(context.filesDir, "capture/names")).dashboard(
                    hasJpAccount = CaptureTracker.accounts.value.any { it.server == "JP" })
            },
            resolve = { LogIconLoader.files(context, it.directory).fetch(it.id, it.address).second },
        ),
    )
    internal val extractionState = tasks.extraction
    internal val iconState = tasks.icons.state
    fun startExtraction() { tasks.startExtraction() }
    fun startIconDownload() { tasks.startDownload() }
}

internal fun extractionSummary(context: Context, state: ExtractionResult): String = when (state) {
    ExtractionResult.Idle -> ""
    ExtractionResult.Extracting -> context.getString(R.string.support_imgs_extracting)
    ExtractionResult.Success -> context.getString(R.string.support_imgs_extracted)
    ExtractionResult.Failure -> context.getString(R.string.support_imgs_extract_failed)
}

/** Empty when idle. */
internal fun iconDownloadSummary(context: Context, progress: IconPrefetchProgress?): String = when (progress) {
    null -> ""
    IconPrefetchProgress.LoadingLists -> context.getString(R.string.icon_prefetch_loading_lists)
    IconPrefetchProgress.ListsFailed -> context.getString(R.string.icon_prefetch_lists_failed)
    is IconPrefetchProgress.Downloading -> context.getString(R.string.icon_prefetch_progress, progress.completed, progress.total)
    is IconPrefetchProgress.Ready -> if (progress.failed == 0) context.getString(R.string.icon_prefetch_ready, progress.ready)
        else context.getString(R.string.icon_prefetch_failed, progress.ready, progress.failed)
}
