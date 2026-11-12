package io.github.fate_grand_automata.scripts.capture.chaldea

/** Whether FGA can run commands as root in this process (issue #362). */
enum class RootState { Unchecked, Granted, Unavailable }

/** How the Chaldea files are reached. */
enum class ChaldeaAccessMode { Root, Shizuku }

/** Everything the sync knows about its access to the Chaldea files. Root wins when both are available. */
data class ChaldeaAccess(val root: RootState, val shizuku: ShizukuState) {
    val mode: ChaldeaAccessMode?
        get() = when {
            root == RootState.Granted -> ChaldeaAccessMode.Root
            shizuku == ShizukuState.Ready -> ChaldeaAccessMode.Shizuku
            else -> null
        }

    val ready get() = mode != null
}

/** Reads the result of `su -c id`. */
object RootProbe {
    /** Granted only for a finished run that exited 0 and printed a `uid=0` line. A missing `su` is Unavailable too. */
    fun parse(exitCode: Int, lines: List<String>, timedOut: Boolean): RootState =
        if (!timedOut && exitCode == 0 && lines.any { it.trimStart().startsWith("uid=0") }) RootState.Granted else RootState.Unavailable
}

/** Whether the root dialog of Magisk / KernelSU may be shown for a probe. */
enum class ProbeKind { Interactive, Silent }

/** One started probe. A result from an older [epoch] (the switch was turned off meanwhile) is ignored. */
data class ProbeTicket(val kind: ProbeKind, val epoch: Int)

/**
 * Decides when the root probe runs and what its result changes (issue #362, R2). Pure: the manager runs the process
 * and calls the `on...` functions; each returns the probe to start now, or null.
 *
 * - Interactive probe: the user turned the switch on, or pressed "sync now" while root is not Granted.
 * - Silent probe: the switch is on at process start or changed by a setting, or the next round after a failed root
 *   bind, but only when the stored flag says root was granted before.
 * - Nothing starts while a script runs; the wish is kept and starts when the script ends. Only one probe runs.
 */
class RootProbePolicy(
    private val storedFlag: () -> Boolean,
    private val setStoredFlag: (Boolean) -> Unit,
) {
    private val lock = Any()
    private var _state = RootState.Unchecked
    private var running = false
    private var scriptRunning = false
    private var epoch = 0
    private var on = false
    private var wanted: ProbeKind? = null
    private var reprobeAfterBindFailure = false

    val state: RootState get() = synchronized(lock) { _state }

    /** A probe is running or waiting for a script to end. */
    val busy: Boolean get() = synchronized(lock) { running || wanted != null }

    /**
     * The Shizuku permission dialog is not shown while root is Granted, nor while a probe may still make it
     * unnecessary. Otherwise the Shizuku flow is exactly the requester's.
     */
    fun shouldRequestShizukuPermission(requester: ShizukuPermissionRequester, shizuku: ShizukuState): Boolean {
        val blocked = synchronized(lock) { _state == RootState.Granted || running || wanted != null }
        return !blocked && requester.shouldRequest(shizuku)
    }

    /** The user turned the switch on. */
    fun onUserTurnedOn(): ProbeTicket? = synchronized(lock) {
        on = true
        want(ProbeKind.Interactive)
    }

    /** The switch is on at process start or was changed by a setting. */
    fun onSettingsOn(): ProbeTicket? = synchronized(lock) {
        on = true
        if (_state == RootState.Unchecked && storedFlag()) want(ProbeKind.Silent) else null
    }

    /** "Sync now" was pressed. */
    fun onSyncNow(): ProbeTicket? = synchronized(lock) {
        if (!on || _state == RootState.Granted) null else want(ProbeKind.Interactive)
    }

    /** The scheduler starts a round and reads the access. */
    fun onRound(): ProbeTicket? = synchronized(lock) {
        if (on && reprobeAfterBindFailure && _state == RootState.Unchecked && storedFlag()) {
            reprobeAfterBindFailure = false
            want(ProbeKind.Silent)
        } else {
            null
        }
    }

    /** The root service could not be bound: forget the state so the next round probes again. */
    fun onRootBindFailed() = synchronized(lock) {
        if (_state == RootState.Granted) {
            _state = RootState.Unchecked
            reprobeAfterBindFailure = true
        }
    }

    fun onScriptStart() = synchronized(lock) { scriptRunning = true }

    /** @return the probe that was waiting for the script to end */
    fun onScriptEnd(): ProbeTicket? = synchronized(lock) {
        scriptRunning = false
        val kind = wanted
        if (on && kind != null) want(kind) else null
    }

    /** The switch was turned off: back to Unchecked; a running probe's result is dropped. */
    fun onSwitchedOff() = synchronized(lock) {
        on = false
        epoch++
        running = false
        wanted = null
        reprobeAfterBindFailure = false
        _state = RootState.Unchecked
    }

    /** @return true when the root state changed */
    fun onProbeResult(ticket: ProbeTicket, result: RootState): Boolean = synchronized(lock) {
        if (ticket.epoch != epoch) return false
        running = false
        val granted = result == RootState.Granted
        if (granted) {
            setStoredFlag(true)
        } else if (ticket.kind == ProbeKind.Interactive) {
            setStoredFlag(false)
        }
        val next = if (granted) RootState.Granted else RootState.Unavailable
        val changed = next != _state
        _state = next
        changed
    }

    private fun want(kind: ProbeKind): ProbeTicket? {
        if (running) return null
        if (scriptRunning) {
            // An interactive wish is never downgraded to a silent one.
            if (wanted != ProbeKind.Interactive) wanted = kind
            return null
        }
        val k = if (wanted == ProbeKind.Interactive) ProbeKind.Interactive else kind
        wanted = null
        running = true
        return ProbeTicket(k, epoch)
    }
}
