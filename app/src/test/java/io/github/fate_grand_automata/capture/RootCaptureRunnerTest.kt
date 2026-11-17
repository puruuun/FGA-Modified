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
import java.util.concurrent.atomic.AtomicReference
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
        val failingPorts: MutableSet<Int> = mutableSetOf(),
        var alive: Boolean = true,
        /** When set, awaitOpenConnections blocks for the whole slice and reports connections still open. */
        var slowAwait: Boolean = false,
    ) : RootCaptureRunner.ProxyController {
        var listeningPort: Int? = null
        var accepting: Boolean = false

        override fun startListening(preferredPort: Int): RootCaptureRunner.ListenerInfo? {
            events += "listen:$preferredPort"
            onStartListeningHook?.invoke()
            if (preferredPort in failingPorts) return null
            val info = listenResult?.copy(port = preferredPort) ?: return null
            listeningPort = info.port
            accepting = true
            return info
        }

        override fun isListenerAlive(): Boolean = alive

        override fun clearContexts() {
            events += "clearContexts"
        }

        override fun isLastListenErrorPortConflict(): Boolean = portConflict

        override fun stopAccepting() {
            events += "stopAccepting"
            accepting = false
        }

        override fun awaitOpenConnections(timeoutMs: Long): Boolean {
            events += "awaitOpenConnections"
            if (slowAwait) {
                Thread.sleep(timeoutMs)
                return false
            }
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
        val cleanupGenerations = CopyOnWriteArrayList<Long>()

        override fun onRulesRemoved() {
            events += "onRulesRemoved"
        }

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

        override fun onCleanupSucceeded(generation: Long) {
            events += "onCleanupSucceeded"
            cleanupGenerations += generation
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
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
                }
                stdin == RootRedirectScripts.precheckIpv6() -> {
                    events += "shell:precheckIpv6"
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
                }
                stdin.contains("-A FGA_CAP") -> {
                    events += "shell:apply"
                    assertTrue(flag.isSet(), "Flag must be set before apply runs")
                    // K2: one script, apply first and the watchdog start after it.
                    assertTrue(stdin.indexOf("-A FGA_CAP") < stdin.indexOf("setsid nohup sh"))
                    events += "shell:startWatchdog"
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
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
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
                stdin == RootRedirectScripts.precheckIpv6() ->
                    RootShellOutput(1, listOf("FGA_FAIL_STEP=1"))
                stdin.contains("-A FGA_CAP") -> {
                    assertFalse(stdin.contains("\"\$IP6T\" -w 3 -t nat -A FGA_CAP"))
                    assertFalse(stdin.contains("00000000000000000000000001000000:"))
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
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
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
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
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
                stdin.contains("-A FGA_CAP") -> {
                    events += "shell:watchdogProbeFail"
                    RootShellOutput(1, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_FAIL_WATCHDOG_PROBE))
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
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
                stdin.contains("-A FGA_CAP") ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
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
                "clearContexts",
                "onRulesRemoved",
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
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
                stdin.contains("-A FGA_CAP") ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
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
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
                stdin.contains("--to-ports 8899") -> {
                    events += "apply:8899"
                    events += "watchdog:8899"
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
                }
                stdin.contains("--to-ports 9001") -> {
                    events += "apply:9001"
                    events += "watchdog:9001"
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
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

    private fun portChangeShell(events: MutableList<String>, flagCleanupCalls: MutableList<String>? = null) =
        RootShell { _, stdin, _ ->
            when {
                stdin == RootRedirectScripts.killWatchdog() -> {
                    events += "shell:killWatchdog"
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
                }
                stdin == RootRedirectScripts.precheckIpv4() || stdin == RootRedirectScripts.precheckIpv6() ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
                stdin.contains("--to-ports 8899") -> {
                    events += "apply:8899"
                    events += "watchdog:8899"
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
                }
                stdin.contains("--to-ports 9001") -> {
                    events += "apply:9001"
                    events += "watchdog:9001"
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
                }
                else -> {
                    flagCleanupCalls?.add("cleanup")
                    events += "shell:cleanup"
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
                }
            }
        }

    private fun portChangeRunner(
        events: MutableList<String>,
        proxy: FakeProxyController,
        callbacks: RecordingCallbacks,
        shell: RootShell,
    ) = RootCaptureRunner(
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

    @Test
    fun portChangeIsTheNormalStopSequenceFollowedByTheNormalStartOnTheNewPort() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events)
        val callbacks = RecordingCallbacks(events = events)
        val runner = portChangeRunner(events, proxy, callbacks, portChangeShell(events))

        runner.requestStartReading(8899)
        drainExecutor()
        events.clear()

        runner.requestPortChange(9001)
        drainExecutor()

        val order = events.filter {
            it in setOf(
                "shell:cleanup", "stopAccepting", "closeProxy", "shell:killWatchdog",
                "listen:9001", "apply:9001", "watchdog:9001",
            )
        }
        assertEquals(
            listOf(
                "shell:cleanup", "stopAccepting", "closeProxy", "shell:killWatchdog",
                "listen:9001", "apply:9001", "watchdog:9001",
            ),
            order,
        )
        // The port change is not a stop: nothing reports the rules or the service as finished.
        assertFalse(events.contains("onCleanupSucceeded"))
        assertFalse(events.contains("onRulesRemoved"))
        assertEquals(9001, callbacks.appliedPort)
        assertEquals(9001, proxy.listeningPort)
        assertTrue(runner.isReadingWanted())
        assertTrue(flag.isSet())
    }

    @Test
    fun portChangeToTheSamePortDoesNothing() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events)
        val callbacks = RecordingCallbacks(events = events)
        val runner = portChangeRunner(events, proxy, callbacks, portChangeShell(events))

        runner.requestStartReading(8899)
        drainExecutor()
        events.clear()

        runner.requestPortChange(8899)
        drainExecutor()

        assertTrue(events.isEmpty(), events.toString())
    }

    @Test
    fun portChangeBindFailureLeavesReadingOffWithPortErrorNoRulesAndFlagClear() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events)
        val callbacks = RecordingCallbacks(events = events)
        val runner = portChangeRunner(events, proxy, callbacks, portChangeShell(events))

        runner.requestStartReading(8899)
        drainExecutor()
        events.clear()
        proxy.failingPorts += 9001

        runner.requestPortChange(9001)
        drainExecutor()

        assertEquals(9001, callbacks.portError)
        assertFalse(runner.isReadingWanted())
        assertFalse(flag.isSet())
        assertNull(proxy.listeningPort)
        assertTrue(runner.isIdle())
        assertFalse(events.any { it.startsWith("apply:") || it.startsWith("watchdog:") })
        // The old rules were taken away before the new port was tried.
        assertTrue(events.indexOf("shell:cleanup") < events.indexOf("listen:9001"))
    }

    @Test
    fun portChangeWithFailingCleanupKeepsTheOldListenerAndRetries() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events)
        val callbacks = RecordingCallbacks(events = events)
        var failCleanup = false
        val base = portChangeShell(events)
        val shell = RootShell { c, stdin, t ->
            if (failCleanup && stdin.contains("-D OUTPUT") && !stdin.contains("-A FGA_CAP")) {
                RootShellOutput(1, listOf("FGA_FAIL_STEP=1"))
            } else {
                base.run(c, stdin, t)
            }
        }
        val runner = portChangeRunner(events, proxy, callbacks, shell)

        runner.requestStartReading(8899)
        drainExecutor()
        failCleanup = true
        events.clear()

        runner.requestPortChange(9001)
        drainExecutor()

        assertFalse(events.contains("closeProxy"))
        assertFalse(events.contains("listen:9001"))
        assertEquals(8899, proxy.listeningPort)
        assertTrue(callbacks.lastApplyFailedRetrying)
        assertTrue(flag.isSet())
    }

    @Test
    fun drainInterruptedByAStartEndsWithReadingOnAndDoesNotStopTheService() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events, slowAwait = true)
        val callbacks = RecordingCallbacks(events = events)
        val runner = RootCaptureRunner(
            rootShell = portChangeShell(events),
            flag = flag,
            fgaUidProvider = { 10999 },
            targetUidsProvider = { listOf(10123) },
            proxyController = proxy,
            callbacks = callbacks,
            drainTimeoutMs = 30_000L,
            drainSliceMs = 20L,
            periodicCheckIntervalMs = 0L,
            executor = executor,
        )

        runner.requestStartReading(8899)
        drainExecutor()
        events.clear()

        runner.requestStopReading()
        val deadline = System.currentTimeMillis() + 3_000L
        while (!events.contains("awaitOpenConnections") && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(events.contains("awaitOpenConnections"))

        runner.requestStartReading(8899)
        val finish = System.currentTimeMillis() + 5_000L
        while (events.none { it.startsWith("onApplied") } && System.currentTimeMillis() < finish) {
            Thread.sleep(10)
        }

        assertEquals(8899, callbacks.appliedPort)
        assertTrue(runner.isReadingWanted())
        assertEquals(1, callbacks.cleanupGenerations.size)
        // The completion of the interrupted stop is stale: the service must not stop itself on it.
        assertFalse(
            RootCaptureRunner.shouldStopServiceAfterCleanup(
                callbacks.cleanupGenerations.single(),
                runner.lastStartGeneration(),
                runner.isReadingWanted(),
            ),
        )
        assertTrue(flag.isSet())
    }

    @Test
    fun userStopFollowedByShutdownClosesOnceAndCompletesOnce() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events, slowAwait = true)
        val callbacks = RecordingCallbacks(events = events)
        val runner = RootCaptureRunner(
            rootShell = portChangeShell(events),
            flag = flag,
            fgaUidProvider = { 10999 },
            targetUidsProvider = { listOf(10123) },
            proxyController = proxy,
            callbacks = callbacks,
            drainTimeoutMs = 30_000L,
            drainSliceMs = 20L,
            periodicCheckIntervalMs = 0L,
            executor = executor,
        )

        runner.requestStartReading(8899)
        drainExecutor()
        events.clear()

        runner.requestStopReading()
        val deadline = System.currentTimeMillis() + 3_000L
        while (!events.contains("awaitOpenConnections") && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        runner.requestShutdown()
        drainExecutor()

        assertEquals(1, events.count { it == "closeProxy" })
        assertEquals(1, events.count { it == "onCleanupSucceeded" })
        // A newer stop does not make the completion stale: the service still stops on it.
        assertTrue(
            RootCaptureRunner.shouldStopServiceAfterCleanup(
                callbacks.cleanupGenerations.single(),
                runner.lastStartGeneration(),
                runner.isReadingWanted(),
            ),
        )
        assertTrue(runner.isIdle())
    }

    @Test
    fun stopWithNothingActiveClosesNothingButStillEndsTheServiceOnce() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events)
        val callbacks = RecordingCallbacks(events = events)
        var shellCalls = 0
        val shell = RootShell { _, _, _ ->
            shellCalls += 1
            RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
        }
        val runner = portChangeRunner(events, proxy, callbacks, shell)

        runner.requestStopReading()
        runner.requestShutdown()
        drainExecutor()

        // K1: nothing to close, but the service completion still arrives, once for two stops (H3).
        assertEquals(listOf("onCleanupSucceeded"), events.toList())
        assertEquals(0, shellCalls)
        assertTrue(
            RootCaptureRunner.shouldStopServiceAfterCleanup(
                callbacks.cleanupGenerations.single(),
                runner.lastStartGeneration(),
                runner.isReadingWanted(),
            ),
        )
        assertTrue(runner.isIdle())
    }

    @Test
    fun stopBeforeTheStartReachedTheFlagStillEndsTheServiceWithoutClosingTwice() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events)
        val callbacks = RecordingCallbacks(events = events)
        var shellCalls = 0
        val shell = RootShell { _, _, _ ->
            shellCalls += 1
            RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
        }
        val runner = portChangeRunner(events, proxy, callbacks, shell)

        val gate = CountDownLatch(1)
        executor.execute { gate.await(2, TimeUnit.SECONDS) }
        runner.requestStartReading(8899)
        runner.requestStopReading()
        runner.requestShutdown()
        gate.countDown()
        drainExecutor()

        assertEquals(0, shellCalls)
        assertEquals(0, events.count { it == "closeProxy" })
        assertEquals(1, events.count { it == "onCleanupSucceeded" })
        assertTrue(
            RootCaptureRunner.shouldStopServiceAfterCleanup(
                callbacks.cleanupGenerations.single(),
                runner.lastStartGeneration(),
                runner.isReadingWanted(),
            ),
        )
        assertTrue(runner.isIdle())
    }

    @Test
    fun applyFailureDoesNotStartTheWatchdogAndWatchdogFailureRollsBack() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events)
        val callbacks = RecordingCallbacks(events = events)
        val shell = RootShell { _, stdin, _ ->
            when {
                stdin == RootRedirectScripts.precheckIpv4() || stdin == RootRedirectScripts.precheckIpv6() ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                stdin.contains("-A FGA_CAP") -> {
                    // A failing apply exits before the watchdog part of the script, so no watchdog marker appears.
                    RootShellOutput(1, listOf("FGA_FAIL_STEP=3"))
                }
                else -> RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
            }
        }
        val runner = portChangeRunner(events, proxy, callbacks, shell)

        runner.requestStartReading(8899)
        drainExecutor()

        assertEquals("루트 규칙 설정 실패 (단계 3)", callbacks.lastError)
        assertFalse(events.any { it.startsWith("onApplied") })
        assertFalse(flag.isSet())
        assertNull(proxy.listeningPort)
    }

    @Test
    fun shutdownDuringApplyDrainsForZeroNotForTheUserStopDrain() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events, slowAwait = true)
        val callbacks = RecordingCallbacks(events = events)
        lateinit var runner: RootCaptureRunner
        val shell = RootShell { _, stdin, _ ->
            when {
                stdin == RootRedirectScripts.precheckIpv4() || stdin == RootRedirectScripts.precheckIpv6() ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK))
                stdin.contains("-A FGA_CAP") -> {
                    runner.requestShutdown()
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
                }
                else -> RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
            }
        }
        runner = RootCaptureRunner(
            rootShell = shell,
            flag = flag,
            fgaUidProvider = { 10999 },
            targetUidsProvider = { listOf(10123) },
            proxyController = proxy,
            callbacks = callbacks,
            drainTimeoutMs = 30_000L,
            drainSliceMs = 20L,
            periodicCheckIntervalMs = 0L,
            executor = executor,
        )

        val started = System.currentTimeMillis()
        runner.requestStartReading(8899)
        drainExecutor()

        assertTrue(System.currentTimeMillis() - started < 10_000L)
        assertEquals(0, events.count { it == "awaitOpenConnections" })
        assertEquals(1, events.count { it == "closeProxy" })
        assertEquals(1, events.count { it == "onCleanupSucceeded" })
        assertFalse(flag.isSet())
        assertTrue(runner.isIdle())
    }

    @Test
    fun shutdownWithFailingCleanupKeepsTheListenerAndIsNotIdle() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events)
        val callbacks = RecordingCallbacks(events = events)
        var failCleanup = false
        val base = portChangeShell(events)
        val shell = RootShell { c, stdin, t ->
            if (failCleanup && stdin.contains("-D OUTPUT") && !stdin.contains("-A FGA_CAP")) {
                RootShellOutput(1, listOf("FGA_FAIL_STEP=1"))
            } else {
                base.run(c, stdin, t)
            }
        }
        val runner = RootCaptureRunner(
            rootShell = shell,
            flag = flag,
            fgaUidProvider = { 10999 },
            targetUidsProvider = { listOf(10123) },
            proxyController = proxy,
            callbacks = callbacks,
            cleanupRetryDelayMs = 60_000L,
            periodicCheckIntervalMs = 0L,
            executor = executor,
        )
        runner.requestStartReading(8899)
        drainExecutor()
        failCleanup = true

        runner.requestShutdown()
        drainExecutor()

        assertFalse(events.contains("closeProxy"))
        assertEquals(8899, proxy.listeningPort)
        assertFalse(runner.isIdle())
        assertTrue(events.contains("onCleanupRetryScheduled"))
    }

    @Test
    fun deadAcceptLoopRemovesTheRulesAtOnceAndReports() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events)
        val callbacks = RecordingCallbacks(events = events)
        val runner = portChangeRunner(events, proxy, callbacks, portChangeShell(events))

        runner.requestStartReading(8899)
        drainExecutor()
        events.clear()
        proxy.alive = false

        runner.requestPeriodicCheck()
        drainExecutor()

        assertTrue(events.contains("shell:cleanup"))
        assertTrue(events.contains("closeProxy"))
        assertEquals(RootCaptureRunner.ERROR_LISTENER_LOST, callbacks.lastError)
        assertFalse(callbacks.lastApplyFailedRetrying)
        assertFalse(flag.isSet())
        assertFalse(runner.isReadingWanted())
    }

    @Test
    fun rulesRemovedCallbackFiresBeforeDrainCompletesAndShutdownCutsTheDrainShort() {
        val events = CopyOnWriteArrayList<String>()
        val proxy = FakeProxyController(events = events, slowAwait = true)
        val callbacks = RecordingCallbacks(events = events)
        val shell = portChangeShell(events)
        val runner = RootCaptureRunner(
            rootShell = shell,
            flag = flag,
            fgaUidProvider = { 10999 },
            targetUidsProvider = { listOf(10123) },
            proxyController = proxy,
            callbacks = callbacks,
            drainTimeoutMs = 30_000L,
            drainSliceMs = 20L,
            periodicCheckIntervalMs = 0L,
            executor = executor,
        )

        runner.requestStartReading(8899)
        drainExecutor()
        events.clear()

        runner.requestStopReading()
        val deadline = System.currentTimeMillis() + 3_000L
        while (!events.contains("awaitOpenConnections") && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        // Draining has started: rules are gone and reading is already reported off, listener not closed yet.
        assertTrue(events.indexOf("onRulesRemoved") >= 0)
        assertTrue(events.indexOf("clearContexts") < events.indexOf("onRulesRemoved"))
        assertTrue(events.indexOf("onRulesRemoved") < events.indexOf("stopAccepting"))
        assertFalse(events.contains("closeProxy"))
        assertFalse(events.contains("onCleanupSucceeded"))

        val started = System.currentTimeMillis()
        runner.requestShutdown()
        val finishDeadline = System.currentTimeMillis() + 5_000L
        while (!events.contains("onCleanupSucceeded") && System.currentTimeMillis() < finishDeadline) {
            Thread.sleep(10)
        }
        assertTrue(events.contains("onCleanupSucceeded"))
        assertTrue(System.currentTimeMillis() - started < 5_000L, "shutdown waited for the full drain")
        assertTrue(events.contains("closeProxy"))
        assertFalse(flag.isSet())
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
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
                stdin == RootRedirectScripts.checkChains(ipv6 = true) -> {
                    events += "checkChains:$chainPresent"
                    if (chainPresent) {
                        RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
                    } else {
                        RootShellOutput(1, listOf("FGA_FAIL_STEP=1"))
                    }
                }
                stdin.contains("-A FGA_CAP") -> {
                    events += "apply"
                    chainPresent = true
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
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
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
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

        // Inspect state immediately after the first batch completes before the retry timer fires
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
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
                stdin.contains("-A FGA_CAP") ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
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
                "clearContexts",
                "onRulesRemoved",
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
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
                stdin.contains("-A FGA_CAP") ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
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
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
                stdin.contains("-A FGA_CAP") ->
                    RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
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
            RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
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
            RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
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

    private fun newObservedExecutor(): Pair<ScheduledExecutorService, AtomicReference<Throwable?>> {
        val uncaught = AtomicReference<Throwable?>(null)
        val factory = java.util.concurrent.ThreadFactory { r ->
            Thread(r, "fga-root-runner-test").apply {
                isDaemon = true
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e ->
                    uncaught.compareAndSet(null, e)
                }
            }
        }
        val executeDelegate = Executors.newSingleThreadExecutor(factory)
        val scheduleDelegate = Executors.newSingleThreadScheduledExecutor(factory)
        val exec = object : ScheduledExecutorService by scheduleDelegate {
            override fun execute(command: Runnable) = executeDelegate.execute(command)
            override fun shutdown() {
                executeDelegate.shutdown()
                scheduleDelegate.shutdown()
            }
            override fun shutdownNow(): MutableList<Runnable> =
                (executeDelegate.shutdownNow() + scheduleDelegate.shutdownNow()).toMutableList()
            override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
                executeDelegate.awaitTermination(timeout, unit) && scheduleDelegate.awaitTermination(timeout, unit)
        }
        return exec to uncaught
    }

    private fun drainExecutor(exec: ScheduledExecutorService) {
        val latch = CountDownLatch(1)
        exec.execute { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun requestStartReadingFailureDoesNotEscapeExecutor() {
        val (exec, uncaught) = newObservedExecutor()
        try {
            val blockingFile = File(tempDir, "blocking-file-start").apply { writeText("x") }
            val uncreatableFlag = RootRulesActiveFlag(File(blockingFile, "sub"))
            val runner = RootCaptureRunner(
                rootShell = portChangeShell(CopyOnWriteArrayList()),
                flag = uncreatableFlag,
                fgaUidProvider = { 10999 },
                targetUidsProvider = { listOf(10123) },
                proxyController = FakeProxyController(),
                callbacks = RecordingCallbacks(),
                periodicCheckIntervalMs = 0L,
                executor = exec,
            )

            runner.requestStartReading(8899)
            drainExecutor(exec)

            assertNull(uncaught.get())
        } finally {
            exec.shutdownNow()
        }
    }

    @Test
    fun requestPortChangeFailureDoesNotEscapeExecutor() {
        val (exec, uncaught) = newObservedExecutor()
        try {
            val flagDir = File(tempDir, "port-change-flag")
            val mutableFlag = RootRulesActiveFlag(flagDir)
            val runner = RootCaptureRunner(
                rootShell = portChangeShell(CopyOnWriteArrayList()),
                flag = mutableFlag,
                fgaUidProvider = { 10999 },
                targetUidsProvider = { listOf(10123) },
                proxyController = FakeProxyController(),
                callbacks = RecordingCallbacks(),
                periodicCheckIntervalMs = 0L,
                executor = exec,
            )

            runner.requestStartReading(8899)
            drainExecutor(exec)

            assertTrue(flagDir.deleteRecursively())
            flagDir.writeText("not-a-dir")

            runner.requestPortChange(9001)
            drainExecutor(exec)

            assertNull(uncaught.get())
        } finally {
            exec.shutdownNow()
        }
    }

    @Test
    fun requestStopReadingFailureDoesNotEscapeExecutor() {
        val (exec, uncaught) = newObservedExecutor()
        try {
            val shell = RootShell { _, stdin, _ ->
                when {
                    stdin == RootRedirectScripts.precheckIpv4() || stdin == RootRedirectScripts.precheckIpv6() ->
                        RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
                    stdin.contains("-A FGA_CAP") ->
                        RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
                    stdin == RootRedirectScripts.cleanup(killWatchdog = false) -> {
                        exec.shutdown()
                        RootShellOutput(1, listOf("FGA_FAIL_STEP=1"))
                    }
                    else -> RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
                }
            }
            val runner = RootCaptureRunner(
                rootShell = shell,
                flag = flag,
                fgaUidProvider = { 10999 },
                targetUidsProvider = { listOf(10123) },
                proxyController = FakeProxyController(),
                callbacks = RecordingCallbacks(),
                drainTimeoutMs = 10L,
                periodicCheckIntervalMs = 0L,
                executor = exec,
            )

            runner.requestStartReading(8899)
            drainExecutor(exec)

            runner.requestStopReading()
            assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS))
            assertNull(uncaught.get())
        } finally {
            exec.shutdownNow()
        }
    }

    @Test
    fun cleanupIfFlagSetCallbackFailureDoesNotEscapeExecutor() {
        val (exec, uncaught) = newObservedExecutor()
        try {
            flag.markMaybeActive()
            val shell = RootShell { _, _, _ -> RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK)) }
            val runner = RootCaptureRunner(
                rootShell = shell,
                flag = flag,
                fgaUidProvider = { 10999 },
                targetUidsProvider = { listOf(10123) },
                proxyController = FakeProxyController(),
                callbacks = RecordingCallbacks(),
                periodicCheckIntervalMs = 0L,
                executor = exec,
            )
            val doneLatch = CountDownLatch(1)

            runner.cleanupIfFlagSet {
                doneLatch.countDown()
                throw IllegalStateException("synthetic cleanup callback failure")
            }

            assertTrue(doneLatch.await(5, TimeUnit.SECONDS))
            drainExecutor(exec)
            assertNull(uncaught.get())
            assertFalse(flag.isSet())
        } finally {
            exec.shutdownNow()
        }
    }

    @Test
    fun requestPeriodicCheckFailureDoesNotEscapeExecutor() {
        val (exec, uncaught) = newObservedExecutor()
        try {
            val flagDir = File(tempDir, "periodic-flag")
            val mutableFlag = RootRulesActiveFlag(flagDir)
            val shell = RootShell { _, stdin, _ ->
                when {
                    stdin == RootRedirectScripts.precheckIpv4() || stdin == RootRedirectScripts.precheckIpv6() ->
                        RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
                    stdin.contains("-A FGA_CAP") ->
                        RootShellOutput(0, listOf(RootRedirectScripts.MARKER_APPLY_OK, RootRedirectScripts.MARKER_WATCHDOG_OK))
                    stdin == RootRedirectScripts.checkChains(ipv6 = true) ->
                        RootShellOutput(1, listOf("FGA_FAIL_STEP=1"))
                    else -> RootShellOutput(0, listOf(RootRedirectScripts.MARKER_CLEANUP_OK))
                }
            }
            val runner = RootCaptureRunner(
                rootShell = shell,
                flag = mutableFlag,
                fgaUidProvider = { 10999 },
                targetUidsProvider = { listOf(10123) },
                proxyController = FakeProxyController(),
                callbacks = RecordingCallbacks(),
                periodicCheckIntervalMs = 0L,
                executor = exec,
            )

            runner.requestStartReading(8899)
            drainExecutor(exec)

            assertTrue(flagDir.deleteRecursively())
            flagDir.writeText("not-a-dir")

            runner.requestPeriodicCheck()
            drainExecutor(exec)

            assertNull(uncaught.get())
        } finally {
            exec.shutdownNow()
        }
    }
}
