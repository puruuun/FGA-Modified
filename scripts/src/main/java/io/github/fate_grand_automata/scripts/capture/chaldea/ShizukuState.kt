package io.github.fate_grand_automata.scripts.capture.chaldea

/** What the Shizuku integration can currently do. */
enum class ShizukuState { NotInstalled, NotRunning, NeedsPermission, Denied, Outdated, Ready }

/** Raw facts read from the Shizuku API and the package manager. */
data class ShizukuFacts(
    val appInstalled: Boolean,
    val binderAlive: Boolean,
    /** The running Shizuku server is older than the API needs (pre-v11). */
    val serverOutdated: Boolean,
    val permissionGranted: Boolean,
    /** The user refused and Shizuku will not show the request again. */
    val permissionDenied: Boolean,
) {
    fun state(): ShizukuState = when {
        !binderAlive -> if (appInstalled) ShizukuState.NotRunning else ShizukuState.NotInstalled
        serverOutdated -> ShizukuState.Outdated
        permissionGranted -> ShizukuState.Ready
        permissionDenied -> ShizukuState.Denied
        else -> ShizukuState.NeedsPermission
    }
}

/**
 * Decides when to show the Shizuku permission dialog after the user turned the sync on (issue #355, review B1).
 * The request must not be lost when the switch flips before the listeners exist, nor repeat while one is open.
 */
class ShizukuPermissionRequester(private val maxRequests: Int = 2) {
    private var wanted = false
    private var inFlight = false
    private var requests = 0

    /** The user turned the switch on. */
    fun arm() {
        wanted = true
        inFlight = false
        requests = 0
    }

    /** The switch was turned off. */
    fun disarm() {
        wanted = false
        inFlight = false
    }

    /** The result listener fired or the screen was resumed: a new request may be shown if still needed. */
    fun released() {
        inFlight = false
    }

    /** @return true when the dialog should be requested now, for the Shizuku state just read */
    fun shouldRequest(state: ShizukuState): Boolean {
        if (!wanted) return false
        return when (state) {
            ShizukuState.NeedsPermission -> {
                if (inFlight || requests >= maxRequests) false else {
                    inFlight = true
                    requests++
                    true
                }
            }
            // Not running yet: keep the wish and ask once the binder arrives (for example after a reboot).
            ShizukuState.NotRunning, ShizukuState.NotInstalled -> false
            ShizukuState.Ready, ShizukuState.Denied, ShizukuState.Outdated -> {
                wanted = false
                inFlight = false
                false
            }
        }
    }
}
