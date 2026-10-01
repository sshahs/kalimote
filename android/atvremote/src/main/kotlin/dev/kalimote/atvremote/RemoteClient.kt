package dev.kalimote.atvremote

import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

data class Volume(val level: Int, val max: Int, val muted: Boolean)

data class RemoteState(
    val status: Status = Status.DISCONNECTED,
    val powered: Boolean? = null,
    val currentApp: String? = null,
    val volume: Volume? = null,
    val error: String? = null,
) {
    enum class Status { DISCONNECTED, CONNECTING, CONNECTED, UNPAIRED }

    val connected: Boolean get() = status == Status.CONNECTED
}

/**
 * Keeps a control connection to a paired TV open, reconnecting with backoff,
 * and reports state changes to [listener] (called on a background thread).
 * Send methods never block; they are queued on a writer thread.
 */
class RemoteClient(
    private val host: String,
    private val identity: ClientIdentity,
    private val deviceInfo: DeviceInfo = DeviceInfo(),
    private val port: Int = REMOTE_PORT,
    private val listener: (RemoteState) -> Unit,
) {
    @Volatile
    var state = RemoteState()
        private set

    @Volatile
    private var running = false

    @Volatile
    private var socket: SSLSocket? = null
    private var thread: Thread? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "atvremote-writer").apply { isDaemon = true } }
    private val lock = Object()
    private var imeCounter = 0
    private var fieldCounter = 0

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "atvremote-$host").apply {
            isDaemon = true
            start()
        }
    }

    /** Forces an immediate reconnect attempt (e.g. after the app returns to the foreground). */
    fun reconnectNow() {
        if (!running) {
            start()
            return
        }
        synchronized(lock) { lock.notifyAll() }
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        synchronized(lock) { lock.notifyAll() }
        thread = null
        update { it.copy(status = RemoteState.Status.DISCONNECTED) }
    }

    fun shutdown() {
        stop()
        writer.shutdown()
    }

    fun sendKey(keyCode: Int, direction: Direction = Direction.SHORT) = send(Remote.key(keyCode, direction))

    fun sendText(text: String) {
        if (text.isEmpty()) return
        send(Remote.imeBatchEdit(imeCounter, fieldCounter, text))
    }

    fun launchApp(url: String) = send(Remote.appLink(url))

    private fun send(payload: ByteArray) {
        if (writer.isShutdown) return
        writer.execute {
            val s = socket ?: return@execute
            try {
                Framing.write(s.outputStream, payload)
            } catch (_: IOException) {
                try {
                    s.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun update(change: (RemoteState) -> RemoteState) {
        val next = change(state)
        if (next != state) {
            state = next
            listener(next)
        }
    }

    private fun loop() {
        var backoff = 1000L
        while (running) {
            update { it.copy(status = RemoteState.Status.CONNECTING) }
            var gotMessage = false
            var handshaken = false
            var current: SSLSocket? = null
            try {
                val s = identity.connect(host, port, 10_000)
                current = s
                handshaken = true
                socket = s
                if (!running) break
                s.soTimeout = 20_000 // the TV pings every few seconds
                while (running) {
                    val msg = ProtoMessage(Framing.read(s.inputStream))
                    gotMessage = true
                    if (handle(msg)) backoff = 1000L
                }
            } catch (e: Exception) {
                if (!running) break
                // The TV hangs up right after the handshake (or fails it with a
                // certificate alert) when it does not recognise our certificate.
                val unpaired = !gotMessage && (handshaken || e is SSLException) &&
                    e !is SocketTimeoutException
                if (unpaired) {
                    running = false
                    update {
                        RemoteState(
                            status = RemoteState.Status.UNPAIRED,
                            error = "The TV does not recognise this remote. Pair again.",
                        )
                    }
                    break
                }
                update {
                    it.copy(
                        status = RemoteState.Status.DISCONNECTED,
                        error = describe(e),
                    )
                }
            } finally {
                try {
                    current?.close()
                } catch (_: IOException) {
                }
                if (socket === current) socket = null
            }
            if (!running) break
            synchronized(lock) { lock.wait(backoff) }
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
        if (state.status != RemoteState.Status.UNPAIRED) {
            update { it.copy(status = RemoteState.Status.DISCONNECTED) }
        }
    }

    /** Returns true when the connection just became active. */
    private fun handle(msg: ProtoMessage): Boolean {
        when {
            msg.has(Remote.CONFIGURE) -> send(Remote.configure(deviceInfo))
            msg.has(Remote.SET_ACTIVE) -> {
                send(Remote.setActive())
                if (!state.connected) {
                    update { it.copy(status = RemoteState.Status.CONNECTED, error = null) }
                    return true
                }
            }
            msg.has(Remote.PING_REQUEST) -> send(Remote.pingResponse(msg.message(Remote.PING_REQUEST)?.long(1) ?: 0))
            msg.has(Remote.START) -> {
                val started = msg.message(Remote.START)?.bool(1) ?: false
                update { it.copy(powered = started) }
            }
            msg.has(Remote.SET_VOLUME_LEVEL) -> {
                val v = msg.message(Remote.SET_VOLUME_LEVEL) ?: return false
                update { it.copy(volume = Volume(v.int(7), v.int(6), v.bool(8))) }
            }
            msg.has(Remote.IME_KEY_INJECT) -> {
                val app = msg.message(Remote.IME_KEY_INJECT)?.message(1)?.string(12)
                if (!app.isNullOrEmpty()) update { it.copy(currentApp = app) }
            }
            msg.has(Remote.IME_BATCH_EDIT) -> {
                val m = msg.message(Remote.IME_BATCH_EDIT) ?: return false
                imeCounter = m.int(1)
                fieldCounter = m.int(2)
            }
            msg.has(Remote.IME_SHOW_REQUEST) -> {
                msg.message(Remote.IME_SHOW_REQUEST)?.message(2)?.let { fieldCounter = it.int(1) }
            }
        }
        return false
    }

    private fun describe(e: Exception): String = when (e) {
        is SocketTimeoutException -> "TV did not respond"
        is EOFException -> "TV closed the connection"
        is java.net.ConnectException -> "Cannot reach the TV (is it on and on the same network?)"
        is java.net.UnknownHostException -> "Unknown host $host"
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        const val REMOTE_PORT = 6466
    }
}
