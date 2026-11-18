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
 *
 * There is one way to take the rules away ([stopRulesAndListener]) and one way to put them on ([runStart]).
 * A port change is the first followed by the second.
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
        fun startListening(preferredPort: Int): ListenerInfo?
        fun isLastListenErrorPortConflict(): Boolean = true

        /** False when the accept loop of the current listener has died (H7). */
        fun isListenerAlive(): Boolean = true

        /** Drops the certificate contexts so open connections finish their current request and close (G2). */
        fun clearContexts() {}
        fun stopAccepting()
        fun awaitOpenConnections(timeoutMs: Long): Boolean

        /** Closes the current listener. */
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

        /** Rules and listener are gone. [generation] is the request that finished them (H2). */
        fun onCleanupSucceeded(generation: Long)

        /** Rules are confirmed gone: reading is off now, the listener only drains open connections (G2). */
        fun onRulesRemoved() {}
        fun onCleanupRetryScheduled(message: String)
        fun onPortError(port: Int)
    }

    private val generation = AtomicLong(0L)
    private val readingWanted = AtomicBoolean(false)

    // Generation of the newest start or port change request; a cleanup from an older start is stale (H2).
    @Volatile
    private var lastStartGeneration: Long = 0L

    // True from a successful listen until the listener is closed; lets a stop with nothing active do nothing (H3).
    @Volatile
    private var listenerOpen: Boolean = false

    // Drain of the newest stop request: 0 for a shutdown, the user drain for a stop (K3).
    @Volatile
    private var lastStopDrainMs: Long = drainTimeoutMs

    // Start generation whose end the service already heard about; an idle stop reports once per start (K1/H3).
    @Volatile
    private var reportedForStartGeneration: Long = -1L

    @Volatile
    private var activePort: Int? = null

    @Volatile
    private var activeIpv6: Boolean = false

    @Volatile
    private var activeUids: List<Int> = emptyList()

    private var periodicCheckFuture: ScheduledFuture<*>? = null

    @Volatile
    private var cleanupRetryFuture: ScheduledFuture<*>? = null

    fun isReadingWanted(): Boolean = readingWanted.get()
    fun currentGeneration(): Long = generation.get()
    fun lastStartGeneration(): Long = lastStartGeneration

    /** True when no listener, rules, flag, retry or wanted reading is left (nothing for a stop to do). */
    fun isIdle(): Boolean = !readingWanted.get() && !listenerOpen && activePort == null && !flag.isSet() &&
        cleanupRetryFuture == null

    /**
     * Requests starting Root capture reading on [preferredPort].
     * Queued onto the single-thread root executor; dropped if a stop/cleanup supersedes it.
     */
    fun requestStartReading(preferredPort: Int) {
        val gen = generation.incrementAndGet()
        lastStartGeneration = gen
        readingWanted.set(true)
        executor.execute {
            cancelCleanupRetryOnExecutor()
            runStart(preferredPort, gen, skipIfSamePort = false)
        }
    }

    /**
     * H1: a port change while reading is the normal stop sequence followed by the normal start on the new port.
     * Between the two FGO connects directly (no rules), so it is never cut off.
     */
    fun requestPortChange(newPort: Int) {
        if (!readingWanted.get()) return
        val gen = generation.incrementAndGet()
        lastStartGeneration = gen
        readingWanted.set(true)
        executor.execute {
            cancelCleanupRetryOnExecutor()
            runStart(newPort, gen, skipIfSamePort = true)
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
        lastStopDrainMs = drainMs
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
     * Chain and watchdog names carry the FGA uid, so a script cannot be built without a valid one. Without a uid
     * nothing is run and the result is a failed cleanup: the flag stays set and the stop retries, rather than
     * guessing a name that could belong to another FGA app (#493).
     */
    private fun runForOwnUid(build: (Int) -> String): RootShellOutput {
        val fgaUid = fgaUidProvider()
        if (fgaUid <= 0) return RootShellOutput(-1, emptyList(), false)
        return runShell(build(fgaUid))
    }

    private fun runCleanup(killWatchdog: Boolean): RootShellOutput =
        runForOwnUid { RootRedirectScripts.cleanup(it, killWatchdog) }

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
                val res = runCleanup(killWatchdog = true)
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
        lastStopDrainMs = 0L
        if (!wasReadingOrFlagged) return
        val future = executor.submit {
            cancelPeriodicCheckOnExecutor()
            cancelCleanupRetryOnExecutor()
            if (flag.isSet() || activePort != null) {
                val res = runCleanup(killWatchdog = true)
                if (isCleanupOk(res)) {
                    flag.clear()
                    giveUpListener()
                    activePort = null
                    activeIpv6 = false
                    activeUids = emptyList()
                    reportCleanupSucceeded(gen)
                }
            } else {
                closeListener()
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

    private fun reportCleanupSucceeded(gen: Long) {
        reportedForStartGeneration = lastStartGeneration
        callbacks.onCleanupSucceeded(gen)
    }

    private fun closeListener() {
        proxyController.closeProxy()
        listenerOpen = false
    }

    private fun giveUpListener() {
        proxyController.stopAccepting()
        closeListener()
    }

    private fun runStart(preferredPort: Int, expectedGen: Long, skipIfSamePort: Boolean) {
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

        if (skipIfSamePort && activePort == preferredPort && listenerOpen) return

        // H1: anything still active (port change, repeated start, leftover flag) is stopped first.
        if (listenerOpen || activePort != null || flag.isSet()) {
            cancelPeriodicCheckOnExecutor()
            if (!stopRulesAndListener(expectedGen, drainMs = 0L, announceRulesRemoved = false)) {
                readingWanted.set(false)
                scheduleCleanupRetryOnExecutor(expectedGen, drainTimeoutMs)
                callbacks.onApplyFailed(ERROR_START_FAILED, TOAST_START_FAILED, cleanupRetrying = true)
                return
            }
            if (!shouldProceed(expectedGen)) return
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
        listenerOpen = true

        if (!shouldProceed(expectedGen)) {
            giveUpListener()
            return
        }

        // B1 pre-check IPv4 nat OUTPUT and xt_owner match
        val ipv4Check = runShell(RootRedirectScripts.precheckIpv4())
        if (!isApplyOk(ipv4Check)) {
            giveUpListener()
            readingWanted.set(false)
            val error = if (isOwnerUnsupported(ipv4Check)) ERROR_OWNER_UNSUPPORTED else ERROR_NAT_UNSUPPORTED
            callbacks.onApplyFailed(error, TOAST_APPLY_FAILED)
            return
        }

        if (!shouldProceed(expectedGen)) {
            giveUpListener()
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
            giveUpListener()
            return
        }

        // #493: stable and beta share the nat table; a live watchdog of another app means it is collecting.
        val guardRes = runShell(RootRedirectScripts.guardOtherApp(fgaUid, useIpv6))
        if (isOtherAppActive(guardRes)) {
            giveUpListener()
            readingWanted.set(false)
            callbacks.onApplyFailed(ERROR_OTHER_APP_ACTIVE, TOAST_APPLY_FAILED)
            return
        }
        if (guardRes.timedOut || guardRes.exitCode != 0) {
            giveUpListener()
            readingWanted.set(false)
            val step = parseFailStep(guardRes.lines)
            callbacks.onApplyFailed(
                if (step != null) "루트 규칙 설정 실패 (단계 $step)" else "루트 규칙 설정 실패",
                TOAST_APPLY_FAILED,
            )
            return
        }

        if (!shouldProceed(expectedGen)) {
            giveUpListener()
            return
        }

        // R5 / B8: synchronously set flag before apply
        flag.markMaybeActive()

        // K2: apply and watchdog start run in one root script, so rules never exist without the watchdog.
        val applyRes = runShell(
            RootRedirectScripts.applyAndStartWatchdog(targetUids, listener.port, fgaUid, useIpv6),
        )
        // The watchdog part exits 1 on a failed probe after FGA_APPLY_OK, so the marker alone says apply was done.
        val applyDone = !applyRes.timedOut &&
            applyRes.lines.any { it.trim() == RootRedirectScripts.MARKER_APPLY_OK }
        if (!applyDone) {
            val step = parseFailStep(applyRes.lines)
            val errMsg = if (step != null) {
                "루트 규칙 설정 실패 (단계 $step)"
            } else {
                "루트 규칙 설정 실패"
            }
            rollbackFailedApply(errMsg)
            return
        }

        if (!isWatchdogOk(applyRes)) {
            val errMsg = if (applyRes.lines.any { it.trim() == RootRedirectScripts.MARKER_FAIL_WATCHDOG_PROBE }) {
                "루트 감시 프로그램 소켓 확인 실패 (${RootRedirectScripts.MARKER_FAIL_WATCHDOG_PROBE})"
            } else {
                "루트 감시 프로그램 시작 실패"
            }
            rollbackFailedApply(errMsg)
            return
        }

        if (!shouldProceed(expectedGen)) {
            // Rules are in the kernel; never leave them behind a start that is no longer wanted.
            // K3: use the drain of the request that cancelled this start (0 for a shutdown).
            if (!readingWanted.get()) runStopReadingOnExecutor(generation.get(), lastStopDrainMs)
            return
        }

        // Two apps that started at the same moment both passed the start guard; the one that notices the other's
        // watchdog now takes its own rules back.
        val conflict = runShell(RootRedirectScripts.detectOtherApp(fgaUid, useIpv6))
        if (isOtherAppActive(conflict)) {
            rollbackFailedApply(ERROR_OTHER_APP_ACTIVE)
            return
        }
        if (conflict.timedOut || conflict.exitCode != 0) {
            val step = parseFailStep(conflict.lines)
            rollbackFailedApply(
                if (step != null) "루트 규칙 설정 실패 (단계 $step)" else "루트 규칙 설정 실패",
            )
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
        val cleanupRes = runCleanup(killWatchdog = true)
        if (isCleanupOk(cleanupRes)) {
            flag.clear()
            giveUpListener()
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

    /**
     * B4 order: (1) remove the rules and confirm `FGA_CLEANUP_OK`, (2) stop accepting, (3) drain open connections
     * (a newer request cuts it short), then close the listener, kill the watchdog and clear the flag.
     * Always finishes what it owns, so the next job starts clean. Returns false, leaving listener and watchdog
     * alone, when the rules could not be removed.
     */
    private fun stopRulesAndListener(expectedGen: Long, drainMs: Long, announceRulesRemoved: Boolean): Boolean {
        val rulesMaybe = flag.isSet() || activePort != null
        if (rulesMaybe) {
            val cleanupRes = runCleanup(killWatchdog = false)
            if (!isCleanupOk(cleanupRes)) return false
            proxyController.clearContexts()
            if (announceRulesRemoved) callbacks.onRulesRemoved()
        }
        proxyController.stopAccepting()
        drain(drainMs, expectedGen) { proxyController.awaitOpenConnections(it) }
        closeListener()
        if (rulesMaybe) {
            runForOwnUid { RootRedirectScripts.killWatchdog(it) }
            flag.clear()
        }
        activePort = null
        activeIpv6 = false
        activeUids = emptyList()
        return true
    }

    private fun runStopReadingOnExecutor(expectedGen: Long, drainMs: Long) {
        if (readingWanted.get()) return
        // H3: nothing active -> nothing to close. K1: the service still has to end, once per start.
        if (!flag.isSet() && activePort == null && !listenerOpen) {
            if (reportedForStartGeneration != lastStartGeneration) reportCleanupSucceeded(expectedGen)
            return
        }

        if (!stopRulesAndListener(expectedGen, drainMs, announceRulesRemoved = true)) {
            // B4: keep listener open so FGO keeps working through it, retry every 10 s
            scheduleCleanupRetryOnExecutor(expectedGen, drainMs)
            return
        }
        reportCleanupSucceeded(expectedGen)
    }

    private fun scheduleCleanupRetryOnExecutor(expectedGen: Long, drainMs: Long) {
        callbacks.onCleanupRetryScheduled(CLEANUP_RETRY_MESSAGE)
        cancelCleanupRetryOnExecutor()
        cleanupRetryFuture = executor.schedule(
            {
                cleanupRetryFuture = null
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
        val fgaUid = fgaUidProvider().takeIf { it > 0 } ?: run {
            rollbackFailedApply(ERROR_INVALID_FGA_UID)
            return
        }

        // H7: a dead accept loop is a lost listener; take the rules away at once so FGO connects directly.
        if (!proxyController.isListenerAlive()) {
            rollbackFailedApply(ERROR_LISTENER_LOST)
            return
        }

        // Another FGA app taking over must stop this app, or the two would keep re-applying against each other.
        // A detection that fails or times out (for example an xtables lock timeout) says nothing about the other
        // app, so it is skipped for this round: a working capture is not torn down by a transient failure.
        val conflict = runShell(RootRedirectScripts.detectOtherApp(fgaUid, useIpv6))
        if (isOtherAppActive(conflict)) {
            rollbackFailedApply(ERROR_OTHER_APP_ACTIVE)
            return
        }
        if (!shouldProceed(expectedGen)) return

        val checkRes = runShell(RootRedirectScripts.checkChains(fgaUid, port, useIpv6))
        if (isApplyOk(checkRes)) {
            return
        }

        if (!shouldProceed(expectedGen)) return

        // Chain, jump or REDIRECT missing while reading -> re-apply idempotently. The watchdog is started again
        // in the same script (K2); startWatchdog first kills this app's previous watchdog, which only removes
        // rules when its own checks fail, so the new rules are not taken away by it.
        flag.markMaybeActive()
        val applyRes = runShell(RootRedirectScripts.applyAndStartWatchdog(uids, port, fgaUid, useIpv6))
        val applyDone = !applyRes.timedOut &&
            applyRes.lines.any { it.trim() == RootRedirectScripts.MARKER_APPLY_OK }
        if (!applyDone) {
            val step = parseFailStep(applyRes.lines)
            val errMsg = if (step != null) {
                "루트 규칙 재적용 실패 (단계 $step)"
            } else {
                "루트 규칙 재적용 실패"
            }
            rollbackFailedApply(errMsg)
            return
        }
        if (!isWatchdogOk(applyRes)) {
            rollbackFailedApply("루트 감시 프로그램 재시작 실패")
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
        const val ERROR_OWNER_UNSUPPORTED =
            "이 기기는 앱별 통신 규칙(iptables owner)을 지원하지 않아 루트 모드를 쓸 수 없습니다. FGA VPN이나 외부 앱 프록시를 쓰세요."
        const val ERROR_OTHER_APP_ACTIVE = "다른 FGA 앱이 루트 수집 중입니다. 그 앱에서 수집을 먼저 꺼 주세요."
        const val ERROR_NO_TARGETS = "수집할 수 있는 FGO 대상이 없습니다 (인증서 및 게임 설치를 확인하세요)"
        const val ERROR_INVALID_FGA_UID = "FGA 앱 UID를 확인할 수 없습니다"
        const val ERROR_LISTENER_LOST = "로그 수집 수신 소켓이 닫혀 루트 규칙을 제거했습니다"
        const val ERROR_START_FAILED = "로그 수집을 시작하지 못했습니다."
        const val IPV6_UNSUPPORTED_NOTE = "이 기기는 IPv6 규칙을 지원하지 않아 IPv6 연결은 수집되지 않습니다(게임은 정상)"
        const val TOAST_APPLY_FAILED = "루트 규칙을 설정하지 못했습니다"
        const val TOAST_START_FAILED = "로그 수집을 시작하지 못했습니다."
        const val TOAST_ROOT_UNAVAILABLE = "루트 권한을 확인할 수 없습니다"
        const val ROOT_CHECKING_MESSAGE = "루트 권한 확인 중 — 허용 팝업을 눌러 주세요"
        const val CLEANUP_RETRY_MESSAGE = "루트 규칙을 지우지 못했습니다. 다시 시도 중이며, 계속 실패하면 재부팅하세요."

        /**
         * H2: the service stops itself after a cleanup only when no start was requested since and reading is not
         * wanted again; a newer stop does not make an earlier completion stale.
         */
        internal fun shouldStopServiceAfterCleanup(
            callbackGeneration: Long,
            lastStartGeneration: Long,
            readingWanted: Boolean,
        ): Boolean = !readingWanted && callbackGeneration >= lastStartGeneration

        internal fun isApplyOk(res: RootShellOutput): Boolean =
            !res.timedOut && res.exitCode == 0 && res.lines.any { it.trim() == RootRedirectScripts.MARKER_APPLY_OK }

        internal fun isOwnerUnsupported(res: RootShellOutput): Boolean =
            !res.timedOut && res.lines.any { it.trim() == RootRedirectScripts.MARKER_FAIL_OWNER }

        internal fun isOtherAppActive(res: RootShellOutput): Boolean =
            !res.timedOut && res.lines.any { it.trim() == RootRedirectScripts.MARKER_FAIL_OTHER_APP }

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
