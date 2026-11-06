package io.github.fate_grand_automata.capture

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class IconPrefetchTest {
    @TempDir lateinit var directory: File
    private fun row(id: Long, type: String = "normal") = AtlasBasic(id, "Name $id", type,
        icon = "https://static.atlasacademy.io/KR/Items/$id.png")
    private fun names() = GameNameMap(
        items = mapOf(1L to "QP"), itemIcons = mapOf(1L to row(1).icon!!),
        servantData = listOf(row(10), row(11, "heroine"), row(12, "enemy"), row(13, "servantEquip")).associateBy { it.id },
        itemData = listOf(row(6510, "skillLvUp"), row(6520, "tdLvUp"), row(999, "eventItem"),
            row(42, "other"), row(1, "qp"), row(100, "apRecover")).associateBy { it.id },
        equipData = mapOf(9L to row(9, "servantEquip")), mysticData = mapOf(10L to row(10)),
    )

    @Test fun targetsIncludeOnlyServantsMaterialsCurrenciesAndMystics() {
        val targets = iconPrefetchTargets(names())
        assertEquals(setOf(10L, 11L), targets.filter { it.directory == "servants" }.map { it.id }.toSet())
        assertEquals(setOf(1L, 100L, 6510L, 6520L), targets.filter { it.directory.isEmpty() }.map { it.id }.toSet())
        assertEquals(listOf(10L), targets.filter { it.directory == "mystics" }.map { it.id })
        assertEquals(7, targets.size)
        assertFalse(targets.any { it.id in setOf(999L, 42L, 9L, 12L, 13L) })
    }

    @Test fun allResourceTileCurrencyIdsAreIncludedOnce() {
        val ids = io.github.fate_grand_automata.ui.log.currencyIds + io.github.fate_grand_automata.ui.log.iconItemIds
        val targets = iconPrefetchTargets(names().copy(itemData = ids.associateWith { row(it, "currency") }))
        assertEquals(ids, targets.filter { it.directory.isEmpty() }.map { it.id }.toSet())
        assertEquals(ids.size, targets.count { it.directory.isEmpty() })
    }

    @Test fun existingFilesAreReusedAndRestartResumesOnlyMissingFiles() = runBlocking {
        val targets = iconPrefetchTargets(names())
        val existing = targets.first()
        File(directory, "${existing.directory}/${existing.id}.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(7)) }
        val calls = AtomicInteger()
        suspend fun resolve(target: IconTarget): Boolean = IconFileStore(File(directory, target.directory),
            { calls.incrementAndGet(); byteArrayOf(1) }).resolve(target.id, target.address) != null
        val job = IconPrefetchJob(this, {}, { names() }, ::resolve)
        job.start(29)!!.join()
        assertEquals(6, calls.get())
        assertEquals(IconPrefetchProgress.Ready(7, 0), job.state.value.icons)
        IconPrefetchJob(this, {}, { names() }, ::resolve).start(29)!!.join()
        assertEquals(6, calls.get())
        assertContentEquals(byteArrayOf(7), File(directory, "servants/10.png").readBytes())
    }

    @Test fun failedIconIsCountedAndAllOthersContinue() = runBlocking {
        val attempted = java.util.concurrent.ConcurrentHashMap.newKeySet<IconTarget>()
        val job = IconPrefetchJob(this, {}, { names() }, {
            attempted += it
            if (it.id == 6510L) error("offline")
            it.id != 6520L
        })
        job.start(29)!!.join()
        assertEquals(iconPrefetchTargets(names()).toSet(), attempted)
        assertEquals(ExtractionResult.Success, job.state.value.extraction)
        assertEquals(IconPrefetchProgress.Ready(5, 2), job.state.value.icons)
    }

    @Test fun nonAtlasAddressesAreRejectedByTheStoreDuringPrefetch() = runBlocking {
        val data = names().copy(mysticData = mapOf(10L to row(10).copy(icon = "https://evil.test/icon.png")))
        val job = IconPrefetchJob(this, {}, { data }, { target ->
            IconFileStore(File(directory, target.directory), { assertTrue(IconFileStore.isPublicIcon(it)); byteArrayOf(1) })
                .resolve(target.id, target.address) != null
        })
        job.start(29)!!.join()
        assertEquals(IconPrefetchProgress.Ready(6, 1), job.state.value.icons)
        assertFalse(File(directory, "mystics/10.png").exists())
    }

    @Test fun secondPressDuringExtractionOrPrefetchReturnsTheSameJobAndProgress() = runBlocking {
        val extractionGate = CompletableDeferred<Unit>()
        val downloadGate = CompletableDeferred<Unit>()
        val enteredDownload = CompletableDeferred<Unit>()
        var extractions = 0
        var listLoads = 0
        val job = IconPrefetchJob(this, { extractions++; extractionGate.await() }, { listLoads++; names() }, {
            enteredDownload.complete(Unit); downloadGate.await(); true
        })
        val first = job.start(29)!!
        assertSame(first, job.start(29))
        assertEquals(ExtractionResult.Extracting, job.state.value.extraction)
        extractionGate.complete(Unit)
        enteredDownload.await()
        val progress = job.state.value
        assertSame(first, job.start(29))
        assertEquals(progress, job.state.value)
        downloadGate.complete(Unit)
        first.join()
        assertEquals(1, extractions)
        assertEquals(1, listLoads)
    }

    @Test fun leavingTheScreenDoesNotCancelTheApplicationJob() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gate = CompletableDeferred<Unit>()
            val job = IconPrefetchJob(appScope, {}, { gate.await(); names() }, { true })
            val observer = launch { job.state.collect() }
            val running = job.start(29)!!
            observer.cancelAndJoin()
            assertTrue(running.isActive)
            gate.complete(Unit)
            running.join()
            assertEquals(IconPrefetchProgress.Ready(7, 0), job.state.value.icons)
            // A new screen can read the last result immediately.
            assertEquals(ExtractionResult.Success, job.state.value.extraction)
        } finally { appScope.cancel() }
    }

    @Test fun atMostFourIconsAreResolvedAtOnce() = runBlocking {
        val entered = AtomicInteger()
        val fourEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = IconPrefetchJob(this, {}, { names() }, {
            if (entered.incrementAndGet() == 4) fourEntered.complete(Unit)
            release.await()
            true
        })
        val running = job.start(29)!!
        withTimeout(5_000) { fourEntered.await() }
        assertEquals(4, entered.get())
        release.complete(Unit)
        running.join()
        assertEquals(7, entered.get())
    }

    @Test fun loadingProgressFollowsExtractionAndCountsCompletedFailures() = runBlocking {
        val listGate = CompletableDeferred<Unit>()
        val iconGate = CompletableDeferred<Unit>()
        val firstIcon = CompletableDeferred<Unit>()
        val job = IconPrefetchJob(this, {}, { listGate.await(); names() }, {
            firstIcon.complete(Unit); iconGate.await(); true
        })
        val running = job.start(29)!!
        yield()
        assertEquals(SupportExtractionState(ExtractionResult.Success, IconPrefetchProgress.LoadingLists), job.state.value)
        listGate.complete(Unit)
        firstIcon.await()
        assertEquals(IconPrefetchProgress.Downloading(0, 7), job.state.value.icons)
        iconGate.complete(Unit)
        running.join()
        assertEquals(IconPrefetchProgress.Ready(7, 0), job.state.value.icons)
    }

    @Test fun unavailableListsPreserveExtractionResultAndDoNotDownloadIcons() = runBlocking {
        for (load in listOf<suspend () -> GameNameMap>({ error("offline") }, { names().copy(equipData = emptyMap()) })) {
            val job = IconPrefetchJob(this, {}, load, { error("must not resolve") })
            job.start(29)!!.join()
            assertEquals(SupportExtractionState(ExtractionResult.Success, IconPrefetchProgress.ListsFailed), job.state.value)
        }
    }

    @Test fun extractionFailureDoesNotStopIconPreparationAndIconFailureDoesNotChangeExtractionResult() = runBlocking {
        for (iconsSucceed in listOf(true, false)) {
            val job = IconPrefetchJob(this, { error("no support folder") }, { names() }, { iconsSucceed })
            job.start(29)!!.join()
            assertEquals(ExtractionResult.Failure, job.state.value.extraction)
            assertEquals(if (iconsSucceed) IconPrefetchProgress.Ready(7, 0) else IconPrefetchProgress.Ready(0, 7), job.state.value.icons)
        }
    }

    @Test fun belowApi29NeverStartsTheNewJob() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val job = IconPrefetchJob(scope, { error("legacy extraction stays with the caller") },
                { error("must not fetch") }, { error("must not download") })
            for (api in 24..28) assertNull(job.start(api))
            assertEquals(SupportExtractionState(), job.state.value)
        } finally { scope.cancel() }
    }

    @Test fun unavailableListsKeepExistingDailyAttemptBudgets() = runBlocking {
        val calls = AtomicInteger()
        suspend fun lists() = GameNames(directory, { calls.incrementAndGet(); error("offline") }) { 1000 }.dashboard()
        val job = IconPrefetchJob(this, {}, ::lists, { error("must not download") })
        job.start(29)!!.join()
        val afterFirst = calls.get()
        assertTrue(afterFirst > 0)
        job.start(29)!!.join()
        assertEquals(afterFirst, calls.get())
        assertEquals(IconPrefetchProgress.ListsFailed, job.state.value.icons)
    }

    @Test fun cancellingTheApplicationScopeStopsPrefetch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val job = IconPrefetchJob(scope, {}, { names() }, { entered.complete(Unit); awaitCancellation() })
        val running = job.start(29)!!
        entered.await()
        scope.cancel()
        running.join()
        assertTrue(running.isCancelled)
        assertFalse(job.state.value.icons is IconPrefetchProgress.Ready)
    }
}
