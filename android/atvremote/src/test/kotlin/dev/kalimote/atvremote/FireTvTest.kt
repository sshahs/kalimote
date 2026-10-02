package dev.kalimote.atvremote

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.math.BigInteger
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mirrors server/test/firetv.test.js; the integration test runs the Node mock Fire TV. */
class FireTvTest {
    private val identity = ClientIdentity.generate("adb-kotlin")

    @Test
    fun framing() {
        val bytes = AdbProtocol.encode(AdbProtocol.OPEN, 1, 0, "shell:ls\u0000".toByteArray())
        val m = AdbProtocol.read(DataInputStream(ByteArrayInputStream(bytes)))
        assertEquals(AdbProtocol.OPEN, m.command)
        assertEquals("shell:ls\u0000", m.data.toString(Charsets.UTF_8))
    }

    @Test
    fun publicKeyFormat() {
        val text = AdbProtocol.publicKey(identity, "me@host")
        assertTrue(text.endsWith(" me@host"))
        val buf = Base64.getDecoder().decode(text.substringBefore(' '))
        assertEquals(524, buf.size)
        val le = java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val n0 = le.getInt(8).toLong() and 0xffffffffL
        val n0inv = le.getInt(4).toLong() and 0xffffffffL
        assertEquals(0xffffffffL, BigInteger.valueOf(n0).multiply(BigInteger.valueOf(n0inv)).mod(BigInteger.ONE.shiftLeft(32)).toLong())
    }

