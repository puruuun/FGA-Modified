package io.github.fate_grand_automata.capture

import android.content.Context
import io.github.fate_grand_automata.battlelog.BattleLogEntry
import io.github.fate_grand_automata.scripts.capture.AccountState
import io.github.fate_grand_automata.scripts.capture.AccountSummary
import io.github.fate_grand_automata.scripts.capture.BodyDecoder
import io.github.fate_grand_automata.scripts.capture.CaptureNames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Why the capture VPN is not running; shown on the settings screen. */
enum class CaptureStopReason { User, Service, Revoked, Error, Unsupported }

/** Process-wide holder. Proxy responses, disk writes and account deletion share one lock. */
object CaptureTracker {
    private const val SAVE_DELAY_SECONDS = 2L
    private val _state = MutableStateFlow<AccountState?>(null)
    val state: StateFlow<AccountState?> = _state.asStateFlow()
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

    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "capture-save").apply { isDaemon = true } }
    private val pendingSaves = mutableMapOf<String, ScheduledFuture<*>>()
    private var initialized = false
    private val lock = Any()
    private val stored = CaptureAccounts { Timber.w("Capture: %s", it) }
    private val runSession = CaptureRunSession()
    private val outside = CaptureOutsideSession(stored, OutsideLogRepository::add,
        warn = { Timber.w("Capture: unreadable outside period") })

    private inline fun outsideWork(block: () -> Unit) {
        runCatching(block).onFailure { Timber.w("Capture: outside log storage failed: %s", it.javaClass.simpleName) }
    }

    fun init(context: Context) = synchronized(lock) {
        if (initialized) return@synchronized
        stored.load(CertStore.directory(context))
        outsideWork {
            OutsideLogRepository.init(context.filesDir)
            outside.load(CertStore.directory(context))
        }
        initialized = true
        publish()
    }

    fun summary(now: Long = System.currentTimeMillis() / 1000): AccountSummary? = synchronized(lock) {
        stored.state?.let { AccountSummary.from(it, now).copy(masterName = stored.displayName(stored.currentId!!)!!) }
    }

    fun accountSummaries(now: Long): List<CaptureAccount> = synchronized(lock) { stored.accounts(now) }

    /** Capture id and immutable state together, so a switch cannot rename another account's export. */
    fun snapshot(userId: String? = null): Pair<String, AccountState>? = synchronized(lock) {
        val id = userId ?: stored.currentId ?: return@synchronized null
        stored.states[id]?.let { id to it }
    }

    private fun runState() = stored.states[runSession.accountId]

    private fun publish() {
        _state.value = stored.state
        _currentId.value = stored.currentId
        _accounts.value = stored.accounts(System.currentTimeMillis() / 1000)
    }

    fun setRunning(running: Boolean, reason: CaptureStopReason? = null) {
        synchronized(lock) {
            runSession.setRunning(running, runState())
            outsideWork { outside.setRunning(running) }
            _running.value = running
        }
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
    fun onGameResponse(target: String, payload: ByteArray, contentEncoding: String?) {
        if (CaptureNames.isAccountPath(target)) return
        val body = try {
            Json.parseToJsonElement(String(BodyDecoder.decode(payload, contentEncoding), Charsets.UTF_8)) as? JsonObject
        } catch (_: Exception) { null } ?: return
        synchronized(lock) {
            val previousCurrent = stored.currentId
            val response = stored.apply(target, body, System.currentTimeMillis() / 1000) ?: return
            runSession.collect(target, body, response.previous, response.next, response.id)
            outsideWork { outside.collect(target, response) }
            publish()
            if (response.next !== response.previous || stored.currentId != previousCurrent) scheduleSave(response.id)
        }
    }

    fun outsideScriptStart() = synchronized(lock) { outsideWork { outside.scriptStart() } }
    fun serviceStop() = synchronized(lock) { outsideWork { outside.serviceStop() } }
    fun startRun() = synchronized(lock) {
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
            .onFailure { Timber.w("Capture: saving the account state failed: %s", it.javaClass.simpleName) }
        Unit
    }
}
