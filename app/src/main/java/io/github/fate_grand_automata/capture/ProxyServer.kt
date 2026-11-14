package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.CaptureNames
import io.github.fate_grand_automata.scripts.capture.HttpReader
import io.github.fate_grand_automata.scripts.capture.Socks5
import io.github.fate_grand_automata.scripts.capture.TlsClientHello
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.time.Duration

data class ProxyOptions(
    val socks5: Boolean = false,
    val ipSniff: Boolean = false,
    val maxConnections: Int? = null,
    val tunnelIdleTimeout: Duration? = null,
)

/**
 * Local proxy on 127.0.0.1 supporting HTTP CONNECT and (optionally) SOCKS5 + TLS SNI sniffing
 * for IP targets. Intercepts KR and enabled JP game hosts with their own TLS contexts. Hosts
 * without a context (or when reading is off) are blind tunnels. Read-only: requests and responses
 * are forwarded byte for byte. Requests are never stored. Logs show the method, path (no query),
 * status and size only, never bodies, tokens or headers.
 */
class ProxyServer(
    sslContexts: Map<String, SSLContext> = emptyMap(),
    private val options: ProxyOptions = ProxyOptions(),
    private val onGameHostConnected: ((Long) -> Unit)? = null,
    private val onUpstreamFailure: ((String) -> Unit)? = null,
    private val onTlsFailure: () -> Unit = { CaptureTracker.addTlsFailure() },
    private val onGameResponse: (String, ByteArray, String?) -> Unit = { target, payload, encoding ->
        CaptureTracker.onGameResponse(target, payload, encoding)
    },
) {
    @Volatile
    private var sslContexts: Map<String, SSLContext> = sslContexts.filterKeys { it in CaptureNames.HOST_SERVERS }

    @Volatile
    private var reading: Boolean = sslContexts.isNotEmpty()

    // A core-size-0 ThreadPoolExecutor hung the spike (the accept loop held its only worker), so
    // connections go to a cached pool and the accept loop has its own thread.
    private val pool = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    private val live = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())
    private val activeConnections = AtomicInteger(0)

    @Volatile
    private var running = false

    internal var handleHookForTesting: ((Socket) -> Unit)? = null
    internal var upstreamSslFactoryForTesting: SSLSocketFactory? = null
    internal var upstreamHostnameVerifierForTesting: HostnameVerifier? = null

    fun setContexts(contexts: Map<String, SSLContext>) {
        val filtered = contexts.filterKeys { it in CaptureNames.HOST_SERVERS }
        this.sslContexts = filtered
        this.reading = filtered.isNotEmpty()
    }

    fun start(preferredPort: Int = 8899, allowFallback: Boolean = true): Int {
        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), preferredPort))
            }
        } catch (e: IOException) {
            if (!allowFallback) throw e
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
            if (!tryAcquireConnectionSlot()) {
                runCatching { c.close() }
                continue
            }
            live += c
            try {
                pool.execute { handle(c) }
            } catch (_: Exception) {
                activeConnections.decrementAndGet()
                live -= c
                runCatching { c.close() }
            }
        }
    }

    private fun tryAcquireConnectionSlot(): Boolean {
        val cap = options.maxConnections ?: return true
        while (true) {
            val current = activeConnections.get()
            if (current >= cap) return false
            if (activeConnections.compareAndSet(current, current + 1)) return true
        }
    }

    private fun handle(client: Socket) {
        try {
            handleHookForTesting?.invoke(client)
            client.soTimeout = 60_000
            val contexts = sslContexts
            if (!options.socks5) {
                val reader = HttpReader(client.getInputStream())
                val head = reader.readHead() ?: return
                if (head.method != "CONNECT") {
                    client.getOutputStream().write(NOT_IMPLEMENTED_RESPONSE)
                    return
                }
                val (host, port) = parseHostPort(head.target, stripBrackets = options.ipSniff)
                val clientStream = if (options.ipSniff) reader.inputStream() else client.getInputStream()
                handleTarget(
                    client = client,
                    clientInput = clientStream,
                    connectHost = host,
                    port = port,
                    contexts = contexts,
                    isSocks5 = false,
                )
                return
            }

            val buffered = BufferedInputStream(client.getInputStream(), 16 * 1024)
            buffered.mark(1)
            val firstByte = buffered.read()
            if (firstByte < 0) return

            if (firstByte == Socks5.VERSION.toInt()) {
                handleSocks5(client, buffered, firstByte, contexts)
            } else {
                buffered.reset()
                val reader = HttpReader(buffered)
                val head = reader.readHead() ?: return
                if (head.method != "CONNECT") {
                    client.getOutputStream().write(NOT_IMPLEMENTED_RESPONSE)
                    return
                }
                val (host, port) = parseHostPort(head.target, stripBrackets = options.ipSniff)
                handleTarget(
                    client = client,
                    clientInput = reader.inputStream(),
                    connectHost = host,
                    port = port,
                    contexts = contexts,
                    isSocks5 = false,
                )
            }
        } catch (e: Exception) {
            if (running) Timber.d("Capture: connection error %s", e.javaClass.simpleName)
        } catch (e: OutOfMemoryError) {
            Timber.w("Capture: out of memory in connection handler")
        } finally {
            live -= client
            if (options.maxConnections != null) {
                activeConnections.decrementAndGet()
            }
            runCatching { client.close() }
        }
    }

    private fun handleSocks5(
        client: Socket,
        buffered: BufferedInputStream,
        firstByte: Int,
        contexts: Map<String, SSLContext>,
    ) {
        val accepted = Socks5.readGreeting(buffered, firstByte = firstByte)
        client.getOutputStream().apply {
            write(Socks5.methodReply(accepted))
            flush()
        }
        if (!accepted) return

        when (val req = Socks5.readRequest(buffered)) {
            is Socks5.RequestResult.Error -> {
                client.getOutputStream().apply {
                    write(Socks5.reply(req.replyCode))
                    flush()
                }
            }

            is Socks5.RequestResult.Connect -> {
                handleTarget(
                    client = client,
                    clientInput = buffered,
                    connectHost = req.target.host,
                    port = req.target.port,
                    contexts = contexts,
                    isSocks5 = true,
                )
            }
        }
    }

    private fun handleTarget(
        client: Socket,
        clientInput: InputStream,
        connectHost: String,
        port: Int,
        contexts: Map<String, SSLContext>,
        isSocks5: Boolean,
    ) {
        if (options.ipSniff && isIpLiteral(connectHost)) {
            sendTunnelReady(client, isSocks5)
            val peeked = peekClientHello(client, clientInput)
            val sni = peeked.sni?.lowercase()
            if (sni != null && sni in CaptureNames.HOST_SERVERS) {
                onGameHostConnected?.invoke(System.currentTimeMillis())
            }
            val sslContext = sni?.let { contexts[it] }
            if (sslContext != null) {
                val combinedInput = SequenceInputStream(ByteArrayInputStream(peeked.bytes), clientInput)
                val replayClient = ReplaySocket(client, combinedInput)
                interceptGame(
                    client = replayClient,
                    connectHost = connectHost,
                    tlsHost = sni,
                    port = port,
                    sslContext = sslContext,
                    alreadyReplied = true,
                )
            } else {
                tunnel(
                    client = client,
                    clientInput = clientInput,
                    connectHost = connectHost,
                    statsHost = sni ?: connectHost,
                    port = port,
                    alreadyReplied = true,
                    isSocks5 = isSocks5,
                    initialUpstreamBytes = peeked.bytes,
                )
            }
            return
        }

        val lowerHost = connectHost.lowercase()
        val sslContext = contexts[lowerHost]
        if (sslContext != null) {
            if (lowerHost in CaptureNames.HOST_SERVERS) {
                onGameHostConnected?.invoke(System.currentTimeMillis())
            }
            sendTunnelReady(client, isSocks5)
            interceptGame(
                client = client,
                connectHost = connectHost,
                tlsHost = connectHost,
                port = port,
                sslContext = sslContext,
                alreadyReplied = true,
                clientInputOverride = clientInput,
            )
        } else {
            tunnel(
                client = client,
                clientInput = clientInput,
                connectHost = connectHost,
                statsHost = lowerHost,
                port = port,
                alreadyReplied = false,
                isSocks5 = isSocks5,
                initialUpstreamBytes = EMPTY_BYTES,
            )
        }
    }

    private fun sendTunnelReady(client: Socket, isSocks5: Boolean) {
        client.getOutputStream().apply {
            write(if (isSocks5) Socks5.reply(Socks5.REP_SUCCEEDED) else HTTP_200_ESTABLISHED)
            flush()
        }
    }

    private data class PeekedHello(val bytes: ByteArray, val sni: String?)

    private fun peekClientHello(client: Socket, input: InputStream): PeekedHello {
        val previousTimeout = client.soTimeout
        client.soTimeout = PEER_SNIFF_TIMEOUT_MS
        val collected = ByteArrayOutputStream()
        try {
            val first = input.read()
            if (first < 0) return PeekedHello(EMPTY_BYTES, null)
            collected.write(first)
            if (first != TlsClientHello.RECORD_TYPE_HANDSHAKE) {
                return PeekedHello(collected.toByteArray(), null)
            }
            val remainingHeader = readUpTo(input, 4)
            collected.write(remainingHeader)
            if (remainingHeader.size < 4) {
                return PeekedHello(collected.toByteArray(), null)
            }
            val header = collected.toByteArray()
            val recordLen = ((header[3].toInt() and 0xFF) shl 8) or (header[4].toInt() and 0xFF)
            if (recordLen <= 0 || recordLen > TlsClientHello.MAX_RECORD_PAYLOAD_BYTES) {
                return PeekedHello(header, null)
            }
            val payload = readUpTo(input, recordLen)
            collected.write(payload)
            val fullRecord = collected.toByteArray()
            val sni = if (payload.size == recordLen) {
                TlsClientHello.sniHost(fullRecord, fullRecord.size)
            } else {
                null
            }
            return PeekedHello(fullRecord, sni)
        } catch (_: SocketTimeoutException) {
            return PeekedHello(collected.toByteArray(), null)
        } finally {
            runCatching { client.soTimeout = previousTimeout }
        }
    }

    private fun readUpTo(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = input.read(buf, offset, count - offset)
            if (n < 0) break
            offset += n
        }
        return if (offset == count) buf else buf.copyOf(offset)
    }

    private fun tunnel(
        client: Socket,
        clientInput: InputStream,
        connectHost: String,
        statsHost: String,
        port: Int,
        alreadyReplied: Boolean,
        isSocks5: Boolean,
        initialUpstreamBytes: ByteArray,
    ) {
        val up = Socket()
        live += up
        try {
            try {
                up.connect(InetSocketAddress(connectHost, port), 15_000)
            } catch (e: IOException) {
                if (statsHost.lowercase() in CaptureNames.HOST_SERVERS) {
                    onUpstreamFailure?.invoke(statsHost.lowercase())
                }
                if (!alreadyReplied && isSocks5) {
                    val code = if (e is ConnectException) {
                        Socks5.REP_CONNECTION_REFUSED
                    } else {
                        Socks5.REP_HOST_UNREACHABLE
                    }
                    runCatching {
                        client.getOutputStream().apply {
                            write(Socks5.reply(code))
                            flush()
                        }
                    }
                }
                return
            }

            if (statsHost.lowercase() in CaptureNames.HOST_SERVERS) {
                onGameHostConnected?.invoke(System.currentTimeMillis())
            }
            if (!alreadyReplied) {
                sendTunnelReady(client, isSocks5)
            }
            if (initialUpstreamBytes.isNotEmpty()) {
                up.getOutputStream().apply {
                    write(initialUpstreamBytes)
                    flush()
                }
            }

            val idleMs = options.tunnelIdleTimeout?.inWholeMilliseconds ?: 0L
            val soTimeout = if (idleMs > 0L) idleMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt() else 0
            client.soTimeout = soTimeout
            up.soTimeout = soTimeout
            val lastActivity = AtomicLong(System.currentTimeMillis())

            val t = Thread { pump(up.getInputStream(), client.getOutputStream(), client, up, idleMs, lastActivity) }
            t.isDaemon = true
            t.start()
            pump(clientInput, up.getOutputStream(), client, up, idleMs, lastActivity)
            t.join(2000)
        } finally {
            live -= up
            runCatching { up.close() }
        }
    }

    private fun pump(
        from: InputStream,
        to: OutputStream,
        a: Socket,
        b: Socket,
        idleTimeoutMs: Long,
        lastActivity: AtomicLong,
    ) {
        try {
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = try {
                    from.read(buf)
                } catch (e: SocketTimeoutException) {
                    if (idleTimeoutMs > 0L && System.currentTimeMillis() - lastActivity.get() < idleTimeoutMs) {
                        continue
                    }
                    break
                }
                if (n < 0) break
                if (n > 0) {
                    lastActivity.set(System.currentTimeMillis())
                }
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: IOException) {
        } catch (e: OutOfMemoryError) {
            Timber.w("Capture: out of memory in tunnel pump")
        } catch (e: Throwable) {
            Timber.d("Capture: tunnel pump error %s", e.javaClass.simpleName)
        } finally {
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }

    private fun interceptGame(
        client: Socket,
        connectHost: String,
        tlsHost: String,
        port: Int,
        sslContext: SSLContext,
        alreadyReplied: Boolean = false,
        clientInputOverride: InputStream? = null,
    ) {
        if (!alreadyReplied) {
            client.getOutputStream().apply {
                write(HTTP_200_ESTABLISHED)
                flush()
            }
        }
        val handshakeSocket = if (clientInputOverride != null && options.socks5) {
            ReplaySocket(client, clientInputOverride)
        } else {
            client
        }
        val tls = sslContext.socketFactory.createSocket(handshakeSocket, null, handshakeSocket.port, true) as SSLSocket
        try {
            tls.useClientMode = false
            tls.sslParameters = tls.sslParameters.apply { applicationProtocols = arrayOf("http/1.1") }
            tls.soTimeout = 120_000
            tls.startHandshake()
        } catch (e: Exception) {
            // The game rejected our certificate: the CA is not installed as a CA certificate.
            onTlsFailure()
            Timber.d("Capture: TLS handshake with the game failed (%s)", e.javaClass.simpleName)
            runCatching { tls.close() }
            return
        }
        val up = try {
            val factory = upstreamSslFactoryForTesting ?: (SSLSocketFactory.getDefault() as SSLSocketFactory)
            val s = factory.createSocket(connectHost, port) as SSLSocket
            s.sslParameters = s.sslParameters.apply {
                serverNames = listOf(SNIHostName(tlsHost))
                applicationProtocols = arrayOf("http/1.1")
            }
            s.soTimeout = 120_000
            s.startHandshake()
            val verifier = upstreamHostnameVerifierForTesting ?: HttpsURLConnection.getDefaultHostnameVerifier()
            if (!verifier.verify(tlsHost, s.session)) {
                s.close()
                throw IOException("upstream hostname verification failed")
            }
            s
        } catch (e: Exception) {
            if (tlsHost.lowercase() in CaptureNames.HOST_SERVERS) {
                onUpstreamFailure?.invoke(tlsHost.lowercase())
            }
            Timber.d("Capture: upstream connection failed (%s)", e.javaClass.simpleName)
            runCatching { tls.close() }
            return
        }
        live += up
        try {
            relay(tls, up, tlsHost)
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
            if (!reading) return
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
            val stillReading = reading
            if (account) {
                Timber.d("Capture: %s account path %d", req.method, resp.status)
            } else {
                Timber.d("Capture: %s %s %d %d", req.method, path, resp.status, total)
                body.dropped?.let { dropReason ->
                    if (stillReading) {
                        val reason = when (dropReason) {
                            io.github.fate_grand_automata.scripts.capture.DropReason.TooLarge -> CaptureDropReason.TooLarge
                            io.github.fate_grand_automata.scripts.capture.DropReason.OutOfMemory -> CaptureDropReason.OutOfMemory
                        }
                        CaptureTracker.recordDroppedResponse(path, total, reason)
                    }
                }
                val payload = body.payload
                if (resp.status in 200..299 && payload != null && payload.isNotEmpty()) {
                    if (!stillReading) return
                    val target = "https://$host/${CaptureNames.path(path)}"
                    runCatching { onGameResponse(target, payload, resp.header("Content-Encoding")) }
                }
            }
            if (!stillReading) return
            if (resp.status == 101 || body.untilClose) return
            if (req.header("Connection")?.equals("close", true) == true) return
            if (resp.header("Connection")?.equals("close", true) == true) return
        }
    }

    private class ReplaySocket(
        private val delegate: Socket,
        private val input: InputStream,
    ) : Socket() {
        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = delegate.getOutputStream()
        override fun close() = delegate.close()
        override fun isClosed(): Boolean = delegate.isClosed
        override fun isConnected(): Boolean = delegate.isConnected
        override fun isInputShutdown(): Boolean = delegate.isInputShutdown
        override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown
        override fun shutdownInput() = delegate.shutdownInput()
        override fun shutdownOutput() = delegate.shutdownOutput()
        override fun getInetAddress(): InetAddress = delegate.inetAddress
        override fun getLocalAddress(): InetAddress = delegate.localAddress
        override fun getPort(): Int = delegate.port
        override fun getLocalPort(): Int = delegate.localPort
        override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress
        override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress
        override fun setSoTimeout(timeout: Int) {
            delegate.soTimeout = timeout
        }

        override fun getSoTimeout(): Int = delegate.soTimeout
        override fun setTcpNoDelay(on: Boolean) {
            delegate.tcpNoDelay = on
        }

        override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
        override fun setKeepAlive(on: Boolean) {
            delegate.keepAlive = on
        }

        override fun getKeepAlive(): Boolean = delegate.keepAlive
    }

    companion object {
        private const val PEER_SNIFF_TIMEOUT_MS = 3_000
        private val EMPTY_BYTES = ByteArray(0)
        private val HTTP_200_ESTABLISHED = "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()
        private val NOT_IMPLEMENTED_RESPONSE =
            "HTTP/1.1 501 Not Implemented\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()

        internal fun parseHostPort(target: String, stripBrackets: Boolean): Pair<String, Int> {
            if (stripBrackets && target.startsWith("[")) {
                val close = target.indexOf(']')
                if (close > 0) {
                    val host = target.substring(1, close)
                    val rest = target.substring(close + 1)
                    val port = rest.removePrefix(":").toIntOrNull() ?: 443
                    return host to port
                }
            }
            val host = target.substringBeforeLast(':')
            val port = target.substringAfterLast(':', "443").toIntOrNull() ?: 443
            return host to port
        }

        internal fun isIpLiteral(host: String): Boolean {
            if (host.isEmpty()) return false
            if (host.contains(':')) return true
            val parts = host.split('.')
            if (parts.size != 4) return false
            return parts.all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } && (part.toIntOrNull() in 0..255)
            }
        }
    }
}
