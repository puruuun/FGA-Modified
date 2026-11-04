package io.github.fate_grand_automata.capturespike

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * What [SingleUseCa.issue] returns. Deliberately holds no CA private key.
 */
class IssuedCerts(
    val caCert: X509Certificate,
    val leafKey: PrivateKey,
    /** leaf first, then the CA. */
    val chain: List<X509Certificate>,
)

/**
 * Builds a CA, signs exactly one leaf with it and throws the CA key away.
 * Hand-rolled DER (JDK only), so there is no BouncyCastle clash with Android's own copy.
 */
object SingleUseCa {
    const val CA_ORG = "FGA-Modified capture spike"

    fun issue(host: String, now: Instant = Instant.now(), random: SecureRandom = SecureRandom()): IssuedCerts {
        val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }
        val caPair = gen.generateKeyPair()
        val leafPair = gen.generateKeyPair()

        val notBefore = now.minusSeconds(86_400)
        val notAfter = now.atZone(ZoneOffset.UTC).plusYears(5).toInstant()
        val day = DateTimeFormatter.ISO_LOCAL_DATE.format(LocalDate.ofInstant(now, ZoneOffset.UTC))
        val caName = name("FGA single-use capture CA $day", CA_ORG)
        val leafName = name(host, null)

        val caSki = keyId(caPair.public)
        val caDer = certificate(
            issuer = caName, subject = caName, subjectKey = caPair.public, signer = caPair.private,
            notBefore = notBefore, notAfter = notAfter, random = random,
            extensions = listOf(
                ext(OID_BASIC, true, seq(bool(true), integer(BigInteger.ZERO))),
                ext(OID_KEY_USAGE, true, bits(0x06, 1)),
                ext(OID_SKI, false, octets(caSki)),
            ),
        )
        val leafDer = certificate(
            issuer = caName, subject = leafName, subjectKey = leafPair.public, signer = caPair.private,
            notBefore = notBefore, notAfter = notAfter, random = random,
            extensions = listOf(
                ext(OID_BASIC, true, seq()),
                ext(OID_KEY_USAGE, true, bits(0xA0, 5)),
                ext(OID_EKU, false, seq(oid(OID_SERVER_AUTH))),
                ext(OID_AKI, false, seq(tlv(0x80, caSki))),
                ext(OID_SKI, false, octets(keyId(leafPair.public))),
                ext(OID_SAN, false, seq(tlv(0x82, host.toByteArray(Charsets.US_ASCII)))),
            ),
        )
        // caPair.private goes out of scope here and is never stored or returned.
        val cf = CertificateFactory.getInstance("X.509")
        val ca = cf.generateCertificate(caDer.inputStream()) as X509Certificate
        val leaf = cf.generateCertificate(leafDer.inputStream()) as X509Certificate
        return IssuedCerts(ca, leafPair.private, listOf(leaf, ca))
    }

    // ---- DER ----

    private const val OID_BASIC = "2.5.29.19"
    private const val OID_KEY_USAGE = "2.5.29.15"
    private const val OID_SKI = "2.5.29.14"
    private const val OID_AKI = "2.5.29.35"
    private const val OID_EKU = "2.5.29.37"
    private const val OID_SAN = "2.5.29.17"
    private const val OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
    private const val OID_SHA256_RSA = "1.2.840.113549.1.1.11"

    private fun certificate(
        issuer: ByteArray, subject: ByteArray, subjectKey: PublicKey, signer: PrivateKey,
        notBefore: Instant, notAfter: Instant, random: SecureRandom, extensions: List<ByteArray>,
    ): ByteArray {
        val serialBytes = ByteArray(16).also { random.nextBytes(it) }
        serialBytes[0] = (serialBytes[0].toInt() and 0x7F or 0x40).toByte()
        val sigAlg = seq(oid(OID_SHA256_RSA), tlv(0x05, ByteArray(0)))
        val tbs = seq(
            tlv(0xA0, tlv(0x02, byteArrayOf(2))), // version v3
            tlv(0x02, serialBytes),
            sigAlg,
            issuer,
            seq(time(notBefore), time(notAfter)),
            subject,
            subjectKey.encoded, // SubjectPublicKeyInfo
            tlv(0xA3, seq(*extensions.toTypedArray())),
        )
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(signer)
            update(tbs)
            sign()
        }
        return seq(tbs, sigAlg, tlv(0x03, byteArrayOf(0) + sig))
    }

    private fun keyId(key: PublicKey): ByteArray = MessageDigest.getInstance("SHA-1").digest(key.encoded)

    private fun name(cn: String, org: String?): ByteArray {
        val rdns = mutableListOf<ByteArray>()
        if (org != null) rdns += set(seq(oid("2.5.4.10"), tlv(0x0C, org.toByteArray())))
        rdns += set(seq(oid("2.5.4.3"), tlv(0x0C, cn.toByteArray())))
        return seq(*rdns.toTypedArray())
    }

    private fun ext(oid: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) seq(oid(oid), bool(true), octets(value)) else seq(oid(oid), octets(value))

    private fun time(t: Instant): ByteArray {
        val z = t.atZone(ZoneOffset.UTC)
        return if (z.year < 2050) {
            tlv(0x17, DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").format(z).toByteArray())
        } else {
            tlv(0x18, DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").format(z).toByteArray())
        }
    }

    private fun bits(value: Int, unused: Int) = tlv(0x03, byteArrayOf(unused.toByte(), value.toByte()))
    private fun octets(b: ByteArray) = tlv(0x04, b)
    private fun bool(v: Boolean) = tlv(0x01, byteArrayOf(if (v) 0xFF.toByte() else 0))
    private fun integer(v: BigInteger) = tlv(0x02, v.toByteArray())
    private fun seq(vararg items: ByteArray) = tlv(0x30, concat(items))
    private fun set(vararg items: ByteArray) = tlv(0x31, concat(items))

    private fun oid(s: String): ByteArray {
        val p = s.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((p[0] * 40 + p[1]).toInt())
        for (v in p.drop(2)) {
            val tmp = mutableListOf<Int>()
            var x = v
            tmp += (x and 0x7F).toInt()
            x = x shr 7
            while (x > 0) {
                tmp += ((x and 0x7F) or 0x80).toInt()
                x = x shr 7
            }
            tmp.reversed().forEach { out.write(it) }
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun concat(items: Array<out ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        items.forEach { o.write(it) }
        return o.toByteArray()
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(tag)
        val n = content.size
        when {
            n < 0x80 -> o.write(n)
            n < 0x100 -> { o.write(0x81); o.write(n) }
            n < 0x10000 -> { o.write(0x82); o.write(n shr 8); o.write(n and 0xFF) }
            else -> { o.write(0x83); o.write(n shr 16); o.write((n shr 8) and 0xFF); o.write(n and 0xFF) }
        }
        o.write(content)
        return o.toByteArray()
    }
}
