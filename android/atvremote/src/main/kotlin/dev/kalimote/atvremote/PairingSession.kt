package dev.kalimote.atvremote

import java.io.Closeable
import java.io.IOException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

class PairingException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * One pairing attempt. Blocking; call from a background thread.
 *
 *     val session = PairingSession(host, identity)
 *     session.start()            // the TV now shows a 6 character code
 *     session.finish("A1B2C3")   // code typed by the user
 */
class PairingSession(
    private val host: String,
    private val identity: ClientIdentity,
    private val clientName: String = "Kalimote",
    private val port: Int = PAIRING_PORT,
    private val timeoutMs: Int = 10_000,
) : Closeable {
    private var socket: SSLSocket? = null
    private var serverCertificate: X509Certificate? = null

    val isActive: Boolean get() = socket?.isClosed == false

    fun start() {
        val s = identity.connect(host, port, timeoutMs)
        socket = s
        try {
            serverCertificate = s.session.peerCertificates.first() as X509Certificate
            exchange(Pairing.request(clientName), Pairing.REQUEST_ACK)
            exchange(Pairing.option(), Pairing.OPTION)
            exchange(Pairing.configuration(), Pairing.CONFIGURATION_ACK)
            s.soTimeout = 5 * 60 * 1000 // give the user time to type the code
        } catch (e: Exception) {
            close()
            throw e as? IOException ?: PairingException(e.message ?: "Pairing failed", e)
        }
    }

    /**
     * Sends the code. Throws [IllegalArgumentException] for a mistyped code
     * (the session stays open so the user can retry) and [PairingException]
     * if the TV rejects it (the session is closed).
     */
    fun finish(code: String) {
        val s = socket ?: throw PairingException("Pairing has not started")
        val secret = PairingSecret.compute(identity.certificate, serverCertificate!!, code)
        try {
            exchange(Pairing.secret(secret), Pairing.SECRET_ACK)
        } finally {
            close()
        }
    }

    private fun exchange(payload: ByteArray, expected: Int): ProtoMessage {
        val s = socket ?: throw PairingException("Not connected")
        Framing.write(s.outputStream, payload)
        val msg = ProtoMessage(Framing.read(s.inputStream))
        val status = msg.int(Pairing.STATUS)
        if (status != Pairing.STATUS_OK) {
            throw PairingException(
                if (status == Pairing.STATUS_BAD_SECRET) "The TV rejected the code" else "The TV returned error $status",
            )
        }
        if (!msg.has(expected)) throw PairingException("Unexpected pairing response from TV")
        return msg
    }

    override fun close() = Sockets.closeInBackground(socket)

    companion object {
        const val PAIRING_PORT = 6467
    }
}
