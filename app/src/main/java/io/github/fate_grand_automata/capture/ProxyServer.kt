package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.CaptureNames
import io.github.fate_grand_automata.scripts.capture.HttpReader
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Local CONNECT proxy on 127.0.0.1. Intercepts KR and enabled JP game hosts with their own TLS
 * contexts. Hosts without a context are blind tunnels. Read-only: requests and responses are
 * forwarded byte for byte. Requests are never stored. Logs show the method,
 * path (no query), status and size only, never bodies, tokens or headers.
 */
class ProxyServer(sslContexts: Map<String, SSLContext>) {
    private val sslContexts = sslContexts.filterKeys { it in CaptureNames.HOST_SERVERS }
    // A core-size-0 ThreadPoolExecutor hung the spike (the accept loop held its only worker), so
    // connections go to a cached pool and the accept loop has its own thread.
    private val pool = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    private val live = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())

    @Volatile
    private var running = false

    fun start(preferredPort: Int = 8899): Int {
        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), preferredPort))
            }
        } catch (_: IOException) {
            ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        }
        server = s
        running = true
        Thread({ acceptLoop(s) }, "capture-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        synchronized(live) { live.toList() }.forEach { runCatching { it.close() } }
        pool.shutdownNow()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val c = try {
                s.accept()
            } catch (_: IOException) {
                break
            }
            live += c
            try {
                pool.execute { handle(c) }
            } catch (_: Exception) {
                runCatching { c.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 60_000
            val head = HttpReader(client.getInputStream()).readHead() ?: return
            if (head.method != "CONNECT") {
                client.getOutputStream().write(
                    "HTTP/1.1 501 Not Implemented\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
                )
                return
            }
            val host = head.target.substringBeforeLast(':')
            val port = head.target.substringAfterLast(':', "443").toIntOrNull() ?: 443
            val sslContext = sslContexts[host.lowercase()]
            if (sslContext != null) {
                interceptGame(client, host, port, sslContext)
            } else {
                tunnel(client, host, port)
            }
        } catch (e: Exception) {
            if (running) Timber.d("Capture: connection error %s", e.javaClass.simpleName)
        } finally {
            live -= client
            runCatching { client.close() }
        }
    }

    private fun tunnel(client: Socket, host: String, port: Int) {
        val up = Socket()
        live += up
        try {
            up.connect(InetSocketAddress(host, port), 15_000)
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

    private fun interceptGame(client: Socket, host: String, port: Int, sslContext: SSLContext) {
        client.getOutputStream().apply { write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); flush() }
        val tls = sslContext.socketFactory.createSocket(client, null, client.port, true) as SSLSocket
        try {
            tls.useClientMode = false
            tls.sslParameters = tls.sslParameters.apply { applicationProtocols = arrayOf("http/1.1") }
            tls.soTimeout = 120_000
            tls.startHandshake()
        } catch (e: Exception) {
            // The game rejected our certificate: the CA is not installed as a CA certificate.
            CaptureTracker.addTlsFailure()
            Timber.d("Capture: TLS handshake with the game failed (%s)", e.javaClass.simpleName)
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
                throw IOException("upstream hostname verification failed")
            }
            s
        } catch (e: Exception) {
            Timber.d("Capture: upstream connection failed (%s)", e.javaClass.simpleName)
            runCatching { tls.close() }
            return
        }
        live += up
        try {
            relay(tls, up, host)
        } finally {
            live -= up
            runCatching { up.close() }
            runCatching { tls.close() }
        }
    }

    private fun relay(client: Socket, up: Socket, host: String) {
        val cr = HttpReader(client.getInputStream())
        val ur = HttpReader(up.getInputStream())
        val cout = client.getOutputStream()
        val uout = up.getOutputStream()
        while (true) {
            val req = cr.readHead() ?: return
            uout.write(req.raw)
            // The request body is forwarded but never kept.
            cr.copyBody(req, uout, isResponse = false, captureBody = false)
            uout.flush()
            val path = CaptureNames.stripQuery(req.target)
            val account = CaptureNames.isAccountPath(path)

            var resp = ur.readHead() ?: return
            while (resp.status in 100..199 && resp.status != 101) {
                cout.write(resp.raw)
                cout.flush()
                resp = ur.readHead() ?: return
            }
            cout.write(resp.raw)
            val body = ur.copyBody(resp, cout, isResponse = true, requestMethod = req.method, captureBody = !account)
            cout.flush()
            val total = resp.raw.size + body.rawBytes
            if (account) {
                Timber.d("Capture: %s account path %d", req.method, resp.status)
            } else {
                Timber.d("Capture: %s %s %d %d", req.method, path, resp.status, total)
                val payload = body.payload
                if (resp.status in 200..299 && payload != null && payload.isNotEmpty()) {
                    val target = "https://$host/${CaptureNames.path(path)}"
                    runCatching { CaptureTracker.onGameResponse(target, payload, resp.header("Content-Encoding")) }
                }
            }
            if (resp.status == 101 || body.untilClose) return
            if (req.header("Connection")?.equals("close", true) == true) return
            if (resp.header("Connection")?.equals("close", true) == true) return
        }
    }
}
