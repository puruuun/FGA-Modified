package io.github.fate_grand_automata.capturespike

import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import android.system.OsConstants
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Diagnostics for the 2026-11-04 tablet check: names what the VPN drops (packets that did not go
 * through the proxy) and which app opens each proxy connection. Every distinct line is logged once.
 */
class DropLog(private val ctx: Context) {
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)

    private fun once(line: String) {
        if (seen.add(line)) SpikeLog.log(line)
    }

    /** Looks at one packet read from the tun and logs new TCP connection attempts and DNS queries. */
    fun packet(buf: ByteArray, len: Int) {
        if (len < 20) return
        val version = (buf[0].toInt() ushr 4) and 0xF
        val proto: Int
        val src: InetAddress
        val dst: InetAddress
        val l4: Int
        when (version) {
            4 -> {
                l4 = (buf[0].toInt() and 0xF) * 4
                proto = buf[9].toInt() and 0xFF
                src = InetAddress.getByAddress(buf.copyOfRange(12, 16))
                dst = InetAddress.getByAddress(buf.copyOfRange(16, 20))
            }
            6 -> {
                if (len < 40) return
                l4 = 40
                proto = buf[6].toInt() and 0xFF
                src = InetAddress.getByAddress(buf.copyOfRange(8, 24))
                dst = InetAddress.getByAddress(buf.copyOfRange(24, 40))
            }
            else -> return
        }
        if (len < l4 + 8) return
        val sport = u16(buf, l4)
        val dport = u16(buf, l4 + 2)
        when (proto) {
            OsConstants.IPPROTO_TCP -> {
                if (len < l4 + 14) return
                val flags = buf[l4 + 13].toInt() and 0xFF
                val syn = flags and 0x02 != 0
                val ack = flags and 0x10 != 0
                if (syn && !ack) {
                    val app = owner(OsConstants.IPPROTO_TCP, src, sport, dst, dport)
                    once("drop tcp ${dst.hostAddress}:$dport from $app")
                }
            }
            OsConstants.IPPROTO_UDP -> {
                if (dport == 53) {
                    val name = dnsName(buf, l4 + 8 + 12, len)
                    once("drop dns $name")
                } else {
                    val app = owner(OsConstants.IPPROTO_UDP, src, sport, dst, dport)
                    once("drop udp ${dst.hostAddress}:$dport from $app")
                }
            }
        }
    }

    /** Logs the app behind a proxy connection, for CONNECT targets that are not plain hosts. */
    fun proxyClient(target: String, client: java.net.Socket) {
        val app = owner(
            OsConstants.IPPROTO_TCP,
            client.inetAddress, client.port,
            client.localAddress, client.localPort,
        )
        once("connect $target from $app")
    }

    private fun owner(proto: Int, src: InetAddress, sport: Int, dst: InetAddress, dport: Int): String = try {
        val uid = cm.getConnectionOwnerUid(proto, InetSocketAddress(src, sport), InetSocketAddress(dst, dport))
        if (uid == Process.INVALID_UID) "uid?" else ctx.packageManager.getNameForUid(uid) ?: "uid $uid"
    } catch (e: Exception) {
        "uid? (${e.javaClass.simpleName})"
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun dnsName(b: ByteArray, start: Int, len: Int): String {
        val sb = StringBuilder()
        var i = start
        while (i < len) {
            val n = b[i].toInt() and 0xFF
            if (n == 0 || n >= 0xC0) break
            if (i + 1 + n > len) break
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(b, i + 1, n, Charsets.US_ASCII))
            i += 1 + n
        }
        return sb.ifEmpty { "?" }.toString()
    }
}
