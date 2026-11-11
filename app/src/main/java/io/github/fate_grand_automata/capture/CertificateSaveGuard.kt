package io.github.fate_grand_automata.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Held through the save dialog, including its callback. VPN startup reserves the loaded leaf too. */
object CertificateSaveGuard {
    private val busy = MutableStateFlow(false)
    val saving = busy.asStateFlow()
    private var creating = false
    private var vpnUsingCertificate = false

    @Synchronized
    fun begin(newCertificate: Boolean, running: Boolean): Boolean {
        if (busy.value || (newCertificate && (running || vpnUsingCertificate))) return false
        creating = newCertificate
        busy.value = true
        return true
    }

    @Synchronized
    fun finish() { creating = false; busy.value = false }

    @Synchronized
    fun beginVpn(): Boolean {
        if (creating) return false
        vpnUsingCertificate = true
        return true
    }

    @Synchronized
    fun endVpn() { vpnUsingCertificate = false }
}
