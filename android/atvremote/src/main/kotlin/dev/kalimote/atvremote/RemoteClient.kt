package dev.kalimote.atvremote

import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

data class Volume(val level: Int, val max: Int, val muted: Boolean)

/**
 * A connection to a TV, whatever the protocol: [RemoteClient] (Google TV /
 * Android TV remote service) or [FireTvClient] (ADB, for Fire TV).
 * Reconnects by itself; [state] changes are reported to the listener.
 */
interface TvClient {
    val state: RemoteState
    fun start()
    fun stop()
    fun shutdown()
    fun reconnectNow()
    fun sendKey(keyCode: Int, direction: Direction = Direction.SHORT)
    /** Types [text] at the cursor of the TV's focused text box (macros, shared links). */
    fun sendText(text: String)
    fun launchApp(url: String)

    /**
     * Live typing: makes the TV's focused text box contain [text]. Call it on
     * every edit; rapid calls are coalesced where the transport is slow.
     */
    fun setText(text: String)

    /** Forgets what [setText] typed so far, before typing into a new text box. */
    fun resetText() {}
}

/**
 * A text box on the TV waiting for input (its on-screen keyboard is up).
 * [id] changes each time the TV asks for text, [value] is what it contains
 * as far as we know, and [label] is its hint ("Search YouTube"), if reported.
 */
data class TextField(val id: Int, val value: String = "", val label: String = "")

