package dev.kalimote.atvremote

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AtvRemoteTest {

    @Test
    fun protoRoundTrip() {
        val bytes = ProtoWriter()
            .varint(1, 300)
            .string(2, "héllo")
            .message(3, ProtoWriter().varint(1, 7))
            .varint(4, -1)
            .toByteArray()
        val m = ProtoMessage(bytes)
        assertEquals(300, m.int(1))
        assertEquals("héllo", m.string(2))
        assertEquals(7, m.message(3)?.int(1))
        assertEquals(-1, m.int(4))
    }

    @Test
    fun framing() {
        val out = ByteArrayOutputStream()
        Framing.write(out, ByteArray(200) { 1 })
        Framing.write(out, byteArrayOf(1, 2, 3))
        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals(200, Framing.read(input).size)
        assertEquals(listOf<Byte>(1, 2, 3), Framing.read(input).toList())
    }

    @Test
    fun identityGenerateAndRestore() {
        val id = ClientIdentity.generate("test")
        id.certificate.checkValidity()
        id.certificate.verify(id.certificate.publicKey)
        assertTrue(id.certificate.subjectX500Principal.name.contains("CN=test"))
        val restored = ClientIdentity.decode(id.encode())
        assertEquals(id.certificate, restored.certificate)
        assertNotNull(restored.sslContext)
    }

    @Test
    fun pairingSecretValidatesCode() {
        val a = ClientIdentity.generate("a")
        val b = ClientIdentity.generate("b")
        assertFailsWith<IllegalArgumentException> { PairingSecret.compute(a.certificate, b.certificate, "xyz") }
        // Exactly one checksum byte out of 256 is valid for a given nonce.
        val valid = (0..255).count {
            runCatching { PairingSecret.compute(a.certificate, b.certificate, "%02X1234".format(it)) }.isSuccess
        }
        assertEquals(1, valid)
    }

    /** Runs the Node.js mock TV from ../server, checking interoperability with the web implementation. */
    @Test
    fun pairAndControlAgainstMockTv() {
        val serverDir = File(System.getProperty("kalimote.serverDir") ?: "../../server")
        val hasNode = runCatching { ProcessBuilder("node", "--version").start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue(hasNode && File(serverDir, "node_modules").isDirectory, "node + server/node_modules required")

        val process = ProcessBuilder("node", "test/mock-tv.js")
            .directory(serverDir)
            .redirectErrorStream(true)
            .apply {
                environment()["PAIRING_PORT"] = "0"
                environment()["REMOTE_PORT"] = "0"
                environment()["HOST"] = "127.0.0.1"
            }
            .start()
        val lines = LinkedBlockingQueue<String>()
        Thread { process.inputStream.bufferedReader().forEachLine { lines.add(it) } }.apply { isDaemon = true }.start()
        fun expect(regex: Regex): MatchResult {
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                val line = lines.poll(100, TimeUnit.MILLISECONDS) ?: continue
                regex.find(line)?.let { return it }
            }
            throw AssertionError("Mock TV never printed $regex")
        }

        try {
            val ports = expect(Regex("pairing (\\d+), remote (\\d+)"))
            val pairingPort = ports.groupValues[1].toInt()
            val remotePort = ports.groupValues[2].toInt()
            val identity = ClientIdentity.generate("kotlin-test")

            // Not paired yet: the TV refuses the control connection.
            val unpaired = RemoteClient("127.0.0.1", identity, port = remotePort) {}
            unpaired.start()
            unpaired.await { it.status == RemoteState.Status.UNPAIRED }
            unpaired.shutdown()

            val session = PairingSession("127.0.0.1", identity, port = pairingPort)
            session.start()
            val code = expect(Regex("Pairing code shown on TV: (\\w+)")).groupValues[1]
            session.finish(code.lowercase())
            expect(Regex("Client paired"))

            val client = RemoteClient("127.0.0.1", identity, port = remotePort) {}
            client.start()
            client.await { it.connected && it.powered == true && it.volume?.level == 10 }

            client.sendKey(KeyCodes.DPAD_UP)
            expect(Regex("key 19 dir 3"))
            client.sendKey(KeyCodes.VOLUME_UP)
            client.await { it.volume?.level == 11 }
            client.sendText("hello")
            expect(Regex("text \"hello\""))
            client.launchApp("https://www.youtube.com")
            client.await { it.currentApp == "https://www.youtube.com" }
            Macro.run(client, Macro.parse("DPAD_LEFT x2, VOLUME_DOWN"))
            expect(Regex("key 21 dir 3"))
            client.await { it.volume?.level == 10 }
            client.sendKey(KeyCodes.POWER)
            client.await { it.powered == false }

            // App backgrounded and reopened quickly (onStop → onStart): the old
            // connection closes in the background and must not be mistaken
            // for the TV rejecting us, nor leave two connection loops.
            repeat(3) {
                client.stop()
                client.start()
            }
            client.await { it.connected }
            Thread.sleep(500)
            check(client.state.connected) { "Lost the connection after restart: ${client.state}" }
            client.sendKey(KeyCodes.DPAD_DOWN)
            expect(Regex("key 20 dir 3"))
            client.shutdown()
        } finally {
            process.destroy()
        }
    }

    private fun RemoteClient.await(timeoutMs: Long = 8000, predicate: (RemoteState) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate(state)) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out; last state: $state")
    }
}
