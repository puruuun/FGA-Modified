package io.github.fate_grand_automata.scripts.capture

/**
 * Pure JVM parser for extracting the SNI hostname from a TLS record containing a ClientHello.
 */
object TlsClientHello {
    const val RECORD_TYPE_HANDSHAKE = 0x16
    const val MAX_RECORD_PAYLOAD_BYTES = 16 * 1024
    private const val HANDSHAKE_CLIENT_HELLO = 0x01
    private const val EXTENSION_SERVER_NAME = 0x0000
    private const val NAME_TYPE_HOST_NAME = 0x00

    /**
     * Parses [bytes] (up to [length]) starting with a 5-byte TLS record header and returns the
     * first SNI host_name string if present, or null on truncation, non-ClientHello, or invalid data.
     */
    fun sniHost(bytes: ByteArray, length: Int = bytes.size): String? {
        if (length < 5 || length > bytes.size) return null
        if ((bytes[0].toInt() and 0xFF) != RECORD_TYPE_HANDSHAKE) return null

        val recordLength = readUint16(bytes, 3)
        if (recordLength <= 0 || recordLength > MAX_RECORD_PAYLOAD_BYTES) return null
        val recordEnd = 5 + recordLength
        if (recordEnd > length) return null

        var pos = 5
        if (pos + 4 > recordEnd) return null
        if ((bytes[pos].toInt() and 0xFF) != HANDSHAKE_CLIENT_HELLO) return null

        val handshakeLength = readUint24(bytes, pos + 1)
        pos += 4
        val handshakeEnd = pos + handshakeLength
        if (handshakeEnd > recordEnd) return null

        // Legacy version (2) + random (32)
        if (pos + 34 > handshakeEnd) return null
        pos += 34

        // Session ID
        if (pos + 1 > handshakeEnd) return null
        val sessionIdLen = bytes[pos].toInt() and 0xFF
        pos += 1
        if (pos + sessionIdLen > handshakeEnd) return null
        pos += sessionIdLen

        // Cipher suites
        if (pos + 2 > handshakeEnd) return null
        val cipherSuitesLen = readUint16(bytes, pos)
        pos += 2
        if (pos + cipherSuitesLen > handshakeEnd) return null
        pos += cipherSuitesLen

        // Compression methods
        if (pos + 1 > handshakeEnd) return null
        val compressionLen = bytes[pos].toInt() and 0xFF
        pos += 1
        if (pos + compressionLen > handshakeEnd) return null
        pos += compressionLen

        // Extensions are optional if packet ended cleanly here
        if (pos == handshakeEnd) return null
        if (pos + 2 > handshakeEnd) return null
        val extensionsLen = readUint16(bytes, pos)
        pos += 2
        val extensionsEnd = pos + extensionsLen
        if (extensionsEnd > handshakeEnd) return null

        while (pos + 4 <= extensionsEnd) {
            val extType = readUint16(bytes, pos)
            val extLen = readUint16(bytes, pos + 2)
            pos += 4
            val extEnd = pos + extLen
            if (extEnd > extensionsEnd) return null

            if (extType == EXTENSION_SERVER_NAME) {
                return parseServerNameExtension(bytes, pos, extEnd)
            }
            pos = extEnd
        }
        return null
    }

    private fun parseServerNameExtension(bytes: ByteArray, start: Int, end: Int): String? {
        if (start + 2 > end) return null
        val listLen = readUint16(bytes, start)
        var pos = start + 2
        val listEnd = pos + listLen
        if (listEnd > end) return null

        while (pos + 3 <= listEnd) {
            val nameType = bytes[pos].toInt() and 0xFF
            val nameLen = readUint16(bytes, pos + 1)
            pos += 3
            if (pos + nameLen > listEnd) return null
            if (nameType == NAME_TYPE_HOST_NAME) {
                if (nameLen == 0) return null
                return String(bytes, pos, nameLen, Charsets.US_ASCII)
            }
            pos += nameLen
        }
        return null
    }

    private fun readUint16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun readUint24(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)
}
