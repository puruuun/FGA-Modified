package io.github.fate_grand_automata.ui.more

import android.content.Context
import android.os.Build
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
    private val job = IconPrefetchJob(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        extract = {
            try { SupportImageExtractor(context, storageProvider).extract()
            } catch (e: Exception) {
                Timber.e(e, "Support image extraction failed")
                throw e
            }
        },
        lists = { GameNames(File(context.filesDir, "capture/names")).dashboard() },
        resolve = { LogIconLoader.files(context, it.directory).resolve(it.id, it.address) != null },
    )
    internal val state = job.state
    fun start() { job.start(Build.VERSION.SDK_INT) }
}

internal fun supportExtractionSummary(context: Context, state: SupportExtractionState): String = buildList {
    when (state.extraction) {
        ExtractionResult.Idle -> Unit
        ExtractionResult.Extracting -> add(context.getString(R.string.support_imgs_extracting))
        ExtractionResult.Success -> add(context.getString(R.string.support_imgs_extracted))
        ExtractionResult.Failure -> add(context.getString(R.string.support_imgs_extract_failed))
    }
    when (val icons = state.icons) {
        null -> Unit
        IconPrefetchProgress.LoadingLists -> add(context.getString(R.string.icon_prefetch_loading_lists))
        IconPrefetchProgress.ListsFailed -> add(context.getString(R.string.icon_prefetch_lists_failed))
        is IconPrefetchProgress.Downloading -> add(context.getString(R.string.icon_prefetch_progress, icons.completed, icons.total))
        is IconPrefetchProgress.Ready -> add(if (icons.failed == 0) context.getString(R.string.icon_prefetch_ready, icons.ready)
            else context.getString(R.string.icon_prefetch_failed, icons.ready, icons.failed))
    }
    add(context.getString(R.string.icon_prefetch_note))
}.joinToString("\n")