data class RemoteState(
    val status: Status = Status.DISCONNECTED,
    val powered: Boolean? = null,
    val currentApp: String? = null,
    val volume: Volume? = null,
    val error: String? = null,
    val textField: TextField? = null,
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
) : TvClient {
    @Volatile
    override var state = RemoteState()
        private set

    @Volatile
    private var running = false

    /** Bumped by stop(), so a loop thread from before a restart exits quietly. */
    @Volatile
    private var generation = 0

    @Volatile
    private var socket: SSLSocket? = null
    private var thread: Thread? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "atvremote-writer").apply { isDaemon = true } }
    private val lock = Object()
    private val stateLock = Object()
    @Volatile
    private var imeCounter = 0
    @Volatile
    private var fieldCounter = 0
    private var fieldSeq = 0

    // The TV echoes each edit back. While our latest edit is in flight, echoes
    // of earlier ones are stale and must not overwrite what we typed.
    @Volatile
    private var lastSent: String? = null
    @Volatile
    private var lastSentAt = 0L

    /** Features agreed with the TV: ours that it also supports. */
    @Volatile
    private var features = Remote.FEATURES

    // Key-press typing (no text box reported by the TV): what it holds as far as we know.
    private val textLock = Object()
    private var typedKeys = ""

    @Synchronized
    override fun start() {
        if (running) return
        running = true
        val gen = generation
        thread = Thread({ loop(gen) }, "atvremote-$host").apply {
            isDaemon = true
            start()
        }
    }

    /** Forces an immediate reconnect attempt (e.g. after the app returns to the foreground). */
    override fun reconnectNow() {
        if (!running) {
            start()
            return
        }
        synchronized(lock) { lock.notifyAll() }
    }

    @Synchronized
    override fun stop() {
        running = false
        generation++
        Sockets.closeInBackground(socket)
        synchronized(lock) { lock.notifyAll() }
        thread = null
        update { it.copy(status = RemoteState.Status.DISCONNECTED) }
    }

    override fun shutdown() {
        stop()
        writer.shutdown()
    }

    override fun sendKey(keyCode: Int, direction: Direction) = send(Remote.key(keyCode, direction))

    override fun sendText(text: String) {
        if (text.isEmpty()) return
        val field = state.textField
        if (field != null) {
            setText(field.value + text)
        } else {
            KeyTyping.edit("", KeyTyping.typeable(text)).forEach { sendKey(it) }
        }
    }

    /**
     * When the TV has told us about its text box, edits go through the input
     * method (the batch edit replaces the box's whole content). Many TVs never
     * do (e.g. Android TV builds whose remote service doesn't support it), and
     * then the text is typed as key presses, which every TV accepts.
     */
    override fun setText(text: String) {
        if (state.textField != null && features and Remote.FEATURE_IME != 0) {
            lastSent = text
            lastSentAt = System.currentTimeMillis()
            send(Remote.imeBatchEdit(imeCounter, fieldCounter, text))
            update { s -> s.textField?.let { s.copy(textField = it.copy(value = text)) } ?: s }
            return
        }
        val target = KeyTyping.typeable(text)
        val keys = synchronized(textLock) {
            KeyTyping.edit(typedKeys, target).also { typedKeys = target }
        }
        keys.forEach { sendKey(it) }
    }

    override fun resetText() = synchronized(textLock) {
        typedKeys = ""
    }

    override fun launchApp(url: String) = send(Remote.appLink(url))

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

    private fun update(change: (RemoteState) -> RemoteState) = synchronized(stateLock) {
        val next = change(state)
        if (next != state) {
            state = next
            listener(next)
        }
    }

    private fun loop(gen: Int) {
        fun active() = running && generation == gen
        var backoff = 1000L
        while (active()) {
            update { it.copy(status = RemoteState.Status.CONNECTING, textField = null) }
            var gotMessage = false
            var handshaken = false
            var current: SSLSocket? = null
            try {
                val s = identity.connect(host, port, 10_000)
                current = s
                handshaken = true
                if (!active()) break
                socket = s
                s.soTimeout = 20_000 // the TV pings every few seconds
                while (active()) {
                    val msg = ProtoMessage(Framing.read(s.inputStream))
                    gotMessage = true
                    if (handle(msg)) backoff = 1000L
                }
            } catch (e: Exception) {
                if (!active()) break
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
            if (!active()) break
            synchronized(lock) { lock.wait(backoff) }
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
        if (generation == gen && state.status != RemoteState.Status.UNPAIRED) {
            update { it.copy(status = RemoteState.Status.DISCONNECTED) }
        }
    }

    /** Returns true when the connection just became active. */
    private fun handle(msg: ProtoMessage): Boolean {
        when {
            msg.has(Remote.CONFIGURE) -> {
                // Agree on the features both sides support, as the official remote does.
                val tv = msg.message(Remote.CONFIGURE)?.int(1) ?: 0
                features = if (tv > 0) Remote.FEATURES and tv else Remote.FEATURES
                send(Remote.configure(deviceInfo, features))
            }
            msg.has(Remote.SET_ACTIVE) -> {
                send(Remote.setActive(features))
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
                // A different app has no text box waiting for us any more.
                if (!app.isNullOrEmpty()) {
                    update { if (it.currentApp == app) it else it.copy(currentApp = app, textField = null) }
                }
            }
            msg.has(Remote.IME_BATCH_EDIT) -> {
                val m = msg.message(Remote.IME_BATCH_EDIT) ?: return false
                imeCounter = m.int(1)
                fieldCounter = m.int(2)
                val value = m.message(3)?.message(2)?.takeIf { it.has(3) }?.string(3)
                val pending = lastSent
                val stale = pending != null && value != pending && System.currentTimeMillis() - lastSentAt < 1500
                val echo = pending != null && value == pending
                if (echo) lastSent = null
                if (value != null && !stale) {
                    // Not an echo of ours: the TV is telling us about its text box.
                    update { s ->
                        val field = s.textField ?: if (echo) null else TextField(++fieldSeq, value)
                        s.copy(textField = field?.copy(value = value))
                    }
                }
            }
            msg.has(Remote.IME_SHOW_REQUEST) -> {
                // The TV focused a text box and wants text: counter, value, hint.
                val f = msg.message(Remote.IME_SHOW_REQUEST)?.message(2) ?: return false
                fieldCounter = f.int(1)
                val field = TextField(++fieldSeq, f.string(2), f.string(6))
                update { it.copy(textField = field) }
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
