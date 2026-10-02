package dev.kalimote.atvremote

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADB over the network (adbd on port 5555), used for Amazon Fire TV and
 * other Android devices with "ADB debugging" on. Mirrors server/src/adb.
 */
class AdbException(message: String, val code: Code) : IOException(message) {
    enum class Code { UNAUTHORIZED, TLS, CLOSED, TIMEOUT }
}

object AdbProtocol {
    const val CNXN = 0x4e584e43
    const val AUTH = 0x48545541
    const val OPEN = 0x4e45504f
    const val OKAY = 0x59414b4f
    const val CLSE = 0x45534c43
    const val WRTE = 0x45545257
    const val STLS = 0x534c5453
    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3
    const val VERSION = 0x01000001
    const val MAX_DATA = 256 * 1024
    const val PORT = 5555

    class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    fun encode(command: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)): ByteArray {
        var sum = 0L
        for (b in data) sum += (b.toInt() and 0xff)
        return ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
            .putInt(sum.toInt()).putInt(command.inv())
            .put(data).array()
    }

    fun read(input: DataInputStream): Message {
        val header = ByteArray(24)
        input.readFully(header)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = b.int
        val arg0 = b.int
        val arg1 = b.int
        val len = b.int
        b.int // checksum (not verified, like modern adb)
        if (b.int != command.inv()) throw IOException("Corrupt ADB message")
        if (len < 0 || len > MAX_DATA * 4) throw IOException("ADB message too large")
        val data = ByteArray(len)
        input.readFully(data)
        return Message(command, arg0, arg1, data)
    }

    // DER prefix of a SHA-1 DigestInfo; adbd treats the token as a SHA-1 digest.
    private val SHA1_DIGEST_INFO = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
    )

    /** PKCS#1 v1.5 signature of the token, computed directly to avoid provider quirks. */
    fun signToken(identity: ClientIdentity, token: ByteArray): ByteArray {
        val priv = identity.privateKey as RSAPrivateKey
        val n = priv.modulus
        val k = (n.bitLength() + 7) / 8
        val t = SHA1_DIGEST_INFO + token
        val em = ByteArray(k)
        em[0] = 0
        em[1] = 1
        for (i in 2 until k - t.size - 1) em[i] = 0xff.toByte()
        em[k - t.size - 1] = 0
        t.copyInto(em, k - t.size)
        val sig = BigInteger(1, em).modPow(priv.privateExponent, n).toByteArray()
        // Strip sign byte / left-pad to k bytes.
        return when {
            sig.size == k -> sig
            sig.size > k -> sig.copyOfRange(sig.size - k, sig.size)
            else -> ByteArray(k - sig.size) + sig
        }
    }

    /** The public key in adbd's format (what "Always allow" stores on the TV). */
    fun publicKey(identity: ClientIdentity, name: String = "kalimote@android"): String {
        val pub = identity.certificate.publicKey as RSAPublicKey
        val n = pub.modulus
        val words = (n.bitLength() + 31) / 32
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32))
        val rr = BigInteger.ONE.shiftLeft(64 * words).mod(n)
        val buf = ByteBuffer.allocate(4 + 4 + words * 8 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(words).putInt(n0inv.toInt())
        for (value in listOf(n, rr)) {
            var v = value
            repeat(words) {
                buf.putInt(v.mod(r32).toLong().toInt())
                v = v.shiftRight(32)
            }
        }
        buf.putInt(pub.publicExponent.toInt())
        return Base64.getEncoder().encodeToString(buf.array()) + " " + name
    }
}

