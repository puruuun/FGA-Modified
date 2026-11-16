package io.github.fate_grand_automata.capture

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Serializes all Root capture mode operations (pre-check, apply, re-apply, cleanup, watchdog start/kill)
 * on a single-thread executor with generation-based cancellation (R3–R6, B1, B3, B4, B6, B8).
 */
internal class RootCaptureRunner(
    private val rootShell: RootShell,
    private val flag: RootRulesActiveFlag,
    private val fgaUidProvider: () -> Int,
    private val targetUidsProvider: () -> List<Int>,
    private val proxyController: ProxyController,
    private val callbacks: Callbacks,
    private val drainTimeoutMs: Long = 30_000L,
    private val cleanupRetryDelayMs: Long = 10_000L,
    private val periodicCheckIntervalMs: Long = 60_000L,
    private val shellTimeoutMs: Long = 10_000L,
    private val drainSliceMs: Long = 200L,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "fga-root-capture").apply { isDaemon = true }
    },
) {
    data class ListenerInfo(
        val port: Int,
        val ipv6Listening: Boolean,
    )

    interface ProxyController {
        /**
         * Starts listening on [preferredPort]. With [keepPrevious] (port change) the current listener stays open
         * and usable until [closePreviousListener]; a failed listen must leave it untouched (G3).
         */
        fun startListening(preferredPort: Int, keepPrevious: Boolean = false): ListenerInfo?
        fun isLastListenErrorPortConflict(): Boolean = true

        /** False when the current listener is already closed or dead. */
        fun isListenerUsable(): Boolean = true

        /** Drops the certificate contexts so open connections finish their current request and close (G2). */
        fun clearContexts() {}
        fun stopAccepting()
        fun awaitOpenConnections(timeoutMs: Long): Boolean

        /** Closes the new listener of a superseded port change and makes the previous one current again. */
        fun abandonNewListener() {}
        fun stopAcceptingPrevious() {}
        fun awaitPreviousConnections(timeoutMs: Long): Boolean = true
        fun closePreviousListener() {}

        /** Closes the current listener and any previous one. */
        fun closeProxy()
    }

    interface Callbacks {
        fun onApplied(
            port: Int,
            ipv4RuleCount: Int,
            ipv6RuleCount: Int,
            ipv6Unsupported: Boolean,
        )
        fun onApplyFailed(
            errorMessage: String,
            toastMessage: String,
            cleanupRetrying: Boolean = false,
        )
        fun onCleanupSucceeded()

        /** Rules are confirmed gone: reading is off now, the listener only drains open connections (G2). */
        fun onRulesRemoved() {}

        /** A port change could not listen on the new port; reading continues on the old port (G3). */
        fun onPortChangeFailed(port: Int, portConflict: Boolean) {}
        fun onCleanupRetryScheduled(message: String)
        fun onPortError(port: Int)
    }

    private val generation = AtomicLong(0L)
    private val readingWanted = AtomicBoolean(false)

    @Volatile
    private var activePort: Int? = null

    @Volatile
    private var activeIpv6: Boolean = false

    @Volatile
    private var activeUids: List<Int> = emptyList()

    private var periodicCheckFuture: ScheduledFuture<*>? = null
    private var cleanupRetryFuture: ScheduledFuture<*>? = null

    fun isReadingWanted(): Boolean = readingWanted.get()
    fun currentGeneration(): Long = generation.get()

    /**
     * Requests starting Root capture reading on [preferredPort].
     * Queued onto the single-thread root executor; dropped if a stop/cleanup supersedes it.
     */
    fun requestStartReading(preferredPort: Int) {
        val gen = generation.incrementAndGet()
        readingWanted.set(true)
        executor.execute {
            cancelCleanupRetryOnExecutor()
            runStartOrPortChange(preferredPort, gen, isPortChange = false)
        }
    }

    /**
     * Requests changing the listening port while reading is active (B6: restarts listener, rules, and watchdog).
     */
    fun requestPortChange(newPort: Int) {
        if (!readingWanted.get()) return
        val gen = generation.incrementAndGet()
        readingWanted.set(true)
        executor.execute {
            cancelCleanupRetryOnExecutor()
            runStartOrPortChange(newPort, gen, isPortChange = true)
        }
    }

    /**
     * Requests turning reading off in B4 order:
     * (1) cleanup rules and confirm `FGA_CLEANUP_OK`,
     * (2) stop accepting new connections,
     * (3) let open connections finish up to [drainTimeoutMs], then close proxy and kill watchdog.
     */
    fun requestStopReading(drainMs: Long = drainTimeoutMs) {
        val gen = generation.incrementAndGet()
        readingWanted.set(false)
        executor.execute {
            cancelPeriodicCheckOnExecutor()
            cancelCleanupRetryOnExecutor()
            runStopReadingOnExecutor(gen, drainMs)
        }
    }

    /**
     * Asynchronous shutdown for mode switch away from Root, tracker disabled, or consent withdrawn (F3).
     * Submits cleanup on the serial executor without blocking the main thread; retries every 10 s on failure.
     */
    fun requestShutdown() {
        requestStopReading(drainMs = 0L)
    }

    private fun runShell(script: String): RootShellOutput {
        val timeoutSec = ((shellTimeoutMs + 999L) / 1000L).coerceAtLeast(1L)
        return runCatching {
            rootShell.run(ROOT_SH_COMMAND, script, timeoutSec)
        }.getOrElse {
            RootShellOutput(-1, emptyList(), false)
        }
    }

    /**
     * R5 / B8 startup cleanup: only runs `su` if the persisted flag file is set.
     * Never invokes `su` when the flag is clear.
     */
    fun cleanupIfFlagSet(onDone: (() -> Unit)? = null) {
        if (!flag.isSet()) {
            onDone?.invoke()
            return
        }
        executor.execute {
            if (!readingWanted.get() && flag.isSet()) {
                val res = runShell(RootRedirectScripts.cleanup(killWatchdog = true))
                if (isCleanupOk(res)) {
                    flag.clear()
                }
            }
            onDone?.invoke()
        }
    }

    /**
     * B6 `onDestroy`: starts cleanup on the serial executor and waits at most [maxWaitMs] (default 2 s).
     */
    fun onDestroyCleanup(maxWaitMs: Long = 2_000L) {
        val wasReadingOrFlagged = readingWanted.getAndSet(false) || flag.isSet() || activePort != null
        val gen = generation.incrementAndGet()
        if (!wasReadingOrFlagged) return
        val future = executor.submit {
            cancelPeriodicCheckOnExecutor()
            cancelCleanupRetryOnExecutor()
            if (flag.isSet() || activePort != null) {
                val res = runShell(RootRedirectScripts.cleanup(killWatchdog = true))
                if (isCleanupOk(res)) {
                    flag.clear()
                    proxyController.stopAccepting()
                    proxyController.closeProxy()
                    activePort = null
                    activeIpv6 = false
                    activeUids = emptyList()
                    callbacks.onCleanupSucceeded()
                }
            } else {
                proxyController.closeProxy()
            }
            gen
        }
        try {
            future.get(maxWaitMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // The detached root watchdog covers any remaining cleanup within ~3 s.
        }
    }

    /**
     * Triggers an immediate chain-existence check on the serial executor (used by tests and periodic timer).
     */
    internal fun requestPeriodicCheck() {
        val expectedGen = generation.get()
        executor.execute {
            runPeriodicCheckOnExecutor(expectedGen)
        }
    }

    private fun shouldProceed(expectedGen: Long): Boolean =
        readingWanted.get() && generation.get() == expectedGen

    private fun runStartOrPortChange(preferredPort: Int, expectedGen: Long, isPortChange: Boolean) {
        if (!shouldProceed(expectedGen)) return

        val targetUids = targetUidsProvider().filter { it > 0 }.distinct()
        if (targetUids.isEmpty()) {
            readingWanted.set(false)
            callbacks.onApplyFailed(ERROR_NO_TARGETS, TOAST_APPLY_FAILED)
            return
        }

        val fgaUid = fgaUidProvider()
        if (fgaUid <= 0) {
            readingWanted.set(false)
            callbacks.onApplyFailed(ERROR_INVALID_FGA_UID, TOAST_APPLY_FAILED)
            return
        }

        if (isPortChange && activePort != null) {
            runPortChange(preferredPort, expectedGen, targetUids, fgaUid)
            return
        }

        // Order (B6): listen -> pre-check -> apply -> watchdog start
        val listener = proxyController.startListening(preferredPort)
        if (listener == null) {
            readingWanted.set(false)
            if (proxyController.isLastListenErrorPortConflict()) {
                callbacks.onPortError(preferredPort)
            } else {
                callbacks.onApplyFailed(ERROR_START_FAILED, TOAST_START_FAILED, cleanupRetrying = false)
            }
            return
        }

        if (!shouldProceed(expectedGen)) {
            proxyController.stopAccepting()
            proxyController.closeProxy()
            return
        }

        // B1 pre-check IPv4 nat OUTPUT
        val ipv4Check = runShell(RootRedirectScripts.precheckIpv4())
        if (!isApplyOk(ipv4Check)) {
            proxyController.stopAccepting()
            proxyController.closeProxy()
            readingWanted.set(false)
            callbacks.onApplyFailed(ERROR_NAT_UNSUPPORTED, TOAST_APPLY_FAILED)
            return
        }

        if (!shouldProceed(expectedGen)) {
            proxyController.stopAccepting()
            proxyController.closeProxy()
            return
        }

        // Final Owner Decision: pre-check IPv6 nat OUTPUT when IPv6 listener is bound
        val useIpv6 = if (listener.ipv6Listening) {
            val ipv6Check = runShell(RootRedirectScripts.precheckIpv6())
            isApplyOk(ipv6Check)
        } else {
            false
        }
        val ipv6Unsupported = !useIpv6

        if (!shouldProceed(expectedGen)) {
            proxyController.stopAccepting()
            proxyController.closeProxy()
            return
        }

        // R5 / B8: synchronously set flag before apply
        flag.markMaybeActive()

        val applyRes = runShell(RootRedirectScripts.apply(targetUids, listener.port, useIpv6))
        if (!isApplyOk(applyRes)) {
            val step = parseFailStep(applyRes.lines)
            val errMsg = if (step != null) {
                "루트 규칙 설정 실패 (단계 $step)"
            } else {
                "루트 규칙 설정 실패"
            }
            rollbackFailedApply(errMsg)
            return
        }

        if (!shouldProceed(expectedGen)) {
            runStopReadingOnExecutor(generation.get(), drainTimeoutMs)
            return
        }

        val watchdogRes = runShell(RootRedirectScripts.startWatchdog(listener.port, fgaUid, useIpv6))
        if (!isWatchdogOk(watchdogRes)) {
            val errMsg = if (watchdogRes.lines.any { it.trim() == RootRedirectScripts.MARKER_FAIL_WATCHDOG_PROBE }) {
                "루트 감시 프로그램 소켓 확인 실패 (${RootRedirectScripts.MARKER_FAIL_WATCHDOG_PROBE})"
            } else {
                "루트 감시 프로그램 시작 실패"
            }
            rollbackFailedApply(errMsg)
            return
        }

        activePort = listener.port
        activeIpv6 = useIpv6
        activeUids = targetUids
        val ipv4Count = targetUids.size
        val ipv6Count = if (useIpv6) targetUids.size else 0
        callbacks.onApplied(
            port = listener.port,
            ipv4RuleCount = ipv4Count,
            ipv6RuleCount = ipv6Count,
            ipv6Unsupported = ipv6Unsupported,
        )
        schedulePeriodicCheckOnExecutor(expectedGen)
    }

    /**
     * G3: listen on the new port first; only then re-apply rules, restart the watchdog and retire the old listener.
     * Rules never point at a port without a listener.
     */
    private fun runPortChange(newPort: Int, expectedGen: Long, targetUids: List<Int>, fgaUid: Int) {
        if (newPort == activePort) return
        val listener = proxyController.startListening(newPort, keepPrevious = true)
        if (listener == null) {
            if (!shouldProceed(expectedGen)) return
            val conflict = proxyController.isLastListenErrorPortConflict()
            if (proxyController.isListenerUsable()) {
                // Old listener and rules stay as they are; reading continues on the old port.
                callbacks.onPortChangeFailed(newPort, conflict)
            } else {
                cleanupAfterUnusableListener(newPort, conflict)
            }
            return
        }
        if (!shouldProceed(expectedGen)) {
            proxyController.abandonNewListener()
            return
        }

        val useIpv6 = activeIpv6 && listener.ipv6Listening
        // Kill the old watchdog while the old listener is still open, so it cannot clean up the new rules.
        runShell(RootRedirectScripts.killWatchdog())
        flag.markMaybeActive()
        val applyRes = runShell(RootRedirectScripts.apply(targetUids, listener.port, useIpv6))
        if (!isApplyOk(applyRes)) {
            val step = parseFailStep(applyRes.lines)
            rollbackFailedApply(if (step != null) "루트 규칙 설정 실패 (단계 $step)" else "루트 규칙 설정 실패")
            return
        }
        if (!shouldProceed(expectedGen)) {
            runStopReadingOnExecutor(generation.get(), drainTimeoutMs)
            return
        }
        val watchdogRes = runShell(RootRedirectScripts.startWatchdog(listener.port, fgaUid, useIpv6))
        if (!isWatchdogOk(watchdogRes)) {
            val errMsg = if (watchdogRes.lines.any { it.trim() == RootRedirectScripts.MARKER_FAIL_WATCHDOG_PROBE }) {
                "루트 감시 프로그램 소켓 확인 실패 (${RootRedirectScripts.MARKER_FAIL_WATCHDOG_PROBE})"
            } else {
                "루트 감시 프로그램 시작 실패"
            }
            rollbackFailedApply(errMsg)
            return
        }

        activePort = listener.port
        activeIpv6 = useIpv6
        activeUids = targetUids
        callbacks.onApplied(
            port = listener.port,
            ipv4RuleCount = targetUids.size,
            ipv6RuleCount = if (useIpv6) targetUids.size else 0,
            ipv6Unsupported = !useIpv6,
        )
        schedulePeriodicCheckOnExecutor(expectedGen)

        // Retire the old listener only now: rules and watchdog already use the new port.
        proxyController.stopAcceptingPrevious()
        drain(drainTimeoutMs, expectedGen) { proxyController.awaitPreviousConnections(it) }
        proxyController.closePreviousListener()
    }

    /** The old listener died and the new one could not start: remove the rules at once (rollback path). */
    private fun cleanupAfterUnusableListener(port: Int, portConflict: Boolean) {
        readingWanted.set(false)
        cancelPeriodicCheckOnExecutor()
        val res = runShell(RootRedirectScripts.cleanup(killWatchdog = true))
        if (isCleanupOk(res)) {
            flag.clear()
            proxyController.closeProxy()
            activePort = null
            activeIpv6 = false
            activeUids = emptyList()
            if (portConflict) {
                callbacks.onPortError(port)
            } else {
                callbacks.onApplyFailed(ERROR_START_FAILED, TOAST_START_FAILED, cleanupRetrying = false)
            }
        } else {
            scheduleCleanupRetryOnExecutor(generation.get(), drainTimeoutMs)
            callbacks.onApplyFailed(ERROR_START_FAILED, TOAST_START_FAILED, cleanupRetrying = true)
        }
    }

    /** Waits for open connections in short slices; a newer request (generation change) cuts the drain short. */
    private fun drain(totalMs: Long, expectedGen: Long, await: (Long) -> Boolean) {
        if (totalMs <= 0L) return
        val deadline = System.currentTimeMillis() + totalMs
        while (generation.get() == expectedGen) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) break
            if (await(minOf(drainSliceMs, remaining))) break
        }
    }

    private fun rollbackFailedApply(errorMessage: String) {
        readingWanted.set(false)
        cancelPeriodicCheckOnExecutor()
        val cleanupRes = runShell(RootRedirectScripts.cleanup(killWatchdog = true))
        if (isCleanupOk(cleanupRes)) {
            flag.clear()
            proxyController.stopAccepting()
            proxyController.closeProxy()
            activePort = null
            activeIpv6 = false
            activeUids = emptyList()
            callbacks.onApplyFailed(errorMessage, TOAST_APPLY_FAILED, cleanupRetrying = false)
        } else {
            // B4 / F2: cleanup failed — keep listener open so FGO traffic still works and retry every 10 s
            scheduleCleanupRetryOnExecutor(generation.get(), drainTimeoutMs)
            callbacks.onApplyFailed(errorMessage, TOAST_APPLY_FAILED, cleanupRetrying = true)
        }
    }

    private fun runStopReadingOnExecutor(expectedGen: Long, drainMs: Long) {
        if (readingWanted.get()) return

        val needRuleCleanup = flag.isSet() || activePort != null
        if (!needRuleCleanup) {
            proxyController.stopAccepting()
            proxyController.closeProxy()
            callbacks.onCleanupSucceeded()
            return
        }

        // B4 step (1): cleanup rules first and confirm FGA_CLEANUP_OK (keep watchdog until proxy closes)
        val cleanupRes = runShell(RootRedirectScripts.cleanup(killWatchdog = false))
        if (!isCleanupOk(cleanupRes)) {
            // B4: keep listener open so FGO keeps working through it, retry every 10 s
            scheduleCleanupRetryOnExecutor(expectedGen, drainMs)
            return
        }

        // G2: rules are gone, so reading is off at once (tracker, status, notification), and open connections
        // finish their current request and close instead of being intercepted again.
        proxyController.clearContexts()
        callbacks.onRulesRemoved()

        // B4 step (2): stop accepting new connections
        proxyController.stopAccepting()

        // B4 step (3): let open connections finish up to drainMs (a newer request cuts it short),
        // then close proxy and kill watchdog
        drain(drainMs, expectedGen) { proxyController.awaitOpenConnections(it) }
        proxyController.closeProxy()
        runShell(RootRedirectScripts.killWatchdog())

        // Confirmed FGA_CLEANUP_OK and chain gone -> clear flag
        flag.clear()

        activePort = null
        activeIpv6 = false
        activeUids = emptyList()
        callbacks.onCleanupSucceeded()
    }

    private fun scheduleCleanupRetryOnExecutor(expectedGen: Long, drainMs: Long) {
        callbacks.onCleanupRetryScheduled(CLEANUP_RETRY_MESSAGE)
        cancelCleanupRetryOnExecutor()
        cleanupRetryFuture = executor.schedule(
            {
                if (!readingWanted.get() && generation.get() == expectedGen) {
                    runStopReadingOnExecutor(expectedGen, drainMs)
                }
            },
            cleanupRetryDelayMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun schedulePeriodicCheckOnExecutor(expectedGen: Long) {
        cancelPeriodicCheckOnExecutor()
        if (periodicCheckIntervalMs <= 0L) return
        periodicCheckFuture = executor.scheduleWithFixedDelay(
            { runPeriodicCheckOnExecutor(expectedGen) },
            periodicCheckIntervalMs,
            periodicCheckIntervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun runPeriodicCheckOnExecutor(expectedGen: Long) {
        if (!shouldProceed(expectedGen)) return
        val port = activePort ?: return
        val uids = activeUids.ifEmpty { return }
        val useIpv6 = activeIpv6

        val checkRes = runShell(RootRedirectScripts.checkChains(useIpv6))
        if (isApplyOk(checkRes)) {
            return
        }

        if (!shouldProceed(expectedGen)) return

        // Chain or jump missing while reading -> re-apply idempotently
        flag.markMaybeActive()
        val applyRes = runShell(RootRedirectScripts.apply(uids, port, useIpv6))
        if (!isApplyOk(applyRes)) {
            val step = parseFailStep(applyRes.lines)
            val errMsg = if (step != null) {
                "루트 규칙 재적용 실패 (단계 $step)"
            } else {
                "루트 규칙 재적용 실패"
            }
            rollbackFailedApply(errMsg)
            return
        }
        callbacks.onApplied(
            port = port,
            ipv4RuleCount = uids.size,
            ipv6RuleCount = if (useIpv6) uids.size else 0,
            ipv6Unsupported = !useIpv6,
        )
    }

    private fun cancelPeriodicCheckOnExecutor() {
        periodicCheckFuture?.cancel(false)
        periodicCheckFuture = null
    }

    private fun cancelCleanupRetryOnExecutor() {
        cleanupRetryFuture?.cancel(false)
        cleanupRetryFuture = null
    }

    companion object {
        const val ERROR_NAT_UNSUPPORTED = "이 기기에서는 루트 규칙(nat REDIRECT)을 쓸 수 없습니다"
        const val ERROR_NO_TARGETS = "수집할 수 있는 FGO 대상이 없습니다 (인증서 및 게임 설치를 확인하세요)"
        const val ERROR_INVALID_FGA_UID = "FGA 앱 UID를 확인할 수 없습니다"
        const val ERROR_START_FAILED = "로그 수집을 시작하지 못했습니다."
        const val IPV6_UNSUPPORTED_NOTE = "이 기기는 IPv6 규칙을 지원하지 않아 IPv6 연결은 수집되지 않습니다(게임은 정상)"
        const val TOAST_APPLY_FAILED = "루트 규칙을 설정하지 못했습니다"
        const val TOAST_START_FAILED = "로그 수집을 시작하지 못했습니다."
        const val TOAST_ROOT_UNAVAILABLE = "루트 권한을 확인할 수 없습니다"
        const val ROOT_CHECKING_MESSAGE = "루트 권한 확인 중 — 허용 팝업을 눌러 주세요"
        const val CLEANUP_RETRY_MESSAGE = "루트 규칙을 지우지 못했습니다. 다시 시도 중이며, 계속 실패하면 재부팅하세요."

        internal fun isApplyOk(res: RootShellOutput): Boolean =
            !res.timedOut && res.exitCode == 0 && res.lines.any { it.trim() == RootRedirectScripts.MARKER_APPLY_OK }

        internal fun isCleanupOk(res: RootShellOutput): Boolean =
            !res.timedOut && res.exitCode == 0 && res.lines.any { it.trim() == RootRedirectScripts.MARKER_CLEANUP_OK }

        internal fun isWatchdogOk(res: RootShellOutput): Boolean =
            !res.timedOut && res.exitCode == 0 && res.lines.any { it.trim() == RootRedirectScripts.MARKER_WATCHDOG_OK }

        internal fun parseFailStep(output: List<String>): Int? =
            output.firstNotNullOfOrNull { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith(RootRedirectScripts.MARKER_FAIL_STEP_PREFIX)) {
                    trimmed.removePrefix(RootRedirectScripts.MARKER_FAIL_STEP_PREFIX).toIntOrNull()
                } else {
                    null
                }
            }
    }
}
