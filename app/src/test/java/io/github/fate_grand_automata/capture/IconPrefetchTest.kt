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
        suspend fun resolve(target: IconTarget): IconOutcome = IconFileStore(File(directory, target.directory),
            { calls.incrementAndGet(); byteArrayOf(1) }).fetch(target.id, target.address).second
        val job = IconPrefetchJob(this, { names() }, ::resolve)
        job.start().join()
        assertEquals(6, calls.get())
        assertEquals(IconPrefetchProgress.Ready(7, 0), job.state.value)
        IconPrefetchJob(this, { names() }, ::resolve).start().join()
        assertEquals(6, calls.get())
        assertContentEquals(byteArrayOf(7), File(directory, "servants/10.png").readBytes())
    }

    @Test fun failedIconIsCountedAndAllOthersContinue() = runBlocking {
        val attempted = java.util.concurrent.ConcurrentHashMap.newKeySet<IconTarget>()
        val job = IconPrefetchJob(this, { names() }, {
            attempted += it
            if (it.id == 6510L) error("offline")
            if (it.id == 6520L) IconOutcome.Failed else IconOutcome.Ready
        })
        job.start().join()
        assertEquals(iconPrefetchTargets(names()).toSet(), attempted)
        assertEquals(IconPrefetchProgress.Ready(5, 2), job.state.value)
    }

    @Test fun nonAtlasAddressesAreRejectedByTheStoreDuringPrefetch() = runBlocking {
        val data = names().copy(mysticData = mapOf(10L to row(10).copy(icon = "https://evil.test/icon.png")))
        val job = IconPrefetchJob(this, { data }, { target ->
            IconFileStore(File(directory, target.directory), { assertTrue(IconFileStore.isPublicIcon(it)); byteArrayOf(1) })
                .fetch(target.id, target.address).second
        })
        job.start().join()
        assertEquals(IconPrefetchProgress.Ready(6, 1), job.state.value)
        assertFalse(File(directory, "mystics/10.png").exists())
    }

    @Test fun secondPressWhileRunningReturnsTheSameJobAndProgress() = runBlocking {
        val downloadGate = CompletableDeferred<Unit>()
        val enteredDownload = CompletableDeferred<Unit>()
        var listLoads = 0
        val job = IconPrefetchJob(this, { listLoads++; names() }, {
            enteredDownload.complete(Unit); downloadGate.await(); IconOutcome.Ready
        })
        assertNull(job.state.value)
        val first = job.start()
        enteredDownload.await()
        val progress = job.state.value
        assertSame(first, job.start())
        assertEquals(progress, job.state.value)
        downloadGate.complete(Unit)
        first.join()
        assertEquals(1, listLoads)
        assertEquals(IconPrefetchProgress.Ready(7, 0), job.state.value)
    }

    @Test fun leavingTheScreenDoesNotCancelTheApplicationJob() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gate = CompletableDeferred<Unit>()
            val job = IconPrefetchJob(appScope, { gate.await(); names() }, { IconOutcome.Ready })
            val observer = launch { job.state.collect() }
            val running = job.start()
            observer.cancelAndJoin()
            assertTrue(running.isActive)
            gate.complete(Unit)
            running.join()
            assertEquals(IconPrefetchProgress.Ready(7, 0), job.state.value)
            } finally { appScope.cancel() }
    }

    @Test fun atMostFourIconsAreResolvedAtOnce() = runBlocking {
        val entered = AtomicInteger()
        val fourEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = IconPrefetchJob(this, { names() }, {
            if (entered.incrementAndGet() == 4) fourEntered.complete(Unit)
            release.await()
            IconOutcome.Ready
        })
        val running = job.start()
        withTimeout(5_000) { fourEntered.await() }
        assertEquals(4, entered.get())
        release.complete(Unit)
        running.join()
        assertEquals(7, entered.get())
    }

    @Test fun loadingProgressFollowsTheListsAndCountsCompletedFailures() = runBlocking {
        val listGate = CompletableDeferred<Unit>()
        val iconGate = CompletableDeferred<Unit>()
        val firstIcon = CompletableDeferred<Unit>()
        val job = IconPrefetchJob(this, { listGate.await(); names() }, {
            firstIcon.complete(Unit); iconGate.await(); IconOutcome.Ready
        })
        val running = job.start()
        yield()
        assertEquals(IconPrefetchProgress.LoadingLists, job.state.value)
        listGate.complete(Unit)
        firstIcon.await()
        assertEquals(IconPrefetchProgress.Downloading(0, 7), job.state.value)
        iconGate.complete(Unit)
        running.join()
        assertEquals(IconPrefetchProgress.Ready(7, 0), job.state.value)
    }

    @Test fun unavailableListsDoNotDownloadIcons() = runBlocking {
        for (load in listOf<suspend () -> GameNameMap>({ error("offline") }, { names().copy(equipData = emptyMap()) })) {
            val job = IconPrefetchJob(this, load, { error("must not resolve") })
            job.start().join()
            assertEquals(IconPrefetchProgress.ListsFailed, job.state.value)
        }
    }

    @Test fun unavailableListsKeepExistingDailyAttemptBudgets() = runBlocking {
        val calls = AtomicInteger()
        suspend fun lists() = GameNames(directory, { calls.incrementAndGet(); error("offline") }) { 1000 }.dashboard()
        val job = IconPrefetchJob(this, ::lists, { error("must not download") })
        job.start().join()
        val afterFirst = calls.get()
        assertTrue(afterFirst > 0)
        job.start().join()
        assertEquals(afterFirst, calls.get())
        assertEquals(IconPrefetchProgress.ListsFailed, job.state.value)
    }

    @Test fun cancellingTheApplicationScopeStopsPrefetch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val job = IconPrefetchJob(scope, { names() }, { entered.complete(Unit); awaitCancellation() })
        val running = job.start()
        entered.await()
        scope.cancel()
        running.join()
        assertTrue(running.isCancelled)
        assertFalse(job.state.value is IconPrefetchProgress.Ready)
    }

    @Test fun extractionAloneNeverStartsTheIconDownload() = runBlocking {
        var extractions = 0
        var listLoads = 0
        val tasks = SupportImageTasks(this, { extractions++ }, IconPrefetchJob(this, { listLoads++; names() }, { IconOutcome.Ready }))
        assertEquals(ExtractionResult.Idle, tasks.extraction.value)
        tasks.startExtraction().join()
        assertEquals(1, extractions)
        assertEquals(ExtractionResult.Success, tasks.extraction.value)
        assertEquals(0, listLoads)
        assertNull(tasks.icons.state.value)
    }

    @Test fun failedExtractionIsReportedAndStillDoesNotDownload() = runBlocking {
        var listLoads = 0
        val tasks = SupportImageTasks(this, { error("no support folder") }, IconPrefetchJob(this, { listLoads++; names() }, { IconOutcome.Ready }))
        tasks.startExtraction().join()
        assertEquals(ExtractionResult.Failure, tasks.extraction.value)
        assertEquals(0, listLoads)
        assertNull(tasks.icons.state.value)
    }

    @Test fun downloadItemStartsTheDownloadOnceAndNeverExtracts() = runBlocking {
        var extractions = 0
        var listLoads = 0
        val gate = CompletableDeferred<Unit>()
        val tasks = SupportImageTasks(this, { extractions++ }, IconPrefetchJob(this, { listLoads++; gate.await(); names() }, { IconOutcome.Ready }))
        val first = tasks.startDownload()
        assertSame(first, tasks.startDownload())
        gate.complete(Unit)
        first.join()
        assertEquals(1, listLoads)
        assertEquals(0, extractions)
        assertEquals(ExtractionResult.Idle, tasks.extraction.value)
        assertEquals(IconPrefetchProgress.Ready(7, 0), tasks.icons.state.value)
    }

    @Test fun secondExtractionWhileRunningIsIgnored() = runBlocking {
        var extractions = 0
        val gate = CompletableDeferred<Unit>()
        val tasks = SupportImageTasks(this, { extractions++; gate.await() }, IconPrefetchJob(this, { names() }, { IconOutcome.Ready }))
        val first = tasks.startExtraction()
        assertEquals(ExtractionResult.Extracting, tasks.extraction.value)
        assertSame(first, tasks.startExtraction())
        gate.complete(Unit)
        first.join()
        assertEquals(1, extractions)
    }

    @Test fun missingImageIsCountedAsNeitherReadyNorFailed() = runBlocking {
        val job = IconPrefetchJob(this, { names() }, {
            when (it.id) { 6510L -> IconOutcome.Missing; 6520L -> IconOutcome.Failed; else -> IconOutcome.Ready }
        })
        job.start().join()
        assertEquals(IconPrefetchProgress.Ready(5, 1), job.state.value)
    }

    @Test fun networkFailureCountsAsFailedNotMissing() = runBlocking {
        val job = IconPrefetchJob(this, { names() }, { if (it.id == 6510L) error("offline") else IconOutcome.Ready })
        job.start().join()
        assertEquals(IconPrefetchProgress.Ready(6, 1), job.state.value)
    }

    @Test fun fiveHundredSeventySixTargetsWithSix404sEndReadyWithNoFailures() = runBlocking {
        val data = names().copy(servantData = (1L..570L).associateWith { row(it) }, itemData = mapOf(42L to row(42, "other")),
            mysticData = (1L..6L).associateWith { row(it) })
        val job = IconPrefetchJob(this, { data }, {
            if (it.directory == "mystics") IconOutcome.Missing else IconOutcome.Ready
        })
        job.start().join()
        assertEquals(576, iconPrefetchTargets(data).size)
        assertEquals(IconPrefetchProgress.Ready(570, 0), job.state.value)
    }

    @Test fun http404StoreOutcomeIsMissingAndRemembered() = runBlocking {
        var calls = 0
        var time = 1000L
        val notFound: (String) -> ByteArray = { calls++; throw IconNotFoundException() }
        val url = "https://static.atlasacademy.io/KR/Items/6008.png"
        assertEquals(IconOutcome.Missing, IconFileStore(directory, notFound) { time }.fetch(6008, url).second)
        assertEquals(IconOutcome.Missing, IconFileStore(directory, notFound) { time + 1 }.fetch(6008, url).second)
        assertEquals(1, calls)
        time += IconFileStore.RETRY_MS
        IconFileStore(directory, notFound) { time }.fetch(6008, url)
        assertEquals(2, calls)
        assertEquals(IconOutcome.Failed, IconFileStore(directory, { error("offline") }) { 0 }.fetch(1, url).second)
    }
}
