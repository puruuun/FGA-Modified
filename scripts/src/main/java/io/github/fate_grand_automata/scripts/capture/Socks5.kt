package io.github.fate_grand_automata.scripts.capture

import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress

/**
 * Pure JVM RFC 1928 SOCKS5 greeting and CONNECT request parser/encoder.
 */
object Socks5 {
    const val VERSION: Byte = 0x05
    const val METHOD_NO_AUTH: Byte = 0x00
    const val METHOD_NO_ACCEPTABLE: Byte = 0xFF.toByte()

    const val CMD_CONNECT: Byte = 0x01
    const val ATYP_IPV4: Byte = 0x01
    const val ATYP_DOMAIN: Byte = 0x03
    const val ATYP_IPV6: Byte = 0x04

    const val REP_SUCCEEDED: Byte = 0x00
    const val REP_GENERAL_FAILURE: Byte = 0x01
    const val REP_HOST_UNREACHABLE: Byte = 0x04
    const val REP_CONNECTION_REFUSED: Byte = 0x05
    const val REP_COMMAND_NOT_SUPPORTED: Byte = 0x07
    const val REP_ADDRESS_TYPE_NOT_SUPPORTED: Byte = 0x08

    enum class AddressType {
        Ipv4,
        Domain,
        Ipv6,
    }

    data class Target(
        val host: String,
        val port: Int,
        val addressType: AddressType,
    )

    sealed interface RequestResult {
        data class Connect(val target: Target) : RequestResult
        data class Error(val replyCode: Byte, val reason: String) : RequestResult
    }

    /**
     * Reads the client method greeting. If [firstByte] is provided, it is used as the already-read
     * version byte; otherwise the version byte is read from [input].
     * Returns true if method 0x00 (No Auth) was offered and the version is 0x05.
     */
    fun readGreeting(input: InputStream, firstByte: Int? = null): Boolean {
        val ver = firstByte ?: input.read()
        if (ver != VERSION.toInt()) return false
        val nMethods = input.read()
        if (nMethods <= 0) return false
        val methods = readExact(input, nMethods) ?: return false
        return methods.any { it == METHOD_NO_AUTH }
    }

    fun methodReply(acceptedNoAuth: Boolean): ByteArray =
        byteArrayOf(VERSION, if (acceptedNoAuth) METHOD_NO_AUTH else METHOD_NO_ACCEPTABLE)

    /**
     * Reads a SOCKS5 request after method negotiation and validates that it is a CONNECT request
     * with a valid IPv4, non-empty Domain, or IPv6 address and port.
     */
    fun readRequest(input: InputStream): RequestResult {
        val header = readExact(input, 4)
            ?: return RequestResult.Error(REP_GENERAL_FAILURE, "EOF reading SOCKS5 header")
        val ver = header[0]
        val cmd = header[1]
        val rsv = header[2]
        val atyp = header[3]

        if (ver != VERSION || rsv != 0x00.toByte()) {
            return RequestResult.Error(REP_GENERAL_FAILURE, "Invalid SOCKS5 header")
        }

        val (addressType, host) = when (atyp) {
            ATYP_IPV4 -> {
                val addr = readExact(input, 4)
                    ?: return RequestResult.Error(REP_GENERAL_FAILURE, "EOF reading IPv4")
                AddressType.Ipv4 to (InetAddress.getByAddress(addr).hostAddress ?: "")
            }

            ATYP_DOMAIN -> {
                val len = input.read()
                if (len < 0) return RequestResult.Error(REP_GENERAL_FAILURE, "EOF reading domain length")
                if (len == 0) return RequestResult.Error(REP_GENERAL_FAILURE, "Empty domain")
                val domainBytes = readExact(input, len)
                    ?: return RequestResult.Error(REP_GENERAL_FAILURE, "EOF reading domain")
                val domain = String(domainBytes, Charsets.UTF_8)
                AddressType.Domain to domain
            }

            ATYP_IPV6 -> {
                val addr = readExact(input, 16)
                    ?: return RequestResult.Error(REP_GENERAL_FAILURE, "EOF reading IPv6")
                AddressType.Ipv6 to (InetAddress.getByAddress(addr).hostAddress ?: "")
            }

            else -> {
                if (cmd != CMD_CONNECT) {
                    return RequestResult.Error(REP_COMMAND_NOT_SUPPORTED, "Unsupported command $cmd")
                }
                return RequestResult.Error(REP_ADDRESS_TYPE_NOT_SUPPORTED, "Unsupported ATYP $atyp")
            }
        }

        val portBytes = readExact(input, 2)
            ?: return RequestResult.Error(REP_GENERAL_FAILURE, "EOF reading port")
        val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        if (cmd != CMD_CONNECT) {
            return RequestResult.Error(REP_COMMAND_NOT_SUPPORTED, "Unsupported command $cmd")
        }
        if (host.isBlank() || port <= 0) {
            return RequestResult.Error(REP_GENERAL_FAILURE, "Invalid host or port")
        }

        return RequestResult.Connect(Target(host = host, port = port, addressType = addressType))
    }

    /** Builds a SOCKS5 reply packet with bound address 0.0.0.0:0. */
    fun reply(rep: Byte): ByteArray =
        byteArrayOf(VERSION, rep, 0x00, ATYP_IPV4, 0, 0, 0, 0, 0, 0)

    private fun readExact(input: InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var off = 0
        while (off < count) {
            val n = input.read(buf, off, count - off)
            if (n < 0) return null
            off += n
        }
        return buf
    }
}
