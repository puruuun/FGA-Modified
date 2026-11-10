package io.github.fate_grand_automata.capture

import android.content.Context
import io.github.fate_grand_automata.prefs.core.LogRetentionPrefsCore
import io.github.fate_grand_automata.battlelog.BattleLogEntry
import io.github.fate_grand_automata.scripts.capture.AccountState
import io.github.fate_grand_automata.widget.ApWidgetRefresh
import io.github.fate_grand_automata.widget.WidgetRefresh
import io.github.fate_grand_automata.scripts.capture.AccountSummary
import io.github.fate_grand_automata.scripts.capture.BodyDecoder
import io.github.fate_grand_automata.scripts.capture.CaptureNames
import io.github.fate_grand_automata.scripts.capture.CapturedBattle
import io.github.fate_grand_automata.scripts.capture.FriendRoster
import io.github.fate_grand_automata.scripts.capture.FriendTopStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import timber.log.Timber
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Why the capture VPN is not running; shown on the settings screen. */
enum class CaptureStopReason { User, Service, Revoked, Error, Unsupported }

/** Process-wide holder. Proxy responses, disk writes and account deletion share one lock. */
object CaptureTracker {
    @Volatile var starting = false
        internal set
    internal val battleInbox = CaptureBattleInbox()
    private const val SAVE_DELAY_SECONDS = 2L
    private val _rosterRevision = MutableStateFlow(0L)
    /** Changes whenever any friend roster was rewritten; the rosters themselves stay on disk (issue #258). */
    val rosterRevision: StateFlow<Long> = _rosterRevision.asStateFlow()
    private val _accounts = MutableStateFlow<List<CaptureAccount>>(emptyList())
    val accounts: StateFlow<List<CaptureAccount>> = _accounts.asStateFlow()
    private val _currentId = MutableStateFlow<String?>(null)
    val currentId: StateFlow<String?> = _currentId.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _tlsFailures = MutableStateFlow(0)
    val tlsFailures: StateFlow<Int> = _tlsFailures.asStateFlow()
    private val _stopReason = MutableStateFlow<CaptureStopReason?>(null)
    val stopReason: StateFlow<CaptureStopReason?> = _stopReason.asStateFlow()

