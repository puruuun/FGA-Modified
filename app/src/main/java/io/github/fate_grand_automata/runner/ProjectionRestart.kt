package io.github.fate_grand_automata.runner

import dagger.hilt.android.scopes.ServiceScoped
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Tracks whether a screen-capture re-consent flow initiated from the overlay play button
 * is currently awaiting a permission result for this service instance.
 */
@ServiceScoped
class ProjectionRestart @Inject constructor() {
    private val pending = AtomicBoolean(false)

    val isPending: Boolean
        get() = pending.get()

    /** Marks a start as pending if idle; returns false when a consent request is already open. */
    fun requestConsent(): Boolean = pending.compareAndSet(false, true)

    /** Consumes the pending start once consent is granted; returns true if one was pending. */
    fun consentGranted(): Boolean = pending.getAndSet(false)

    /** Clears any pending start when consent is denied, cancelled, or fails to launch. */
    fun consentDenied() {
        pending.set(false)
    }

    /** Clears pending state when the service is destroyed. */
    fun reset() {
        pending.set(false)
    }
}
