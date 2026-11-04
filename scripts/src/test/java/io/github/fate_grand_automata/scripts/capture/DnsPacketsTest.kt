package io.github.fate_grand_automata.scripts.capture

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DnsPacketsTest {
    private val dns = ByteArray(12) { it.toByte() } + byteArrayOf(1, 'a'.code.toByte(), 0, 0, 1, 0, 1)

    /** Builds a valid client packet to the fake DNS address, independent of [DnsPackets.reply]. */
    private fun query(payload: ByteArray = dns, udpChecksum: Boolean = true): ByteArray {
        val src = byteArrayOf(10, 255.toByte(), 77, 1)
        val size = 8 + payload.size
        val p = ByteArray(20 + size)
        p[0] = 0x45; p[2] = (p.size ushr 8).toByte(); p[3] = p.size.toByte(); p[8] = 64; p[9] = 17
        src.copyInto(p, 12); DnsPackets.FAKE_DNS.copyInto(p, 16)
        val ip = DnsPackets.checksum(p.copyOfRange(0, 20)); p[10] = (ip ushr 8).toByte(); p[11] = ip.toByte()
        p[20] = 0x9c.toByte(); p[21] = 0x40; p[23] = 53; p[24] = (size ushr 8).toByte(); p[25] = size.toByte()
        payload.copyInto(p, 28)
        if (udpChecksum) {
            val pseudo = src + DnsPackets.FAKE_DNS + byteArrayOf(0, 17, (size ushr 8).toByte(), size.toByte())
            val c = DnsPackets.checksum(pseudo + p.copyOfRange(20, p.size))
            p[26] = (c ushr 8).toByte(); p[27] = c.toByte()
        }
        return p
    }

    @Test fun parsesValidQuery() {
        val q = assertNotNull(DnsPackets.parse(query()))
        assertEquals(0x9c40, q.sourcePort)
        assertContentEquals(dns, q.payload)
        assertContentEquals(byteArrayOf(10, 255.toByte(), 77, 1), q.source)
    }

    @Test fun acceptsZeroUdpChecksum() {
        assertNotNull(DnsPackets.parse(query(udpChecksum = false)))
    }

    @Test fun dropsCorruptTruncatedAndForeignPackets() {
        val good = query()
        assertNull(DnsPackets.parse(good.copyOf(good.size - 1)))
        assertNull(DnsPackets.parse(good.copyOf().also { it[30] = (it[30] + 1).toByte() }), "payload corruption")
        assertNull(DnsPackets.parse(good.copyOf().also { it[12] = 11 }), "ip header corruption")
        assertNull(DnsPackets.parse(good.copyOf().also { it[17] = 0 }), "other destination")
        assertNull(DnsPackets.parse(good.copyOf().also { it[23] = 80 }), "other port")
        assertNull(DnsPackets.parse(good.copyOf().also { it[9] = 6 }), "not udp")
        assertNull(DnsPackets.parse(ByteArray(10)))
    }

    @Test fun replyHasValidChecksumsAndSwappedEndpoints() {
        val q = assertNotNull(DnsPackets.parse(query()))
        val answer = ByteArray(40) { (it + 3).toByte() }
        val r = DnsPackets.reply(q, answer)
        assertEquals(0, DnsPackets.checksum(r.copyOfRange(0, 20)))
        val pseudo = DnsPackets.FAKE_DNS + q.source + byteArrayOf(0, 17, 0, (8 + answer.size).toByte())
        assertEquals(0, DnsPackets.checksum(pseudo + r.copyOfRange(20, r.size)))
        assertEquals(r.size, ((r[2].toInt() and 255) shl 8) or (r[3].toInt() and 255))
        assertContentEquals(DnsPackets.FAKE_DNS, r.copyOfRange(12, 16))
        assertContentEquals(q.source, r.copyOfRange(16, 20))
        assertEquals(0x9c40, ((r[22].toInt() and 255) shl 8) or (r[23].toInt() and 255))
        assertContentEquals(answer, r.copyOfRange(28, r.size))
    }

    @Test fun oddLengthPayloadChecksum() {
        val q = assertNotNull(DnsPackets.parse(query(dns + 7)))
        val r = DnsPackets.reply(q, dns + 9)
        val pseudo = DnsPackets.FAKE_DNS + q.source + byteArrayOf(0, 17, 0, (8 + dns.size + 1).toByte())
        assertEquals(0, DnsPackets.checksum(pseudo + r.copyOfRange(20, r.size)))
    }
}
