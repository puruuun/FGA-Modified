package io.github.fate_grand_automata.scripts.capture

import java.io.ByteArrayInputStream
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Socks5Test {
    @Test
    fun `greeting accepts no-auth and rejects when absent`() {
        assertTrue(Socks5.readGreeting(ByteArrayInputStream(byteArrayOf(0x05, 0x02, 0x02, 0x00))))
        assertTrue(Socks5.readGreeting(ByteArrayInputStream(byteArrayOf(0x01, 0x00)), firstByte = 0x05))
        assertFalse(Socks5.readGreeting(ByteArrayInputStream(byteArrayOf(0x05, 0x01, 0x02))))
        assertContentEquals(byteArrayOf(0x05, 0x00), Socks5.methodReply(true))
        assertContentEquals(byteArrayOf(0x05, 0xFF.toByte()), Socks5.methodReply(false))
    }

    @Test
    fun `parses IPv4 CONNECT request`() {
        val packet = byteArrayOf(
            0x05, 0x01, 0x00, 0x01,
            127, 0, 0, 1,
            0x01, 0xBB.toByte(), // 443
        )
        val result = Socks5.readRequest(ByteArrayInputStream(packet))
        val connect = assertIs<Socks5.RequestResult.Connect>(result)
        assertEquals("127.0.0.1", connect.target.host)
        assertEquals(443, connect.target.port)
        assertEquals(Socks5.AddressType.Ipv4, connect.target.addressType)
    }

    @Test
    fun `parses domain CONNECT request`() {
        val domain = "game.fate-go.jp".toByteArray(Charsets.UTF_8)
        val packet = byteArrayOf(0x05, 0x01, 0x00, 0x03, domain.size.toByte()) +
            domain + byteArrayOf(0x20, 0xFB.toByte()) // 8443
        val result = Socks5.readRequest(ByteArrayInputStream(packet))
        val connect = assertIs<Socks5.RequestResult.Connect>(result)
        assertEquals("game.fate-go.jp", connect.target.host)
        assertEquals(8443, connect.target.port)
        assertEquals(Socks5.AddressType.Domain, connect.target.addressType)
    }

    @Test
    fun `parses IPv6 CONNECT request`() {
        val addr6 = InetAddress.getByName("::1").address
        val packet = byteArrayOf(0x05, 0x01, 0x00, 0x04) + addr6 + byteArrayOf(0x01, 0xBB.toByte())
        val result = Socks5.readRequest(ByteArrayInputStream(packet))
        val connect = assertIs<Socks5.RequestResult.Connect>(result)
        assertEquals("0:0:0:0:0:0:0:1", connect.target.host)
        assertEquals(443, connect.target.port)
        assertEquals(Socks5.AddressType.Ipv6, connect.target.addressType)
    }

    @Test
    fun `rejects empty domain`() {
        val packet = byteArrayOf(0x05, 0x01, 0x00, 0x03, 0x00, 0x01, 0xBB.toByte())
        val result = Socks5.readRequest(ByteArrayInputStream(packet))
        val error = assertIs<Socks5.RequestResult.Error>(result)
        assertEquals(Socks5.REP_GENERAL_FAILURE, error.replyCode)
    }

    @Test
    fun `rejects non-CONNECT commands with 0x07`() {
        val udpAssociate = byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0)
        val error = assertIs<Socks5.RequestResult.Error>(Socks5.readRequest(ByteArrayInputStream(udpAssociate)))
        assertEquals(Socks5.REP_COMMAND_NOT_SUPPORTED, error.replyCode)

        val bind = byteArrayOf(0x05, 0x02, 0x00, 0x01, 0, 0, 0, 0, 0, 0)
        val bindError = assertIs<Socks5.RequestResult.Error>(Socks5.readRequest(ByteArrayInputStream(bind)))
        assertEquals(Socks5.REP_COMMAND_NOT_SUPPORTED, bindError.replyCode)
    }

    @Test
    fun `builds 0x00 reply packet with 0_0_0_0 port 0`() {
        assertContentEquals(
            byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0),
            Socks5.reply(Socks5.REP_SUCCEEDED),
        )
    }
}