    @Test
    fun signatureIsPkcs1Sha1() {
        val token = ByteArray(20) { it.toByte() }
        val sig = AdbProtocol.signToken(identity, token)
        assertEquals(256, sig.size)
        // Verify with the JDK: NONEwithRSA over DigestInfo+token equals SHA1withRSA semantics.
        val v = java.security.Signature.getInstance("NONEwithRSA")
        v.initVerify(identity.certificate.publicKey)
        v.update(byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14) + token)
        assertTrue(v.verify(sig))
    }

    @Test
    fun appNames() {
        assertEquals("Netflix", Apps.name("com.netflix.ninja"))
        assertEquals("YouTube", Apps.name("com.amazon.firetv.youtube"))
        assertEquals("Jellyfin (debug)", Apps.name("org.jellyfin.androidtv.debug"))
        assertEquals("Coolapp", Apps.name("com.example.coolapp"))
        assertEquals("VLC", Apps.name("org.videolan.vlc"))
        assertEquals(
            listOf("com.netflix.ninja", "org.xbmc.kodi", "com.x.y"),
            FireTvCommands.packages("priority=0 match=0x1\n  com.netflix.ninja/.MainActivity\n  org.xbmc.kodi/org.xbmc.kodi.Splash\npackage:com.x.y"),
        )
    }

    @Test
    fun commands() {
        val out = "  mCurrentFocus=Window{9f0 u0 org.jellyfin.androidtv.debug/org.jellyfin.androidtv.ui.MainActivity}\n mWakefulness=Asleep\n"
        assertEquals("org.jellyfin.androidtv.debug", FireTvCommands.currentApp(out))
        assertEquals("com.netflix.ninja", FireTvCommands.currentApp("mFocusedApp=ActivityRecord{1 u0 com.netflix.ninja/.MainActivity t5}"))
        assertEquals(false, FireTvCommands.powered(out))
        assertEquals(true, FireTvCommands.powered("Display Power: state=ON"))
        assertNull(FireTvCommands.powered("nothing"))
        assertEquals("input text 'it'\\''s%s50\\%%soff'", FireTvCommands.inputText("it's 50% off"))
        assertEquals(
            "monkey -p com.netflix.ninja -c android.intent.category.LAUNCHER 1 || monkey -p com.netflix.ninja 1",
            FireTvCommands.launch("market://launch?id=com.netflix.ninja"),
        )
        assertEquals(
            "am start -a android.intent.action.VIEW -d 'https://youtu.be/x?a=1&b='\\''2'\\'''",
            FireTvCommands.launch("https://youtu.be/x?a=1&b='2'"),
        )
        assertNull(FireTvCommands.key(23, Direction.END_LONG))
        assertEquals("input keyevent --longpress 23", FireTvCommands.key(23, Direction.START_LONG))
    }

    @Test
    fun pairAndControlAgainstMockFireTv() {
        val serverDir = File(System.getProperty("kalimote.serverDir") ?: "../../server")
        val hasNode = runCatching { ProcessBuilder("node", "--version").start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue(hasNode && File(serverDir, "node_modules").isDirectory, "node + server/node_modules required")

        val process = ProcessBuilder("node", "test/mock-firetv.js").directory(serverDir).redirectErrorStream(true)
            .apply { environment()["PORT"] = "0" }.start()
        val lines = LinkedBlockingQueue<String>()
        Thread { process.inputStream.bufferedReader().forEachLine { lines.add(it) } }.apply { isDaemon = true }.start()
        val seen = mutableListOf<String>()
        fun expect(regex: Regex): MatchResult {
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                val line = lines.poll(100, TimeUnit.MILLISECONDS) ?: continue
                seen += line
                regex.find(line)?.let { return it }
            }
            throw AssertionError("Mock Fire TV never printed $regex; output:\n" + seen.joinToString("\n"))
        }
        fun FireTvClient.await(predicate: (RemoteState) -> Boolean) {
            val deadline = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < deadline) {
                if (predicate(state)) return
                Thread.sleep(20)
            }
            throw AssertionError("Timed out; last state: $state")
        }
        try {
            val port = expect(Regex("listening on (\\d+)")).groupValues[1].toInt()

            // Unknown key: refused without pairing.
            val plain = AdbConnection("127.0.0.1", port, identity)
            assertEquals(
                AdbException.Code.UNAUTHORIZED,
                assertFailsWith<AdbException> { plain.connect() }.code,
            )
            val unpaired = FireTvClient("127.0.0.1", identity, port) {}
            unpaired.start()
            unpaired.await { it.status == RemoteState.Status.UNPAIRED }
            unpaired.shutdown()

            // Pairing: the mock accepts the prompt; it verifies our key and signature.
            var prompted = false
            val banner = FireTvClient.pair("127.0.0.1", identity, port, onAwaitingApproval = { prompted = true })
            assertTrue(prompted)
            assertTrue(banner.contains("AFTMM"))
            expect(Regex("Debugging allowed"))

            val client = FireTvClient("127.0.0.1", identity, port) {}
            client.start()
            client.await { it.connected && it.powered == true && it.currentApp == "com.amazon.tv.launcher" }
            client.sendKey(KeyCodes.DPAD_UP)
            expect(Regex("^key 19$"))
            client.sendKey(KeyCodes.DPAD_CENTER, Direction.START_LONG)
            client.sendKey(KeyCodes.DPAD_CENTER, Direction.END_LONG)
            expect(Regex("^key 23 long$"))
            client.sendText("it's 50% off")
            expect(Regex("^text \"it's 50% off\"$"))
            Macro.run(client, Macro.parse("DPAD_DOWN x2\nopen market://launch?id=org.jellyfin.androidtv.debug"))
            expect(Regex("launch org.jellyfin.androidtv.debug"))
            client.await { it.currentApp == "org.jellyfin.androidtv.debug" }
            assertEquals(true, Jellyfin.detect(client.state.currentApp)?.debug)
            // App list, screenshot and APK install
            assertEquals(
                listOf("Coolapp", "Jellyfin", "Jellyfin (debug)", "Netflix", "VLC", "YouTube"),
                client.listApps().map { it.name },
            )
            val png = client.screenshot()
            assertEquals("PNG", String(png, 1, 3))
            assertFailsWith<IllegalArgumentException> { client.installApk("not an apk".byteInputStream()) }
            val apk = byteArrayOf(0x50, 0x4b, 3, 4) + ByteArray(300 * 1024) { (it * 7).toByte() }
            val progress = mutableListOf<Long>()
            val out = client.installApk(apk.inputStream()) { progress += it }
            assertTrue(out.contains("Success"))
            expect(Regex("installed apk ${apk.size} bytes"))
            assertTrue(progress.count { it > 0 } >= 5)
            assertEquals(-1L, progress.last())

            client.sendKey(KeyCodes.POWER)
            client.await { it.powered == false }
            client.shutdown()
        } finally {
            process.destroy()
        }
    }
}