/** One ADB connection; [shell] may be called from several threads. */
class AdbConnection(
    private val host: String,
    private val port: Int = AdbProtocol.PORT,
    private val identity: ClientIdentity,
    private val name: String = "kalimote@android",
) : Closeable {
    private val socket = Socket()
    private lateinit var input: DataInputStream
    private val writeLock = Any()
    private val nextId = AtomicInteger(1)
    private val streams = ConcurrentHashMap<Int, Stream>()

    @Volatile
    var isOpen = false
        private set
    var banner: String = ""
        private set
    var onClose: (() -> Unit)? = null

    /** One stream. Writes wait for the device's OKAY (flow control). */
    private class Stream {
        val out = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        val opened = CountDownLatch(1)
        val okays = java.util.concurrent.Semaphore(0)
        val dataLock = Object()
        @Volatile var remoteId = 0
        @Volatile var error: IOException? = null
        @Volatile var closed = false
    }

    private fun write(bytes: ByteArray) = synchronized(writeLock) {
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    /**
     * Connects and authenticates. With [approvalTimeoutMs] > 0 an untrusted key
     * is offered and we wait for the user to accept the TV's prompt
     * ([onAwaitingApproval] is called when the prompt should be showing).
     */
    fun connect(timeoutMs: Int = 8000, approvalTimeoutMs: Int = 0, onAwaitingApproval: () -> Unit = {}): String {
        try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.tcpNoDelay = true
            input = DataInputStream(socket.getInputStream().buffered())
            write(AdbProtocol.encode(AdbProtocol.CNXN, AdbProtocol.VERSION, AdbProtocol.MAX_DATA, "host::\u0000".toByteArray()))
            var triedSignature = false
            var sentKey = false
            while (true) {
                val m = try {
                    AdbProtocol.read(input)
                } catch (e: EOFException) {
                    throw if (sentKey) AdbException("The TV did not allow the connection", AdbException.Code.UNAUTHORIZED)
                    else AdbException("The device closed the connection", AdbException.Code.CLOSED)
                } catch (e: SocketTimeoutException) {
                    throw if (sentKey) AdbException("The prompt on the TV was not accepted in time", AdbException.Code.UNAUTHORIZED)
                    else AdbException("Timed out connecting to the device", AdbException.Code.TIMEOUT)
                }
                when (m.command) {
                    AdbProtocol.AUTH -> if (m.arg0 == AdbProtocol.AUTH_TOKEN) {
                        if (!triedSignature) {
                            triedSignature = true
                            write(AdbProtocol.encode(AdbProtocol.AUTH, AdbProtocol.AUTH_SIGNATURE, 0, AdbProtocol.signToken(identity, m.data)))
                        } else if (!sentKey) {
                            if (approvalTimeoutMs <= 0) {
                                throw AdbException("The device has not authorised this remote", AdbException.Code.UNAUTHORIZED)
                            }
                            sentKey = true
                            write(
                                AdbProtocol.encode(
                                    AdbProtocol.AUTH, AdbProtocol.AUTH_RSAPUBLICKEY, 0,
                                    (AdbProtocol.publicKey(identity, name) + "\u0000").toByteArray(),
                                ),
                            )
                            socket.soTimeout = approvalTimeoutMs
                            onAwaitingApproval()
                        }
                    }
                    AdbProtocol.CNXN -> {
                        banner = m.data.toString(Charsets.UTF_8).trimEnd('\u0000')
                        break
                    }
                    AdbProtocol.STLS -> throw AdbException(
                        "The device requires TLS (Wireless debugging); use classic ADB debugging on port 5555",
                        AdbException.Code.TLS,
                    )
                }
            }
        } catch (e: AdbException) {
            close()
            throw e
        } catch (e: IOException) {
            close()
            throw AdbException("Cannot reach $host:$port (${e.message})", AdbException.Code.CLOSED)
        }
        socket.soTimeout = 0
        isOpen = true
        Thread(::readLoop, "adb-$host").apply { isDaemon = true }.start()
        return banner
    }

    private fun readLoop() {
        try {
            while (isOpen) {
                val m = AdbProtocol.read(input)
                val stream = streams[m.arg1]
                if (stream == null) {
                    if (m.command == AdbProtocol.WRTE || m.command == AdbProtocol.OKAY) {
                        write(AdbProtocol.encode(AdbProtocol.CLSE, 0, m.arg0))
                    }
                    continue
                }
                when (m.command) {
                    AdbProtocol.OKAY -> if (stream.remoteId == 0) {
                        stream.remoteId = m.arg0
                        stream.opened.countDown()
                    } else {
                        stream.okays.release()
                    }
                    AdbProtocol.WRTE -> {
                        write(AdbProtocol.encode(AdbProtocol.OKAY, m.arg1, m.arg0))
                        synchronized(stream.dataLock) {
                            stream.out.write(m.data)
                            stream.dataLock.notifyAll()
                        }
                    }
                    AdbProtocol.CLSE -> {
                        streams.remove(m.arg1)
                        if (stream.remoteId != 0) write(AdbProtocol.encode(AdbProtocol.CLSE, m.arg1, m.arg0))
                        finish(stream, null)
                    }
                }
            }
        } catch (_: IOException) {
        } finally {
            val wasOpen = isOpen
            close()
            if (wasOpen) onClose?.invoke()
        }
    }

    private fun finish(stream: Stream, error: IOException?) {
        synchronized(stream) {
            if (stream.closed) return
            if (error != null) stream.error = error
            stream.closed = true
        }
        stream.opened.countDown()
        stream.okays.release(1_000_000) // wake any writer
        synchronized(stream.dataLock) { stream.dataLock.notifyAll() }
        stream.done.countDown()
    }

    private fun open(service: String, timeoutMs: Long): Pair<Int, Stream> {
        if (!isOpen) throw AdbException("Not connected", AdbException.Code.CLOSED)
        val id = nextId.getAndIncrement()
        val stream = Stream()
        streams[id] = stream
        try {
            write(AdbProtocol.encode(AdbProtocol.OPEN, id, 0, "$service\u0000".toByteArray()))
        } catch (e: IOException) {
            streams.remove(id)
            throw AdbException("Connection closed", AdbException.Code.CLOSED)
        }
        if (!stream.opened.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            streams.remove(id)
            throw AdbException("Timed out opening $service", AdbException.Code.TIMEOUT)
        }
        stream.error?.let { throw it }
        if (stream.remoteId == 0) throw AdbException("Device refused ${service.substringBefore(':')}", AdbException.Code.CLOSED)
        return id to stream
    }

    /** Runs a command and returns its raw output (exec: keeps binary output intact). Blocking. */
    fun exec(command: String, timeoutMs: Long = 10_000, service: String = "exec"): ByteArray {
        val (id, stream) = open("$service:$command", timeoutMs)
        if (!stream.done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            streams.remove(id)
            throw AdbException("Command timed out: $command", AdbException.Code.TIMEOUT)
        }
        stream.error?.let { throw it }
        return synchronized(stream.dataLock) { stream.out.toByteArray() }
    }

    /** Runs a shell command and returns its output. Blocking. */
    fun shell(command: String, timeoutMs: Long = 10_000): String =
        exec(command, timeoutMs, "shell").toString(Charsets.UTF_8)

    private fun writeStream(id: Int, stream: Stream, data: ByteArray, timeoutMs: Long) {
        if (stream.closed) throw stream.error ?: AdbException("Stream closed", AdbException.Code.CLOSED)
        write(AdbProtocol.encode(AdbProtocol.WRTE, id, stream.remoteId, data))
        if (!stream.okays.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw AdbException("Timed out writing to device", AdbException.Code.TIMEOUT)
        }
        if (stream.closed && stream.error != null) throw stream.error!!
    }

    private fun readExactly(stream: Stream, n: Int, timeoutMs: Long): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(stream.dataLock) {
            while (stream.out.size() < n) {
                stream.error?.let { throw it }
                if (stream.closed) throw AdbException("Stream closed", AdbException.Code.CLOSED)
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw AdbException("Timed out reading from device", AdbException.Code.TIMEOUT)
                stream.dataLock.wait(left)
            }
            val all = stream.out.toByteArray()
            stream.out.reset()
            stream.out.write(all, n, all.size - n)
            return all.copyOfRange(0, n)
        }
    }

    /**
     * Uploads [input] to [remotePath] with the ADB sync protocol. Blocking.
     * [onProgress] receives the number of bytes sent so far.
     */
    fun push(input: java.io.InputStream, remotePath: String, mode: Int = 420, onProgress: (Long) -> Unit = {}, timeoutMs: Long = 30_000) {
        val (id, stream) = open("sync:", timeoutMs)
        fun req(tag: String, length: Int, payload: ByteArray = ByteArray(0), payloadLen: Int = payload.size): ByteArray {
            val b = ByteBuffer.allocate(8 + payloadLen).order(ByteOrder.LITTLE_ENDIAN)
            b.put(tag.toByteArray(Charsets.US_ASCII)).putInt(length).put(payload, 0, payloadLen)
            return b.array()
        }
        try {
            val spec = "$remotePath,$mode".toByteArray()
            writeStream(id, stream, req("SEND", spec.size, spec), timeoutMs)
            val chunk = ByteArray(64 * 1024)
            var sent = 0L
            while (true) {
                var n = 0
                while (n < chunk.size) {
                    val r = input.read(chunk, n, chunk.size - n)
                    if (r < 0) break
                    n += r
                }
                if (n == 0) break
                writeStream(id, stream, req("DATA", n, chunk, n), timeoutMs)
                sent += n
                onProgress(sent)
                if (n < chunk.size) break
            }
            writeStream(id, stream, req("DONE", (System.currentTimeMillis() / 1000).toInt()), timeoutMs)
            val reply = readExactly(stream, 8, timeoutMs)
            val tag = String(reply, 0, 4, Charsets.US_ASCII)
            if (tag == "FAIL") {
                val len = ByteBuffer.wrap(reply, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                throw AdbException("Upload failed: " + readExactly(stream, len, timeoutMs).toString(Charsets.UTF_8), AdbException.Code.CLOSED)
            }
            if (tag != "OKAY") throw AdbException("Unexpected sync reply $tag", AdbException.Code.CLOSED)
            runCatching { writeStream(id, stream, req("QUIT", 0), 5000) }
        } finally {
            if (!stream.closed) {
                streams.remove(id)
                runCatching { write(AdbProtocol.encode(AdbProtocol.CLSE, id, stream.remoteId)) }
            }
        }
    }

    override fun close() {
        isOpen = false
        runCatching { socket.close() }
        for (s in streams.values) finish(s, AdbException("Connection closed", AdbException.Code.CLOSED))
        streams.clear()
    }
}

