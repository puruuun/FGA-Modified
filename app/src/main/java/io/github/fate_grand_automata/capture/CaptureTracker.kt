package io.github.fate_grand_automata.capture

import android.content.Context
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
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Why the capture VPN is not running; shown on the settings screen. */
enum class CaptureStopReason { User, Script, Revoked, Error, Unsupported }

/**
 * Process-wide holder of the captured account state. The VPN service, the proxy threads and the UI
 * live in one process, so a plain object is enough. Nothing here ever leaves the device.
 */
object CaptureTracker {
    private const val SAVE_DELAY_SECONDS = 2L

    private val _state = MutableStateFlow<AccountState?>(null)
    val state: StateFlow<AccountState?> = _state.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _tlsFailures = MutableStateFlow(0)
    val tlsFailures: StateFlow<Int> = _tlsFailures.asStateFlow()

    private val _stopReason = MutableStateFlow<CaptureStopReason?>(null)
    val stopReason: StateFlow<CaptureStopReason?> = _stopReason.asStateFlow()

    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "capture-save").apply { isDaemon = true } }
    private var pendingSave: ScheduledFuture<*>? = null
    private var dir: File? = null
    private val lock = Any()
    private val runSession = CaptureRunSession()

    fun init(context: Context) {
        synchronized(lock) {
            if (dir != null) return
            dir = CertStore.directory(context)
            val file = File(dir, STATE_FILE)
            if (file.isFile) {
                _state.value = runCatching { AccountState.fromJson(file.readText()) }
                    .onFailure { Timber.w("Capture: saved account state is unreadable") }
                    .getOrNull()
            }
        }
    }

    fun summary(now: Long = System.currentTimeMillis() / 1000): AccountSummary? =
        _state.value?.let { AccountSummary.from(it, now) }

    fun setRunning(running: Boolean, reason: CaptureStopReason? = null) {
        synchronized(lock) {
            runSession.setRunning(running, _state.value)
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

    fun addTlsFailure() {
        _tlsFailures.value += 1
    }

    /** Feeds one decoded game API response; account paths never get here. */
    fun onGameResponse(target: String, payload: ByteArray, contentEncoding: String?) {
        if (CaptureNames.isAccountPath(target)) return
        val body = try {
            Json.parseToJsonElement(String(BodyDecoder.decode(payload, contentEncoding), Charsets.UTF_8)) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return
        val changed = synchronized(lock) {
            val previous = _state.value
            val next = AccountState.reduce(previous, target, body, System.currentTimeMillis() / 1000)
            runSession.collect(target, body, previous, next)
            if (next !== previous) _state.value = next
            next !== previous
        }
        if (changed) scheduleSave()
    }

    fun startRun() = synchronized(lock) { runSession.start(_running.value, _state.value) }

    fun endRunCollection() = synchronized(lock) { runSession.stopCollection(_state.value) }

    fun finishRun() = synchronized(lock) { runSession.finish(_state.value) }

    fun discardRun() = synchronized(lock) { runSession.discard() }

    fun delete() {
        synchronized(lock) {
            pendingSave?.cancel(false)
            _state.value = null
            dir?.let { File(it, STATE_FILE).delete() }
        }
    }

    private fun scheduleSave() {
        synchronized(lock) {
            pendingSave?.cancel(false)
            pendingSave = io.schedule({ save() }, SAVE_DELAY_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun flush() {
        val pending = synchronized(lock) { pendingSave?.also { it.cancel(false) } } ?: return
        pending.let { io.execute { save() } }
    }

    private fun save() {
        val (target, state) = synchronized(lock) { dir to _state.value }
        if (target == null || state == null) return
        runCatching {
            target.mkdirs()
            val tmp = File(target, "$STATE_FILE.tmp")
            tmp.writeText(state.toJson())
            if (!tmp.renameTo(File(target, STATE_FILE))) {
                File(target, STATE_FILE).delete()
                check(tmp.renameTo(File(target, STATE_FILE)))
            }
        }.onFailure { Timber.w("Capture: saving the account state failed: %s", it.javaClass.simpleName) }
    }

    private const val STATE_FILE = "account-state.json"
}
