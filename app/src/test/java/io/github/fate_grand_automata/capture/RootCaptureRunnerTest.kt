package io.github.fate_grand_automata.capture

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RootCaptureRunnerTest {
    private lateinit var tempDir: File
    private lateinit var flag: RootRulesActiveFlag
    private lateinit var executor: ScheduledExecutorService

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("fga-root-runner-test").toFile()
        flag = RootRulesActiveFlag(tempDir)
        executor = Executors.newSingleThreadScheduledExecutor()
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
        tempDir.deleteRecursively()
    }

    private fun drainExecutor() {
        val latch = CountDownLatch(1)
        executor.execute { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    private class FakeProxyController(
        var listenResult: RootCaptureRunner.ListenerInfo? = RootCaptureRunner.ListenerInfo(8899, ipv6Listening = true),
        var portConflict: Boolean = true,
        var onStartListeningHook: (() -> Unit)? = null,
        val events: MutableList<String> = CopyOnWriteArrayList(),
    ) : RootCaptureRunner.ProxyController {
        var listeningPort: Int? = null
        var accepting: Boolean = false

        override fun startListening(preferredPort: Int): RootCaptureRunner.ListenerInfo? {
            events += "listen:$preferredPort"
            onStartListeningHook?.invoke()
            val info = listenResult?.copy(port = preferredPort) ?: return null
            listeningPort = info.port
            accepting = true
            return info
        }

        override fun isLastListenErrorPortConflict(): Boolean = portConflict

        override fun stopAccepting() {
            events += "stopAccepting"
            accepting = false
        }

        override fun awaitOpenConnections(timeoutMs: Long): Boolean {
            events += "awaitOpenConnections"
            return true
        }

        override fun closeProxy() {
            events += "closeProxy"
            listeningPort = null
            accepting = false
        }
    }

    private class RecordingCallbacks(
        val events: MutableList<String> = CopyOnWriteArrayList(),
    ) : RootCaptureRunner.Callbacks {
        var appliedPort: Int? = null
        var ipv4Rules: Int = 0
        var ipv6Rules: Int = 0
        var ipv6Unsupported: Boolean = false
        var lastError: String? = null
        var lastToast: String? = null
        var lastApplyFailedRetrying: Boolean = false
        var cleanupRetryMessage: String? = null
        var portError: Int? = null

        override fun onApplied(port: Int, ipv4RuleCount: Int, ipv6RuleCount: Int, ipv6Unsupported: Boolean) {
            events += "onApplied:$port:$ipv4RuleCount:$ipv6RuleCount:$ipv6Unsupported"
            this.appliedPort = port
            this.ipv4Rules = ipv4RuleCount
            this.ipv6Rules = ipv6RuleCount
            this.ipv6Unsupported = ipv6Unsupported
        }

        override fun onApplyFailed(
            errorMessage: String,
            toastMessage: String,
            cleanupRetrying: Boolean,
        ) {
            events += "onApplyFailed:$errorMessage:$cleanupRetrying"
            this.lastError = errorMessage
            this.lastToast = toastMessage
            this.lastApplyFailedRetrying = cleanupRetrying
        }

        override fun onCleanupSucceeded() {
            events += "onCleanupSucceeded"
            this.appliedPort = null
        }

        override fun onCleanupRetryScheduled(message: String) {
            events += "onCleanupRetryScheduled"
            this.cleanupRetryMessage = message
        }

        override fun onPortError(port: Int) {
            events += "onPortError:$port"
            this.portError = port
        }
    }

    @Test
    fun applySuccessFollowsListenPrecheckApplyWatchdogOrderForIpv4AndIpv6() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events)
        val callbacks = RecordingCallbacks(events = events)
        val shell = RootShell { _, stdin, _ ->
            when {
                stdin == RootRedirectScripts.precheckIpv4() -> {
                    events += "shell:precheckIpv4"
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                }
                stdin == RootRedirectScripts.precheckIpv6() -> {
                    events += "shell:precheckIpv6"
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                }
                stdin.contains("-A FGA_CAP") -> {
                    events += "shell:apply"
                    assertTrue(flag.isSet(), "Flag must be set before apply runs")
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                }
                stdin.contains("setsid nohup sh") -> {
                    events += "shell:startWatchdog"
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_WATCHDOG_OK))
                }
                else -> RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
            }
        }

        val runner = RootCaptureRunner(
            rootShell = shell,
            flag = flag,
            fgaUidProvider = { 10999 },
            targetUidsProvider = { listOf(10123, 10456) },
            proxyController = proxy,
            callbacks = callbacks,
            periodicCheckIntervalMs = 0L,
            executor = executor,
        )

        runner.requestStartReading(8899)
        drainExecutor()

        assertEquals(
            listOf(
                "listen:8899",
                "shell:precheckIpv4",
                "shell:precheckIpv6",
                "shell:apply",
                "shell:startWatchdog",
                "onApplied:8899:2:2:false",
            ),
            events.toList(),
        )
        assertTrue(flag.isSet())
        assertEquals(8899, callbacks.appliedPort)
        assertEquals(2, callbacks.ipv4Rules)
        assertEquals(2, callbacks.ipv6Rules)
        assertFalse(callbacks.ipv6Unsupported)
    }

    @Test
    fun ipv6PrecheckFailureAppliesIpv4OnlyAndMarksIpv6Unsupported() {
        val proxy = FakeProxyController()
        val callbacks = RecordingCallbacks()
        val shell = RootShell { _, stdin, _ ->
            when {
                stdin == RootRedirectScripts.precheckIpv4() ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                stdin == RootRedirectScripts.precheckIpv6() ->
                    RootShellOutput(1, listOf("FGA_FAIL_STEP=1"))
                stdin.contains("-A FGA_CAP") -> {
                    assertFalse(stdin.contains("ip6tables"))
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                }
                stdin.contains("setsid nohup sh") -> {
                    assertFalse(stdin.contains("/proc/net/tcp6"))
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_WATCHDOG_OK))
                }
                else -> RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
            }
        }

        val runner = RootCaptureRunner(
            rootShell = shell,
            flag = flag,
            fgaUidProvider = { 10999 },
            targetUidsProvider = { listOf(10123) },
            proxyController = proxy,
            callbacks = callbacks,
            periodicCheckIntervalMs = 0L,
            executor = executor,
        )

        runner.requestStartReading(8899)
        drainExecutor()

        assertEquals(8899, callbacks.appliedPort)
        assertEquals(1, callbacks.ipv4Rules)
        assertEquals(0, callbacks.ipv6Rules)
        assertTrue(callbacks.ipv6Unsupported)
    }

    @Test
    fun ipv4PrecheckFailureDoesNotApplyRulesAndLeavesFlagClear() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events)
        val callbacks = RecordingCallbacks(events = events)
        val shell = RootShell { _, stdin, _ ->
            events += "shell"
            assertEquals(RootRedirectScripts.precheckIpv4(), stdin)
            RootShellOutput(1, listOf("FGA_FAIL_STEP=1"))
        }

        val runner = RootCaptureRunner(
            rootShell = shell,
            flag = flag,
            fgaUidProvider = { 10999 },
            targetUidsProvider = { listOf(10123) },
            proxyController = proxy,
            callbacks = callbacks,
            periodicCheckIntervalMs = 0L,
            executor = executor,
        )

        runner.requestStartReading(8899)
        drainExecutor()

        assertFalse(flag.isSet())
        assertNull(proxy.listeningPort)
        assertEquals(RootCaptureRunner.ERROR_NAT_UNSUPPORTED, callbacks.lastError)
        assertEquals(RootCaptureRunner.TOAST_APPLY_FAILED, callbacks.lastToast)
    }

    @Test
    fun applyStepFailureRunsCleanupClearsFlagClosesListenerAndReportsStep() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events)
        val callbacks = RecordingCallbacks(events = events)
        val shell = RootShell { _, stdin, _ ->
            when {
                stdin == RootRedirectScripts.precheckIpv4() || stdin == RootRedirectScripts.precheckIpv6() ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                stdin.contains("-A FGA_CAP") -> {
                    events += "shell:applyFail"
                    RootShellOutput(1, listOf("FGA_FAIL_STEP=2"))
                }
                stdin.contains("-X FGA_CAP") -> {
                    events += "shell:cleanup"
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
                }
                else -> RootShellOutput(0, emptyList())
            }
        }

        val runner = RootCaptureRunner(
            rootShell = shell,
            flag = flag,
            fgaUidProvider = { 10999 },
            targetUidsProvider = { listOf(10123) },
            proxyController = proxy,
            callbacks = callbacks,
            periodicCheckIntervalMs = 0L,
            executor = executor,
        )

        runner.requestStartReading(8899)
        drainExecutor()

        assertTrue(events.contains("shell:cleanup"))
        assertFalse(flag.isSet())
        assertNull(proxy.listeningPort)
        assertEquals("루트 규칙 설정 실패 (단계 2)", callbacks.lastError)
        assertEquals(RootCaptureRunner.TOAST_APPLY_FAILED, callbacks.lastToast)
    }

    @Test
    fun watchdogProbeFailureRollsBackRulesAndClosesListener() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events)
        val callbacks = RecordingCallbacks(events = events)
        val shell = RootShell { _, stdin, _ ->
            when {
                stdin == RootRedirectScripts.precheckIpv4() || stdin == RootRedirectScripts.precheckIpv6() ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                stdin.contains("-A FGA_CAP") ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                stdin.contains("setsid nohup sh") -> {
                    events += "shell:watchdogProbeFail"
                    RootShellOutput(1, listOf(RootRedirectScripts.MARKER_FAIL_WATCHDOG_PROBE))
                }
                stdin.contains("-X FGA_CAP") -> {
                    events += "shell:cleanup"
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
                }
                else -> RootShellOutput(0, emptyList())
            }
        }

        val runner = RootCaptureRunner(
            rootShell = shell,
            flag = flag,
            fgaUidProvider = { 10999 },
            targetUidsProvider = { listOf(10123) },
            proxyController = proxy,
            callbacks = callbacks,
            periodicCheckIntervalMs = 0L,
            executor = executor,
        )

        runner.requestStartReading(8899)
        drainExecutor()

        assertTrue(events.contains("shell:cleanup"))
        assertFalse(flag.isSet())
        assertNull(proxy.listeningPort)
        assertTrue(callbacks.lastError?.contains(RootRedirectScripts.MARKER_FAIL_WATCHDOG_PROBE) == true)
        assertEquals(RootCaptureRunner.TOAST_APPLY_FAILED, callbacks.lastToast)
    }

    @Test
    fun readingOffFollowsB4OrderCleanupThenStopAcceptingThenDrainThenCloseAndKillWatchdog() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events)
        val callbacks = RecordingCallbacks(events = events)
        val shell = RootShell { _, stdin, _ ->
            when {
                stdin == RootRedirectScripts.precheckIpv4() || stdin == RootRedirectScripts.precheckIpv6() ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                stdin.contains("-A FGA_CAP") ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                stdin.contains("setsid nohup sh") ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_WATCHDOG_OK))
                stdin == RootRedirectScripts.cleanup(killWatchdog = false) -> {
                    events += "shell:cleanupRules"
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
                }
                stdin == RootRedirectScripts.killWatchdog() -> {
                    events += "shell:killWatchdog"
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
                }
                else -> RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
            }
        }

        val runner = RootCaptureRunner(
            rootShell = shell,
            flag = flag,
            fgaUidProvider = { 10999 },
            targetUidsProvider = { listOf(10123) },
            proxyController = proxy,
            callbacks = callbacks,
            drainTimeoutMs = 100L,
            periodicCheckIntervalMs = 0L,
            executor = executor,
        )

        runner.requestStartReading(8899)
        drainExecutor()
        events.clear()

        runner.requestStopReading()
        drainExecutor()

        assertEquals(
            listOf(
                "shell:cleanupRules",
                "stopAccepting",
                "awaitOpenConnections",
                "closeProxy",
                "shell:killWatchdog",
                "onCleanupSucceeded",
            ),
            events.toList(),
        )
        assertFalse(flag.isSet())
    }

    @Test
    fun cleanupFailureKeepsListenerOpenAndRetriesUntilCleanupSucceeds() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events)
        val callbacks = RecordingCallbacks(events = events)
        var cleanupAttempts = 0
        val retryDone = CountDownLatch(1)

        val shell = RootShell { _, stdin, _ ->
            when {
                stdin == RootRedirectScripts.precheckIpv4() || stdin == RootRedirectScripts.precheckIpv6() ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                stdin.contains("-A FGA_CAP") ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                stdin.contains("setsid nohup sh") ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_WATCHDOG_OK))
                stdin == RootRedirectScripts.cleanup(killWatchdog = false) -> {
                    cleanupAttempts += 1
                    if (cleanupAttempts == 1) {
                        RootShellOutput(1, listOf("FGA_FAIL_STEP=1"))
                    } else {
                        RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
                    }
                }
                stdin == RootRedirectScripts.killWatchdog() -> {
                    retryDone.countDown()
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
                }
                else -> RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
            }
        }

        val runner = RootCaptureRunner(
            rootShell = shell,
            flag = flag,
            fgaUidProvider = { 10999 },
            targetUidsProvider = { listOf(10123) },
            proxyController = proxy,
            callbacks = callbacks,
            drainTimeoutMs = 10L,
            cleanupRetryDelayMs = 40L,
            periodicCheckIntervalMs = 0L,
            executor = executor,
        )

        runner.requestStartReading(8899)
        drainExecutor()

        runner.requestStopReading()
        // Wait for first cleanup attempt (which fails)
        val firstBatch = CountDownLatch(1)
        executor.execute { firstBatch.countDown() }
        assertTrue(firstBatch.await(2, TimeUnit.SECONDS))

        // After first failure, listener is still accepting and flag is still set
        assertEquals(RootCaptureRunner.CLEANUP_RETRY_MESSAGE, callbacks.cleanupRetryMessage)
        assertTrue(flag.isSet())

        // Wait for retry to succeed
        assertTrue(retryDone.await(3, TimeUnit.SECONDS))
        drainExecutor()

        assertFalse(flag.isSet())
        assertNull(proxy.listeningPort)
        assertTrue(events.contains("onCleanupSucceeded"))
    }

    @Test
    fun r5FlagLogicNeverCallsRootShellWhenClearAndCleansUpWhenSet() {
        var shellCalls = 0
        val shell = RootShell { _, _, _ ->
            shellCalls += 1
            RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
        }
        val runner = RootCaptureRunner(
            rootShell = shell,
            flag = flag,
            fgaUidProvider = { 10999 },
            targetUidsProvider = { listOf(10123) },
            proxyController = FakeProxyController(),
            callbacks = RecordingCallbacks(),
            periodicCheckIntervalMs = 0L,
            executor = executor,
        )

        // Flag clear -> 0 shell calls
        assertFalse(flag.isSet())
        runner.cleanupIfFlagSet()
        drainExecutor()
        assertEquals(0, shellCalls)

        // Flag set -> cleanup called and flag cleared
        flag.markMaybeActive()
        assertTrue(flag.isSet())
        runner.cleanupIfFlagSet()
        drainExecutor()
        assertEquals(1, shellCalls)
        assertFalse(flag.isSet())
    }

    @Test
    fun portChangeRestartsListenerRulesAndWatchdogWithNewPort() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events)
        val callbacks = RecordingCallbacks(events = events)
        val shell = RootShell { _, stdin, _ ->
            when {
                stdin == RootRedirectScripts.precheckIpv4() || stdin == RootRedirectScripts.precheckIpv6() ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                stdin.contains("--to-ports 8899") -> {
                    events += "apply:8899"
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                }
                stdin.contains("--to-ports 9001") -> {
                    events += "apply:9001"
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                }
                stdin.contains("0100007F:22C3") -> {
                    events += "watchdog:8899"
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_WATCHDOG_OK))
                }
                stdin.contains("0100007F:2329") -> {
                    events += "watchdog:9001"
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_WATCHDOG_OK))
                }
                else -> RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
            }
        }

        val runner = RootCaptureRunner(
            rootShell = shell,
            flag = flag,
            fgaUidProvider = { 10999 },
            targetUidsProvider = { listOf(10123) },
            proxyController = proxy,
            callbacks = callbacks,
            periodicCheckIntervalMs = 0L,
            executor = executor,
        )

        runner.requestStartReading(8899)
        runner.requestPortChange(9001)
        drainExecutor()

        assertTrue(events.contains("apply:9001"))
        assertTrue(events.contains("watchdog:9001"))
        assertEquals(9001, callbacks.appliedPort)
    }

    @Test
    fun periodicCheckReappliesRulesWhenChainIsMissing() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events)
        val callbacks = RecordingCallbacks(events = events)
        var chainPresent = true

        val shell = RootShell { _, stdin, _ ->
            when {
                stdin == RootRedirectScripts.precheckIpv4() || stdin == RootRedirectScripts.precheckIpv6() ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                stdin == RootRedirectScripts.checkChains(ipv6 = true) -> {
                    events += "checkChains:$chainPresent"
                    if (chainPresent) {
                        RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                    } else {
                        RootShellOutput(1, listOf("FGA_FAIL_STEP=1"))
                    }
                }
                stdin.contains("-A FGA_CAP") -> {
                    events += "apply"
                    chainPresent = true
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                }
                stdin.contains("setsid nohup sh") ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_WATCHDOG_OK))
                else -> RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
            }
        }

        val runner = RootCaptureRunner(
            rootShell = shell,
            flag = flag,
            fgaUidProvider = { 10999 },
            targetUidsProvider = { listOf(10123) },
            proxyController = proxy,
            callbacks = callbacks,
            periodicCheckIntervalMs = 0L,
            executor = executor,
        )

        runner.requestStartReading(8899)
        drainExecutor()
        events.clear()

        // Chain present -> no re-apply
        runner.requestPeriodicCheck()
        drainExecutor()
        assertEquals(listOf("checkChains:true"), events.toList())

        // Chain missing -> re-applies
        events.clear()
        chainPresent = false
        runner.requestPeriodicCheck()
        drainExecutor()
        assertEquals(listOf("checkChains:false", "apply", "onApplied:8899:1:1:false"), events.toList())
    }

    @Test
    fun applyFailureWithFailedCleanupKeepsListenerOpenAndRetriesUntilCleanupSucceeds() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events)
        val callbacks = RecordingCallbacks(events = events)
        val retryDone = CountDownLatch(1)

        val shell = RootShell { _, stdin, _ ->
            when {
                stdin == RootRedirectScripts.precheckIpv4() || stdin == RootRedirectScripts.precheckIpv6() ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                stdin.contains("-A FGA_CAP") -> {
                    events += "shell:applyFail"
                    RootShellOutput(1, listOf("FGA_FAIL_STEP=2"))
                }
                stdin == RootRedirectScripts.cleanup(killWatchdog = true) -> {
                    events += "shell:rollbackCleanupFail"
                    RootShellOutput(1, listOf("FGA_FAIL_STEP=1"))
                }
                stdin == RootRedirectScripts.cleanup(killWatchdog = false) -> {
                    events += "shell:retryCleanupOk"
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
                }
                stdin == RootRedirectScripts.killWatchdog() -> {
                    events += "shell:killWatchdog"
                    retryDone.countDown()
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
                }
                else -> RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
            }
        }

        val runner = RootCaptureRunner(
            rootShell = shell,
            flag = flag,
            fgaUidProvider = { 10999 },
            targetUidsProvider = { listOf(10123) },
            proxyController = proxy,
            callbacks = callbacks,
            drainTimeoutMs = 10L,
            cleanupRetryDelayMs = 40L,
            periodicCheckIntervalMs = 0L,
            executor = executor,
        )

        // Pause retry by inspecting state immediately after the first batch completes
        runner.requestStartReading(8899)
        val firstBatch = CountDownLatch(1)
        executor.execute { firstBatch.countDown() }
        assertTrue(firstBatch.await(2, TimeUnit.SECONDS))

        // After failed apply + failed rollback cleanup: listener open, retry scheduled, onApplyFailed reports retrying, flag set
        assertEquals(8899, proxy.listeningPort)
        assertTrue(proxy.accepting)
        assertTrue(callbacks.lastApplyFailedRetrying)
        assertEquals(RootCaptureRunner.CLEANUP_RETRY_MESSAGE, callbacks.cleanupRetryMessage)
        assertTrue(flag.isSet())
        assertTrue(
            events.indexOf("onCleanupRetryScheduled") < events.indexOf("onApplyFailed:루트 규칙 설정 실패 (단계 2):true"),
            "Retry state must be scheduled before onApplyFailed is posted",
        )

        // Wait for retry to succeed
        assertTrue(retryDone.await(3, TimeUnit.SECONDS))
        drainExecutor()

        assertNull(proxy.listeningPort)
        assertFalse(flag.isSet())
        assertTrue(events.contains("onCleanupSucceeded"))
    }

    @Test
    fun shutdownCleanupSuccessClosesListenerKillsWatchdogAndClearsFlag() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events)
        val callbacks = RecordingCallbacks(events = events)
        val shell = RootShell { _, stdin, _ ->
            when {
                stdin == RootRedirectScripts.precheckIpv4() || stdin == RootRedirectScripts.precheckIpv6() ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                stdin.contains("-A FGA_CAP") ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                stdin.contains("setsid nohup sh") ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_WATCHDOG_OK))
                stdin == RootRedirectScripts.cleanup(killWatchdog = false) -> {
                    events += "shell:cleanupRules"
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
                }
                stdin == RootRedirectScripts.killWatchdog() -> {
                    events += "shell:killWatchdog"
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
                }
                else -> RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
            }
        }

        val runner = RootCaptureRunner(
            rootShell = shell,
            flag = flag,
            fgaUidProvider = { 10999 },
            targetUidsProvider = { listOf(10123) },
            proxyController = proxy,
            callbacks = callbacks,
            periodicCheckIntervalMs = 0L,
            executor = executor,
        )

        runner.requestStartReading(8899)
        drainExecutor()
        assertTrue(flag.isSet())
        events.clear()

        runner.requestShutdown()
        drainExecutor()

        assertEquals(
            listOf(
                "shell:cleanupRules",
                "stopAccepting",
                "closeProxy",
                "shell:killWatchdog",
                "onCleanupSucceeded",
            ),
            events.toList(),
        )
        assertNull(proxy.listeningPort)
        assertFalse(flag.isSet())
    }

    @Test
    fun shutdownCleanupFailureKeepsListenerOpenAndRetriesUntilSuccess() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events)
        val callbacks = RecordingCallbacks(events = events)
        var cleanupAttempts = 0
        val retryDone = CountDownLatch(1)

        val shell = RootShell { _, stdin, _ ->
            when {
                stdin == RootRedirectScripts.precheckIpv4() || stdin == RootRedirectScripts.precheckIpv6() ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                stdin.contains("-A FGA_CAP") ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                stdin.contains("setsid nohup sh") ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_WATCHDOG_OK))
                stdin == RootRedirectScripts.cleanup(killWatchdog = false) -> {
                    cleanupAttempts += 1
                    if (cleanupAttempts == 1) {
                        RootShellOutput(1, listOf("FGA_FAIL_STEP=1"))
                    } else {
                        RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
                    }
                }
                stdin == RootRedirectScripts.killWatchdog() -> {
                    retryDone.countDown()
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
                }
                else -> RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
            }
        }

        val runner = RootCaptureRunner(
            rootShell = shell,
            flag = flag,
            fgaUidProvider = { 10999 },
            targetUidsProvider = { listOf(10123) },
            proxyController = proxy,
            callbacks = callbacks,
            cleanupRetryDelayMs = 40L,
            periodicCheckIntervalMs = 0L,
            executor = executor,
        )

        runner.requestStartReading(8899)
        drainExecutor()

        runner.requestShutdown()
        val firstBatch = CountDownLatch(1)
        executor.execute { firstBatch.countDown() }
        assertTrue(firstBatch.await(2, TimeUnit.SECONDS))

        // First shutdown cleanup failed -> listener kept, retry scheduled, flag still set
        assertEquals(8899, proxy.listeningPort)
        assertTrue(proxy.accepting)
        assertTrue(flag.isSet())
        assertEquals(RootCaptureRunner.CLEANUP_RETRY_MESSAGE, callbacks.cleanupRetryMessage)
        assertFalse(events.contains("onCleanupSucceeded"))

        // Retry succeeds -> proxy closed, flag cleared
        assertTrue(retryDone.await(3, TimeUnit.SECONDS))
        drainExecutor()

        assertNull(proxy.listeningPort)
        assertFalse(flag.isSet())
        assertTrue(events.contains("onCleanupSucceeded"))
    }

    @Test
    fun onDestroyCleanupClearsFlagOnSuccessAndLeavesFlagSetOnFailureOrTimeout() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events)
        val callbacks = RecordingCallbacks(events = events)
        var cleanupMode = "ok"
        val unblockTimeout = CountDownLatch(1)

        val shell = RootShell { _, stdin, _ ->
            when {
                stdin == RootRedirectScripts.precheckIpv4() || stdin == RootRedirectScripts.precheckIpv6() ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                stdin.contains("-A FGA_CAP") ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                stdin.contains("setsid nohup sh") ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_WATCHDOG_OK))
                stdin == RootRedirectScripts.cleanup(killWatchdog = true) -> {
                    when (cleanupMode) {
                        "ok" -> RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
                        "fail" -> RootShellOutput(1, listOf("FGA_FAIL_STEP=1"))
                        else -> {
                            unblockTimeout.await(2, TimeUnit.SECONDS)
                            RootShellOutput(1, emptyList(), timedOut = true)
                        }
                    }
                }
                else -> RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
            }
        }

        val runner = RootCaptureRunner(
            rootShell = shell,
            flag = flag,
            fgaUidProvider = { 10999 },
            targetUidsProvider = { listOf(10123) },
            proxyController = proxy,
            callbacks = callbacks,
            periodicCheckIntervalMs = 0L,
            executor = executor,
        )

        // 1. Success -> flag cleared and proxy closed
        cleanupMode = "ok"
        runner.requestStartReading(8899)
        drainExecutor()
        assertTrue(flag.isSet())
        runner.onDestroyCleanup(maxWaitMs = 2_000L)
        assertFalse(flag.isSet())
        assertNull(proxy.listeningPort)

        // 2. Cleanup failure -> flag stays set
        cleanupMode = "fail"
        runner.requestStartReading(8899)
        drainExecutor()
        assertTrue(flag.isSet())
        runner.onDestroyCleanup(maxWaitMs = 2_000L)
        assertTrue(flag.isSet(), "Flag must remain set when onDestroyCleanup fails")

        // 3. Timeout -> returns after maxWaitMs and leaves flag set
        cleanupMode = "timeout"
        flag.markMaybeActive()
        runner.onDestroyCleanup(maxWaitMs = 25L)
        assertTrue(flag.isSet(), "Flag must remain set when onDestroyCleanup times out")
        unblockTimeout.countDown()
        drainExecutor()
        assertTrue(flag.isSet())
    }

    @Test
    fun nonPortConflictListenFailureReportsGenericStartFailedInsteadOfPortError() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(
            listenResult = null,
            portConflict = false,
            events = events,
        )
        val callbacks = RecordingCallbacks(events = events)
        var shellCalls = 0
        val shell = RootShell { _, _, _ ->
            shellCalls += 1
            RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
        }

        val runner = RootCaptureRunner(
            rootShell = shell,
            flag = flag,
            fgaUidProvider = { 10999 },
            targetUidsProvider = { listOf(10123) },
            proxyController = proxy,
            callbacks = callbacks,
            periodicCheckIntervalMs = 0L,
            executor = executor,
        )

        runner.requestStartReading(8899)
        drainExecutor()

        assertEquals(0, shellCalls)
        assertNull(callbacks.portError)
        assertEquals(RootCaptureRunner.ERROR_START_FAILED, callbacks.lastError)
        assertEquals(RootCaptureRunner.TOAST_START_FAILED, callbacks.lastToast)
        assertFalse(callbacks.lastApplyFailedRetrying)
        assertFalse(flag.isSet())
    }

    @Test
    fun generationRaceStopQueuedAfterStartDropsPendingApply() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events)
        val callbacks = RecordingCallbacks(events = events)
        var applyCalled = false

        val shell = RootShell { _, stdin, _ ->
            if (stdin.contains("-A FGA_CAP")) {
                applyCalled = true
            }
            RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_CLEANUP_OK))
        }

        val runner = RootCaptureRunner(
            rootShell = shell,
            flag = flag,
            fgaUidProvider = { 10999 },
            targetUidsProvider = { listOf(10123) },
            proxyController = proxy,
            callbacks = callbacks,
            periodicCheckIntervalMs = 0L,
            executor = executor,
        )

        // Hold executor while start and stop are queued back-to-back
        val gate = CountDownLatch(1)
        executor.execute { gate.await(2, TimeUnit.SECONDS) }
        runner.requestStartReading(8899)
        runner.requestStopReading()
        gate.countDown()
        drainExecutor()

        assertFalse(applyCalled, "Pending apply must be dropped when superseded by stop")
        assertFalse(flag.isSet())
        assertNull(proxy.listeningPort)

        // Also verify stop queued while startListening is in flight drops apply and closes listener
        events.clear()
        proxy.onStartListeningHook = {
            runner.requestStopReading()
        }
        runner.requestStartReading(8899)
        drainExecutor()

        assertFalse(applyCalled, "In-flight start must drop apply when stop advances generation")
        assertFalse(flag.isSet())
        assertNull(proxy.listeningPort)
    }

    @Test
    fun noTargetUidDoesNotStartReadingAndMakesNoShellCall() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events)
        val callbacks = RecordingCallbacks(events = events)
        var shellCalls = 0
        val shell = RootShell { _, _, _ ->
            shellCalls += 1
            RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
        }

        val runner = RootCaptureRunner(
            rootShell = shell,
            flag = flag,
            fgaUidProvider = { 10999 },
            targetUidsProvider = { emptyList() },
            proxyController = proxy,
            callbacks = callbacks,
            periodicCheckIntervalMs = 0L,
            executor = executor,
        )

        runner.requestStartReading(8899)
        drainExecutor()

        assertEquals(0, shellCalls)
        assertNull(proxy.listeningPort)
        assertFalse(runner.isReadingWanted())
        assertFalse(flag.isSet())
        assertEquals(RootCaptureRunner.ERROR_NO_TARGETS, callbacks.lastError)
        assertEquals(RootCaptureRunner.TOAST_APPLY_FAILED, callbacks.lastToast)
    }
}
