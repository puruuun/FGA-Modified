package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.ui.log.currencyIds
import io.github.fate_grand_automata.ui.log.iconItemIds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class IconTarget(val directory: String, val id: Long, val address: String?)

/** Public Atlas metadata only; no account state is needed to build this list. */
internal fun iconPrefetchTargets(names: GameNameMap): List<IconTarget> = buildList {
    names.servantData.values.filter { it.type in setOf("normal", "heroine") }.forEach {
        add(IconTarget("servants", it.id, it.icon))
    }
    val itemIds = names.itemData.values.filter { it.type in setOf("skillLvUp", "tdLvUp") }.map { it.id } +
        currencyIds + iconItemIds
    itemIds.distinct().filter { it in names.itemData }.forEach {
        add(IconTarget("", it, names.itemIcons[it] ?: names.itemData[it]?.icon))
    }
    names.mysticData.values.forEach { add(IconTarget("mystics", it.id, it.icon)) }
}

internal enum class ExtractionResult { Idle, Extracting, Success, Failure }
internal sealed interface IconPrefetchProgress {
    data object LoadingLists : IconPrefetchProgress
    data class Downloading(val completed: Int, val total: Int, val failed: Int = 0) : IconPrefetchProgress
    data class Ready(val ready: Int, val failed: Int) : IconPrefetchProgress
    data object ListsFailed : IconPrefetchProgress
}

/** One process-lifetime icon download. The caller observes state without owning or cancelling the work. */
internal class IconPrefetchJob(
    private val scope: CoroutineScope,
    private val lists: suspend () -> GameNameMap,
    private val resolve: suspend (IconTarget) -> IconOutcome,
) {
    private val mutableState = MutableStateFlow<IconPrefetchProgress?>(null)
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    /** A second start while the download runs returns the running job and changes nothing. */
    @Synchronized fun start(): Job {
        job?.takeIf { it.isActive }?.let { return it }
        mutableState.value = IconPrefetchProgress.LoadingLists
        return scope.launch(start = CoroutineStart.LAZY) {
            val targets = try {
                val names = lists()
                check(names.items.isNotEmpty() && names.itemIcons.isNotEmpty() && names.itemData.isNotEmpty() &&
                    names.servantData.isNotEmpty() && names.equipData.isNotEmpty() && names.mysticData.isNotEmpty())
                iconPrefetchTargets(names)
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                mutableState.value = IconPrefetchProgress.ListsFailed
                return@launch
            }
            mutableState.value = IconPrefetchProgress.Downloading(0, targets.size)
            val progressLock = Mutex()
            var completed = 0
            var failed = 0
            var missing = 0
            coroutineScope {
                repeat(minOf(4, targets.size)) { worker ->
                    launch {
                        for (index in worker until targets.size step 4) {
                            ensureActive()
                            val outcome = try { resolve(targets[index])
                            } catch (e: CancellationException) { throw e
                            } catch (_: Exception) { IconOutcome.Failed }
                            progressLock.withLock {
                                if (outcome == IconOutcome.Missing) missing++ else completed++
                                if (outcome == IconOutcome.Failed) failed++
                                mutableState.value = IconPrefetchProgress.Downloading(completed, targets.size - missing, failed)
                            }
                        }
                    }
                }
            }
            mutableState.value = IconPrefetchProgress.Ready(completed - failed, failed)
        }.also { job = it; it.start() }
    }
}

/**
 * The default support image extraction and the icon download are two independent actions:
 * extracting never starts the download, and the download never extracts.
 */
internal class SupportImageTasks(
    private val scope: CoroutineScope,
    private val extract: suspend () -> Unit,
    val icons: IconPrefetchJob,
) {
    private val mutableExtraction = MutableStateFlow(ExtractionResult.Idle)
    val extraction = mutableExtraction.asStateFlow()
    private var extractionJob: Job? = null

    @Synchronized fun startExtraction(): Job {
        extractionJob?.takeIf { it.isActive }?.let { return it }
        mutableExtraction.value = ExtractionResult.Extracting
        return scope.launch(start = CoroutineStart.LAZY) {
            mutableExtraction.value = try {
                extract()
                ExtractionResult.Success
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { ExtractionResult.Failure }
        }.also { extractionJob = it; it.start() }
    }

    fun startDownload(): Job = icons.start()
}
