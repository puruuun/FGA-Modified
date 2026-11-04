package io.github.fate_grand_automata.scripts.capture

/** IPv4/UDP only. Fragmented, truncated, corrupt and non-DNS packets are dropped. */
object DnsPackets {
    val FAKE_DNS = byteArrayOf(10, 255.toByte(), 77, 2)
    data class Query(val source: ByteArray, val destination: ByteArray, val sourcePort: Int, val payload: ByteArray)

    fun parse(packet: ByteArray, length: Int = packet.size): Query? {
        if (length !in 28..packet.size || packet[0].toInt() ushr 4 and 15 != 4) return null
        val header = (packet[0].toInt() and 15) * 4
        if (header < 20 || header + 8 > length || u16(packet, 2) != length) return null
        if (packet[9].toInt() != 17 || u16(packet, 6) and 0x3fff != 0) return null
        if (checksum(packet.copyOfRange(0, header)) != 0) return null
        val destination = packet.copyOfRange(16, 20)
        if (!destination.contentEquals(FAKE_DNS) || u16(packet, header + 2) != 53) return null
        val size = u16(packet, header + 4)
        if (size < 20 || size + header != length) return null // DNS header is at least 12 bytes
        val source = packet.copyOfRange(12, 16)
        val udp = packet.copyOfRange(header, length)
        if (u16(udp, 6) != 0 && checksum(pseudo(source, destination, size) + udp) != 0) return null
        return Query(source, destination, u16(packet, header), udp.copyOfRange(8, size))
    }

    fun reply(query: Query, payload: ByteArray): ByteArray {
        require(query.source.size == 4 && query.destination.size == 4 && payload.size in 12..65507)
        val size = 8 + payload.size
        val packet = ByteArray(20 + size)
        packet[0] = 0x45; put16(packet, 2, packet.size); packet[8] = 64; packet[9] = 17
        query.destination.copyInto(packet, 12); query.source.copyInto(packet, 16)
        put16(packet, 10, checksum(packet.copyOfRange(0, 20)))
        put16(packet, 20, 53); put16(packet, 22, query.sourcePort); put16(packet, 24, size)
        payload.copyInto(packet, 28)
        val sum = checksum(pseudo(query.destination, query.source, size) + packet.copyOfRange(20, packet.size))
        put16(packet, 26, if (sum == 0) 0xffff else sum)
        return packet
    }

    private fun pseudo(source: ByteArray, destination: ByteArray, size: Int) =
        source + destination + byteArrayOf(0, 17, (size ushr 8).toByte(), size.toByte())

    fun checksum(bytes: ByteArray): Int {
        var sum = 0L
        for (i in bytes.indices step 2) sum += ((bytes[i].toInt() and 255) shl 8) +
            (if (i + 1 < bytes.size) bytes[i + 1].toInt() and 255 else 0)
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.toInt().inv() and 0xffff
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 255) shl 8) or (b[i + 1].toInt() and 255)
    private fun put16(b: ByteArray, i: Int, v: Int) { b[i] = (v ushr 8).toByte(); b[i + 1] = v.toByte() }
}