/** Shell commands and output parsing for Fire TV; same as server/src/adb/firetv.js. */
object FireTvCommands {
    const val STATUS =
        "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' ; dumpsys power | grep -E 'mWakefulness=|Display Power: state='"

    fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun inputText(text: String) = "input text " + shQuote(text.replace("%", "\\%").replace(" ", "%s"))

    fun launch(url: String): String {
        val pkg = Regex("^market://launch\\?id=([\\w.]+)").find(url)?.groupValues?.get(1)
        return if (pkg != null) {
            "monkey -p $pkg -c android.intent.category.LAUNCHER 1 || monkey -p $pkg 1"
        } else {
            "am start -a android.intent.action.VIEW -d ${shQuote(url)}"
        }
    }

    fun currentApp(out: String): String? =
        Regex("mCurrentFocus=Window\\{\\S+ \\S+ ([\\w.]+)/").find(out)?.groupValues?.get(1)
            ?: Regex("mFocusedApp=.*? ([\\w.]+)/[\\w.$]+").find(out)?.groupValues?.get(1)

    fun powered(out: String): Boolean? {
        Regex("mWakefulness=(\\w+)").find(out)?.let { return it.groupValues[1] == "Awake" }
        Regex("Display Power: state=(\\w+)").find(out)?.let { return it.groupValues[1] == "ON" }
        return null
    }

