package io.github.fate_grand_automata.scripts.capture.chaldea

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decides when captured account data is written into Chaldea (issue #355).
 *
 * Everything here is pure JVM: the app wires the Shizuku session, the account source, the stores and the clock.
 * The hooks ([onAccountSaved], [onScriptStart], ...) only put a small event on a queue and wake the single event
 * coroutine, so they never block and never call back into the capture code. Time is measured with [clock]
 * (elapsed realtime in the app); waits use `delay`.
 *
 * Timing rules (REF-0023 11.1, amendments D, I, J): a save restarts a 1 minute quiet timer; nothing runs while a
 * script runs; 15 s after a script ends the attempt starts; Chaldea is never touched while it is visible or its
 * state is unknown; in the background it is closed after the chosen minutes, with a re-check right before; state is
 * polled every minute for the first 10 minutes of waiting and every 5 minutes after that, binding the service only
 * for the poll. When neither FGO nor Chaldea is active on screen for the configured stop minutes, one final pass
 * runs and the session stops until a new save, script end, or switch toggle.
 */
class ChaldeaSyncScheduler(
    private val scope: CoroutineScope,
    private val env: ChaldeaSyncEnv,
    private val accounts: ChaldeaAccountSource,
    private val store: ChaldeaSyncStore,
    private val backups: ChaldeaBackupStore,
    private val clock: () -> Long,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val timing: Timing = Timing(),
) {
    data class Timing(
        val quietMs: Long = 60_000,
        val graceMs: Long = 15_000,
        val pollFastMs: Long = 60_000,
        val pollSlowMs: Long = 300_000,
        val fastWindowMs: Long = 600_000,
        /** Two observations further apart than the poll interval plus this restart the background count. */
        val gapMs: Long = 180_000,
        val scriptStaleMs: Long = 6 * 3_600_000L,
        /** After this many failed recovery attempts of an interrupted write, nothing more is tried until a toggle. */
        val maxRecoveryFailures: Int = 3,
    )

    private sealed interface Event {
        /** The ids themselves are in [savedIds]: bursts of saves share one queued event. */
        data object Saved : Event
        data class Deleted(val id: String?) : Event
        data object ScriptStart : Event
        data object ScriptEnd : Event
        data object Foreground : Event
        data object Settings : Event
        data object AccessChanged : Event
    }

    private enum class Phase { Idle, Quiet, Grace, Waiting, Writing }
    private enum class Pass { Done, Retry, Abort }
    private class Target(
        val id: String, val server: ChaldeaServer, val patch: ChaldeaPatch, val hash: String,
        val jsonLength: Int, val allowCreate: Boolean, val gen: Long,
    )

    private val _status = MutableStateFlow<ChaldeaSyncStatus>(ChaldeaSyncStatus.Idle)
    val status: StateFlow<ChaldeaSyncStatus> = _status.asStateFlow()

    private val _results = MutableStateFlow<Map<ChaldeaServer, ChaldeaSyncStatus>>(emptyMap())

    /** The last result of each server, kept apart so that one server's success never hides the other's failure. */
    val serverResults: StateFlow<Map<ChaldeaServer, ChaldeaSyncStatus>> = _results.asStateFlow()

    private val _scriptRunning = MutableStateFlow(false)

    /** Whether a script is running, for the "sync now" button. */
    val scriptRunningState: StateFlow<Boolean> = _scriptRunning.asStateFlow()

    private val events = ConcurrentLinkedQueue<Event>()
    private val savedIds = ConcurrentHashMap.newKeySet<String>()
    private val savedPosted = AtomicBoolean(false)

    /** Events waiting for the consumer; for tests. */
    internal fun queuedEventCount(): Int = events.size
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Guards the small state below. Never held across a suspension, a binder call or file work. */
    private val lock = Any()
    private val pending = LinkedHashSet<String>()
    private var backgroundSince: Long? = null
    private var lastObservation: Long? = null
    private var lastInterval: Long = 0
    private var lastActive: Long? = null
    private var stopped = false
    private var attemptJob: Job? = null
    private var generation = 0
    private var phase = Phase.Idle

    /** Bumped for an account every time it is saved; a write only settles the account when it did not change. */
    private val saveGen = HashMap<String, Long>()

    /** A save arrived while an attempt was running: one more attempt follows when it ends normally. */
    private var savedDuringAttempt = false
    private var recoveryFailures = 0
    private var recoveryGaveUp = false
    private var lastRecoveryCode: ChaldeaSyncCode? = null

    @Volatile private var active = false
    @Volatile private var scriptRunning = false
    @Volatile private var scriptStamp = 0L

    init {
        scope.launch {
            for (ignored in wake) {
                while (true) {
                    val e = events.poll() ?: break
                    try {
                        handle(e)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        // Never escapes: a failed event only loses that event.
                    }
                }
            }
        }
    }

    // ---- hooks: non-blocking, no Context, no callbacks ----

    /** Called after accounts were actually written to disk. */
    fun onAccountSaved(ids: Collection<String>) {
        if (!active || ids.isEmpty()) return
        savedIds.addAll(ids)
        if (savedPosted.compareAndSet(false, true)) post(Event.Saved)
    }

    /** [id] null means every account was deleted. */
    fun onAccountDeleted(id: String?) = post(Event.Deleted(id))

    fun onScriptStart() {
        if (!active) return
        scriptRunning = true
        _scriptRunning.value = true
        scriptStamp = clock()
        post(Event.ScriptStart)
    }

    fun onScriptEnd() {
        if (!active) return
        scriptRunning = false
        _scriptRunning.value = false
        post(Event.ScriptEnd)
    }

    /** FGA came to the foreground: restore what was persisted and try again. */
    fun onForeground() = post(Event.Foreground)

    /** The switch, log collection or consent changed (also called once at process start). */
    fun onSettingsChanged() = post(Event.Settings)

    /** The access changed (Shizuku binder arrived or died, permission result, resume, or the root state). */
    fun onAccessChanged() = post(Event.AccessChanged)

    private fun post(e: Event) {
        events.add(e)
        wake.trySend(Unit)
    }

    private fun scriptActive(): Boolean {
        if (!scriptRunning) return false
        if (clock() - scriptStamp > timing.scriptStaleMs) {
            scriptRunning = false
            _scriptRunning.value = false
            return false
        }
        return true
    }

    // ---- events ----

    private suspend fun handle(e: Event) {
        when (e) {
            Event.Saved -> {
                savedPosted.set(false)
                val ids = savedIds.toList()
                savedIds.removeAll(ids.toSet())
                if (!active || ids.isEmpty()) return
                val now = clock()
                val wasStopped = synchronized(lock) {
                    lastActive = now
                    val prev = stopped
                    stopped = false
                    pending += ids
                    ids.forEach { saveGen.merge(it, 1L, Long::plus) }
                    prev
                }
                if (wasStopped) {
                    val snapshot = synchronized(lock) { pending.toSet() }
                    runCatching { store.update { it.copy(pending = snapshot, stopped = false) } }
                } else {
                    persistPending()
                }
                if (scriptActive()) return setTransient(ChaldeaSyncStatus.WaitingScript)
                val idle = synchronized(lock) {
                    val quiet = phase == Phase.Idle || phase == Phase.Quiet || phase == Phase.Grace
                    if (!quiet) savedDuringAttempt = true
                    quiet
                }
                if (idle) scheduleAttempt(timing.quietMs, Phase.Quiet)
            }
            is Event.Deleted -> {
                synchronized(lock) {
                    if (e.id == null) {
                        pending.clear()
                        saveGen.clear()
                    } else {
                        pending -= e.id
                        saveGen.remove(e.id)
                    }
                }
                if (e.id == null) savedIds.clear() else savedIds.remove(e.id)
                runCatching { store.update { d -> if (e.id == null) d.copy(pending = emptySet(), syncedPatchHash = emptyMap()) else d.copy(pending = d.pending - e.id, syncedPatchHash = d.syncedPatchHash - e.id) } }
            }
            Event.ScriptStart -> {
                cancelAttempt()
                if (hasWork()) setTransient(ChaldeaSyncStatus.WaitingScript)
            }
            Event.ScriptEnd -> if (active) {
                val now = clock()
                val wasStopped = synchronized(lock) {
                    lastActive = now
                    stopped.also { stopped = false }
                }
                if (wasStopped) persistStopped(false)
                if (hasWork()) scheduleAttempt(timing.graceMs, Phase.Grace)
            }
            Event.Foreground -> if (env.enabled()) {
                if (!active) return handle(Event.Settings)
                restore(includeStopped = false)
                val isStopped = synchronized(lock) { stopped }
                if (!isStopped && hasWork()) scheduleAttempt(0, Phase.Quiet)
            }
            Event.Settings -> {
                val on = env.enabled()
                if (!on) {
                    active = false
                    cancelAttempt()
                    synchronized(lock) {
                        pending.clear()
                        backgroundSince = null
                        lastObservation = null
                        lastActive = null
                        stopped = false
                    }
                    runCatching { store.update { it.copy(backgroundSince = null, lastObservation = null, stopped = false) } }
                    resetStatuses()
                } else if (!active) {
                    active = true
                    resetStatuses()
                    restore(includeStopped = true)
                    val isStopped = synchronized(lock) { stopped }
                    if (!isStopped && hasWork()) {
                        synchronized(lock) { lastActive = clock() }
                        scheduleAttempt(0, Phase.Quiet)
                    }
                } else {
                    // Another switch (log collection, consent, close minutes) changed: keep going if not stopped.
                    val isStopped = synchronized(lock) { stopped }
                    if (!isStopped && hasWork()) scheduleAttempt(0, Phase.Quiet)
                }
            }
            Event.AccessChanged -> {
                if (!active) return
                if (env.access().ready && hasWork() && !scriptActive()) {
                    val canStart = synchronized(lock) { !stopped && phase == Phase.Idle }
                    if (canStart) scheduleAttempt(0, Phase.Quiet)
                }
            }
        }
    }

    private fun hasWork(): Boolean =
        synchronized(lock) { pending.isNotEmpty() } || (store.readInflight() != null && !synchronized(lock) { recoveryGaveUp })

    /** A switch toggle clears every status and the recovery retry count. */
    private fun resetStatuses() {
        synchronized(lock) {
            recoveryFailures = 0
            recoveryGaveUp = false
            lastRecoveryCode = null
        }
        _results.value = emptyMap()
        _status.value = ChaldeaSyncStatus.Idle
    }

    private fun restore(includeStopped: Boolean) {
        val d = store.read()
        val now = clock()
        synchronized(lock) {
            pending += d.pending
            if (includeStopped) stopped = d.stopped
            if (!stopped && backgroundSince == null && d.backgroundSince != null && d.lastObservation != null &&
                d.backgroundSince <= now && d.lastObservation <= now &&
                now - d.lastObservation <= timing.pollSlowMs + timing.gapMs
            ) {
                backgroundSince = d.backgroundSince
                lastObservation = d.lastObservation
                lastInterval = timing.pollSlowMs
            }
        }
    }

    private fun persistPending() {
        val snapshot = synchronized(lock) { pending.toSet() }
        runCatching { store.update { it.copy(pending = snapshot) } }
    }

    private fun persistStopped(value: Boolean) {
        runCatching { store.update { it.copy(stopped = value) } }
    }

    private fun persistBackground() {
        val (since, obs) = synchronized(lock) { backgroundSince to lastObservation }
        runCatching { store.update { it.copy(backgroundSince = since, lastObservation = obs) } }
    }

    private fun cancelAttempt() {
        val job = synchronized(lock) { attemptJob }
        job?.cancel()
    }

    private fun scheduleAttempt(initialDelay: Long, first: Phase) {
        synchronized(lock) {
            if (phase == Phase.Writing) return
            attemptJob?.cancel()
            val gen = ++generation
            phase = first
            attemptJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                var normal = false
                var again = false
                try {
                    if (initialDelay > 0) delay(initialDelay)
                    runAttempt(gen)
                    normal = true
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    // The attempt is best effort: a failure here must not reach anything else.
                    setResult(ChaldeaSyncStatus.Failed(ChaldeaSyncCode.ServiceError))
                } finally {
                    synchronized(lock) {
                        if (gen == generation) {
                            phase = Phase.Idle
                            attemptJob = null
                            again = normal && savedDuringAttempt && active && !stopped
                            savedDuringAttempt = false
                        }
                    }
                    // A save that came in while this attempt ran must not wait for the next trigger.
                    if (again) scheduleAttempt(timing.quietMs, Phase.Quiet)
                }
            }.also { it.start() }
        }
    }

    private fun setPhase(gen: Int, p: Phase) = synchronized(lock) { if (gen == generation) phase = p }

    // ---- status ----

    private fun setResult(s: ChaldeaSyncStatus) {
        _status.value = s
    }

    /** The result of the account of [server]; replaced only by that server's next result. */
    private fun setServerResult(server: ChaldeaServer, s: ChaldeaSyncStatus) {
        _results.value = _results.value + (server to s)
        if (s is ChaldeaSyncStatus.Synced && _status.value.persistent) _status.value = ChaldeaSyncStatus.Idle
    }

    /** Waiting statuses never hide a persistent failure. */
    private fun setTransient(s: ChaldeaSyncStatus) {
        if (!_status.value.persistent) _status.value = s
    }

    // ---- the attempt ----

    private fun interval(waitStart: Long, now: Long) = if (now - waitStart < timing.fastWindowMs) timing.pollFastMs else timing.pollSlowMs

    private suspend fun runAttempt(gen: Int) {
        val attemptStart = clock()
        synchronized(lock) { if (lastActive == null) lastActive = attemptStart }
        var waitStart: Long? = null
        // The patch is built and compared once, and again only when an account was saved since or right before a
        // write: waiting polls must not parse the account tables every minute.
        var resolved: List<Target>? = null
        var resolvedGens: Map<String, Long> = emptyMap()
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!env.enabled()) return
            if (scriptActive()) return setTransient(ChaldeaSyncStatus.WaitingScript)
            val access = env.access()
            if (!access.ready) return setResult(ChaldeaSyncStatus.ShizukuUnavailable(access.shizuku))
            val gensNow = synchronized(lock) { pending.associateWith { saveGen[it] ?: 0L } }
            if (resolved == null || gensNow != resolvedGens) {
                resolved = resolveTargets(null, false)
                resolvedGens = synchronized(lock) { pending.associateWith { saveGen[it] ?: 0L } }
                synchronized(lock) { savedDuringAttempt = false }
            }
            val targets = resolved
            val inflight = store.readInflight() != null
            if (inflight && synchronized(lock) { recoveryGaveUp }) {
                return setResult(ChaldeaSyncStatus.RecoveryBlocked(lastRecoveryCode ?: ChaldeaSyncCode.ServiceError))
            }
            if (targets.isEmpty() && !inflight) return
            setPhase(gen, Phase.Waiting)
            val now0 = clock()
            val start = waitStart ?: now0.also { waitStart = it }
            val session = env.openSession()
            var nextDelay: Long
            if (session == null) {
                setTransient(ChaldeaSyncStatus.CannotBind)
                nextDelay = interval(start, now0)
            } else {
                try {
                    val outcome = step(session, gen, targets, interval(start, now0))
                    if (outcome.finished) return
                    nextDelay = outcome.delay
                } finally {
                    session.close()
                }
            }
            noteObservation(nextDelay)
            delay(nextDelay)
        }
    }

    private class Step(val finished: Boolean, val delay: Long = 0)

    private fun noteObservation(interval: Long) {
        synchronized(lock) {
            lastObservation = clock()
            lastInterval = interval
        }
        persistBackground()
    }

    private fun resetBackground() {
        synchronized(lock) {
            backgroundSince = null
            lastObservation = null
        }
        persistBackground()
    }

    /** One poll with an open session. */
    private suspend fun step(session: ChaldeaSyncSession, gen: Int, targets: List<Target>, interval: Long): Step {
        val state = session.state().state
        val now = clock()
        val stopMin = env.stopMinutes()
        val stopMs = stopMin * 60_000L
        val isActiveNow = env.screenOn() && (state == ChaldeaRunState.Visible || session.gameVisible())
        if (isActiveNow) {
            synchronized(lock) { lastActive = now }
        }
        val last = synchronized(lock) { lastActive }
        val inactive = last == null || now - last >= stopMs
        if (inactive) {
            return endSession(session, gen, state, interval, stopMin)
        }
        val boundedInterval = minOf(interval, maxOf(stopMs - (now - last), 1L))
        when (state) {
            ChaldeaRunState.NotRunning -> return writePass(session, gen, boundedInterval)
            ChaldeaRunState.Visible, ChaldeaRunState.Unknown -> {
                resetBackground()
                setTransient(if (state == ChaldeaRunState.Visible) ChaldeaSyncStatus.WaitingVisible else ChaldeaSyncStatus.StateUnknown)
                return Step(false, boundedInterval)
            }
            ChaldeaRunState.Background -> {
                val since = synchronized(lock) {
                    val lastObs = lastObservation
                    val restart = backgroundSince == null || lastObs == null || now - lastObs > lastInterval + timing.gapMs
                    if (restart) backgroundSince = now
                    backgroundSince!!
                }
                persistBackground()
                val closeMin = env.closeMinutes()
                val closeMs = closeMin * 60_000L
                val elapsed = now - since
                if (closeMs > 0 && elapsed >= closeMs) {
                    // Re-check right before closing: the user may have opened Chaldea again.
                    return when (session.state().state) {
                        ChaldeaRunState.NotRunning -> writePass(session, gen, boundedInterval)
                        ChaldeaRunState.Background -> {
                            if (session.forceStop().state == ChaldeaRunState.NotRunning) writePass(session, gen, boundedInterval)
                            else {
                                setResult(ChaldeaSyncStatus.StateUnknown)
                                Step(false, boundedInterval)
                            }
                        }
                        else -> {
                            resetBackground()
                            setTransient(ChaldeaSyncStatus.WaitingVisible)
                            Step(false, boundedInterval)
                        }
                    }
                }
                setTransient(ChaldeaSyncStatus.WaitingBackground((elapsed / 60_000).toInt(), closeMin))
                val wait = if (closeMs > 0) minOf(boundedInterval, closeMs - elapsed) else boundedInterval
                return Step(false, maxOf(wait, 1))
            }
        }
    }

    /** Session became inactive: run one final pass if allowed, then stop until a new trigger. */
    private suspend fun endSession(
        session: ChaldeaSyncSession,
        gen: Int,
        state: ChaldeaRunState,
        interval: Long,
        stopMin: Int,
    ): Step {
        val activeBefore = synchronized(lock) { lastActive }
        when (state) {
            ChaldeaRunState.NotRunning -> writePass(session, gen, interval)
            ChaldeaRunState.Background -> if (env.closeMinutes() > 0) {
                when (session.state().state) {
                    ChaldeaRunState.NotRunning -> writePass(session, gen, interval)
                    ChaldeaRunState.Background -> {
                        if (session.forceStop().state == ChaldeaRunState.NotRunning) {
                            writePass(session, gen, interval)
                        }
                    }
                    else -> Unit
                }
            }
            ChaldeaRunState.Visible, ChaldeaRunState.Unknown -> Unit
        }
        if (scriptActive()) {
            setTransient(ChaldeaSyncStatus.WaitingScript)
            return Step(true)
        }
        return stopSessionIfNotReactivated(activeBefore, stopMin, interval)
    }

    private fun stopSessionIfNotReactivated(activeBefore: Long?, stopMin: Int, interval: Long): Step {
        val didStop = synchronized(lock) {
            val reactivated = savedDuringAttempt || lastActive != activeBefore
            if (reactivated) {
                false
            } else {
                stopped = true
                backgroundSince = null
                lastObservation = null
                savedDuringAttempt = false
                true
            }
        }
        if (!didStop) return Step(false, interval)
        if (synchronized(lock) { stopped }) {
            runCatching {
                store.update { d ->
                    if (synchronized(lock) { stopped }) {
                        d.copy(backgroundSince = null, lastObservation = null, stopped = true)
                    } else d
                }
            }
            if (synchronized(lock) { stopped }) {
                setTransient(ChaldeaSyncStatus.Stopped(stopMin))
            }
        }
        return Step(true)
    }

    /** Chaldea is not running: recover an interrupted write, then write every target in this one session. */
    private suspend fun writePass(session: ChaldeaSyncSession, gen: Int, interval: Long): Step {
        resetBackground()
        setPhase(gen, Phase.Writing)
        if (!recoverInflight(session)) return Step(true)
        val gameData = refreshGameData(session)
        val targets = resolveTargets(null, false, gameData)
        var retry = false
        for (t in targets) {
            if (scriptActive()) return Step(true).also { setTransient(ChaldeaSyncStatus.WaitingScript) }
            when (writeOne(session, t)) {
                Pass.Abort -> return Step(true)
                Pass.Retry -> retry = true
                Pass.Done -> {}
            }
        }
        setPhase(gen, Phase.Waiting)
        // An account that was saved during its own write stays pending: poll again and write the newer patch.
        val left = synchronized(lock) { pending.isNotEmpty() }
        return if (retry || left) Step(false, interval) else Step(true)
    }

    private fun genOf(id: String): Long = synchronized(lock) { saveGen[id] ?: 0L }

    /** Removes [id] from pending unless it was saved again since [gen] was read. */
    private fun settleIf(id: String, gen: Long) {
        synchronized(lock) { if ((saveGen[id] ?: 0L) == gen) pending -= id }
        persistPending()
    }

    private suspend fun refreshGameData(session: ChaldeaSyncSession): ChaldeaGameData? {
        val cached = store.readGameData()
        return when (val result = session.gameData(cached?.version)) {
            is ChaldeaGameDataResult.Loaded -> {
                val newData = result.data
                val contentChanged = cached == null || !newData.sameContentAs(cached)
                if (contentChanged || newData.version != cached.version) {
                    runCatching { store.writeGameData(newData) }
                }
                if (contentChanged) {
                    val currentIds = accounts.currentIds().values
                    if (currentIds.isNotEmpty()) {
                        synchronized(lock) {
                            pending += currentIds
                            currentIds.forEach { saveGen.merge(it, 1L, Long::plus) }
                        }
                        persistPending()
                    }
                }
                newData
            }
            is ChaldeaGameDataResult.Unchanged, is ChaldeaGameDataResult.Error -> cached
        }
    }

    private fun resolveTargets(
        ids: Collection<String>?,
        ignoreHash: Boolean,
        gameData: ChaldeaGameData? = store.readGameData(),
    ): List<Target> {
        val source = ids ?: synchronized(lock) { pending.toList() }
        val synced = store.read().syncedPatchHash
        val current = accounts.currentIds()
        val numbers = accounts.collectionNumbers()
        val out = ArrayList<Target>()
        for (id in source) {
            val gen = genOf(id)
            val loaded = accounts.load(id)
            if (loaded == null) {
                settleIf(id, gen)
                continue
            }
            val patch = ChaldeaPatch.from(loaded.state, loaded.server, numbers, gameData)
            if (patch.friendCode == null) {
                setServerResult(loaded.server, ChaldeaSyncStatus.AccountNotIdentified)
                settleIf(id, gen)
                continue
            }
            val json = patch.toJson()
            val hash = sha256Hex(json.toByteArray(Charsets.UTF_8))
            if (!ignoreHash && synced[id] == hash) {
                settleIf(id, gen)
                continue
            }
            out += Target(
                id,
                loaded.server,
                patch,
                hash,
                json.length,
                allowCreate = current[loaded.server] == id,
                gen = gen,
            )
        }
        return out
    }

    /**
     * Counts a failed recovery. After [Timing.maxRecoveryFailures] of them recovery gives up and, deliberately, every
     * sync stays blocked (nothing is written over a file whose state is unknown) until the switch is toggled or "sync
     * now" is pressed. The backup it points at is kept.
     */
    private fun recoveryFailed(code: ChaldeaSyncCode, status: ChaldeaSyncStatus = ChaldeaSyncStatus.RecoveryBlocked(code)) {
        synchronized(lock) {
            recoveryFailures++
            lastRecoveryCode = code
            if (recoveryFailures >= timing.maxRecoveryFailures) recoveryGaveUp = true
        }
        setResult(status)
    }

    private fun recoveryDone() {
        store.clearInflight()
        synchronized(lock) {
            recoveryFailures = 0
            recoveryGaveUp = false
            lastRecoveryCode = null
        }
    }

    /**
     * Undoes or settles a write that was interrupted. The backup folder it points at is never deleted unless the
     * files are known to be fine or were restored. @return false when the attempt must stop.
     */
    private suspend fun recoverInflight(session: ChaldeaSyncSession): Boolean {
        val inflight = store.readInflight() ?: return true
        val temp = backups.incoming()
        val curDir = backups.currentCheckDir()
        try {
            val outcome = try {
                FileOutputStream(temp).use { session.prepare(it) }
            } catch (e: IOException) {
                recoveryFailed(ChaldeaSyncCode.Unreadable)
                return false
            }
            val r = outcome.result
            val damaged = r.code in setOf(
                ChaldeaSyncCode.InvalidJson, ChaldeaSyncCode.NotObject, ChaldeaSyncCode.MissingUsers,
                ChaldeaSyncCode.UnknownVersion, ChaldeaSyncCode.UnexpectedShape,
            ) || (r.ok && r.base == ChaldeaBaseSource.Bak)
            if (!r.ok && !damaged) {
                // Chaldea running or unknown: wait, this is not a failure. Anything else is counted and shown.
                if (r.code != ChaldeaSyncCode.ChaldeaRunning && r.code != ChaldeaSyncCode.StateUnknown) recoveryFailed(r.code)
                return false
            }
            if (!damaged) {
                // userdata.json is valid, but the interrupted write also covers .bak (written second): it must be
                // its own original or the new userdata.json. Anything else (cut short, empty) means interrupted.
                val consistent = bakConsistent(inflight, outcome, temp, curDir)
                if (consistent == null) {
                    recoveryFailed(ChaldeaSyncCode.Unreadable)
                    return false
                }
                if (consistent) {
                    // The write finished; the account is synced again to be sure.
                    recoveryDone()
                    backups.release(inflight.packageName)
                    return true
                }
            }
            return restoreFromBundle(session, inflight)
        } finally {
            temp.delete()
            curDir.deleteRecursively()
        }
    }

    /** @return null when the current files or the backup cannot be read, so nothing can be judged */
    private fun bakConsistent(
        inflight: ChaldeaSyncStore.Inflight,
        outcome: ChaldeaSyncSession.PrepareOutcome,
        currentBundle: File,
        curDir: File,
    ): Boolean? {
        val orig = File(inflight.backupPath)
        if (!orig.isDirectory) return null
        val origBak = File(orig, ChaldeaBackupBundle.BAK)
        if (!origBak.isFile) return true // there was no .bak: the write never touched one
        if (!outcome.backupMatches) return null
        val split = try {
            currentBundle.inputStream().use { ChaldeaBackupBundle.split(it, curDir) }
        } catch (e: IOException) {
            false
        }
        if (!split) return null
        val curBak = File(curDir, ChaldeaBackupBundle.BAK)
        val curJson = File(curDir, ChaldeaBackupBundle.JSON)
        if (!curBak.isFile) return false
        return backups.sameContent(curBak, origBak) || backups.sameContent(curBak, curJson)
    }

    private suspend fun restoreFromBundle(session: ChaldeaSyncSession, inflight: ChaldeaSyncStore.Inflight): Boolean {
        val folder = File(inflight.backupPath)
        val scratch = backups.restoreScratch()
        val restored = try {
            val rec = backups.bundleOf(folder, scratch)
            if (folder.isDirectory && rec.sha256 == inflight.bundleSha256 && rec.size == inflight.bundleSize) {
                withContext(NonCancellable) { session.restore(scratch, rec.sha256, inflight.packageName) }
            } else null
        } catch (e: IOException) {
            null
        } finally {
            scratch.delete()
        }
        return if (restored != null && restored.ok) {
            recoveryDone()
            backups.release(inflight.packageName)
            setResult(ChaldeaSyncStatus.FailedRestored)
            true
        } else {
            recoveryFailed(restored?.code ?: ChaldeaSyncCode.Unreadable, ChaldeaSyncStatus.RestoreFailed(inflight.backupPath))
            false
        }
    }

    private suspend fun writeOne(session: ChaldeaSyncSession, t: Target): Pass {
        if (t.jsonLength > ChaldeaPatch.MAX_JSON_CHARS) {
            return afterFailure(t, ChaldeaSyncCode.PatchTooLarge)
        }
        val incoming = backups.incoming()
        val outcome = try {
            FileOutputStream(incoming).use { session.prepare(it) }
        } catch (e: IOException) {
            incoming.delete()
            setServerResult(t.server, ChaldeaSyncStatus.BackupFailed)
            return Pass.Abort
        }
        val prepared = outcome.result
        if (!prepared.ok) {
            incoming.delete()
            return afterFailure(t, prepared.code)
        }
        val baseSha = prepared.sha256
        val bundleSize = prepared.bundleSize
        val bundleSha = prepared.bundleSha256
        val pkg = prepared.packageName
        if (!outcome.backupMatches || baseSha == null || bundleSize == null || bundleSha == null || pkg == null ||
            !backups.verify(incoming, bundleSize, bundleSha)
        ) {
            incoming.delete()
            setServerResult(t.server, ChaldeaSyncStatus.BackupFailed)
            return Pass.Abort
        }
        val skipRotating = store.read().lastWritten[pkg] == baseSha
        val backupFolder = try {
            backups.adopt(pkg, incoming, skipRotating, wallClock())
        } catch (e: IOException) {
            incoming.delete()
            setServerResult(t.server, ChaldeaSyncStatus.BackupFailed)
            return Pass.Abort
        }
        try {
            store.writeInflight(ChaldeaSyncStore.Inflight(pkg, backupFolder.path, baseSha, bundleSize, bundleSha, wallClock()))
        } catch (e: IOException) {
            backups.release(pkg)
            setServerResult(t.server, ChaldeaSyncStatus.BackupFailed)
            return Pass.Abort
        }
        // From here the commit finishes even if a script starts or the switch is turned off.
        return withContext(NonCancellable) {
            val r = session.commit(t.patch, baseSha, t.allowCreate, pkg, bundleSha)
            when (r.code) {
                ChaldeaSyncCode.Ok -> {
                    val after = runCatching { session.state().state }.getOrDefault(ChaldeaRunState.Unknown)
                    finishWrite(pkg)
                    if (after != ChaldeaRunState.NotRunning) {
                        setResult(ChaldeaSyncStatus.ChaldeaStartedDuring)
                        Pass.Retry
                    } else {
                        runCatching {
                            store.update {
                                it.copy(
                                    syncedPatchHash = it.syncedPatchHash + (t.id to t.hash),
                                    lastWritten = it.lastWritten + (pkg to (r.sha256 ?: "")),
                                )
                            }
                        }
                        settleIf(t.id, t.gen)
                        setServerResult(
                            t.server,
                            ChaldeaSyncStatus.Synced(
                                wallClock(),
                                r.itemCount,
                                r.mysticCodeCount,
                                r.boardCount,
                                coins = r.coinCount,
                                craftEssences = r.ceCount,
                                commandCodes = r.ccCount,
                                unmapped = t.patch.unmapped,
                                created = r.created,
                                regionMismatch = r.regionMismatch,
                                packageName = r.packageName,
                                bothPackages = r.bothPackages,
                                servants = r.servantCount,
                            ),
                        )
                        Pass.Done
                    }
                }
                ChaldeaSyncCode.Restored -> {
                    finishWrite(pkg)
                    setServerResult(t.server, ChaldeaSyncStatus.FailedRestored)
                    settleIf(t.id, t.gen)
                    Pass.Done
                }
                ChaldeaSyncCode.RestoreFailed -> {
                    // The in-flight record stays: the next attempt tries the restore again.
                    setServerResult(t.server, ChaldeaSyncStatus.RestoreFailed(backupFolder.path))
                    settleIf(t.id, t.gen)
                    Pass.Abort
                }
                else -> {
                    finishWrite(pkg)
                    afterFailure(t, r.code)
                }
            }
        }
    }

    private fun finishWrite(pkg: String) {
        store.clearInflight()
        backups.release(pkg)
    }

    /** Maps a refusal to a status; says whether the account stays pending. */
    private fun afterFailure(t: Target, code: ChaldeaSyncCode): Pass {
        when (code) {
            ChaldeaSyncCode.ChaldeaRunning -> return Pass.Retry
            ChaldeaSyncCode.StateUnknown -> {
                setResult(ChaldeaSyncStatus.StateUnknown)
                return Pass.Retry
            }
            ChaldeaSyncCode.FileChanged -> return Pass.Retry
            ChaldeaSyncCode.NoMatchingUser -> {}
            ChaldeaSyncCode.AccountNotIdentified -> setServerResult(t.server, ChaldeaSyncStatus.AccountNotIdentified)
            ChaldeaSyncCode.PatchTooLarge -> setServerResult(t.server, ChaldeaSyncStatus.PatchTooLarge)
            ChaldeaSyncCode.NotFound -> setServerResult(t.server, ChaldeaSyncStatus.FileNotFound)
            ChaldeaSyncCode.NoAccess -> setServerResult(t.server, ChaldeaSyncStatus.NoAccess)
            ChaldeaSyncCode.UnknownVersion -> setServerResult(t.server, ChaldeaSyncStatus.UnknownVersion)
            ChaldeaSyncCode.OwnerChanged -> setServerResult(t.server, ChaldeaSyncStatus.OwnerChanged)
            ChaldeaSyncCode.Unreadable, ChaldeaSyncCode.InvalidJson, ChaldeaSyncCode.NotObject,
            ChaldeaSyncCode.MissingUsers, ChaldeaSyncCode.UnexpectedShape -> setServerResult(t.server, ChaldeaSyncStatus.FileUnreadable)
            else -> setServerResult(t.server, ChaldeaSyncStatus.Failed(code))
        }
        settleIf(t.id, t.gen)
        return Pass.Done
    }

    // ---- sync now ----

    /**
     * Writes the current account of each server now, ignoring the patch hash. [confirmed] is the state the user
     * agreed to close; null on the first call. A Chaldea in a state that was not confirmed (or that is
     * [ChaldeaRunState.Unknown]) is never closed.
     */
    suspend fun syncNow(confirmed: ChaldeaRunState?): ChaldeaSyncNowOutcome {
        val job = scope.async { manualSync(confirmed) }
        synchronized(lock) {
            attemptJob?.cancel()
            generation++
            attemptJob = job
            phase = Phase.Waiting
        }
        return try {
            job.await()
        } catch (c: CancellationException) {
            if (!currentCoroutineContext().isActive) throw c
            ChaldeaSyncNowOutcome.Unavailable(ChaldeaSyncStatus.WaitingScript)
        } finally {
            synchronized(lock) {
                if (attemptJob === job) {
                    attemptJob = null
                    phase = Phase.Idle
                }
            }
        }
    }

    private suspend fun manualSync(confirmed: ChaldeaRunState?): ChaldeaSyncNowOutcome {
        if (!env.enabled()) return ChaldeaSyncNowOutcome.Unavailable(ChaldeaSyncStatus.Idle)
        if (scriptActive()) return ChaldeaSyncNowOutcome.Unavailable(ChaldeaSyncStatus.WaitingScript)
        val access = env.access()
        if (!access.ready) {
            val s = ChaldeaSyncStatus.ShizukuUnavailable(access.shizuku)
            setResult(s)
            return ChaldeaSyncNowOutcome.Unavailable(s)
        }
        // A manual sync is the user's own action: it also gets a fresh start on a blocked recovery.
        synchronized(lock) {
            recoveryFailures = 0
            recoveryGaveUp = false
        }
        val cachedGameData = store.readGameData()
        val initialTargets = resolveTargets(accounts.currentIds().values.toList(), true, cachedGameData)
        if (initialTargets.isEmpty() && store.readInflight() == null) return ChaldeaSyncNowOutcome.Done
        val session = env.openSession() ?: return ChaldeaSyncNowOutcome.Unavailable(ChaldeaSyncStatus.CannotBind).also { setResult(it.status) }
        try {
            val state = session.state().state
            if (state == ChaldeaRunState.Unknown) {
                setResult(ChaldeaSyncStatus.StateUnknown)
                return ChaldeaSyncNowOutcome.Unavailable(ChaldeaSyncStatus.StateUnknown)
            }
            if (state != ChaldeaRunState.NotRunning) {
                val covered = when (state) {
                    ChaldeaRunState.Background -> confirmed == ChaldeaRunState.Background || confirmed == ChaldeaRunState.Visible
                    else -> confirmed == ChaldeaRunState.Visible
                }
                if (!covered) return ChaldeaSyncNowOutcome.NeedsConfirm(state)
                if (session.forceStop().state != ChaldeaRunState.NotRunning) {
                    setResult(ChaldeaSyncStatus.StateUnknown)
                    return ChaldeaSyncNowOutcome.Unavailable(ChaldeaSyncStatus.StateUnknown)
                }
            }
            resetBackground()
            if (!recoverInflight(session)) return ChaldeaSyncNowOutcome.Done
            val gameData = refreshGameData(session)
            val gameDataChanged = gameData != null && (cachedGameData == null || !gameData.sameContentAs(cachedGameData))
            val targets = if (gameDataChanged) {
                resolveTargets(accounts.currentIds().values.toList(), true, gameData)
            } else {
                initialTargets
            }
            for (t in targets) if (writeOne(session, t) == Pass.Abort) break
            return ChaldeaSyncNowOutcome.Done
        } finally {
            session.close()
        }
    }
}
