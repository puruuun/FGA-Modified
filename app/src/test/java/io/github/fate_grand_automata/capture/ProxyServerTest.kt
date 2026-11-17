package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.CaptureNames
import io.github.fate_grand_automata.scripts.capture.HttpReader
import io.github.fate_grand_automata.scripts.capture.SingleUseCa
import io.github.fate_grand_automata.scripts.capture.Socks5
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ProxyServerTest {
    @Test(timeout = 10000)
    fun outOfMemoryInHandlerDoesNotStopServerFromAcceptingNextConnection() {
        val server = ProxyServer(emptyMap())
        val port = server.start(0)
        var throwOom = true
        server.handleHookForTesting = {
            if (throwOom) {
                throwOom = false
                throw OutOfMemoryError("synthetic OOM in handle")
            }
        }

        try {
            // First connection: triggers OOM in handle
            val client1 = Socket()
            client1.connect(InetSocketAddress("127.0.0.1", port), 5000)
            client1.getOutputStream().write("CONNECT dummy:443 HTTP/1.1\r\n\r\n".toByteArray())
            client1.getOutputStream().flush()
            // Server will catch OOM and close client1 in finally
            client1.getInputStream().read()
            runCatching { client1.close() }

            // Second connection: server must still accept and process normally
            val client2 = Socket()
            client2.connect(InetSocketAddress("127.0.0.1", port), 5000)
            client2.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray())
            client2.getOutputStream().flush()
            val buf = ByteArray(1024)
            val n = client2.getInputStream().read(buf)
            assertTrue(n > 0)
            val response = String(buf, 0, n)
            assertTrue(response.startsWith("HTTP/1.1 501"))
            client2.close()
        } finally {
            server.stop()
        }
    }

    @Test(timeout = 10000)
    fun nonOomErrorInHandlerClosesClientReleasesSlotAndKeepsAccepting() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val uncaught = AtomicReference<Throwable?>(null)
        val uncaughtLatch = CountDownLatch(1)
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            uncaught.compareAndSet(null, e)
            uncaughtLatch.countDown()
        }
        val server = ProxyServer(emptyMap(), ProxyOptions(maxConnections = 1))
        val port = server.start(0)
        val firstClientSocket = AtomicReference<Socket?>(null)
        var throwError = true
        server.handleHookForTesting = { client ->
            if (throwError) {
                throwError = false
                firstClientSocket.set(client)
                throw StackOverflowError("synthetic error in handle")
            }
        }

        try {
            val client1 = Socket()
            client1.connect(InetSocketAddress("127.0.0.1", port), 5000)
            client1.soTimeout = 3000
            client1.getOutputStream().write("CONNECT dummy:443 HTTP/1.1\r\n\r\n".toByteArray())
            client1.getOutputStream().flush()
            assertEquals(-1, client1.getInputStream().read())
            runCatching { client1.close() }

            assertTrue(server.awaitOpenConnections(2000))
            assertTrue(firstClientSocket.get()?.isClosed == true)

            val client2 = Socket()
            client2.connect(InetSocketAddress("127.0.0.1", port), 5000)
            client2.soTimeout = 3000
            client2.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray())
            client2.getOutputStream().flush()
            val buf = ByteArray(1024)
            val n = client2.getInputStream().read(buf)
            assertTrue(n > 0)
            assertTrue(String(buf, 0, n).startsWith("HTTP/1.1 501"))
            client2.close()

            assertFalse(uncaughtLatch.await(100, TimeUnit.MILLISECONDS))
            assertNull(uncaught.get())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            server.stop()
        }
    }

    @Test(timeout = 10000)
    fun startWithAllowFallbackFalseThrowsWhenPortIsOccupied() {
        val blocker = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val occupiedPort = blocker.localPort
        val strictProxy = ProxyServer(emptyMap(), ProxyOptions(socks5 = true, ipSniff = true))
        val fallbackProxy = ProxyServer(emptyMap())
        try {
            assertFailsWith<IOException> {
                strictProxy.start(occupiedPort, allowFallback = false)
            }
            val fallbackPort = fallbackProxy.start(occupiedPort, allowFallback = true)
            assertTrue(fallbackPort > 0 && fallbackPort != occupiedPort)
        } finally {
            strictProxy.stop()
            fallbackProxy.stop()
            blocker.close()
        }
    }

    @Test(timeout = 10000)
    fun socks5TunnelIpv4AndRejectsInvalidRequests() {
        val echoServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val echoThread = Thread {
            runCatching {
                while (!echoServer.isClosed) {
                    val s = echoServer.accept()
                    Thread {
                        s.use {
                            val data = s.getInputStream().readNBytes(5)
                            s.getOutputStream().write(data)
                            s.getOutputStream().flush()
                        }
                    }.start()
                }
            }
        }.apply { isDaemon = true; start() }

        val proxy = ProxyServer(emptyMap(), ProxyOptions(socks5 = true, ipSniff = true))
        val port = proxy.start(0)
        try {
            // Valid SOCKS5 IPv4 tunnel
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 3000
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                assertContentEquals(byteArrayOf(0x05, 0x00), inp.readNBytes(2))

                val p = echoServer.localPort
                out.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1, (p ushr 8).toByte(), p.toByte()))
                out.flush()
                assertContentEquals(Socks5.reply(Socks5.REP_SUCCEEDED), inp.readNBytes(10))

                out.write("HELLO".toByteArray())
                out.flush()
                assertEquals("HELLO", String(inp.readNBytes(5)))
            }

            // Unsupported auth method -> 0x05 0xFF
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 3000
                s.getOutputStream().apply { write(byteArrayOf(0x05, 0x01, 0x02)); flush() }
                assertContentEquals(byteArrayOf(0x05, 0xFF.toByte()), s.getInputStream().readNBytes(2))
            }

            // Unsupported command (UDP associate 0x03) -> reply 0x07
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 3000
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                assertContentEquals(byteArrayOf(0x05, 0x00), inp.readNBytes(2))
                out.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 127, 0, 0, 1, 0, 80))
                out.flush()
                assertContentEquals(Socks5.reply(Socks5.REP_COMMAND_NOT_SUPPORTED), inp.readNBytes(10))
            }

            // Domain CONNECT to dead local port -> SOCKS5 connection refused 0x05
            val deadPort = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 3000
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                assertContentEquals(byteArrayOf(0x05, 0x00), inp.readNBytes(2))
                val domain = "localhost".toByteArray()
                out.write(
                    byteArrayOf(0x05, 0x01, 0x00, 0x03, domain.size.toByte()) +
                        domain + byteArrayOf((deadPort ushr 8).toByte(), deadPort.toByte())
                )
                out.flush()
                val reply = inp.readNBytes(10)
                assertEquals(10, reply.size)
                assertEquals(Socks5.REP_CONNECTION_REFUSED, reply[1])
            }
        } finally {
            proxy.stop()
            echoServer.close()
            echoThread.join(1000)
        }
    }

    @Test(timeout = 10000)
    fun nonTlsBytesToIpTargetTunnelIntactWithoutCountingAsGameHost() {
        val received = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        val echoServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val t = Thread {
            runCatching {
                echoServer.accept().use { s ->
                    val data = s.getInputStream().readNBytes(7)
                    received.write(data)
                    s.getOutputStream().apply { write("ACK".toByteArray()); flush() }
                    latch.countDown()
                }
            }
        }.apply { isDaemon = true; start() }

        val gameConnects = AtomicInteger(0)
        val proxy = ProxyServer(
            sslContexts = emptyMap(),
            options = ProxyOptions(socks5 = true, ipSniff = true),
            onGameHostConnected = { gameConnects.incrementAndGet() },
        )
        val port = proxy.start(0)
        try {
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 3000
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                out.write("CONNECT 127.0.0.1:${echoServer.localPort} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                out.flush()
                val head = HttpReader(inp).readHead()!!
                assertEquals(200, head.status)
                out.write("PLAIN!!\r\n".take(7).toByteArray())
                out.flush()
                assertTrue(latch.await(3, TimeUnit.SECONDS))
                assertEquals("PLAIN!!", received.toString())
                assertEquals("ACK", String(inp.readNBytes(3)))
            }
            assertEquals(0, gameConnects.get())
        } finally {
            proxy.stop()
            echoServer.close()
            t.join(1000)
        }
    }

    @Test(timeout = 10000)
    fun ipConnectWithGameSniCompletesTlsHandshakeViaReplayWrapperAndReadsResponse() {
        val gameHost = CaptureNames.JP_HOST
        val upstreamCerts = SingleUseCa.issue(gameHost)
        val mitmCerts = SingleUseCa.issue(gameHost)
        val upstreamServerCtx = serverSslContext(upstreamCerts)
        val upstreamClientCtx = clientTrustContext(upstreamCerts)
        val mitmServerCtx = serverSslContext(mitmCerts)
        val mitmClientCtx = clientTrustContext(mitmCerts)

        val capturedTargets = CopyOnWriteArrayList<String>()
        val gameConnects = AtomicInteger(0)
        val upstreamFailures = AtomicInteger(0)
        val tlsFailures = AtomicInteger(0)
        val req1CapturedLatch = CountDownLatch(1)
        val req2InFlightAtServer = CountDownLatch(1)
        val allowReq2Response = CountDownLatch(1)

        val upstreamAcceptCount = AtomicInteger(0)
        val mockServer = (upstreamServerCtx.serverSocketFactory.createServerSocket(
            0,
            50,
            InetAddress.getByName("127.0.0.1"),
        ) as SSLServerSocket).apply { enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3") }

        val serverThread = Thread {
            runCatching {
                while (!mockServer.isClosed) {
                    val s = mockServer.accept() as SSLSocket
                    upstreamAcceptCount.incrementAndGet()
                    Thread {
                        s.use { sock ->
                            sock.startHandshake()
                            val r = HttpReader(sock.getInputStream())
                            while (true) {
                                val req = r.readHead() ?: break
                                if (req.target.contains("battle/result")) {
                                    req2InFlightAtServer.countDown()
                                    allowReq2Response.await(5, TimeUnit.SECONDS)
                                }
                                val payload = """{"ok":true,"req":"${req.target}"}"""
                                val resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${payload.length}\r\n\r\n$payload"
                                sock.getOutputStream().apply {
                                    write(resp.toByteArray())
                                    flush()
                                }
                            }
                        }
                    }.start()
                }
            }
        }.apply { isDaemon = true; start() }

        val proxy = ProxyServer(
            sslContexts = emptyMap(),
            options = ProxyOptions(socks5 = true, ipSniff = true, maxConnections = 256),
            onGameHostConnected = { gameConnects.incrementAndGet() },
            onUpstreamFailure = { upstreamFailures.incrementAndGet() },
            onTlsFailure = { tlsFailures.incrementAndGet() },
            onGameResponse = { target, _, _ ->
                capturedTargets += target
                req1CapturedLatch.countDown()
            },
        ).apply {
            upstreamSslFactoryForTesting = upstreamClientCtx.socketFactory
            upstreamHostnameVerifierForTesting = HostnameVerifier { host, session ->
                val leaf = session.peerCertificates.firstOrNull() as? X509Certificate ?: return@HostnameVerifier false
                leaf.subjectAlternativeNames?.any { entry -> entry.size >= 2 && entry[1] == host } == true
            }
        }
        proxy.setContexts(mapOf(gameHost to mitmServerCtx))
        val proxyPort = proxy.start(0)

        try {
            val raw = Socket("127.0.0.1", proxyPort)
            raw.soTimeout = 5000
            raw.getOutputStream().apply {
                write("CONNECT 127.0.0.1:${mockServer.localPort} HTTP/1.1\r\nHost: 127.0.0.1:${mockServer.localPort}\r\n\r\n".toByteArray())
                flush()
            }
            val connectResp = readUntilBlankLine(raw.getInputStream())
            assertTrue(connectResp.startsWith("HTTP/1.1 200"))

            val tlsClient = (mitmClientCtx.socketFactory.createSocket(raw, "127.0.0.1", mockServer.localPort, true) as SSLSocket)
            tlsClient.sslParameters = tlsClient.sslParameters.apply {
                serverNames = listOf(SNIHostName(gameHost))
            }
            tlsClient.startHandshake()

            val reader = HttpReader(tlsClient.getInputStream())
            tlsClient.getOutputStream().apply {
                write("GET /login/top?userId=1 HTTP/1.1\r\nHost: $gameHost\r\n\r\n".toByteArray())
                flush()
            }
            val head1 = reader.readHead()
                ?: error("Expected HTTP response head1 (tlsFailures=${tlsFailures.get()}, upstreamFailures=${upstreamFailures.get()})")
            val out1 = ByteArrayOutputStream()
            val body1 = reader.copyBody(head1, out1, isResponse = true, requestMethod = "GET")
            assertEquals(200, head1.status)
            assertTrue(String(body1.payload!!).contains("login/top"))
            assertTrue(req1CapturedLatch.await(3, TimeUnit.SECONDS))
            assertEquals(listOf("https://$gameHost/login/top"), capturedTargets.toList())
            assertEquals(1, gameConnects.get())

            // Start request 2 while reading is ON, pause at upstream so it is genuinely in-flight,
            // turn reading OFF, then let upstream respond: client receives full response,
            // onGameResponse is NOT called, and connection closes cleanly on next boundary.
            tlsClient.getOutputStream().apply {
                write("GET /battle/result HTTP/1.1\r\nHost: $gameHost\r\n\r\n".toByteArray())
                flush()
            }
            assertTrue(req2InFlightAtServer.await(3, TimeUnit.SECONDS))
            proxy.setContexts(emptyMap())
            allowReq2Response.countDown()

            val head2 = reader.readHead()!!
            val out2 = ByteArrayOutputStream()
            val body2 = reader.copyBody(head2, out2, isResponse = true, requestMethod = "GET")
            assertEquals(200, head2.status)
            assertTrue(String(body2.payload!!).contains("battle/result"))
            assertEquals(1, capturedTargets.size, "Expected onGameResponse NOT to be called after reading turned off")

            // Next boundary should be closed cleanly by proxy
            val eof = runCatching { reader.readHead() }.getOrNull()
            assertEquals(null, eof)
            assertEquals(1, upstreamAcceptCount.get())
            tlsClient.close()
        } finally {
            proxy.stop()
            mockServer.close()
            serverThread.join(1000)
        }
    }

    @Test(timeout = 10000)
    fun maxConnectionsCapRejectsOverflowConnections() {
        val heldLatch = CountDownLatch(2)
        val releaseLatch = CountDownLatch(1)
        val proxy = ProxyServer(
            emptyMap(),
            ProxyOptions(socks5 = true, ipSniff = true, maxConnections = 2),
        )
        proxy.handleHookForTesting = {
            heldLatch.countDown()
            releaseLatch.await(5, TimeUnit.SECONDS)
        }
        val port = proxy.start(0)
        val s1 = Socket("127.0.0.1", port)
        val s2 = Socket("127.0.0.1", port)
        try {
            assertTrue(heldLatch.await(3, TimeUnit.SECONDS))
            Socket("127.0.0.1", port).use { s3 ->
                s3.soTimeout = 2000
                // Capped immediately: stream hits EOF (-1)
                val b = runCatching { s3.getInputStream().read() }.getOrDefault(-1)
                assertEquals(-1, b)
            }
        } finally {
            releaseLatch.countDown()
            runCatching { s1.close() }
            runCatching { s2.close() }
            proxy.stop()
        }
    }

    @Test(timeout = 10000)
    fun tunnelIdleTimeoutClosesSilentTunnel() {
        val silentUpstream = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val upstreamAccept = CountDownLatch(1)
        val t = Thread {
            runCatching {
                silentUpstream.accept().use { s ->
                    s.getInputStream().readNBytes(4)
                    upstreamAccept.countDown()
                    Thread.sleep(5000)
                }
            }
        }.apply { isDaemon = true; start() }

        val proxy = ProxyServer(
            emptyMap(),
            ProxyOptions(socks5 = true, ipSniff = true, tunnelIdleTimeout = 150.milliseconds),
        )
        val port = proxy.start(0)
        try {
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 3000
                s.getOutputStream().apply {
                    write("CONNECT 127.0.0.1:${silentUpstream.localPort} HTTP/1.1\r\n\r\n".toByteArray())
                    flush()
                }
                val resp = readUntilBlankLine(s.getInputStream())
                assertTrue(resp.startsWith("HTTP/1.1 200"))
                // Send non-TLS probe so peek exits immediately and the tunnel is established, then go idle.
                s.getOutputStream().apply {
                    write("PING".toByteArray())
                    flush()
                }
                assertTrue(upstreamAccept.await(2, TimeUnit.SECONDS))
                // No further traffic in either direction -> proxy closes tunnel after ~150 ms
                assertEquals(-1, s.getInputStream().read())
            }
        } finally {
            proxy.stop()
            silentUpstream.close()
            t.join(1000)
        }
    }

    @Test(timeout = 10000)
    fun upstreamFailureCounterIncrementsOnlyForGameHosts() {
        val failures = AtomicInteger(0)
        val connects = AtomicInteger(0)
        val proxy = ProxyServer(
            emptyMap(),
            ProxyOptions(socks5 = true, ipSniff = true),
            onGameHostConnected = { connects.incrementAndGet() },
            onUpstreamFailure = { failures.incrementAndGet() },
        )
        proxy.setContexts(emptyMap())
        val port = proxy.start(0)
        try {
            // Non-game host failure must NOT increment game upstream failure counter
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 3000
                s.getOutputStream().apply {
                    write("CONNECT non-existent.invalid:443 HTTP/1.1\r\n\r\n".toByteArray())
                    flush()
                }
                s.getInputStream().read()
            }
            assertEquals(0, failures.get())
            assertEquals(0, connects.get())
        } finally {
            proxy.stop()
        }
    }

    @Test(timeout = 10000)
    fun defaultOptionsTunnelIpAtOnceAndDoNotSniffSocks5() {
        val echoServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val echoThread = Thread {
            runCatching {
                echoServer.accept().use { s ->
                    val data = s.getInputStream().readNBytes(3)
                    s.getOutputStream().apply {
                        write(data)
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val proxy = ProxyServer(emptyMap())
        val port = proxy.start(0)
        try {
            // CONNECT to IP literal with TLS-like first byte 0x16 must tunnel immediately (no 3s peek delay).
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 3000
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                out.write("CONNECT 127.0.0.1:${echoServer.localPort} HTTP/1.1\r\n\r\n".toByteArray())
                out.flush()
                val resp = readUntilBlankLine(inp)
                assertTrue(resp.startsWith("HTTP/1.1 200"))

                val probe = byteArrayOf(0x16, 0x03, 0x01)
                val startedNs = System.nanoTime()
                out.write(probe)
                out.flush()
                assertContentEquals(probe, inp.readNBytes(3))
                val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
                assertTrue(elapsedMs < 1500, "Expected immediate tunnel without peek delay, took ${elapsedMs}ms")
            }

            // First byte 0x05 is NOT treated as SOCKS5 under default options (501 on HTTP line or close on EOF).
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 3000
                s.getOutputStream().apply {
                    write(byteArrayOf(0x05, 0x01, 0x00) + "\r\n\r\n".toByteArray())
                    flush()
                }
                val resp = readUntilBlankLine(s.getInputStream())
                assertTrue(resp.startsWith("HTTP/1.1 501"), "Expected 501, got: $resp")
            }
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 3000
                s.getOutputStream().apply {
                    write(byteArrayOf(0x05, 0x01, 0x00))
                    flush()
                }
                s.shutdownOutput()
                assertEquals(-1, s.getInputStream().read())
            }
        } finally {
            proxy.stop()
            echoServer.close()
            echoThread.join(1000)
        }
    }

    @Test(timeout = 10000)
    fun defaultOptionsConnectHostnameRoundTrip() {
        val echoServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val echoThread = Thread {
            runCatching {
                echoServer.accept().use { s ->
                    val buf = ByteArray(1024)
                    val inp = s.getInputStream()
                    val out = s.getOutputStream()
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        out.flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val proxy = ProxyServer(emptyMap())
        val port = proxy.start(0)
        try {
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 3000
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                out.write("CONNECT localhost:${echoServer.localPort} HTTP/1.1\r\nHost: localhost:${echoServer.localPort}\r\n\r\n".toByteArray())
                out.flush()
                val resp = readUntilBlankLine(inp)
                assertTrue(resp.startsWith("HTTP/1.1 200"))

                out.write("PING".toByteArray())
                out.flush()
                assertEquals("PING", String(inp.readNBytes(4)))

                out.write("PONG".toByteArray())
                out.flush()
                assertEquals("PONG", String(inp.readNBytes(4)))
            }
        } finally {
            proxy.stop()
            echoServer.close()
            echoThread.join(1000)
        }
    }

    @Test(timeout = 10000)
    fun adGuardOptionsConnectHeadAndPayloadInOneWriteToIpTarget() {
        val received = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        val echoServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val t = Thread {
            runCatching {
                echoServer.accept().use { s ->
                    val data = s.getInputStream().readNBytes(11)
                    received.write(data)
                    s.getOutputStream().apply {
                        write(data)
                        flush()
                    }
                    latch.countDown()
                }
            }
        }.apply { isDaemon = true; start() }

        val proxy = ProxyServer(emptyMap(), ProxyOptions(socks5 = true, ipSniff = true))
        val port = proxy.start(0)
        try {
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 3000
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                val combined = "CONNECT 127.0.0.1:${echoServer.localPort} HTTP/1.1\r\n\r\nPAYLOAD-123".toByteArray()
                out.write(combined)
                out.flush()

                val resp = readUntilBlankLine(inp)
                assertTrue(resp.startsWith("HTTP/1.1 200"))
                assertTrue(latch.await(3, TimeUnit.SECONDS))
                assertEquals("PAYLOAD-123", received.toString())
                assertEquals("PAYLOAD-123", String(inp.readNBytes(11)))
            }
        } finally {
            proxy.stop()
            echoServer.close()
            t.join(1000)
        }
    }

    @Test(timeout = 10000)
    fun adGuardOptionsSocks5GreetingRequestAndPayloadInOneWrite() {
        val received = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        val echoServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val t = Thread {
            runCatching {
                echoServer.accept().use { s ->
                    val data = s.getInputStream().readNBytes(8)
                    received.write(data)
                    s.getOutputStream().apply {
                        write(data)
                        flush()
                    }
                    latch.countDown()
                }
            }
        }.apply { isDaemon = true; start() }

        val proxy = ProxyServer(emptyMap(), ProxyOptions(socks5 = true, ipSniff = true))
        val port = proxy.start(0)
        try {
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 3000
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                val p = echoServer.localPort
                val oneWrite = byteArrayOf(0x05, 0x01, 0x00) +
                    byteArrayOf(0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1, (p ushr 8).toByte(), p.toByte()) +
                    "ONESHOT!".toByteArray()
                out.write(oneWrite)
                out.flush()

                assertContentEquals(byteArrayOf(0x05, 0x00), inp.readNBytes(2))
                assertContentEquals(Socks5.reply(Socks5.REP_SUCCEEDED), inp.readNBytes(10))
                assertTrue(latch.await(3, TimeUnit.SECONDS))
                assertEquals("ONESHOT!", received.toString())
                assertEquals("ONESHOT!", String(inp.readNBytes(8)))
            }
        } finally {
            proxy.stop()
            echoServer.close()
            t.join(1000)
        }
    }

    @Test(timeout = 10000)
    fun adGuardOptionsSocks5DomainConnectRoundTrip() {
        val echoServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val t = Thread {
            runCatching {
                echoServer.accept().use { s ->
                    val buf = ByteArray(1024)
                    val inp = s.getInputStream()
                    val out = s.getOutputStream()
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        out.flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val proxy = ProxyServer(emptyMap(), ProxyOptions(socks5 = true, ipSniff = true))
        val port = proxy.start(0)
        try {
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 3000
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                assertContentEquals(byteArrayOf(0x05, 0x00), inp.readNBytes(2))

                val domain = "localhost".toByteArray()
                val p = echoServer.localPort
                out.write(
                    byteArrayOf(0x05, 0x01, 0x00, 0x03, domain.size.toByte()) +
                        domain + byteArrayOf((p ushr 8).toByte(), p.toByte())
                )
                out.flush()
                assertContentEquals(Socks5.reply(Socks5.REP_SUCCEEDED), inp.readNBytes(10))

                out.write("ROUND".toByteArray())
                out.flush()
                assertEquals("ROUND", String(inp.readNBytes(5)))

                out.write("TRIP".toByteArray())
                out.flush()
                assertEquals("TRIP", String(inp.readNBytes(4)))
            }
        } finally {
            proxy.stop()
            echoServer.close()
            t.join(1000)
        }
    }

    @Test(timeout = 15000)
    fun peekDeadlineIsTotalNotPerRead() {
        val firstByteAtNs = java.util.concurrent.atomic.AtomicLong(0)
        val upstreamSawNs = java.util.concurrent.atomic.AtomicLong(0)
        val seen = CountDownLatch(1)
        val echoServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val t = Thread {
            runCatching {
                echoServer.accept().use { s ->
                    if (s.getInputStream().read() >= 0) {
                        upstreamSawNs.set(System.nanoTime())
                        seen.countDown()
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val proxy = ProxyServer(emptyMap(), ProxyOptions(socks5 = true, ipSniff = true))
        val port = proxy.start(0)
        try {
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 6000
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                out.write("CONNECT 127.0.0.1:${echoServer.localPort} HTTP/1.1\r\n\r\n".toByteArray())
                out.flush()
                assertTrue(readUntilBlankLine(inp).startsWith("HTTP/1.1 200"))

                // A TLS record header sent one byte per 1.2 s: no single read waits 3 s, but the
                // total peek deadline (3 s) must still end the peek and start the tunnel.
                val header = byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x20, 0x01)
                firstByteAtNs.set(System.nanoTime())
                for (b in header) {
                    if (seen.count == 0L) break
                    out.write(byteArrayOf(b))
                    out.flush()
                    if (seen.await(1200, TimeUnit.MILLISECONDS)) break
                }
                assertTrue(seen.await(3, TimeUnit.SECONDS), "tunnel must start")
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(upstreamSawNs.get() - firstByteAtNs.get())
                assertTrue(elapsedMs in 2800..4500, "Expected the tunnel after the ~3s total deadline, took ${elapsedMs}ms")
            }
        } finally {
            proxy.stop()
            echoServer.close()
            t.join(1000)
        }
    }

    @Test(timeout = 15000)
    fun peekTimeoutReplaysAlreadyReadBytesInOrder() {
        val received = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        val echoServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val t = Thread {
            runCatching {
                echoServer.accept().use { s ->
                    val data = s.getInputStream().readNBytes(10)
                    received.write(data)
                    s.getOutputStream().apply {
                        write(data)
                        flush()
                    }
                    latch.countDown()
                }
            }
        }.apply { isDaemon = true; start() }

        val proxy = ProxyServer(emptyMap(), ProxyOptions(socks5 = true, ipSniff = true))
        val port = proxy.start(0)
        try {
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 6000
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                out.write("CONNECT 127.0.0.1:${echoServer.localPort} HTTP/1.1\r\n\r\n".toByteArray())
                out.flush()
                val resp = readUntilBlankLine(inp)
                assertTrue(resp.startsWith("HTTP/1.1 200"))

                val part1 = byteArrayOf(0x16, 0x03, 0x01)
                val part2 = byteArrayOf(0x00, 0x05, 0x01, 0x02, 0x03, 0x04, 0x05)
                out.write(part1)
                out.flush()
                Thread.sleep(3300)
                out.write(part2)
                out.flush()

                assertTrue(latch.await(5, TimeUnit.SECONDS))
                val expected = part1 + part2
                assertContentEquals(expected, received.toByteArray())
                assertContentEquals(expected, inp.readNBytes(10))
            }
        } finally {
            proxy.stop()
            echoServer.close()
            t.join(1000)
        }
    }

    @Test(timeout = 10000)
    fun upstreamFailureCounterIncrementsForInterceptedGameHost() {
        val gameHost = CaptureNames.GAME_HOST
        val mitmCerts = SingleUseCa.issue(gameHost)
        val mitmServerCtx = serverSslContext(mitmCerts)
        val mitmClientCtx = clientTrustContext(mitmCerts)
        // Accept TCP so preconnect succeeds, then close immediately to trigger a TLS-stage upstream failure.
        val tlsDropServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val acceptThread = Thread {
            runCatching {
                tlsDropServer.accept().use { }
            }
        }.apply { isDaemon = true; start() }

        val failedHosts = CopyOnWriteArrayList<String>()
        val nonGameFailed = AtomicInteger(0)
        val failureLatch = CountDownLatch(1)
        val proxy = ProxyServer(
            sslContexts = mapOf(gameHost to mitmServerCtx),
            options = ProxyOptions(socks5 = true, ipSniff = true),
            onUpstreamFailure = { host ->
                failedHosts += host
                failureLatch.countDown()
            },
            onNonGameUpstreamFailure = { nonGameFailed.incrementAndGet() },
        )
        val proxyPort = proxy.start(0)
        try {
            val raw = Socket("127.0.0.1", proxyPort)
            raw.soTimeout = 3000
            raw.getOutputStream().apply {
                write("CONNECT 127.0.0.1:${tlsDropServer.localPort} HTTP/1.1\r\n\r\n".toByteArray())
                flush()
            }
            assertTrue(readUntilBlankLine(raw.getInputStream()).startsWith("HTTP/1.1 200"))

            val tlsClient = mitmClientCtx.socketFactory.createSocket(raw, "127.0.0.1", tlsDropServer.localPort, true) as SSLSocket
            tlsClient.sslParameters = tlsClient.sslParameters.apply {
                serverNames = listOf(SNIHostName(gameHost))
            }
            tlsClient.startHandshake()

            assertTrue(failureLatch.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(gameHost), failedHosts.toList())
            assertEquals(0, nonGameFailed.get())
            runCatching { tlsClient.close() }
        } finally {
            proxy.stop()
            tlsDropServer.close()
            acceptThread.join(1000)
        }
    }

    @Test(timeout = 10000)
    fun snapshotPersistsThroughTlsHandshakeAfterContextsClearedBeforeFirstRequest() {
        val gameHost = CaptureNames.JP_HOST
        val upstreamCerts = SingleUseCa.issue(gameHost)
        val mitmCerts = SingleUseCa.issue(gameHost)
        val upstreamServerCtx = serverSslContext(upstreamCerts)
        val upstreamClientCtx = clientTrustContext(upstreamCerts)
        val mitmServerCtx = serverSslContext(mitmCerts)
        val mitmClientCtx = clientTrustContext(mitmCerts)

        val mockServer = (upstreamServerCtx.serverSocketFactory.createServerSocket(
            0,
            50,
            InetAddress.getByName("127.0.0.1"),
        ) as SSLServerSocket).apply { enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3") }

        val upstreamTargets = CopyOnWriteArrayList<String>()
        val serverThread = Thread {
            runCatching {
                while (!mockServer.isClosed) {
                    val s = mockServer.accept() as SSLSocket
                    Thread {
                        runCatching {
                            s.use { sock ->
                                sock.startHandshake()
                                val r = HttpReader(sock.getInputStream())
                                while (true) {
                                    val req = r.readHead() ?: break
                                    upstreamTargets += req.target
                                    val payload = """{"ok":true,"req":"${req.target}"}"""
                                    val resp = "HTTP/1.1 200 OK\r\nContent-Length: ${payload.length}\r\n\r\n$payload"
                                    sock.getOutputStream().apply {
                                        write(resp.toByteArray())
                                        flush()
                                    }
                                }
                            }
                        }
                    }.start()
                }
            }
        }.apply { isDaemon = true; start() }

        val capturedTargets = CopyOnWriteArrayList<String>()
        val proxy = ProxyServer(
            sslContexts = mapOf(gameHost to mitmServerCtx),
            options = ProxyOptions(socks5 = true, ipSniff = true),
            onGameResponse = { target, _, _ -> capturedTargets += target },
        ).apply {
            upstreamSslFactoryForTesting = upstreamClientCtx.socketFactory
            upstreamHostnameVerifierForTesting = HostnameVerifier { host, session ->
                val leaf = session.peerCertificates.firstOrNull() as? X509Certificate ?: return@HostnameVerifier false
                leaf.subjectAlternativeNames?.any { entry -> entry.size >= 2 && entry[1] == host } == true
            }
        }
        val proxyPort = proxy.start(0)
        try {
            val raw = Socket("127.0.0.1", proxyPort)
            raw.soTimeout = 5000
            raw.getOutputStream().apply {
                write("CONNECT 127.0.0.1:${mockServer.localPort} HTTP/1.1\r\n\r\n".toByteArray())
                flush()
            }
            assertTrue(readUntilBlankLine(raw.getInputStream()).startsWith("HTTP/1.1 200"))

            // Turn reading off before the TLS handshake and first request; connection keeps its snapshot.
            proxy.setContexts(emptyMap())

            val tlsClient = mitmClientCtx.socketFactory.createSocket(raw, "127.0.0.1", mockServer.localPort, true) as SSLSocket
            tlsClient.sslParameters = tlsClient.sslParameters.apply {
                serverNames = listOf(SNIHostName(gameHost))
            }
            tlsClient.startHandshake()

            tlsClient.getOutputStream().apply {
                write("GET /login/top HTTP/1.1\r\nHost: $gameHost\r\n\r\n".toByteArray())
                flush()
            }
            // Reading is off: the request still reaches the upstream and the client gets the
            // response, but nothing is recorded; then the connection is closed.
            val reader = HttpReader(tlsClient.getInputStream())
            val head = reader.readHead()
            assertTrue(head != null && head.status == 200, "client must get the upstream response")
            val body = reader.copyBody(head!!, java.io.ByteArrayOutputStream(), isResponse = true, requestMethod = "GET", captureBody = true)
            assertEquals("""{"ok":true,"req":"/login/top"}""", String(body.payload ?: ByteArray(0)))
            assertEquals(listOf("/login/top"), upstreamTargets.toList())
            assertEquals(emptyList(), capturedTargets.toList())
            // Closed means EOF or a close-type socket/TLS exception; anything else fails the test.
            val closed = try {
                tlsClient.getInputStream().read()
            } catch (_: java.net.SocketException) {
                -1
            } catch (_: javax.net.ssl.SSLException) {
                -1
            }
            assertEquals(-1, closed)
            assertEquals(emptyList(), capturedTargets.toList())
            runCatching { tlsClient.close() }
        } finally {
            proxy.stop()
            mockServer.close()
            serverThread.join(1000)
        }
    }

    @Test(timeout = 10000)
    fun maxConnectionsCapReleasesSlotWhenConnectionCloses() {
        val entered = AtomicInteger(0)
        val twoHeld = CountDownLatch(2)
        val releaseOne = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val proxy = ProxyServer(
            emptyMap(),
            ProxyOptions(socks5 = true, ipSniff = true, maxConnections = 2),
        )
        proxy.handleHookForTesting = {
            when (entered.incrementAndGet()) {
                1 -> {
                    twoHeld.countDown()
                    releaseOne.await(5, TimeUnit.SECONDS)
                }
                2 -> {
                    twoHeld.countDown()
                    releaseSecond.await(5, TimeUnit.SECONDS)
                }
            }
        }
        val port = proxy.start(0)
        val s1 = Socket("127.0.0.1", port)
        val s2 = Socket("127.0.0.1", port)
        try {
            assertTrue(twoHeld.await(3, TimeUnit.SECONDS))
            Socket("127.0.0.1", port).use { rejected ->
                rejected.soTimeout = 2000
                assertEquals(-1, runCatching { rejected.getInputStream().read() }.getOrDefault(-1))
            }

            releaseOne.countDown()
            runCatching { s1.close() }

            var accepted = false
            val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < deadlineNs && !accepted) {
                Socket("127.0.0.1", port).use { candidate ->
                    candidate.soTimeout = 1000
                    runCatching {
                        candidate.getOutputStream().apply {
                            write("GET / HTTP/1.1\r\n\r\n".toByteArray())
                            flush()
                        }
                        val resp = readUntilBlankLine(candidate.getInputStream())
                        if (resp.startsWith("HTTP/1.1 501")) {
                            accepted = true
                        }
                    }
                }
                if (!accepted) Thread.sleep(20)
            }
            assertTrue(accepted, "Expected new connection to be accepted after slot was released")
        } finally {
            releaseOne.countDown()
            releaseSecond.countDown()
            runCatching { s1.close() }
            runCatching { s2.close() }
            proxy.stop()
        }
    }

    @Test(timeout = 10000)
    fun transparentModeInterceptsGameHostSniAndCapturesResponse() {
        val gameHost = CaptureNames.JP_HOST
        val mitmCerts = SingleUseCa.issue(gameHost)
        val upstreamCerts = SingleUseCa.issue(gameHost)
        val mitmServerCtx = serverSslContext(mitmCerts)
        val mitmClientCtx = clientTrustContext(mitmCerts)
        val upstreamServerCtx = serverSslContext(upstreamCerts)
        val upstreamClientCtx = clientTrustContext(upstreamCerts)

        val mockServer = upstreamServerCtx.serverSocketFactory.createServerSocket(
            0,
            50,
            InetAddress.getByName("127.0.0.1"),
        ) as SSLServerSocket
        val serverThread = Thread {
            runCatching {
                val up = mockServer.accept() as SSLSocket
                up.use {
                    up.startHandshake()
                    val reader = HttpReader(up.getInputStream())
                    val head = reader.readHead() ?: return@use
                    reader.copyBody(head, ByteArrayOutputStream(), isResponse = false, captureBody = false)
                    val body = """{"captured":"transparent"}"""
                    val resp = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                    up.getOutputStream().write(resp.toByteArray())
                    up.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true; start() }

        val capturedTargets = CopyOnWriteArrayList<String>()
        val unexpectedCount = AtomicInteger(0)
        val tlsSuccessCount = AtomicInteger(0)
        val upstreamSuccessCount = AtomicInteger(0)

        val proxy = ProxyServer(
            sslContexts = mapOf(gameHost to mitmServerCtx),
            options = ProxyOptions(transparent = true, ipv6Loopback = true, maxConnections = 1024),
            onGameTlsSuccess = { tlsSuccessCount.incrementAndGet() },
            onGameUpstreamSuccess = { upstreamSuccessCount.incrementAndGet() },
            onUnexpectedConnection = { unexpectedCount.incrementAndGet() },
            onGameResponse = { target, _, _ -> capturedTargets += target },
        ).apply {
            upstreamSslFactoryForTesting = upstreamClientCtx.socketFactory
            upstreamHostnameVerifierForTesting = HostnameVerifier { host, _ -> host == gameHost }
            transparentUpstreamOverrideForTesting = { _, _ ->
                InetSocketAddress("127.0.0.1", mockServer.localPort)
            }
        }
        val proxyPort = proxy.start(0)
        try {
            val raw = Socket("127.0.0.1", proxyPort)
            raw.soTimeout = 5000
            val tlsClient = mitmClientCtx.socketFactory.createSocket(raw, gameHost, 443, true) as SSLSocket
            tlsClient.sslParameters = tlsClient.sslParameters.apply {
                serverNames = listOf(SNIHostName(gameHost))
            }
            tlsClient.startHandshake()
            tlsClient.getOutputStream().apply {
                write("GET /Home/top HTTP/1.1\r\nHost: $gameHost\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            val reader = HttpReader(tlsClient.getInputStream())
            val head = reader.readHead()
            assertTrue(head != null && head.status == 200)
            val body = reader.copyBody(head!!, ByteArrayOutputStream(), isResponse = true, requestMethod = "GET", captureBody = true)
            assertEquals("""{"captured":"transparent"}""", String(body.payload ?: ByteArray(0)))
            assertEquals(listOf("https://$gameHost/Home/top"), capturedTargets.toList())
            assertEquals(0, unexpectedCount.get())
            assertEquals(1, tlsSuccessCount.get())
            assertEquals(1, upstreamSuccessCount.get())
            runCatching { tlsClient.close() }
        } finally {
            proxy.stop()
            mockServer.close()
            serverThread.join(1000)
        }
    }

    @Test(timeout = 10000)
    fun transparentModeBlindTunnelsNonGameHostSniAndCountsNonGameUpstreamFailure() {
        val nonGameHost = "login.netmarble.com"
        val upstreamCerts = SingleUseCa.issue(nonGameHost)
        val upstreamServerCtx = serverSslContext(upstreamCerts)
        val upstreamClientCtx = clientTrustContext(upstreamCerts)

        val mockServer = upstreamServerCtx.serverSocketFactory.createServerSocket(
            0,
            50,
            InetAddress.getByName("127.0.0.1"),
        ) as SSLServerSocket
        val serverThread = Thread {
            runCatching {
                val up = mockServer.accept() as SSLSocket
                up.use {
                    up.startHandshake()
                    val reader = HttpReader(up.getInputStream())
                    val head = reader.readHead() ?: return@use
                    reader.copyBody(head, ByteArrayOutputStream(), isResponse = false, captureBody = false)
                    val body = """{"tunnel":"ok"}"""
                    val resp = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                    up.getOutputStream().write(resp.toByteArray())
                    up.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true; start() }

        val capturedTargets = CopyOnWriteArrayList<String>()
        val unexpectedCount = AtomicInteger(0)
        val gameUpstreamFailures = AtomicInteger(0)
        val nonGameUpstreamFailures = AtomicInteger(0)
        val nonGameFailLatch = CountDownLatch(1)

        val deadPort = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        val proxy = ProxyServer(
            sslContexts = emptyMap(),
            options = ProxyOptions(transparent = true, maxConnections = 1024),
            onUpstreamFailure = { gameUpstreamFailures.incrementAndGet() },
            onUnexpectedConnection = { unexpectedCount.incrementAndGet() },
            onNonGameUpstreamFailure = {
                nonGameUpstreamFailures.incrementAndGet()
                nonGameFailLatch.countDown()
            },
            onGameResponse = { target, _, _ -> capturedTargets += target },
        ).apply {
            transparentUpstreamOverrideForTesting = { host, _ ->
                if (host.equals("dead.example.com", ignoreCase = true)) {
                    InetSocketAddress("127.0.0.1", deadPort)
                } else {
                    InetSocketAddress("127.0.0.1", mockServer.localPort)
                }
            }
        }
        val proxyPort = proxy.start(0)
        try {
            // 1. Non-game SNI blind tunnel succeeds end-to-end with upstream's own TLS certificate
            val raw = Socket("127.0.0.1", proxyPort)
            raw.soTimeout = 5000
            val tlsClient = upstreamClientCtx.socketFactory.createSocket(raw, nonGameHost, 443, true) as SSLSocket
            tlsClient.sslParameters = tlsClient.sslParameters.apply {
                serverNames = listOf(SNIHostName(nonGameHost))
            }
            tlsClient.startHandshake()
            tlsClient.getOutputStream().apply {
                write("GET /oauth HTTP/1.1\r\nHost: $nonGameHost\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            val reader = HttpReader(tlsClient.getInputStream())
            val head = reader.readHead()
            assertTrue(head != null && head.status == 200)
            val body = reader.copyBody(head!!, ByteArrayOutputStream(), isResponse = true, requestMethod = "GET", captureBody = true)
            assertEquals("""{"tunnel":"ok"}""", String(body.payload ?: ByteArray(0)))
            assertEquals(emptyList(), capturedTargets.toList())
            assertEquals(0, unexpectedCount.get())
            runCatching { tlsClient.close() }

            // 2. Non-game SNI blind tunnel failure increments nonGameUpstreamFailures, NOT gameUpstreamFailures
            val raw2 = Socket("127.0.0.1", proxyPort)
            raw2.soTimeout = 5000
            val tlsClient2 = upstreamClientCtx.socketFactory.createSocket(raw2, "dead.example.com", 443, true) as SSLSocket
            tlsClient2.sslParameters = tlsClient2.sslParameters.apply {
                serverNames = listOf(SNIHostName("dead.example.com"))
            }
            runCatching { tlsClient2.startHandshake() }
            runCatching { tlsClient2.close() }

            assertTrue(nonGameFailLatch.await(3, TimeUnit.SECONDS))
            assertEquals(1, nonGameUpstreamFailures.get())
            assertEquals(0, gameUpstreamFailures.get())
            assertEquals(0, unexpectedCount.get())
        } finally {
            proxy.stop()
            mockServer.close()
            serverThread.join(1000)
        }
    }

    @Test(timeout = 10000)
    fun transparentModeClosesAndCountsNoSniOrNonTlsConnections() {
        val unexpectedCount = AtomicInteger(0)
        val latch = CountDownLatch(2)
        val proxy = ProxyServer(
            sslContexts = emptyMap(),
            options = ProxyOptions(transparent = true),
            onUnexpectedConnection = {
                unexpectedCount.incrementAndGet()
                latch.countDown()
            },
        )
        val proxyPort = proxy.start(0)
        try {
            // 1. Plain non-TLS HTTP bytes -> closed and counted as unexpected
            Socket("127.0.0.1", proxyPort).use { s ->
                s.soTimeout = 3000
                s.getOutputStream().apply {
                    write("GET / HTTP/1.1\r\n\r\n".toByteArray())
                    flush()
                }
                assertEquals(-1, runCatching { s.getInputStream().read() }.getOrDefault(-1))
            }

            // 2. TLS ClientHello without SNI extension -> closed and counted as unexpected
            val raw = Socket("127.0.0.1", proxyPort)
            raw.soTimeout = 3000
            val tlsNoSni = SSLContext.getDefault().socketFactory.createSocket(raw, "127.0.0.1", proxyPort, true) as SSLSocket
            tlsNoSni.sslParameters = tlsNoSni.sslParameters.apply {
                serverNames = emptyList()
            }
            runCatching { tlsNoSni.startHandshake() }
            runCatching { tlsNoSni.close() }

            assertTrue(latch.await(3, TimeUnit.SECONDS))
            assertEquals(2, unexpectedCount.get())
        } finally {
            proxy.stop()
        }
    }

    @Test(timeout = 10000)
    fun ipv6LoopbackListenerAcceptsConnectionsOnColonColonOne() {
        val unexpectedCount = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val proxy = ProxyServer(
            sslContexts = emptyMap(),
            options = ProxyOptions(transparent = true, ipv6Loopback = true),
            onUnexpectedConnection = {
                unexpectedCount.incrementAndGet()
                latch.countDown()
            },
        )
        val port = proxy.start(0)
        try {
            if (!proxy.isIpv6Listening) {
                return
            }
            Socket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getByName("::1"), port), 3000)
                s.soTimeout = 3000
                s.getOutputStream().apply {
                    write("PLAIN\r\n".toByteArray())
                    flush()
                }
                assertEquals(-1, runCatching { s.getInputStream().read() }.getOrDefault(-1))
            }
            assertTrue(latch.await(3, TimeUnit.SECONDS))
            assertEquals(1, unexpectedCount.get())
        } finally {
            proxy.stop()
        }
    }

    @Test(timeout = 10000)
    fun socks5IpTargetRefusedRepliesFailureBeforeSuccessAndCountsNonGame() {
        val deadPort = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val gameFailures = AtomicInteger(0)
        val nonGameFailedHosts = CopyOnWriteArrayList<String>()
        val nonGameLatch = CountDownLatch(1)
        val proxy = ProxyServer(
            sslContexts = emptyMap(),
            options = ProxyOptions(socks5 = true, ipSniff = true),
            onUpstreamFailure = { gameFailures.incrementAndGet() },
            onNonGameUpstreamFailure = { host ->
                nonGameFailedHosts += host
                nonGameLatch.countDown()
            },
        )
        val proxyPort = proxy.start(0)
        try {
            Socket("127.0.0.1", proxyPort).use { s ->
                s.soTimeout = 3000
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                assertContentEquals(byteArrayOf(0x05, 0x00), inp.readNBytes(2))

                out.write(
                    byteArrayOf(
                        0x05,
                        0x01,
                        0x00,
                        0x01,
                        127,
                        0,
                        0,
                        1,
                        (deadPort ushr 8).toByte(),
                        deadPort.toByte(),
                    )
                )
                out.flush()

                val reply = inp.readNBytes(10)
                assertEquals(10, reply.size)
                assertEquals(Socks5.VERSION, reply[0])
                assertTrue(reply[1] != Socks5.REP_SUCCEEDED, "Expected non-zero SOCKS5 REP, got ${reply[1]}")
                assertEquals(Socks5.REP_CONNECTION_REFUSED, reply[1])
                assertEquals(-1, runCatching { inp.read() }.getOrDefault(-1))
            }
            assertTrue(nonGameLatch.await(3, TimeUnit.SECONDS))
            assertEquals(listOf("127.0.0.1"), nonGameFailedHosts.toList())
            assertEquals(0, gameFailures.get())
            assertTrue(proxy.awaitOpenConnections(2000))
        } finally {
            proxy.stop()
        }
    }

    @Test(timeout = 10000)
    fun socks5IpTargetTimeoutOrUnreachableRepliesHostOrNetworkUnreachable() {
        val gameFailures = AtomicInteger(0)
        val nonGameFailedHosts = CopyOnWriteArrayList<String>()
        val nonGameLatch = CountDownLatch(1)
        val proxy = ProxyServer(
            sslContexts = emptyMap(),
            options = ProxyOptions(socks5 = true, ipSniff = true),
            onUpstreamFailure = { gameFailures.incrementAndGet() },
            onNonGameUpstreamFailure = { host ->
                nonGameFailedHosts += host
                nonGameLatch.countDown()
            },
        ).apply {
            ipPreconnectTimeoutMsForTesting = 150
            transparentUpstreamOverrideForTesting = { _, _ ->
                // TEST-NET-1 (RFC 5737): unroutable so connect either times out or fails unreachable.
                InetSocketAddress("192.0.2.1", 443)
            }
        }
        val proxyPort = proxy.start(0)
        try {
            Socket("127.0.0.1", proxyPort).use { s ->
                s.soTimeout = 5000
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                assertContentEquals(byteArrayOf(0x05, 0x00), inp.readNBytes(2))

                val ipv6Addr = InetAddress.getByName("2001:db8::1").address
                out.write(byteArrayOf(0x05, 0x01, 0x00, 0x04) + ipv6Addr + byteArrayOf(0x01, 0xBB.toByte()))
                out.flush()

                val reply = inp.readNBytes(10)
                assertEquals(10, reply.size)
                assertEquals(Socks5.VERSION, reply[0])
                assertTrue(
                    reply[1] == Socks5.REP_HOST_UNREACHABLE || reply[1] == Socks5.REP_NETWORK_UNREACHABLE,
                    "Expected host/network unreachable REP, got ${reply[1]}"
                )
                assertEquals(-1, runCatching { inp.read() }.getOrDefault(-1))
            }
            assertTrue(nonGameLatch.await(3, TimeUnit.SECONDS))
            assertEquals(listOf("2001:db8:0:0:0:0:0:1"), nonGameFailedHosts.toList())
            assertEquals(0, gameFailures.get())
            assertTrue(proxy.awaitOpenConnections(2000))
        } finally {
            proxy.stop()
        }
    }

    @Test(timeout = 10000)
    fun httpConnectIpTargetRefusedReturns502Without200() {
        val deadPort = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val gameFailures = AtomicInteger(0)
        val nonGameFailedHosts = CopyOnWriteArrayList<String>()
        val nonGameLatch = CountDownLatch(1)
        val proxy = ProxyServer(
            sslContexts = emptyMap(),
            options = ProxyOptions(socks5 = true, ipSniff = true),
            onUpstreamFailure = { gameFailures.incrementAndGet() },
            onNonGameUpstreamFailure = { host ->
                nonGameFailedHosts += host
                nonGameLatch.countDown()
            },
        )
        val proxyPort = proxy.start(0)
        try {
            Socket("127.0.0.1", proxyPort).use { s ->
                s.soTimeout = 3000
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                out.write("CONNECT 127.0.0.1:$deadPort HTTP/1.1\r\nHost: 127.0.0.1:$deadPort\r\n\r\n".toByteArray())
                out.flush()

                val resp = readUntilBlankLine(inp)
                assertTrue(resp.startsWith("HTTP/1.1 502"), "Expected 502 response, got: $resp")
                assertEquals(-1, runCatching { inp.read() }.getOrDefault(-1))
            }
            assertTrue(nonGameLatch.await(3, TimeUnit.SECONDS))
            assertEquals(listOf("127.0.0.1"), nonGameFailedHosts.toList())
            assertEquals(0, gameFailures.get())
            assertTrue(proxy.awaitOpenConnections(2000))
        } finally {
            proxy.stop()
        }
    }

    @Test(timeout = 10000)
    fun socks5IpTargetWithGameSniInterceptsAndAcceptsSingleUpstreamConnection() {
        val gameHost = CaptureNames.JP_HOST
        val upstreamCerts = SingleUseCa.issue(gameHost)
        val mitmCerts = SingleUseCa.issue(gameHost)
        val upstreamServerCtx = serverSslContext(upstreamCerts)
        val upstreamClientCtx = clientTrustContext(upstreamCerts)
        val mitmServerCtx = serverSslContext(mitmCerts)
        val mitmClientCtx = clientTrustContext(mitmCerts)

        val upstreamAcceptCount = AtomicInteger(0)
        val mockServer = (upstreamServerCtx.serverSocketFactory.createServerSocket(
            0,
            50,
            InetAddress.getByName("127.0.0.1"),
        ) as SSLServerSocket).apply { enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3") }

        val serverThread = Thread {
            runCatching {
                while (!mockServer.isClosed) {
                    val s = mockServer.accept() as SSLSocket
                    upstreamAcceptCount.incrementAndGet()
                    Thread {
                        runCatching {
                            s.use { sock ->
                                sock.startHandshake()
                                val r = HttpReader(sock.getInputStream())
                                val req = r.readHead() ?: return@use
                                val payload = """{"ok":true,"req":"${req.target}"}"""
                                val resp = "HTTP/1.1 200 OK\r\nContent-Length: ${payload.length}\r\nConnection: close\r\n\r\n$payload"
                                sock.getOutputStream().apply {
                                    write(resp.toByteArray())
                                    flush()
                                }
                            }
                        }
                    }.start()
                }
            }
        }.apply { isDaemon = true; start() }

        val capturedTargets = CopyOnWriteArrayList<String>()
        val capturedLatch = CountDownLatch(1)
        val proxy = ProxyServer(
            sslContexts = mapOf(gameHost to mitmServerCtx),
            options = ProxyOptions(socks5 = true, ipSniff = true),
            onGameResponse = { target, _, _ ->
                capturedTargets += target
                capturedLatch.countDown()
            },
        ).apply {
            upstreamSslFactoryForTesting = upstreamClientCtx.socketFactory
            upstreamHostnameVerifierForTesting = HostnameVerifier { host, _ -> host == gameHost }
        }
        val proxyPort = proxy.start(0)
        try {
            val raw = Socket("127.0.0.1", proxyPort)
            raw.soTimeout = 5000
            val out = raw.getOutputStream()
            val inp = raw.getInputStream()
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            assertContentEquals(byteArrayOf(0x05, 0x00), inp.readNBytes(2))

            val p = mockServer.localPort
            out.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1, (p ushr 8).toByte(), p.toByte()))
            out.flush()
            assertContentEquals(Socks5.reply(Socks5.REP_SUCCEEDED), inp.readNBytes(10))

            val tlsClient = mitmClientCtx.socketFactory.createSocket(raw, "127.0.0.1", p, true) as SSLSocket
            tlsClient.sslParameters = tlsClient.sslParameters.apply {
                serverNames = listOf(SNIHostName(gameHost))
            }
            tlsClient.startHandshake()
            tlsClient.getOutputStream().apply {
                write("GET /login/top HTTP/1.1\r\nHost: $gameHost\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }

            val reader = HttpReader(tlsClient.getInputStream())
            val head = reader.readHead() ?: error("Expected HTTP response")
            assertEquals(200, head.status)
            val body = reader.copyBody(head, ByteArrayOutputStream(), isResponse = true, requestMethod = "GET")
            assertTrue(String(body.payload ?: ByteArray(0)).contains("login/top"))
            assertTrue(capturedLatch.await(3, TimeUnit.SECONDS))
            assertEquals(listOf("https://$gameHost/login/top"), capturedTargets.toList())
            assertEquals(1, upstreamAcceptCount.get())
            tlsClient.close()
        } finally {
            proxy.stop()
            mockServer.close()
            serverThread.join(1000)
        }
    }

    @Test(timeout = 10000)
    fun socks5IpTargetWithNonGameSniTunnelsPeekedClientHelloAndDataWithSingleUpstreamConnection() {
        val gameHost = CaptureNames.JP_HOST
        val nonGameHost = "example.com"
        val mitmCerts = SingleUseCa.issue(gameHost)
        val upstreamCerts = SingleUseCa.issue(nonGameHost)
        val mitmServerCtx = serverSslContext(mitmCerts)
        val upstreamServerCtx = serverSslContext(upstreamCerts)
        val upstreamClientCtx = clientTrustContext(upstreamCerts)

        val upstreamAcceptCount = AtomicInteger(0)
        val mockServer = (upstreamServerCtx.serverSocketFactory.createServerSocket(
            0,
            50,
            InetAddress.getByName("127.0.0.1"),
        ) as SSLServerSocket).apply { enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3") }

        val serverThread = Thread {
            runCatching {
                while (!mockServer.isClosed) {
                    val s = mockServer.accept() as SSLSocket
                    upstreamAcceptCount.incrementAndGet()
                    Thread {
                        runCatching {
                            s.use { sock ->
                                sock.startHandshake()
                                val r = HttpReader(sock.getInputStream())
                                val req = r.readHead() ?: return@use
                                val payload = """{"tunnel":"${req.target}"}"""
                                val resp = "HTTP/1.1 200 OK\r\nContent-Length: ${payload.length}\r\nConnection: close\r\n\r\n$payload"
                                sock.getOutputStream().apply {
                                    write(resp.toByteArray())
                                    flush()
                                }
                            }
                        }
                    }.start()
                }
            }
        }.apply { isDaemon = true; start() }

        val gameConnects = AtomicInteger(0)
        val proxy = ProxyServer(
            sslContexts = mapOf(gameHost to mitmServerCtx),
            options = ProxyOptions(socks5 = true, ipSniff = true),
            onGameHostConnected = { gameConnects.incrementAndGet() },
        )
        val proxyPort = proxy.start(0)
        try {
            val raw = Socket("127.0.0.1", proxyPort)
            raw.soTimeout = 5000
            val out = raw.getOutputStream()
            val inp = raw.getInputStream()
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            assertContentEquals(byteArrayOf(0x05, 0x00), inp.readNBytes(2))

            val p = mockServer.localPort
            out.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1, (p ushr 8).toByte(), p.toByte()))
            out.flush()
            assertContentEquals(Socks5.reply(Socks5.REP_SUCCEEDED), inp.readNBytes(10))

            // TLS handshake verifies the peeked ClientHello bytes were forwarded intact to upstream.
            val tlsClient = upstreamClientCtx.socketFactory.createSocket(raw, nonGameHost, p, true) as SSLSocket
            tlsClient.sslParameters = tlsClient.sslParameters.apply {
                serverNames = listOf(SNIHostName(nonGameHost))
            }
            tlsClient.startHandshake()
            tlsClient.getOutputStream().apply {
                write("GET /ping HTTP/1.1\r\nHost: $nonGameHost\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }

            val reader = HttpReader(tlsClient.getInputStream())
            val head = reader.readHead() ?: error("Expected HTTP response")
            assertEquals(200, head.status)
            val body = reader.copyBody(head, ByteArrayOutputStream(), isResponse = true, requestMethod = "GET")
            assertEquals("""{"tunnel":"/ping"}""", String(body.payload ?: ByteArray(0)))
            assertEquals(1, upstreamAcceptCount.get())
            assertEquals(0, gameConnects.get())
            tlsClient.close()
        } finally {
            proxy.stop()
            mockServer.close()
            serverThread.join(1000)
        }
    }

    @Test(timeout = 10000)
    fun hostnameTargetRepliesOnlyAfterUpstreamConnect() {
        val echoServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val echoThread = Thread {
            runCatching {
                echoServer.accept().use { s ->
                    val data = s.getInputStream().readNBytes(4)
                    s.getOutputStream().apply {
                        write(data)
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val deadPort = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val connectEntered = CountDownLatch(1)
        val releaseConnect = CountDownLatch(1)

        val proxy = ProxyServer(
            sslContexts = emptyMap(),
            options = ProxyOptions(socks5 = true, ipSniff = true),
        ).apply {
            transparentUpstreamOverrideForTesting = { host, _ ->
                if (host == "delayed.example.com") {
                    connectEntered.countDown()
                    releaseConnect.await(5, TimeUnit.SECONDS)
                    InetSocketAddress("127.0.0.1", echoServer.localPort)
                } else {
                    InetSocketAddress("127.0.0.1", deadPort)
                }
            }
        }
        val proxyPort = proxy.start(0)
        try {
            // Hostname HTTP CONNECT must not send 200 before upstream connect completes.
            Socket("127.0.0.1", proxyPort).use { s ->
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                out.write("CONNECT delayed.example.com:443 HTTP/1.1\r\nHost: delayed.example.com:443\r\n\r\n".toByteArray())
                out.flush()

                assertTrue(connectEntered.await(3, TimeUnit.SECONDS))
                s.soTimeout = 150
                assertFailsWith<java.net.SocketTimeoutException> {
                    inp.read()
                }

                releaseConnect.countDown()
                s.soTimeout = 3000
                val resp = readUntilBlankLine(inp)
                assertTrue(resp.startsWith("HTTP/1.1 200"), "Expected 200 after connect, got: $resp")

                out.write("ECHO".toByteArray())
                out.flush()
                assertEquals("ECHO", String(inp.readNBytes(4)))
            }

            // Hostname HTTP CONNECT to dead upstream closes without sending 200.
            Socket("127.0.0.1", proxyPort).use { s ->
                s.soTimeout = 3000
                s.getOutputStream().apply {
                    write("CONNECT dead.example.com:443 HTTP/1.1\r\nHost: dead.example.com:443\r\n\r\n".toByteArray())
                    flush()
                }
                assertEquals(-1, s.getInputStream().read())
            }
        } finally {
            releaseConnect.countDown()
            proxy.stop()
            echoServer.close()
            echoThread.join(1000)
        }
    }

    @Test(timeout = 10000)
    fun secondPumpThreadWithClosedSocketsEndsQuietlyWithoutUncaughtException() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val uncaught = AtomicReference<Throwable?>(null)
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.compareAndSet(null, e) }
        val proxy = ProxyServer(emptyMap())
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val accepted = CopyOnWriteArrayList<Socket>()
        try {
            // 1. Both sockets already closed.
            val closedUp = Socket().apply { close() }
            val closedClient = Socket().apply { close() }
            val tBothClosed = proxy.startPumpThread(closedUp, closedClient, 0L, AtomicLong(0))
            tBothClosed.join(5000)
            assertFalse(tBothClosed.isAlive)
            assertNull(uncaught.get())
            assertTrue(closedUp.isClosed)
            assertTrue(closedClient.isClosed)

            // 2. Upstream open, client already closed (matches Socket.getOutputStream crash in #479).
            val openUp = Socket("127.0.0.1", server.localPort)
            accepted += server.accept()
            val closedClient2 = Socket().apply { close() }
            val tClientClosed = proxy.startPumpThread(openUp, closedClient2, 0L, AtomicLong(0))
            tClientClosed.join(5000)
            assertFalse(tClientClosed.isAlive)
            assertNull(uncaught.get())
            assertTrue(openUp.isClosed)
            assertTrue(closedClient2.isClosed)

            // 3. Upstream already closed, client open (matches Socket.getInputStream crash).
            val closedUp3 = Socket().apply { close() }
            val openClient = Socket("127.0.0.1", server.localPort)
            accepted += server.accept()
            val tUpClosed = proxy.startPumpThread(closedUp3, openClient, 0L, AtomicLong(0))
            tUpClosed.join(5000)
            assertFalse(tUpClosed.isAlive)
            assertNull(uncaught.get())
            assertTrue(closedUp3.isClosed)
            assertTrue(openClient.isClosed)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            accepted.forEach { runCatching { it.close() } }
            runCatching { server.close() }
            proxy.stop()
        }
    }

    @Test(timeout = 10000)
    fun acceptLoopCatchesNonIoExceptionStopsAcceptingAndLeavesNoUncaughtException() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val uncaught = AtomicReference<Throwable?>(null)
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.compareAndSet(null, e) }
        val proxy = ProxyServer(emptyMap())
        val failingSocket = object : ServerSocket() {
            override fun accept(): Socket {
                throw OutOfMemoryError("synthetic OOM in accept")
            }
        }
        try {
            proxy.start(0)
            assertTrue(proxy.isAccepting)
            val t = proxy.startAcceptThread(failingSocket, "test-accept-failure")
            t.join(5000)
            assertFalse(t.isAlive)
            assertNull(uncaught.get())
            assertFalse(proxy.isAccepting)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            runCatching { failingSocket.close() }
            proxy.stop()
        }
    }

    private fun readUntilBlankLine(input: java.io.InputStream): String {
        val sb = StringBuilder()
        var tail = 0
        while (true) {
            val b = input.read()
            if (b < 0) break
            sb.append(b.toChar())
            tail = if ((tail == 0 || tail == 2) && b == '\r'.code) tail + 1
            else if ((tail == 1 || tail == 3) && b == '\n'.code) tail + 1
            else 0
            if (tail == 4) break
        }
        return sb.toString()
    }

    private fun serverSslContext(certs: io.github.fate_grand_automata.scripts.capture.IssuedCerts): SSLContext {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("server", certs.leafKey, CharArray(0), certs.chain.toTypedArray())
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(ks, CharArray(0))
        }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    private fun clientTrustContext(certs: io.github.fate_grand_automata.scripts.capture.IssuedCerts): SSLContext {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("ca", certs.caCert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(ks)
        }
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
    }
}
