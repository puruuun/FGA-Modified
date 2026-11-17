package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.DnsPackets
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CaptureVpnDrainTest {
    @Test(timeout = 5000)
    fun submitErrorStopsDrainLoopAndInvokesFatalCallbackOnce() {
        val packet = fakeDnsQueryPacket()
        val fatalErrors = mutableListOf<Throwable>()
        val error = OutOfMemoryError("unable to create native thread")
        var reads = 0

        runTunDrainLoop(
            readPacket = { buf ->
                reads++
                packet.copyInto(buf)
                packet.size
            },
            submitDns = { throw error },
            onFatal = { fatalErrors += it },
        )

        assertEquals(1, reads)
        assertEquals(1, fatalErrors.size)
        assertSame(error, fatalErrors.single())
    }

    @Test(timeout = 5000)
    fun readExceptionEndsDrainLoopWithoutFatalCallback() {
        val fatalErrors = mutableListOf<Throwable>()

        runTunDrainLoop(
            readPacket = { throw IOException("fd closed") },
            submitDns = { error("submitDns must not be called") },
            onFatal = { fatalErrors += it },
        )

        assertTrue(fatalErrors.isEmpty())
    }

    private fun fakeDnsQueryPacket(): ByteArray {
        val dnsPayload = ByteArray(12)
        val udpLength = 8 + dnsPayload.size
        val totalLength = 20 + udpLength
        val packet = ByteArray(totalLength)
        packet[0] = 0x45
        packet[2] = (totalLength ushr 8).toByte()
        packet[3] = totalLength.toByte()
        packet[9] = 17
        packet[12] = 10
        packet[13] = 255.toByte()
        packet[14] = 77
        packet[15] = 1
        DnsPackets.FAKE_DNS.copyInto(packet, 16)
        val ipSum = DnsPackets.checksum(packet.copyOfRange(0, 20))
        packet[10] = (ipSum ushr 8).toByte()
        packet[11] = ipSum.toByte()
        packet[20] = (12345 ushr 8).toByte()
        packet[21] = 12345.toByte()
        packet[22] = 0
        packet[23] = 53
        packet[24] = (udpLength ushr 8).toByte()
        packet[25] = udpLength.toByte()
        dnsPayload.copyInto(packet, 28)
        return packet
    }
}
