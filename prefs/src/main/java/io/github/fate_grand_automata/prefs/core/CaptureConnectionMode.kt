package io.github.fate_grand_automata.prefs.core

enum class CaptureConnectionMode {
    Vpn,
    AdGuard,
    ;

    companion object {
        const val DEFAULT_PROXY_PORT = 8899
        val PROXY_PORT_RANGE = 1024..65535

        fun parse(raw: String?): CaptureConnectionMode =
            entries.firstOrNull { it.name == raw } ?: Vpn

        fun clampProxyPort(port: Int): Int = port.coerceIn(PROXY_PORT_RANGE)

        fun isValidProxyPort(port: Int): Boolean = port in PROXY_PORT_RANGE
    }
}
