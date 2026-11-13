package io.github.fate_grand_automata.runner

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test

class ProjectionRestartTest {
    @Test
    fun requestConsent_whenIdle_returnsTrueAndMarksPending() {
        val restart = ProjectionRestart()

        assertThat(restart.requestConsent()).isTrue()
        assertThat(restart.isPending).isTrue()
    }

    @Test
    fun requestConsent_whilePending_returnsFalse() {
        val restart = ProjectionRestart()
        restart.requestConsent()

        assertThat(restart.requestConsent()).isFalse()
        assertThat(restart.isPending).isTrue()
    }

    @Test
    fun consentGranted_whenPending_returnsTrueAndClearsPending() {
        val restart = ProjectionRestart()
        restart.requestConsent()

        assertThat(restart.consentGranted()).isTrue()
        assertThat(restart.isPending).isFalse()
        assertThat(restart.consentGranted()).isFalse()
        assertThat(restart.requestConsent()).isTrue()
    }

    @Test
    fun consentGranted_whenIdle_returnsFalse() {
        val restart = ProjectionRestart()

        assertThat(restart.consentGranted()).isFalse()
        assertThat(restart.isPending).isFalse()
    }

    @Test
    fun consentDenied_clearsPending() {
        val restart = ProjectionRestart()
        restart.requestConsent()

        restart.consentDenied()

        assertThat(restart.isPending).isFalse()
        assertThat(restart.consentGranted()).isFalse()
        assertThat(restart.requestConsent()).isTrue()
    }

    @Test
    fun reset_clearsPending() {
        val restart = ProjectionRestart()
        restart.requestConsent()

        restart.reset()

        assertThat(restart.isPending).isFalse()
        assertThat(restart.consentGranted()).isFalse()
        assertThat(restart.requestConsent()).isTrue()
    }
}
