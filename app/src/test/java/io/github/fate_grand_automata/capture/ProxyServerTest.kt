package io.github.fate_grand_automata.capture

import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.assertTrue

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
}
