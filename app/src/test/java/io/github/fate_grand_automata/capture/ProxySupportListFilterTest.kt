package io.github.fate_grand_automata.capture

import io.github.fate_grand_automata.scripts.capture.BodyDecoder
import io.github.fate_grand_automata.scripts.capture.CaptureNames
import io.github.fate_grand_automata.scripts.capture.HttpHead
import io.github.fate_grand_automata.scripts.capture.HttpReader
import io.github.fate_grand_automata.scripts.capture.SingleUseCa
import io.github.fate_grand_automata.scripts.capture.SupportListCounts
import io.github.fate_grand_automata.scripts.capture.SupportListCriteria
import io.github.fate_grand_automata.scripts.capture.SupportListFilter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxySupportListFilterTest {
    private val gameHost = CaptureNames.JP_HOST

    private fun slot(svt: Long, deck: Int, cls: Int) =
        """{"supportDeckId":$deck,"classId":$cls,"svtId":$svt,"userSvtId":${if (svt == 0L) 0 else 7000 + svt},"equipTarget1":{"svtId":0}}"""

    private fun hash(svt: Long = 0) = (1..3).flatMap { d -> (0..8).map { c -> slot(if (d == 1 && c == 1) svt else 0, d, c) } }.joinToString(",")

    private fun follower(id: String, type: Int, svt: Long = 0) =
        """{"type":$type,"userId":"$id","mainSupportDeckIds":[1,2,3],"userSvtLeaderHash":[${hash(svt)}],"eventUserSvtLeaderHash":[${hash()}],"userSvtGrandHash":[]}"""

    private val listJson = """{"response":[{"resCode":"00"}],"cache":{"updated":{"userFollower":[{"followerInfo":[""" +
        """${follower("a", 1, 100)},${follower("b", 1)},${follower("c", 2)}]}]},"serverTime":1}}"""

    private val criteria = SupportListCriteria(setOf(100L))

    /** What the game received for one response, and what the fake upstream and capture saw around it. */
    private class Exchange(
        val clientWire: ByteArray,
        val head: HttpHead,
        val body: ByteArray,
        val captured: List<ByteArray>,
        val upstreamReceived: ByteArray,
    )

    private class Step(val request: ByteArray, val response: ByteArray)

    private val request = "POST /follower/list?_userId=1&token=t HTTP/1.1\r\nHost: $gameHost\r\nContent-Length: 11\r\n\r\nhello=world".toByteArray()

    private fun gzip(text: String) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()

    private fun response(body: ByteArray, headers: String = "", chunked: Boolean = false, status: String = "200 OK", trailers: String = ""): ByteArray {
        val out = ByteArrayOutputStream()
        if (chunked) {
            out.write("HTTP/1.1 $status\r\nContent-Type: application/json\r\n${headers}Transfer-Encoding: chunked\r\n\r\n".toByteArray())
            body.toList().chunked(7).forEach { part ->
                out.write("${part.size.toString(16)}\r\n".toByteArray())
                out.write(part.toByteArray())
                out.write("\r\n".toByteArray())
            }
            out.write("0\r\n${trailers}\r\n".toByteArray())
        } else {
            out.write("HTTP/1.1 $status\r\nContent-Type: application/json\r\n${headers}Content-Length: ${body.size}\r\n\r\n".toByteArray())
            out.write(body)
        }
        return out.toByteArray()
    }

    private class Recording(input: InputStream, val sink: ByteArrayOutputStream) : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) sink.write(it) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) sink.write(b, off, it) }
    }

    /**
     * Runs [steps] over one keep-alive connection. The fake upstream records every byte it receives until the
     * client closes, so anything the proxy adds to a request shows up in [Exchange.upstreamReceived].
     */
    private fun exchangeAll(
        steps: List<Step>,
        criteria: SupportListCriteria?,
        readingOff: Boolean = false,
        configure: ProxyServer.() -> Unit = {},
        source: () -> SupportListCriteria? = { criteria },
    ): List<Exchange> {
        val upstreamCerts = SingleUseCa.issue(gameHost)
        val mitmCerts = SingleUseCa.issue(gameHost)
        val upstreamClientCtx = trust(upstreamCerts)
        val mitmClientCtx = trust(mitmCerts)
        val captured = CopyOnWriteArrayList<ByteArray>()
        val upstreamSaw = ByteArrayOutputStream()
        val upstreamDone = CountDownLatch(1)
        val mockServer = (serverContext(upstreamCerts).serverSocketFactory.createServerSocket(0, 50, InetAddress.getByName("127.0.0.1")) as SSLServerSocket)
        val serverThread = Thread {
            runCatching {
                (mockServer.accept() as SSLSocket).use { sock ->
                    sock.startHandshake()
                    val recording = Recording(sock.getInputStream(), upstreamSaw)
                    val r = HttpReader(recording)
                    for (step in steps) {
                        val req = r.readHead() ?: break
                        r.copyBody(req, ByteArrayOutputStream(), isResponse = false, captureBody = false)
                        sock.getOutputStream().apply { write(step.response); flush() }
                    }
                    val drain = ByteArray(1024)
                    while (recording.read(drain) >= 0) Unit
                }
            }
            upstreamDone.countDown()
        }.apply { isDaemon = true; start() }
        val proxy = ProxyServer(
            sslContexts = emptyMap(),
            options = ProxyOptions(socks5 = true, ipSniff = true, maxConnections = 8),
            onGameResponse = { _, payload, _ -> captured += payload },
            supportListCriteria = source,
        ).apply {
            upstreamSslFactoryForTesting = upstreamClientCtx.socketFactory
            upstreamHostnameVerifierForTesting = HostnameVerifier { host, session ->
                val leaf = session.peerCertificates.firstOrNull() as? X509Certificate ?: return@HostnameVerifier false
                leaf.subjectAlternativeNames?.any { entry -> entry.size >= 2 && entry[1] == host } == true
            }
            configure()
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
            // Reading goes off only after the connection is up, so the next request is the one that is not recorded.
            if (readingOff) proxy.setContexts(emptyMap())
            val reader = HttpReader(tls.getInputStream())
            val results = steps.map { step ->
                tls.getOutputStream().apply { write(step.request); flush() }
                val head = reader.readHead()!!
                val wire = ByteArrayOutputStream()
                wire.write(head.raw)
                // copyBody tees the wire bytes, chunk framing included, so head + tee is what the game received.
                val body = reader.copyBody(head, wire, isResponse = true, requestMethod = "POST")
                Triple(wire.toByteArray(), head, body.payload!!)
            }
            tls.close()
            assertTrue(upstreamDone.await(5, TimeUnit.SECONDS))
            return results.map { (wire, head, body) -> Exchange(wire, head, body, captured.toList(), upstreamSaw.toByteArray()) }
        } finally {
            proxy.stop()
            mockServer.close()
        }
    }

    private fun exchange(
        upstreamResponse: ByteArray,
        criteria: SupportListCriteria?,
        sent: ByteArray = request,
        readingOff: Boolean = false,
        configure: ProxyServer.() -> Unit = {},
    ): Exchange = exchangeAll(listOf(Step(sent, upstreamResponse)), criteria, readingOff, configure).single()

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

    /** The proxy wired to the app's state, as the capture services wire it (#695). */
    private fun exchangeWithState(upstream: ByteArray) =
        exchangeAll(listOf(Step(request, upstream)), null, source = SupportListFilterState::criteria).single()

    @Test(timeout = 30000)
    fun featureOffOrToggleOffRelaysByteForByteThroughTheAppState() {
        val ready = SupportFilterBuild.Ready(criteria)
        try {
            for (chunked in listOf(false, true)) {
                val upstream = response(listJson.toByteArray(), chunked = chunked)
                for ((feature, toggle) in listOf(false to true, true to false, false to false)) {
                    SupportListFilterState.clear()
                    SupportListFilterState.offer(SupportListFilterState.request(), ready) { feature && toggle }
                    assertContentEquals(upstream, exchangeWithState(upstream).clientWire, "feature=$feature toggle=$toggle")
                }
                SupportListFilterState.offer(SupportListFilterState.request(), ready) { true }
                val e = exchangeWithState(upstream)
                assertEquals(1, kept(e.body).size)
                assertContentEquals(request, e.upstreamReceived)
            }
        } finally {
            SupportListFilterState.resetForTesting()
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
    fun nonSuccessStatusIsRelayedByteForByteWithSwitchOn() {
        for (status in listOf("500 Internal Server Error", "404 Not Found", "302 Found")) {
            val upstream = response(listJson.toByteArray(), status = status)
            assertContentEquals(upstream, exchange(upstream, criteria).clientWire)
        }
    }

    @Test(timeout = 20000)
    fun captureNotReadingRelaysByteForByteWithSwitchOn() {
        val upstream = response(listJson.toByteArray())
        val e = exchange(upstream, criteria, readingOff = true)
        assertContentEquals(upstream, e.clientWire)
        assertTrue(e.captured.isEmpty())
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

    @Test(timeout = 20000)
    fun headersThatDescribeTheOriginalBodyPassTheOriginalBytes() {
        for (header in listOf("ETag: \"abc\"\r\n", "Content-MD5: abc\r\n", "Digest: sha-256=abc\r\n", "Trailer: X-Sum\r\n")) {
            val upstream = response(listJson.toByteArray(), header)
            assertContentEquals(upstream, exchange(upstream, criteria).clientWire, header)
        }
    }

    @Test(timeout = 20000)
    fun chunkedBodyWithTrailersPassesTheOriginalBytes() {
        val upstream = response(listJson.toByteArray(), chunked = true, trailers = "X-Sum: 1\r\n")
        assertContentEquals(upstream, exchange(upstream, criteria).clientWire)
    }

    @Test(timeout = 20000)
    fun failureWhileBuildingTheNewHeadPassesTheOriginalBytes() {
        for (chunked in listOf(false, true)) {
            val upstream = response(listJson.toByteArray(), chunked = chunked)
            val e = exchange(upstream, criteria, configure = { setSupportListRewriteHeadForTesting { _, _ -> error("boom") } })
            assertContentEquals(upstream, e.clientWire)
        }
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

    private fun followerWithOther(id: String) = follower(id, 1, 100).replaceFirst(
        """{"supportDeckId":1,"classId":2,"svtId":0,""",
        """{"supportDeckId":1,"classId":2,"svtId":999,""",
    )

    private val listWithOtherServant = """{"response":[{"resCode":"00"}],"cache":{"updated":{"userFollower":[{"followerInfo":[""" +
        """${followerWithOther("a")},${follower("b", 2)}]}]},"serverTime":1}}"""

    @Test(timeout = 20000)
    fun aSlotThatDoesNotMatchIsBlankedInTheBodyTheGameGetsAndTheCaptureKeepsTheOriginal() {
        val e = exchange(response(listWithOtherServant.toByteArray()), criteria)
        val slots = kept(e.body)[0].jsonObject["userSvtLeaderHash"]!!.jsonArray
        assertEquals(27, slots.size)
        assertEquals("100", slots[1].jsonObject["svtId"]!!.toString())
        assertEquals("0", slots[2].jsonObject["svtId"]!!.toString())
        assertEquals(1, kept(e.body).size)
        assertContentEquals(listWithOtherServant.toByteArray(), e.captured[0])
    }

    private fun equip(ce: Long, lv: Int = 100) =
        """{"userId":9,"userSvtId":${if (ce == 0L) 0 else 8},"svtId":$ce,"limitCount":${if (ce == 0L) 0 else 4},"lv":${if (ce == 0L) 0 else lv},"exp":0,"hp":0,"atk":0,""" +
            """"skillId1":0,"skillLv1":0,"skillId2":0,"skillLv2":0,"skillId3":0,"skillLv3":0,"addSkills":[],"updatedAt":0}"""

    /**
     * One userSvtGrandHash entry with the keys of a real JP entry: no supportDeckId or classId, three CEs, grandGraphId,
     * and a script with isSetClassBoardBaseIds (present in every real entry) and equipTarget2SkillChange.
     */
    private fun grandEntry(svt: Long, graph: Long) =
        """{"afterTransformSvtInfo":null,"dispLimitCount":4,"imageLimitCount":4,"commandCardLimitCount":4,"iconLimitCount":4,"portraitLimitCount":4,""" +
            """"battleVoice":0,"grandSvt":1,"transformVal":0,"randomLimitCountTargets":[],"classPassive":[3],""" +
            """"equipTarget1":${equip(7)},"equipTarget2":${equip(9, lv = 80)},"equipTarget3":${equip(0)},""" +
            """"commandCode":[],"commandCardParam":[1,2,3,4,5],"appendPassiveSkill":[{"skillId":1,"skillLv":10}],"script":{"equipTarget2SkillChange":1,"isSetClassBoardBaseIds":[]},""" +
            """"eventSvtPoint":0,"imagePartsGroupInfo":[],"grandGraphId":$graph,"isSetClassBoardBaseIds":[],"userId":555,"userSvtId":43,"svtId":$svt,""" +
            """"limitCount":4,"lv":120,"exp":1,"hp":1,"atk":1,"adjustHp":0,"adjustAtk":0,"skillId1":1,"skillId2":2,"skillId3":3,""" +
            """"skillLv1":10,"skillLv2":10,"skillLv3":10,"treasureDeviceId":5,"treasureDeviceLv":5,"exceedCount":1,"updatedAt":1708036772}"""

    private fun grandList(vararg svts: Long) = svts.mapIndexed { i, svt -> grandEntry(svt, 10001L + i) }.joinToString(",", "[", "]")

    private fun jpFollower(id: String, type: Int, svt: Long, grand: String, other: Long = 0) = follower(id, type, svt)
        .replace(""""userSvtGrandHash":[]""", """"userSvtGrandHash":$grand""")
        .let { if (other == 0L) it else it.replaceFirst("""{"supportDeckId":1,"classId":2,"svtId":0,""", """{"supportDeckId":1,"classId":2,"svtId":$other,""") }

    private fun jpList(vararg followers: String) =
        """{"response":[{"resCode":"00"}],"cache":{"updated":{"userFollower":[{"followerInfo":[${followers.joinToString(",")}]}]},"serverTime":1}}"""

    @Test(timeout = 20000)
    fun aJpListWhereEverySlotAndGrandEntryMatchesReachesTheGameByteForByte() {
        val list = jpList(jpFollower("a", 1, 100, grandList(100, 100)), jpFollower("b", 2, 100, grandList(100)))
        for (chunked in listOf(false, true)) {
            val upstream = response(list.toByteArray(), chunked = chunked)
            assertContentEquals(upstream, exchange(upstream, criteria).clientWire)
        }
        val gz = response(gzip(list), "Content-Encoding: gzip\r\n")
        assertContentEquals(gz, exchange(gz, criteria).clientWire)
    }

    @Test(timeout = 20000)
    fun aFilteredJpListCarriesOnlyTheKeptFollowersMatchingGrandEntries() {
        val list = jpList(
            jpFollower("a", 1, 100, grandList(999, 100, 998), other = 996),
            jpFollower("b", 1, 0, grandList(995)),
            jpFollower("c", 2, 100, grandList(994)),
        )
        val e = exchange(response(list.toByteArray()), criteria)
        val (a, c) = kept(e.body).map { it.jsonObject }
        assertEquals("0", a["userSvtLeaderHash"]!!.jsonArray[2].jsonObject["svtId"]!!.toString())
        assertTrue(String(e.body).contains(""""userSvtGrandHash":[${grandEntry(100, 10002)}]"""))
        assertEquals("[]", c["userSvtGrandHash"].toString())
        assertEquals(2, kept(e.body).size)
        assertContentEquals(list.toByteArray(), e.captured[0])
        assertContentEquals(request, e.upstreamReceived)
    }

    @Test(timeout = 20000)
    fun lastResultHoldsTheCountsAfterAFilteredListAndUnchangedAfterAPassThrough() {
        SupportListLastResult.resetForTesting()
        assertNull(SupportListLastResult.last.value)
        exchange(response(listWithOtherServant.toByteArray()), criteria)
        assertEquals(
            SupportListLastResult.Result.Filtered(SupportListCounts(2, 1, 2, 1)),
            SupportListLastResult.last.value,
        )
        // Nothing to leave out or blank: the original bytes go through.
        val untouched = """{"response":[{"resCode":"00"}],"cache":{"updated":{"userFollower":[{"followerInfo":[${follower("a", 1, 100)}]}]},"serverTime":1}}"""
        val upstream = response(untouched.toByteArray())
        assertContentEquals(upstream, exchange(upstream, criteria).clientWire)
        assertEquals(SupportListLastResult.Result.Unchanged, SupportListLastResult.last.value)
    }

    @Test
    fun readingTurnedOffClearsTheLastResult() {
        val proxy = ProxyServer(
            sslContexts = emptyMap(),
            options = ProxyOptions(socks5 = true, ipSniff = true, maxConnections = 8),
            onGameResponse = { _, _, _ -> },
        )
        val certs = SingleUseCa.issue(gameHost)
        SupportListLastResult.filtered(SupportListCounts(2, 1, 2, 1))
        proxy.setContexts(mapOf(gameHost to serverContext(certs)))
        assertEquals(SupportListLastResult.Result.Filtered(SupportListCounts(2, 1, 2, 1)), SupportListLastResult.last.value)
        proxy.setContexts(emptyMap())
        assertNull(SupportListLastResult.last.value)
    }

    @Test(timeout = 20000)
    fun captureRecordsTheOriginalResponse() {
        val e = exchange(response(gzip(listJson), "Content-Encoding: gzip\r\n"), criteria)
        assertEquals(1, e.captured.size)
        assertContentEquals(gzip(listJson), e.captured[0])
        assertEquals(3, kept(e.captured[0], "gzip").size)
    }

    @Test(timeout = 20000)
    fun upstreamReceivesExactlyTheBytesTheClientSentWithSwitchOn() {
        val e = exchange(response(listJson.toByteArray()), criteria)
        assertContentEquals(request, e.upstreamReceived)
    }

    @Test(timeout = 30000)
    fun keepAliveConnectionDeliversBothRequestsAndResponsesIntact() {
        val second = "POST /battle/result HTTP/1.1\r\nHost: $gameHost\r\nContent-Length: 4\r\n\r\nabcd".toByteArray()
        val secondResponse = response("""{"ok":true}""".toByteArray())
        val results = exchangeAll(listOf(Step(request, response(gzip(listJson), "Content-Encoding: gzip\r\n")), Step(second, secondResponse)), criteria)
        assertContentEquals(request + second, results[0].upstreamReceived)
        assertEquals(1, kept(results[0].body, "gzip").size)
        assertEquals(results[0].body.size, contentLength(results[0].head))
        assertContentEquals(secondResponse, results[1].clientWire)
    }

    @Test
    fun heldResponseNeverKeepsMoreThanItsLimitAndSendsEverythingInOrder() {
        val out = ByteArrayOutputStream()
        val head = ByteArray(10) { 1 }
        val held = HeldResponse(out, head, 100)
        val data = ByteArray(250) { it.toByte() }
        var highest = 0
        for (i in data.indices step 7) {
            held.write(data, i, minOf(7, data.size - i))
            highest = maxOf(highest, held.heldBytes)
        }
        assertTrue(highest <= 100, "held $highest")
        assertTrue(held.released)
        assertEquals(0, held.heldBytes)
        assertContentEquals(head + data, out.toByteArray())
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
