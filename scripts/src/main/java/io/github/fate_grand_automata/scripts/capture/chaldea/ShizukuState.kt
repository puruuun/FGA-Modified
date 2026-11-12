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