    private fun lowPriority(name: String) = ThreadFactory { r ->
        Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            r.run()
        }, name).apply { isDaemon = true }
    }
    private val io = Executors.newSingleThreadScheduledExecutor(lowPriority("capture-save"))

    /**
     * All account and roster file work runs on this one low-priority thread (issue #258), never on the main or
     * script thread. Its tasks only touch files, so a caller may wait for it while holding [lock].
     */
    private val diskExecutor = Executors.newSingleThreadExecutor(lowPriority("capture-disk"))
    private val onDiskThread = ThreadLocal<Boolean>()
    private val disk = object : DiskRunner {
        override fun <T> run(block: () -> T): T {
            if (onDiskThread.get() == true) return block()
            return try {
                diskExecutor.submit(Callable { onDiskThread.set(true); block() }).get()
            } catch (e: ExecutionException) { throw e.cause ?: e }
        }
    }
    private val pendingSaves = mutableMapOf<String, ScheduledFuture<*>>()
    private var initialized = false
    /** For the home-screen widget refresh after a save. */
    private var widgetContext: Context? = null
    private val lock = Any()
    private val stored = CaptureAccounts({ Timber.w("Capture: %s", it) }, disk)
    private val runSession = CaptureRunSession()
    private val outside = CaptureOutsideSession(stored, OutsideLogRepository::add,
        warn = { Timber.w("Capture: unreadable outside period") })
    val openOutsideEntries = outside.openEntries

    /** Serialize live and stored deletion with response handling and period closure. */
    fun deleteOutsideEntry(id: String) = synchronized(lock) {
        outsideWork { outside.deleteEntry(id) }
        OutsideLogRepository.delete(id)
    }

    fun clearOutsideEntries() = synchronized(lock) {
        outsideWork { outside.clear() }
        OutsideLogRepository.clear()
    }

    private inline fun outsideWork(block: () -> Unit) {
        runCatching(block).onFailure { Timber.w("Capture: outside log storage failed: %s", it.javaClass.simpleName) }
    }

    private var indexLoaded = false

    /**
     * Account list only: metadata and summaries, no account state, roster or outside period is parsed. For the AP
     * widget, which may wake the process while FGA is not running (issue #258).
     */
    fun initSummaries(context: Context) = synchronized(lock) {
        widgetContext = context.applicationContext
        if (indexLoaded) return@synchronized
        stored.load(CertStore.directory(context), migrateLegacy = false)
        indexLoaded = true
        publish()
    }

    fun init(context: Context) = synchronized(lock) {
        widgetContext = context.applicationContext
        if (initialized) return@synchronized
        stored.load(CertStore.directory(context))
        outsideWork {
            OutsideLogRepository.init(context.filesDir) {
                LogRetentionPrefsCore.normalize(LogRetentionPrefsCore.fromContext(context).limit.get())
            }
            outside.load(CertStore.directory(context))
        }
        initialized = true
        indexLoaded = true
        publish()
        // Read the current account in the background so that the first response does not wait for the disk.
        if (stored.currentId != null) io.execute { synchronized(lock) { runCatching { stored.state } } }
        if (stored.hasUnsaved) io.execute { save() }
    }

    fun summary(now: Long = System.currentTimeMillis() / 1000): AccountSummary? = synchronized(lock) {
        stored.currentId?.let { id -> stored.summary(id, now)?.copy(masterName = stored.displayName(id)!!) }
    }

    fun accountSummaries(now: Long): List<CaptureAccount> = synchronized(lock) { stored.accounts(now) }

    /**
     * Capture id and immutable state together, so a switch cannot rename another account's export. A state that is
     * not resident is read from disk for this call only.
     */
    fun snapshot(userId: String? = null): Pair<String, AccountState>? {
        val (id, resident) = synchronized(lock) {
            val id = userId ?: stored.currentId ?: return null
            if (id !in stored) return null
            id to stored.residentState(id)
        }
        return (resident ?: stored.readState(id))?.let { id to it }
    }

    /** One account's state for a read-only screen; see [snapshot]. */
    fun loadState(userId: String): AccountState? = snapshot(userId)?.second

    /** The friend roster of [userId], read from disk for this call only. */
    fun loadRoster(userId: String): FriendRoster? = synchronized(lock) { if (userId in stored) stored else null }?.roster(userId)

    private fun runState() = runSession.accountId?.let(stored::stateOf)

    private fun publish() {
        _currentId.value = stored.currentId
        _accounts.value = stored.accounts(System.currentTimeMillis() / 1000)
    }

    fun setRunning(running: Boolean, reason: CaptureStopReason? = null) {
        starting = false
        val changed = synchronized(lock) {
            runSession.setRunning(running, runState())
            outsideWork { outside.setRunning(running) }
            val changed = _running.value != running
            _running.value = running
            changed
        }
        if (changed) widgetContext?.let(WidgetRefresh::requestUpdate)
        if (running) {
            _stopReason.value = null
            _tlsFailures.value = 0
        } else {
            _stopReason.value = reason
            flush()
        }
    }

    fun addTlsFailure() { _tlsFailures.value += 1 }

    /** Feeds one decoded game API response; account/token paths never get here. */
    fun onGameResponse(target: String, payload: ByteArray, contentEncoding: String?) =
        guardedCapture(CaptureNames.path(target), payload.size) { handleGameResponse(target, payload, contentEncoding) }

    private fun handleGameResponse(target: String, payload: ByteArray, contentEncoding: String?) {
        if (CaptureNames.isAccountPath(target)) return
        val path = CaptureNames.path(target)
        var login: AccountState.LoginRead? = null
        val body = try {
            when {
                path == "friend/top" ->
                    // Issue #254: the body is tens of MB decoded, so only the roster slices are ever kept.
                    BodyDecoder.decodeStream(payload, contentEncoding).use { FriendTopStream.parse(it) }
                path == "login/top" ->
                    // Issue #258: the login body is read table by table; no full String or tree exists.
                    BodyDecoder.decodeStream(payload, contentEncoding).use { AccountState.readLogin(it, target) }
                        .also { login = it }.routing
                else -> Json.parseToJsonElement(String(BodyDecoder.decode(payload, contentEncoding), Charsets.UTF_8)) as? JsonObject
            }
        } catch (_: Exception) { null } ?: return
        synchronized(lock) {
            val previousCurrent = stored.currentId
            val now = System.currentTimeMillis() / 1000
            val rosterId = stored.friendTarget(target, body)
            val rosterChanged = rosterId != null && stored.applyFriends(rosterId, target, body, now)
            if (rosterChanged) { _rosterRevision.value += 1; publish(); scheduleSave(rosterId) }
            val response = (login?.let { read -> stored.apply(target, body, now, loadPrevious = !read.replacesAccount) { read.reducer(it, now) } }
                ?: stored.apply(target, body, now)) ?: return
            runSession.collect(target, body, response.previous, response.next, response.id)
            if (path == "battle/result" && runSession.collectingFor(response.id)) {
                battleInbox.add(CapturedBattle.parse(body, response.previous))
            }
            outsideWork { outside.collect(target, response) }
            publish()
            if (response.next !== response.previous || stored.currentId != previousCurrent) scheduleSave(response.id)
        }
    }

    fun outsideScriptStart() = synchronized(lock) { outsideWork { outside.scriptStart() } }
    fun serviceStop() = synchronized(lock) { outsideWork { outside.serviceStop() } }
    fun startRun() = synchronized(lock) {
        battleInbox.clear()
        outsideWork { outside.scriptStart() }
        runSession.start(_running.value, stored.state, stored.currentId)
    }
    fun endRunCollection() = synchronized(lock) {
        outside.scriptEnd()
        runSession.stopCollection(runState())
    }
    fun finishRun() = synchronized(lock) {
        outside.scriptEnd()
        runSession.finish(runState())
    }

    /** Small battle-log hook: freeze the display name at the same instant as the run gains. */
    fun finishRun(entry: BattleLogEntry): BattleLogEntry = synchronized(lock) {
        outside.scriptEnd()
        val id = runSession.accountId
        val name = id?.let { stored.displayName(it) ?: "#$it" }
        val gains = runSession.finish(runState())
        entry.copy(
            gains = gains?.withRunCount(entry.timesRan),
            accountId = id.takeIf { gains != null }, accountName = name.takeIf { gains != null },
        )
    }

    fun discardRun() = synchronized(lock) { outside.scriptEnd(); runSession.discard() }

    fun delete() = synchronized(lock) {
        pendingSaves.values.forEach { it.cancel(false) }
        pendingSaves.clear()
        outside.clear()
        stored.clear()
        publish()
    }

    fun rename(userId: String, name: String) = synchronized(lock) {
        stored.rename(userId, name)
        outside.publish()
        publish()
        scheduleSave(userId)
    }

    fun delete(userId: String) = synchronized(lock) {
        pendingSaves.remove(userId)?.cancel(false)
        outside.delete(userId)
        stored.delete(userId)
        publish()
    }

    private fun scheduleSave(userId: String) {
        pendingSaves.remove(userId)?.cancel(false)
        pendingSaves[userId] = io.schedule({ save(userId) }, SAVE_DELAY_SECONDS, TimeUnit.SECONDS)
    }

    private fun flush() = synchronized(lock) {
        pendingSaves.values.forEach { it.cancel(false) }
        pendingSaves.clear()
        io.execute { save() }
    }

    private fun save(userId: String? = null) = synchronized(lock) {
        if (userId != null) pendingSaves.remove(userId)
        runCatching { stored.save(userId) }
            .onSuccess { widgetContext?.let { context -> runCatching { ApWidgetRefresh.onAccountSaved(context, userId) } } }
            .onFailure { Timber.w("Capture: saving the account state failed: %s", it.javaClass.simpleName) }
        Unit
    }
}

/**
 * Runs one capture handler without ever letting a failure escape to the proxy relay (issue #254: an
 * OutOfMemoryError used to vanish silently). Logs the path, encoded size and error class only.
 */
internal fun guardedCapture(path: String, encodedSize: Int, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        Timber.w("Capture: handling %s (%d bytes) failed: %s", path, encodedSize, t.javaClass.simpleName)
    }
}
