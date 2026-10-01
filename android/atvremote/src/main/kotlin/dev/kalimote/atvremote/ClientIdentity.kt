package dev.kalimote.atvremote

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The client certificate and key presented to the TV. The TV remembers the
 * certificate after pairing, so the identity must be persisted and reused.
 */
class ClientIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {

    /** Serialises to a compact string for storage (base64 PKCS#8 key and DER cert). */
    fun encode(): String =
        b64.encodeToString(privateKey.encoded) + ":" + b64.encodeToString(certificate.encoded)

    val sslContext: SSLContext by lazy {
        val password = CharArray(0)
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("client", privateKey, password, arrayOf(certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)
        SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, arrayOf<TrustManager>(TrustAllManager), SecureRandom())
        }
    }

    internal fun connect(host: String, port: Int, timeoutMs: Int): SSLSocket {
        val raw = Socket()
        try {
            raw.connect(java.net.InetSocketAddress(host, port), timeoutMs)
            raw.soTimeout = timeoutMs
            raw.tcpNoDelay = true
            val socket = sslContext.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            socket.startHandshake()
            return socket
        } catch (e: Exception) {
            raw.close()
            throw e
        }
    }

    companion object {
        private val b64 = Base64.getEncoder()

        fun decode(encoded: String): ClientIdentity {
            val (key, cert) = encoded.split(":").also { require(it.size == 2) { "Bad identity" } }
            val d = Base64.getDecoder()
            val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(d.decode(key)))
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(d.decode(cert))) as X509Certificate
            return ClientIdentity(privateKey, certificate)
        }

        /** Generates an RSA-2048 key and a self-signed certificate valid for 20 years. */
        fun generate(commonName: String = "kalimote"): ClientIdentity {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val now = System.currentTimeMillis()
            val notBefore = Date(now - 24L * 3600 * 1000)
            val notAfter = Date(now + 20L * 365 * 24 * 3600 * 1000)
            val serial = BigInteger(63, SecureRandom()).add(BigInteger.ONE)
            val sha256WithRsa = Der.seq(Der.oid("1.2.840.113549.1.1.11"), Der.NULL)
            val name = Der.seq(Der.set(Der.seq(Der.oid("2.5.4.3"), Der.utf8(commonName))))

            val tbs = Der.seq(
                Der.explicit(0, Der.int(BigInteger.valueOf(2))),
                Der.int(serial),
                sha256WithRsa,
                name,
                Der.seq(Der.time(notBefore), Der.time(notAfter)),
                name,
                keyPair.public.encoded, // SubjectPublicKeyInfo, already DER
            )
            val signature = Signature.getInstance("SHA256withRSA").run {
                initSign(keyPair.private)
                update(tbs)
                sign()
            }
            val der = Der.seq(tbs, sha256WithRsa, Der.bitString(signature))
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            return ClientIdentity(keyPair.private, certificate)
        }
    }
}

/** TVs use self-signed certificates; trust comes from the pairing code instead. */
internal object TrustAllManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * The pairing secret: SHA-256 over client modulus, client exponent, server
 * modulus, server exponent and the last two bytes of the code. The first byte
 * of the code must match the first byte of the hash.
 */
object PairingSecret {
    fun compute(client: X509Certificate, server: X509Certificate, code: String): ByteArray {
        val normalized = code.trim().uppercase(Locale.ROOT)
        if (!Regex("^[0-9A-F]{6}$").matches(normalized)) {
            throw IllegalArgumentException("The code must be 6 characters (0-9, A-F)")
        }
        val c = client.publicKey as RSAPublicKey
        val s = server.publicKey as RSAPublicKey
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(unsigned(c.modulus))
        digest.update(unsigned(c.publicExponent))
        digest.update(unsigned(s.modulus))
        digest.update(unsigned(s.publicExponent))
        digest.update(hex(normalized.substring(2)))
        val hash = digest.digest()
        if ((hash[0].toInt() and 0xff) != normalized.substring(0, 2).toInt(16)) {
            throw IllegalArgumentException("That code is not correct")
        }
        return hash
    }

    private fun unsigned(n: BigInteger): ByteArray {
        val bytes = n.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** Just enough DER to build a self-signed X.509 v3 certificate. */
internal object Der {
    val NULL = byteArrayOf(0x05, 0x00)

    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = content.size
        when {
            len < 0x80 -> out.write(len)
            len <= 0xff -> { out.write(0x81); out.write(len) }
            len <= 0xffff -> { out.write(0x82); out.write(len shr 8); out.write(len and 0xff) }
            else -> { out.write(0x83); out.write(len shr 16); out.write((len shr 8) and 0xff); out.write(len and 0xff) }
        }
        out.write(content)
        return out.toByteArray()
    }

    fun seq(vararg items: ByteArray) = tlv(0x30, concat(items))
    fun set(vararg items: ByteArray) = tlv(0x31, concat(items))
    fun int(value: BigInteger) = tlv(0x02, value.toByteArray())
    fun utf8(value: String) = tlv(0x0c, value.toByteArray(Charsets.UTF_8))
    fun bitString(value: ByteArray) = tlv(0x03, byteArrayOf(0) + value)
    fun explicit(n: Int, value: ByteArray) = tlv(0xa0 or n, value)

    fun time(date: Date): ByteArray {
        val utc = TimeZone.getTimeZone("UTC")
        val year = java.util.Calendar.getInstance(utc).apply { time = date }.get(java.util.Calendar.YEAR)
        return if (year < 2050) {
            tlv(0x17, fmt("yyMMddHHmmss'Z'", utc).format(date).toByteArray(Charsets.US_ASCII))
        } else {
            tlv(0x18, fmt("yyyyMMddHHmmss'Z'", utc).format(date).toByteArray(Charsets.US_ASCII))
        }
    }

    fun oid(dotted: String): ByteArray {
        val parts = dotted.split(".").map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (p in parts.drop(2)) {
            val stack = ArrayList<Int>()
            var v = p
            stack.add((v and 0x7f).toInt())
            v = v shr 7
            while (v > 0) {
                stack.add(((v and 0x7f) or 0x80).toInt())
                v = v shr 7
            }
            for (b in stack.reversed()) out.write(b)
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun fmt(pattern: String, tz: TimeZone) =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = tz }

    private fun concat(items: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        items.forEach { out.write(it) }
        return out.toByteArray()
    }
}
