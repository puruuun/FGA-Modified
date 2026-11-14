package io.github.fate_grand_automata.scripts.capture

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TlsClientHelloTest {
    @Test
    fun `extracts SNI from valid ClientHello`() {
        val packet = buildClientHelloRecord(serverName = "game.fate-go.jp")
        assertEquals("game.fate-go.jp", TlsClientHello.sniHost(packet, packet.size))
    }

    @Test
    fun `returns null when SNI extension is absent`() {
        val packet = buildClientHelloRecord(serverName = null)
        assertNull(TlsClientHello.sniHost(packet, packet.size))
    }

    @Test
    fun `returns null on truncated packet`() {
        val packet = buildClientHelloRecord(serverName = "game.fate-go.jp")
        for (len in listOf(0, 4, 5, packet.size / 2, packet.size - 1)) {
            assertNull(TlsClientHello.sniHost(packet, len), "Expected null for truncated length $len")
        }
    }

    @Test
    fun `returns null on wrong TLS record type`() {
        val packet = buildClientHelloRecord(serverName = "game.fate-go.jp", recordType = 0x17)
        assertNull(TlsClientHello.sniHost(packet, packet.size))
    }

    @Test
    fun `returns null on non-ClientHello handshake message`() {
        val packet = buildClientHelloRecord(serverName = "game.fate-go.jp", handshakeType = 0x02)
        assertNull(TlsClientHello.sniHost(packet, packet.size))
    }

    @Test
    fun `handles multiple extensions before server_name`() {
        val packet = buildClientHelloRecord(serverName = "line1-s1.bilibiligame.net", includeGreaseExtension = true)
        assertEquals("line1-s1.bilibiligame.net", TlsClientHello.sniHost(packet, packet.size))
    }

    @Test
    fun `returns null on oversized TLS record`() {
        val valid = buildClientHelloRecord(serverName = "game.fate-go.jp")
        val oversizedPayloadLen = TlsClientHello.MAX_RECORD_PAYLOAD_BYTES + 1
        val oversized = ByteArray(5 + oversizedPayloadLen)
        valid.copyInto(oversized)
        oversized[3] = ((oversizedPayloadLen ushr 8) and 0xFF).toByte()
        oversized[4] = (oversizedPayloadLen and 0xFF).toByte()
        assertNull(TlsClientHello.sniHost(oversized, oversized.size))
    }

    companion object {
        fun buildClientHelloRecord(
            serverName: String?,
            recordType: Int = 0x16,
            handshakeType: Int = 0x01,
            includeGreaseExtension: Boolean = false,
        ): ByteArray {
            val extensions = ByteArrayOutputStream()
            if (includeGreaseExtension) {
                // Unknown extension 0x0017 (extended_master_secret), 0 bytes
                extensions.write(byteArrayOf(0x00, 0x17, 0x00, 0x00))
            }
            if (serverName != null) {
                val hostBytes = serverName.toByteArray(Charsets.US_ASCII)
                val serverNameListLen = 1 + 2 + hostBytes.size
                val extPayloadLen = 2 + serverNameListLen
                extensions.write(0x00)
                extensions.write(0x00)
                extensions.write((extPayloadLen ushr 8) and 0xFF)
                extensions.write(extPayloadLen and 0xFF)
                extensions.write((serverNameListLen ushr 8) and 0xFF)
                extensions.write(serverNameListLen and 0xFF)
                extensions.write(0x00) // host_name
                extensions.write((hostBytes.size ushr 8) and 0xFF)
                extensions.write(hostBytes.size and 0xFF)
                extensions.write(hostBytes)
            }
            val extBytes = extensions.toByteArray()

            val body = ByteArrayOutputStream()
            // Legacy version TLS 1.2
            body.write(byteArrayOf(0x03, 0x03))
            // Random (32 bytes)
            body.write(ByteArray(32) { it.toByte() })
            // Session ID (4 bytes)
            body.write(4)
            body.write(byteArrayOf(1, 2, 3, 4))
            // Cipher suites (2 suites = 4 bytes)
            body.write(byteArrayOf(0x00, 0x04, 0x13, 0x01, 0x13, 0x02))
            // Compression methods (1 byte null)
            body.write(byteArrayOf(0x01, 0x00))
            if (extBytes.isNotEmpty()) {
                body.write((extBytes.size ushr 8) and 0xFF)
                body.write(extBytes.size and 0xFF)
                body.write(extBytes)
            }
            val bodyBytes = body.toByteArray()

            val hs = ByteArrayOutputStream()
            hs.write(handshakeType)
            hs.write((bodyBytes.size ushr 16) and 0xFF)
            hs.write((bodyBytes.size ushr 8) and 0xFF)
            hs.write(bodyBytes.size and 0xFF)
            hs.write(bodyBytes)
            val hsBytes = hs.toByteArray()

            val record = ByteArrayOutputStream()
            record.write(recordType)
            record.write(byteArrayOf(0x03, 0x01))
            record.write((hsBytes.size ushr 8) and 0xFF)
            record.write(hsBytes.size and 0xFF)
            record.write(hsBytes)
            return record.toByteArray()
        }
    }
}
