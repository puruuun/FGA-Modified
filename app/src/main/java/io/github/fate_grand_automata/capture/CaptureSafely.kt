package io.github.fate_grand_automata.capture

import timber.log.Timber
import java.util.concurrent.CancellationException

/**
 * Runs a capture-related block and catches any [Throwable] except [CancellationException].
 * Logs the label and error class only: no payloads or account ids.
 */
inline fun <T> captureSafely(label: String, default: T, block: () -> T): T {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Timber.w("Capture: %s failed: %s", label, t.javaClass.simpleName)
        default
    }
}

inline fun captureSafely(label: String, block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Timber.w("Capture: %s failed: %s", label, t.javaClass.simpleName)
    }
}
