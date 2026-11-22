package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.BodyDecoder
import io.github.fate_grand_automata.scripts.capture.CaptureNames
import io.github.fate_grand_automata.scripts.capture.HttpHead
import io.github.fate_grand_automata.scripts.capture.HttpReader
import io.github.fate_grand_automata.scripts.capture.SingleUseCa
import io.github.fate_grand_automata.scripts.capture.SupportListCriteria
import io.github.fate_grand_automata.scripts.capture.SupportListFilter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxySupportListFilterTest {
    private val gameHost = CaptureNames.JP_HOST

    private fun slot(svt: Long) = """{"supportDeckId":1,"classId":1,"svtId":$svt,"equipTarget1":{"svtId":0}}"""

    private val listJson = """{"response":[{"resCode":"00"}],"cache":{"updated":{"userFollower":[{"followerInfo":[""" +
        """{"type":1,"userId":"a","userSvtLeaderHash":[${slot(100)},${slot(999)}]},""" +
        """{"type":1,"userId":"b","userSvtLeaderHash":[${slot(999)}]}]}]},"serverTime":1},"sign":"s"}"""

    private val criteria = SupportListCriteria(setOf(100L))

    private class Exchange(val clientWire: ByteArray, val head: HttpHead, val body: ByteArray, val captured: List<ByteArray>, val upstreamRequest: ByteArray)

    private val request = "POST /follower/list?_userId=1&token=t HTTP/1.1\r\nHost: $gameHost\r\nContent-Length: 11\r\n\r\nhello=world".toByteArray()

    private fun gzip(text: String) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()

    private fun response(body: ByteArray, headers: String = "", chunked: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        if (chunked) {
            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n${headers}Transfer-Encoding: chunked\r\n\r\n".toByteArray())
            body.toList().chunked(7).forEach { part ->
                out.write("${part.size.toString(16)}\r\n".toByteArray())
                out.write(part.toByteArray())
                out.write("\r\n".toByteArray())
            }
            out.write("0\r\n\r\n".toByteArray())
        } else {
            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n${headers}Content-Length: ${body.size}\r\n\r\n".toByteArray())
            out.write(body)
        }
        return out.toByteArray()
    }

    private fun exchange(
        upstreamResponse: ByteArray,
        criteria: SupportListCriteria?,
        sent: ByteArray = request,
        path: String = "follower/list",
    ): Exchange {
        val upstreamCerts = SingleUseCa.issue(gameHost)
        val mitmCerts = SingleUseCa.issue(gameHost)
        val upstreamClientCtx = trust(upstreamCerts)
        val mitmClientCtx = trust(mitmCerts)
        val captured = CopyOnWriteArrayList<ByteArray>()
        val upstreamSaw = ByteArrayOutputStream()
        val requestSeen = CountDownLatch(1)
        val mockServer = (serverContext(upstreamCerts).serverSocketFactory.createServerSocket(0, 50, InetAddress.getByName("127.0.0.1")) as SSLServerSocket)
        val serverThread = Thread {
            runCatching {
                (mockServer.accept() as SSLSocket).use { sock ->
                    sock.startHandshake()
                    val r = HttpReader(sock.getInputStream())
                    val req = r.readHead()!!
                    upstreamSaw.write(req.raw)
                    r.copyBody(req, upstreamSaw, isResponse = false, captureBody = false)
                    requestSeen.countDown()
                    sock.getOutputStream().apply { write(upstreamResponse); flush() }
                    sock.getInputStream().read()
                }
            }
        }.apply { isDaemon = true; start() }
        val proxy = ProxyServer(
            sslContexts = emptyMap(),
            options = ProxyOptions(socks5 = true, ipSniff = true, maxConnections = 8),
            onGameResponse = { _, payload, _ -> captured += payload },
            supportListCriteria = { criteria },
        ).apply {
            upstreamSslFactoryForTesting = upstreamClientCtx.socketFactory
            upstreamHostnameVerifierForTesting = HostnameVerifier { host, session ->
                val leaf = session.peerCertificates.firstOrNull() as? X509Certificate ?: return@HostnameVerifier false
                leaf.subjectAlternativeNames?.any { entry -> entry.size >= 2 && entry[1] == host } == true
            }
        }
        proxy.setContexts(mapOf(gameHost to serverContext(mitmCerts)))
        val proxyPort = proxy.start(0)
        try {
            val raw = Socket("127.0.0.1", proxyPort)
            raw.soTimeout = 5000
            raw.getOutputStream().apply {
                write("CONNECT 127.0.0.1:${mockServer.localPort} HTTP/1.1\r\nHost: 127.0.0.1:${mockServer.localPort}\r\n\r\n".toByteArray())
                flush()
            }
            val connect = StringBuilder()
            while (!connect.endsWith("\r\n\r\n")) connect.append(raw.getInputStream().read().toChar())
            val tls = mitmClientCtx.socketFactory.createSocket(raw, "127.0.0.1", mockServer.localPort, true) as SSLSocket
            tls.sslParameters = tls.sslParameters.apply { serverNames = listOf(SNIHostName(gameHost)) }
            tls.startHandshake()
            tls.getOutputStream().apply { write(sent); flush() }
            val reader = HttpReader(tls.getInputStream())
            val head = reader.readHead()!!
            val wire = ByteArrayOutputStream()
            wire.write(head.raw)
            // copyBody tees the wire bytes, chunk framing included, so head + tee is what the game received.
            val body = reader.copyBody(head, wire, isResponse = true, requestMethod = "POST")
            assertTrue(requestSeen.await(3, TimeUnit.SECONDS))
            tls.close()
            return Exchange(wire.toByteArray(), head, body.payload!!, captured.toList(), upstreamSaw.toByteArray())
        } finally {
            proxy.stop()
            mockServer.close()
        }
    }

    private fun filteredPlain() = SupportListFilter.apply(listJson.toByteArray(), null, criteria)!!.wire

    private fun kept(body: ByteArray, encoding: String? = null) = Json.parseToJsonElement(String(BodyDecoder.decode(body, encoding)))
        .jsonObject["cache"]!!.jsonObject["updated"]!!.jsonObject["userFollower"]!!.jsonArray[0].jsonObject["followerInfo"]!!.jsonArray

    private fun contentLength(head: HttpHead) = head.header("Content-Length")?.toInt()

    @Test(timeout = 20000)
    fun switchOffRelaysSupportListByteForByte() {
        for (chunked in listOf(false, true)) {
            val upstream = response(listJson.toByteArray(), chunked = chunked)
            val e = exchange(upstream, null)
            assertContentEquals(upstream, e.clientWire)
        }
    }

    @Test(timeout = 20000)
    fun noServantChosenRelaysByteForByte() {
        val upstream = response(listJson.toByteArray())
        assertContentEquals(upstream, exchange(upstream, SupportListCriteria(emptySet())).clientWire)
    }

    @Test(timeout = 20000)
    fun otherPathIsRelayedByteForByteWithSwitchOn() {
        val upstream = response(listJson.toByteArray())
        val sent = "POST /friend/top HTTP/1.1\r\nHost: $gameHost\r\nContent-Length: 0\r\n\r\n".toByteArray()
        assertContentEquals(upstream, exchange(upstream, criteria, sent).clientWire)
    }

    @Test(timeout = 20000)
    fun switchOnFiltersPlainBodyWithMatchingHeaders() {
        val e = exchange(response(listJson.toByteArray()), criteria)
        assertEquals(1, kept(e.body).size)
        assertContentEquals(filteredPlain(), e.body)
        assertEquals(e.body.size, contentLength(e.head))
        assertNull(e.head.header("Transfer-Encoding"))
    }

    @Test(timeout = 20000)
    fun switchOnFiltersGzipBodyAndKeepsTheEncoding() {
        val e = exchange(response(gzip(listJson), "Content-Encoding: gzip\r\n"), criteria)
        assertEquals("gzip", e.head.header("Content-Encoding"))
        assertEquals(e.body.size, contentLength(e.head))
        assertEquals(1, kept(e.body, "gzip").size)
    }

    @Test(timeout = 20000)
    fun switchOnFiltersChunkedBodyAndSendsALength() {
        val e = exchange(response(listJson.toByteArray(), chunked = true), criteria)
        assertNull(e.head.header("Transfer-Encoding"))
        assertEquals(e.body.size, contentLength(e.head))
        assertEquals(1, kept(e.body).size)
        assertEquals(e.head.raw.size + e.body.size, e.clientWire.size)
    }

    @Test(timeout = 20000)
    fun parseErrorPassesTheOriginalBytes() {
        for (chunked in listOf(false, true)) {
            val upstream = response("""{"cache":{"updated":{"userFollower":[""".toByteArray(), chunked = chunked)
            assertContentEquals(upstream, exchange(upstream, criteria).clientWire)
        }
    }

    @Test(timeout = 20000)
    fun unknownEncodingPassesTheOriginalBytes() {
        val upstream = response(listJson.toByteArray(), "Content-Encoding: br\r\n")
        assertContentEquals(upstream, exchange(upstream, criteria).clientWire)
    }

    @Test(timeout = 30000)
    fun bodyOverTheLimitPassesTheOriginalBytesAndStillStreams() {
        val padded = (listJson + " ".repeat(SupportListFilter.MAX_WIRE_BYTES)).toByteArray()
        for (chunked in listOf(false, true)) {
            val upstream = response(padded, chunked = chunked)
            val e = exchange(upstream, criteria)
            assertContentEquals(upstream, e.clientWire)
        }
    }

    @Test(timeout = 20000)
    fun captureRecordsTheOriginalResponse() {
        val e = exchange(response(gzip(listJson), "Content-Encoding: gzip\r\n"), criteria)
        assertEquals(1, e.captured.size)
        assertContentEquals(gzip(listJson), e.captured[0])
        assertEquals(2, kept(e.captured[0], "gzip").size)
    }

    @Test(timeout = 20000)
    fun requestReachesTheServerUnchangedWithSwitchOn() {
        val e = exchange(response(listJson.toByteArray()), criteria)
        assertContentEquals(request, e.upstreamRequest)
        assertFalse(e.upstreamRequest.isEmpty())
    }

    private fun serverContext(certs: io.github.fate_grand_automata.scripts.capture.IssuedCerts): SSLContext {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("server", certs.leafKey, CharArray(0), certs.chain.toTypedArray())
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    private fun trust(certs: io.github.fate_grand_automata.scripts.capture.IssuedCerts): SSLContext {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("ca", certs.caCert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
    }
}
