package io.github.fate_grand_automata.capturespike

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Local CONNECT proxy on 127.0.0.1. Intercepts only [CertStore.GAME_HOST]; read-only. */
class ProxyServer(private val ctx: Context, private val sslContext: SSLContext?) {
    // A ThreadPoolExecutor with core size 0 and a queue runs a single worker until the queue fills,
    // and the accept loop held that worker, so accepted connections were never handled (tablet, 2026-11-04).
    // Connections get their own threads, and the accept loop runs on a dedicated thread.
    private val pool = Executors.newCachedThreadPool()
    private val dropLog = DropLog(ctx)
    private val stats: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var server: ServerSocket? = null
    private val live = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())

    private val exchanges = AtomicInteger()
    private val saved = AtomicInteger()
    private val passed = AtomicInteger()
    private val tlsFailures = AtomicInteger()
    private val seq = AtomicInteger()

    @Volatile
    private var running = false

    val port: Int get() = server!!.localPort

    fun start(preferredPort: Int = 8899): Int {
        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), preferredPort))
            }
        } catch (e: IOException) {
            ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        }
        server = s
        running = true
        Thread({ acceptLoop(s) }, "spike-accept").apply { isDaemon = true }.start()
        stats.scheduleAtFixedRate({
            SpikeLog.log("stats: game exchanges=${exchanges.get()} saved=${saved.get()} passed=${passed.get()} tlsFailures=${tlsFailures.get()}")
        }, 30, 30, TimeUnit.SECONDS)
        return s.localPort
    }

    fun stop() {
        running = false
        stats.shutdownNow()
        runCatching { server?.close() }
        synchronized(live) { live.toList() }.forEach { runCatching { it.close() } }
        pool.shutdownNow()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val c = try {
                s.accept()
            } catch (e: IOException) {
                break
            }
            live += c
            try {
                pool.execute { handle(c) }
            } catch (e: Exception) {
                c.close()
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 60_000
            val head = HttpReader(client.getInputStream()).readHead() ?: return
            if (head.method != "CONNECT") {
                SpikeLog.log("plain HTTP proxy request rejected (501): ${head.method} ${CaptureNames.stripQuery(head.target)}")
                client.getOutputStream().write("HTTP/1.1 501 Not Implemented\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            val hostPort = head.target
            val host = hostPort.substringBeforeLast(':')
            if (host.equals("localhost", ignoreCase = true) || host.startsWith("127.")) {
                runCatching { dropLog.proxyClient(hostPort, client) }
            }
            val port = hostPort.substringAfterLast(':', "443").toIntOrNull() ?: 443
            if (host.equals(CertStore.GAME_HOST, ignoreCase = true) && sslContext != null) {
                interceptGame(client, host, port)
            } else {
                tunnel(client, host, port)
            }
        } catch (e: Exception) {
            if (running) SpikeLog.log("conn error: $e")
        } finally {
            live -= client
            runCatching { client.close() }
        }
    }

    // ---- blind tunnel ----

    private fun tunnel(client: Socket, host: String, port: Int) {
        val up = Socket()
        live += up
        try {
            up.connect(InetSocketAddress(host, port), 15_000)
            SpikeLog.log("pass $host")
            passed.incrementAndGet()
            client.getOutputStream().apply { write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); flush() }
            client.soTimeout = 0
            val t = Thread { pump(up.getInputStream(), client.getOutputStream(), client, up) }
            t.isDaemon = true
            t.start()
            pump(client.getInputStream(), up.getOutputStream(), client, up)
            t.join(2000)
        } finally {
            live -= up
            runCatching { up.close() }
        }
    }

    private fun pump(from: InputStream, to: OutputStream, a: Socket, b: Socket) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: IOException) {
        } finally {
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }

    // ---- game host ----

    private fun interceptGame(client: Socket, host: String, port: Int) {
        client.getOutputStream().apply { write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); flush() }
        val tls = sslContext!!.socketFactory.createSocket(client, null, client.port, true) as SSLSocket
        try {
            tls.useClientMode = false
            tls.sslParameters = tls.sslParameters.apply { applicationProtocols = arrayOf("http/1.1") }
            tls.soTimeout = 120_000
            tls.startHandshake()
            SpikeLog.log("TLS ✓ game")
        } catch (e: Exception) {
            tlsFailures.incrementAndGet()
            SpikeLog.log("TLS ✗ game: $e")
            runCatching { tls.close() }
            return
        }
        val up = try {
            val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(host, port) as SSLSocket
            s.sslParameters = s.sslParameters.apply {
                serverNames = listOf(SNIHostName(host))
                applicationProtocols = arrayOf("http/1.1")
            }
            s.soTimeout = 120_000
            s.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.session)) {
                s.close()
                throw IOException("upstream hostname verification failed for $host")
            }
            s
        } catch (e: Exception) {
            SpikeLog.log("upstream ✗ game: $e")
            runCatching { tls.close() }
            return
        }
        live += up
        try {
            relay(tls, up)
        } finally {
            live -= up
            runCatching { up.close() }
            runCatching { tls.close() }
        }
    }

    private fun relay(client: Socket, up: Socket) {
        val cr = HttpReader(client.getInputStream())
        val ur = HttpReader(up.getInputStream())
        val cout = client.getOutputStream()
        val uout = up.getOutputStream()
        while (true) {
            val req = cr.readHead() ?: return
            uout.write(req.raw)
            cr.copyBody(req, uout, isResponse = false)
            uout.flush()
            val path = CaptureNames.stripQuery(req.target)

            var resp = ur.readHead() ?: return
            // forward interim 1xx responses, then the final one
            while (resp.status in 100..199 && resp.status != 101) {
                cout.write(resp.raw)
                cout.flush()
                resp = ur.readHead() ?: return
            }
            cout.write(resp.raw)
            val body = ur.copyBody(resp, cout, isResponse = true, requestMethod = req.method)
            cout.flush()
            exchanges.incrementAndGet()
            val total = resp.raw.size + body.rawBytes
            if (CaptureNames.isAccountPath(path)) {
                SpikeLog.log("${req.method} $path ${resp.status} $total skipped (account)")
            } else {
                SpikeLog.log("${req.method} $path ${resp.status} $total")
                save(path, resp, body)
            }
            if (resp.status == 101 || body.untilClose) return
            if (req.header("Connection")?.equals("close", true) == true) return
            if (resp.header("Connection")?.equals("close", true) == true) return
        }
    }

    private fun save(path: String, resp: HttpHead, body: BodyResult) {
        val payload = body.payload ?: return SpikeLog.log("not saved (too large): $path")
        if (payload.isEmpty()) return
        try {
            val data = HttpReader.gunzipIfNeeded(payload, resp.header("Content-Encoding"))
            val dir = File(ctx.filesDir, "captures").apply { mkdirs() }
            val name = CaptureNames.fileName(LocalDateTime.now(), seq.incrementAndGet(), path)
            File(dir, name).writeBytes(data)
            saved.incrementAndGet()
        } catch (e: Exception) {
            SpikeLog.log("save failed for $path: $e")
        }
    }
}
