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
import java.util.concurrent.ConcurrentLinkedQueue

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
 * for the poll.
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
    )

    private sealed interface Event {
        data class Saved(val ids: Collection<String>) : Event
        data class Deleted(val id: String?) : Event
        data object ScriptStart : Event
        data object ScriptEnd : Event
        data object Foreground : Event
        data object Settings : Event
        data object ShizukuChanged : Event
    }

    private enum class Phase { Idle, Quiet, Grace, Waiting, Writing }
    private enum class Pass { Done, Retry, Abort }
    private class Target(val id: String, val patch: ChaldeaPatch, val hash: String, val allowCreate: Boolean)

    private val _status = MutableStateFlow<ChaldeaSyncStatus>(ChaldeaSyncStatus.Idle)
    val status: StateFlow<ChaldeaSyncStatus> = _status.asStateFlow()

    private val _scriptRunning = MutableStateFlow(false)

    /** Whether a script is running, for the "sync now" button. */
    val scriptRunningState: StateFlow<Boolean> = _scriptRunning.asStateFlow()

    private val events = ConcurrentLinkedQueue<Event>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Guards the small state below. Never held across a suspension, a binder call or file work. */
    private val lock = Any()
    private val pending = LinkedHashSet<String>()
    private var backgroundSince: Long? = null
    private var lastObservation: Long? = null
    private var lastInterval: Long = 0
    private var attemptJob: Job? = null
    private var generation = 0
    private var phase = Phase.Idle

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
        post(Event.Saved(ids.toList()))
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

    /** The Shizuku state changed (binder arrived or died, permission result, resume). */
    fun onShizukuChanged() = post(Event.ShizukuChanged)

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
            is Event.Saved -> {
                if (!active) return
                synchronized(lock) { pending += e.ids }
                persistPending()
                if (scriptActive()) return setTransient(ChaldeaSyncStatus.WaitingScript)
                val idle = synchronized(lock) { phase == Phase.Idle || phase == Phase.Quiet || phase == Phase.Grace }
                if (idle) scheduleAttempt(timing.quietMs, Phase.Quiet)
            }
            is Event.Deleted -> {
                synchronized(lock) { if (e.id == null) pending.clear() else pending -= e.id }
                runCatching { store.update { d -> if (e.id == null) d.copy(pending = emptySet(), syncedPatchHash = emptyMap()) else d.copy(pending = d.pending - e.id, syncedPatchHash = d.syncedPatchHash - e.id) } }
            }
            Event.ScriptStart -> {
                cancelAttempt()
                if (hasWork()) setTransient(ChaldeaSyncStatus.WaitingScript)
            }
            Event.ScriptEnd -> if (active && hasWork()) scheduleAttempt(timing.graceMs, Phase.Grace)
            Event.Foreground -> if (env.enabled()) {
                if (!active) return handle(Event.Settings)
                restore()
                if (hasWork()) scheduleAttempt(0, Phase.Quiet)
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
                    }
                    runCatching { store.update { it.copy(backgroundSince = null, lastObservation = null) } }
                    _status.value = ChaldeaSyncStatus.Idle
                } else if (!active) {
                    active = true
                    _status.value = ChaldeaSyncStatus.Idle
                    restore()
                    if (hasWork()) scheduleAttempt(0, Phase.Quiet)
                } else {
                    // Another switch (log collection, consent, close minutes) changed: keep going.
                    if (hasWork()) scheduleAttempt(0, Phase.Quiet)
                }
            }
            Event.ShizukuChanged -> {
                if (!active) return
                if (env.shizukuState() == ShizukuState.Ready && hasWork() && !scriptActive()) {
                    val idle = synchronized(lock) { phase == Phase.Idle }
                    if (idle) scheduleAttempt(0, Phase.Quiet)
                }
            }
        }
    }

    private fun hasWork(): Boolean = synchronized(lock) { pending.isNotEmpty() } || store.readInflight() != null

    private fun restore() {
        val d = store.read()
        val now = clock()
        synchronized(lock) {
            pending += d.pending
            if (backgroundSince == null && d.backgroundSince != null && d.lastObservation != null &&
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
                try {
                    if (initialDelay > 0) delay(initialDelay)
                    runAttempt(gen)
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
                        }
                    }
                }
            }.also { it.start() }
        }
    }

    private fun setPhase(gen: Int, p: Phase) = synchronized(lock) { if (gen == generation) phase = p }

    // ---- status ----

    private fun setResult(s: ChaldeaSyncStatus) {
        _status.value = s
    }

    /** Waiting statuses never hide a persistent failure. */
    private fun setTransient(s: ChaldeaSyncStatus) {
        if (!_status.value.persistent) _status.value = s
    }

    // ---- the attempt ----

    private fun interval(waitStart: Long, now: Long) = if (now - waitStart < timing.fastWindowMs) timing.pollFastMs else timing.pollSlowMs

    private suspend fun runAttempt(gen: Int) {
        var waitStart: Long? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!env.enabled()) return
            if (scriptActive()) return setTransient(ChaldeaSyncStatus.WaitingScript)
            val shizuku = env.shizukuState()
            if (shizuku != ShizukuState.Ready) return setResult(ChaldeaSyncStatus.ShizukuUnavailable(shizuku))
            val targets = resolveTargets(null, false)
            if (targets.isEmpty() && store.readInflight() == null) return
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
        when (state) {
            ChaldeaRunState.NotRunning -> return writePass(session, gen, interval)
            ChaldeaRunState.Visible, ChaldeaRunState.Unknown -> {
                resetBackground()
                setTransient(if (state == ChaldeaRunState.Visible) ChaldeaSyncStatus.WaitingVisible else ChaldeaSyncStatus.StateUnknown)
                return Step(false, interval)
            }
            ChaldeaRunState.Background -> {
                val since = synchronized(lock) {
                    val last = lastObservation
                    val restart = backgroundSince == null || last == null || now - last > lastInterval + timing.gapMs
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
                        ChaldeaRunState.NotRunning -> writePass(session, gen, interval)
                        ChaldeaRunState.Background -> {
                            if (session.forceStop().state == ChaldeaRunState.NotRunning) writePass(session, gen, interval)
                            else {
                                setResult(ChaldeaSyncStatus.StateUnknown)
                                Step(false, interval)
                            }
                        }
                        else -> {
                            resetBackground()
                            setTransient(ChaldeaSyncStatus.WaitingVisible)
                            Step(false, interval)
                        }
                    }
                }
                setTransient(ChaldeaSyncStatus.WaitingBackground((elapsed / 60_000).toInt(), closeMin))
                val wait = if (closeMs > 0) minOf(interval, closeMs - elapsed) else interval
                return Step(false, maxOf(wait, 1))
            }
        }
    }

    /** Chaldea is not running: recover an interrupted write, then write every target in this one session. */
    private suspend fun writePass(session: ChaldeaSyncSession, gen: Int, interval: Long): Step {
        resetBackground()
        setPhase(gen, Phase.Writing)
        if (!recoverInflight(session)) return Step(true)
        val targets = resolveTargets(null, false)
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
        return if (retry) Step(false, interval) else Step(true)
    }

    private fun settle(id: String) {
        synchronized(lock) { pending -= id }
        persistPending()
    }

    private fun resolveTargets(ids: Collection<String>?, ignoreHash: Boolean): List<Target> {
        val source = ids ?: synchronized(lock) { pending.toList() }
        val synced = store.read().syncedPatchHash
        val current = accounts.currentIds()
        val out = ArrayList<Target>()
        for (id in source) {
            val loaded = accounts.load(id)
            if (loaded == null) {
                settle(id)
                continue
            }
            val patch = ChaldeaPatch.from(loaded.state, loaded.server)
            if (patch.friendCode == null) {
                setResult(ChaldeaSyncStatus.AccountNotIdentified)
                settle(id)
                continue
            }
            val hash = patch.hash()
            if (!ignoreHash && synced[id] == hash) {
                settle(id)
                continue
            }
            out += Target(id, patch, hash, allowCreate = current[loaded.server] == id)
        }
        return out
    }

    /** Re-applies the stored base when the last write was interrupted. @return false when the attempt must stop. */
    private suspend fun recoverInflight(session: ChaldeaSyncSession): Boolean {
        val inflight = store.readInflight() ?: return true
        val temp = backups.incoming()
        val outcome = try {
            FileOutputStream(temp).use { session.prepare(it) }
        } catch (e: IOException) {
            temp.delete()
            setResult(ChaldeaSyncStatus.BackupFailed)
            return false
        }
        temp.delete()
        val r = outcome.result
        val damaged = r.code in setOf(
            ChaldeaSyncCode.InvalidJson, ChaldeaSyncCode.NotObject, ChaldeaSyncCode.MissingUsers,
            ChaldeaSyncCode.UnknownVersion, ChaldeaSyncCode.UnexpectedShape,
        ) || (r.ok && r.base == ChaldeaBaseSource.Bak)
        if (!r.ok && !damaged) {
            // Running or unknown: try again on a later poll. Anything else cannot be judged now.
            return false
        }
        if (!damaged) {
            // userdata.json is valid. If it is not the original it holds a finished write; it is synced again.
            store.clearInflight()
            backups.release(inflight.packageName)
            return true
        }
        val file = File(inflight.backupPath)
        val restored = if (file.isFile && backups.verify(file, file.length(), inflight.baseSha256)) {
            withContext(NonCancellable) { session.restore(file, inflight.baseSha256, inflight.packageName) }
        } else null
        return if (restored != null && restored.ok) {
            store.clearInflight()
            backups.release(inflight.packageName)
            setResult(ChaldeaSyncStatus.FailedRestored)
            true
        } else {
            setResult(ChaldeaSyncStatus.RestoreFailed(inflight.backupPath))
            false
        }
    }

    private suspend fun writeOne(session: ChaldeaSyncSession, t: Target): Pass {
        val incoming = backups.incoming()
        val outcome = try {
            FileOutputStream(incoming).use { session.prepare(it) }
        } catch (e: IOException) {
            incoming.delete()
            setResult(ChaldeaSyncStatus.BackupFailed)
            return Pass.Abort
        }
        val prepared = outcome.result
        if (!prepared.ok) {
            incoming.delete()
            return afterFailure(t.id, prepared.code)
        }
        val size = prepared.size
        val sha = prepared.sha256
        val pkg = prepared.packageName
        if (!outcome.backupMatches || size == null || sha == null || pkg == null || !backups.verify(incoming, size, sha)) {
            incoming.delete()
            setResult(ChaldeaSyncStatus.BackupFailed)
            return Pass.Abort
        }
        val skipRotating = store.read().lastWritten[pkg] == sha
        val backupFile = try {
            backups.adopt(pkg, incoming, skipRotating, wallClock())
        } catch (e: IOException) {
            incoming.delete()
            setResult(ChaldeaSyncStatus.BackupFailed)
            return Pass.Abort
        }
        try {
            store.writeInflight(ChaldeaSyncStore.Inflight(pkg, backupFile.path, sha, wallClock()))
        } catch (e: IOException) {
            backups.release(pkg)
            setResult(ChaldeaSyncStatus.BackupFailed)
            return Pass.Abort
        }
        // From here the commit finishes even if a script starts or the switch is turned off.
        return withContext(NonCancellable) {
            val r = session.commit(t.patch, sha, t.allowCreate, pkg)
            when (r.code) {
                ChaldeaSyncCode.Ok -> {
                    val after = runCatching { session.state().state }.getOrDefault(ChaldeaRunState.Unknown)
                    finishWrite(pkg)
                    if (after != ChaldeaRunState.NotRunning) {
                        setResult(ChaldeaSyncStatus.ChaldeaStartedDuring)
                        Pass.Retry
                    } else {
                        runCatching { store.update { it.copy(syncedPatchHash = it.syncedPatchHash + (t.id to t.hash), lastWritten = it.lastWritten + (pkg to (r.sha256 ?: ""))) } }
                        settle(t.id)
                        val both = r.bothPackages
                        setResult(
                            ChaldeaSyncStatus.Synced(
                                wallClock(), r.itemCount, r.mysticCodeCount, r.boardCount,
                                created = r.created, regionMismatch = r.regionMismatch,
                                packageName = r.packageName, bothPackages = both,
                            ),
                        )
                        Pass.Done
                    }
                }
                ChaldeaSyncCode.Restored -> {
                    finishWrite(pkg)
                    setResult(ChaldeaSyncStatus.FailedRestored)
                    settle(t.id)
                    Pass.Done
                }
                ChaldeaSyncCode.RestoreFailed -> {
                    // The in-flight record stays: the next attempt tries the restore again.
                    setResult(ChaldeaSyncStatus.RestoreFailed(backupFile.path))
                    settle(t.id)
                    Pass.Abort
                }
                else -> {
                    finishWrite(pkg)
                    afterFailure(t.id, r.code)
                }
            }
        }
    }

    private fun finishWrite(pkg: String) {
        store.clearInflight()
        backups.release(pkg)
    }

    /** Maps a refusal to a status; says whether the account stays pending. */
    private fun afterFailure(id: String, code: ChaldeaSyncCode): Pass {
        when (code) {
            ChaldeaSyncCode.ChaldeaRunning -> return Pass.Retry
            ChaldeaSyncCode.StateUnknown -> {
                setResult(ChaldeaSyncStatus.StateUnknown)
                return Pass.Retry
            }
            ChaldeaSyncCode.FileChanged -> return Pass.Retry
            ChaldeaSyncCode.NoMatchingUser -> {}
            ChaldeaSyncCode.AccountNotIdentified -> setResult(ChaldeaSyncStatus.AccountNotIdentified)
            ChaldeaSyncCode.NotFound -> setResult(ChaldeaSyncStatus.FileNotFound)
            ChaldeaSyncCode.NoAccess -> setResult(ChaldeaSyncStatus.NoAccess)
            ChaldeaSyncCode.UnknownVersion -> setResult(ChaldeaSyncStatus.UnknownVersion)
            ChaldeaSyncCode.Unreadable, ChaldeaSyncCode.InvalidJson, ChaldeaSyncCode.NotObject,
            ChaldeaSyncCode.MissingUsers, ChaldeaSyncCode.UnexpectedShape -> setResult(ChaldeaSyncStatus.FileUnreadable)
            else -> setResult(ChaldeaSyncStatus.Failed(code))
        }
        settle(id)
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
        val shizuku = env.shizukuState()
        if (shizuku != ShizukuState.Ready) {
            val s = ChaldeaSyncStatus.ShizukuUnavailable(shizuku)
            setResult(s)
            return ChaldeaSyncNowOutcome.Unavailable(s)
        }
        val targets = resolveTargets(accounts.currentIds().values.toList(), true)
        if (targets.isEmpty()) return ChaldeaSyncNowOutcome.Done
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
            for (t in targets) if (writeOne(session, t) == Pass.Abort) break
            return ChaldeaSyncNowOutcome.Done
        } finally {
            session.close()
        }
    }
}