    const val APPS =
        "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER ; " +
            "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
    private val HIDDEN = Regex("^(com\\.amazon\\.tv\\.launcher|com\\.google\\.android\\.tvlauncher|com\\.android\\.tv\\.settings)$")

    fun packages(out: String): List<String> {
        val pkgs = LinkedHashSet<String>()
        Regex("(?:^|\\s)([a-zA-Z]\\w*(?:\\.\\w+)+)/[\\w.$]+", RegexOption.MULTILINE).findAll(out).forEach { pkgs += it.groupValues[1] }
        Regex("^package:([\\w.]+)$", RegexOption.MULTILINE).findAll(out).forEach { pkgs += it.groupValues[1] }
        return pkgs.toList()
    }

    fun visible(pkg: String) = !HIDDEN.matches(pkg)

    fun key(code: Int, direction: Direction): String? = when (direction) {
        Direction.END_LONG -> null // `--longpress` already includes the release
        Direction.START_LONG -> "input keyevent --longpress $code"
        Direction.SHORT -> "input keyevent $code"
    }
}

/**
 * Fire TV (or any Android device with network ADB) behind [TvClient].
 * Keeps an ADB connection, polls the foreground app and power state every
 * few seconds, and runs commands in order on a background thread.
 */
class FireTvClient(
    private val host: String,
    private val identity: ClientIdentity,
    private val port: Int = AdbProtocol.PORT,
    private val listener: (RemoteState) -> Unit,
) : TvClient {
    @Volatile
    override var state = RemoteState()
        private set

    @Volatile
    private var running = false
    @Volatile
    private var conn: AdbConnection? = null
    private val lock = Object()
    private val commands = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "firetv-commands").apply { isDaemon = true }
    }

    private fun update(change: (RemoteState) -> RemoteState) {
        val next = change(state)
        if (next != state) {
            state = next
            listener(next)
        }
    }

    @Synchronized
    override fun start() {
        if (running) return
        running = true
        Thread(::loop, "firetv-$host").apply { isDaemon = true }.start()
    }

    override fun reconnectNow() {
        if (!running) start() else synchronized(lock) { lock.notifyAll() }
    }

    @Synchronized
    override fun stop() {
        running = false
        conn?.close()
        synchronized(lock) { lock.notifyAll() }
        update { it.copy(status = RemoteState.Status.DISCONNECTED) }
    }

    override fun shutdown() {
        stop()
        commands.shutdown()
    }

    private fun poll(c: AdbConnection) {
        val out = c.shell(FireTvCommands.STATUS, 5000)
        update { it.copy(currentApp = FireTvCommands.currentApp(out) ?: it.currentApp, powered = FireTvCommands.powered(out)) }
    }

    private fun loop() {
        var backoff = 1000L
        while (running) {
            update { it.copy(status = RemoteState.Status.CONNECTING) }
            val c = AdbConnection(host, port, identity)
            try {
                c.connect()
                conn = c
                update { it.copy(status = RemoteState.Status.CONNECTED, error = null) }
                backoff = 1000L
                while (running && c.isOpen) {
                    runCatching { poll(c) }
                    synchronized(lock) { lock.wait(3000) }
                }
            } catch (e: AdbException) {
                if (!running) break
                if (e.code == AdbException.Code.UNAUTHORIZED) {
                    running = false
                    update {
                        RemoteState(status = RemoteState.Status.UNPAIRED, error = "The Fire TV does not allow this remote. Pair again.")
                    }
                    break
                }
                update { it.copy(status = RemoteState.Status.DISCONNECTED, error = e.message) }
            } finally {
                c.close()
                if (conn === c) conn = null
            }
            if (!running) break
            update { it.copy(status = RemoteState.Status.DISCONNECTED) }
            synchronized(lock) { lock.wait(backoff) }
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
        if (state.status != RemoteState.Status.UNPAIRED) update { it.copy(status = RemoteState.Status.DISCONNECTED) }
    }

    private fun run(command: String) {
        if (commands.isShutdown) return
        commands.execute {
            val c = conn ?: return@execute
            runCatching { c.shell(command) }
            // Pick up app / power changes quickly.
            synchronized(lock) { lock.notifyAll() }
        }
    }

    override fun sendKey(keyCode: Int, direction: Direction) {
        FireTvCommands.key(keyCode, direction)?.let(::run)
    }

    override fun sendText(text: String) {
        if (text.isNotEmpty()) run(FireTvCommands.inputText(text))
    }

    override fun launchApp(url: String) = run(FireTvCommands.launch(url))

    private fun live(): AdbConnection = conn?.takeIf { it.isOpen && state.connected }
        ?: throw AdbException("Not connected to the Fire TV", AdbException.Code.CLOSED)

    /** Installed launchable apps, sorted by name. Blocking. */
    fun listApps(): List<TvApp> {
        val c = live()
        var pkgs = FireTvCommands.packages(c.shell(FireTvCommands.APPS, 15_000))
        if (pkgs.isEmpty()) pkgs = FireTvCommands.packages(c.shell("pm list packages -3", 15_000))
        return pkgs.filter(FireTvCommands::visible).map { TvApp(Apps.name(it), it) }.sortedBy { it.name.lowercase() }
    }

    /** PNG screenshot of the screen. Blocking. */
    fun screenshot(): ByteArray {
        val png = live().exec("screencap -p", 15_000)
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        if (png.size < 8 || !png.copyOfRange(0, 8).contentEquals(sig)) {
            throw AdbException(png.toString(Charsets.UTF_8).trim().take(200).ifEmpty { "The TV did not return a screenshot" }, AdbException.Code.CLOSED)
        }
        return png
    }

    /**
     * Uploads and installs an APK. Blocking. [onProgress] gets bytes uploaded
     * (and -1 once the TV is installing). Returns pm's output.
     */
    fun installApk(apk: java.io.InputStream, onProgress: (Long) -> Unit = {}): String {
        val c = live()
        val input = java.io.BufferedInputStream(apk)
        input.mark(4)
        val magic = ByteArray(2)
        if (input.read(magic) != 2 || magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte()) {
            throw IllegalArgumentException("That is not an APK file")
        }
        input.reset()
        val remote = "/data/local/tmp/kalimote-${System.currentTimeMillis()}.apk"
        try {
            c.push(input, remote, onProgress = onProgress)
            onProgress(-1)
            val out = c.shell("pm install -r $remote", 180_000).trim()
            if (!out.contains("Success")) throw AdbException(out.lines().lastOrNull() ?: "Install failed", AdbException.Code.CLOSED)
            return out
        } finally {
            runCatching { c.shell("rm -f $remote") }
        }
    }

    companion object {
        /**
         * Pairs with a Fire TV: offers our key so the TV shows "Allow USB
         * debugging?" and waits for the user to accept. Blocking. Returns the
         * device banner. [connection] receives the in-flight connection so the
         * caller can cancel by closing it.
         */
        fun pair(
            host: String,
            identity: ClientIdentity,
            port: Int = AdbProtocol.PORT,
            approvalTimeoutMs: Int = 90_000,
            connection: (AdbConnection) -> Unit = {},
            onAwaitingApproval: () -> Unit = {},
        ): String {
            val c = AdbConnection(host, port, identity)
            connection(c)
            try {
                return c.connect(approvalTimeoutMs = approvalTimeoutMs, onAwaitingApproval = onAwaitingApproval)
            } finally {
                c.close()
            }
        }
    }
}
