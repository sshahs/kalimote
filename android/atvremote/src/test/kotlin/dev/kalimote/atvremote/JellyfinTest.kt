package dev.kalimote.atvremote

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mirrors server/test/jellyfin.test.js against the same fixture and mock server. */
class JellyfinTest {
    private val serverDir = File(System.getProperty("kalimote.serverDir") ?: "../../server")
    private val fixture = File(serverDir, "test/fixtures/jellyfin-sessions.json")

    @Test
    fun detect() {
        assertEquals(JellyfinApp("org.jellyfin.androidtv", false, "androidtv"), Jellyfin.detect("org.jellyfin.androidtv"))
        assertEquals(JellyfinApp("org.jellyfin.androidtv.debug", true, "androidtv"), Jellyfin.detect("org.jellyfin.androidtv.debug"))
        assertEquals("mobile", Jellyfin.detect("org.jellyfin.mobile.debug")?.flavor)
        assertNull(Jellyfin.detect("com.netflix.ninja"))
        assertNull(Jellyfin.detect(null))
    }

    @Test
    fun endpointIp() {
        assertEquals("192.168.1.5", Jellyfin.endpointIp("::ffff:192.168.1.5"))
        assertEquals("192.168.1.5", Jellyfin.endpointIp("192.168.1.5:51234"))
        assertEquals("fe80::1", Jellyfin.endpointIp("[fe80::1]:8096"))
        assertEquals("10.0.0.2", Jellyfin.endpointIp("10.0.0.2"))
    }

    @Test
    fun parseAndPick() {
        assumeTrue(fixture.exists(), "fixture from ../server required")
        val sessions = Jellyfin.parseSessions(fixture.readText())
        val tv = sessions[1]
        assertEquals("127.0.0.1", tv.remoteIp)
        assertEquals("The One Where It Works", tv.item?.name)
        assertEquals(2, tv.item?.season)
        assertEquals(2_600_000L, tv.item?.runtimeMs)
        assertEquals(600_000L, tv.positionMs)
        assertEquals(listOf(1 to true, 2 to false), tv.audio.map { it.index to it.selected })
        assertEquals(listOf(3, 4), tv.subtitles.map { it.index })
        assertEquals(-1, tv.subtitleIndex)
        assertNull(sessions[0].item)
        assertEquals("tv-session", Jellyfin.pick(sessions, listOf("127.0.0.1"))?.sessionId)
        assertEquals("tv-session", Jellyfin.pick(sessions, listOf("10.9.9.9"))?.sessionId)
        assertNull(Jellyfin.pick(listOf(sessions[0]), listOf("10.9.9.9")))
    }

    @Test
    fun clientAgainstMockServer() {
        val hasNode = runCatching { ProcessBuilder("node", "--version").start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue(hasNode && File(serverDir, "node_modules").isDirectory, "node + server/node_modules required")
        assertFailsWith<JellyfinException> { JellyfinClient("jellyfin.local", "k") }
        assertFailsWith<JellyfinException> { JellyfinClient("http://x", " ") }

        val process = ProcessBuilder("node", "test/mock-jellyfin.js").directory(serverDir).redirectErrorStream(true)
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
            throw AssertionError("Mock Jellyfin never printed $regex; output was:\n" + seen.joinToString("\n"))
        }
        try {
            val url = expect(Regex("listening on (\\S+)")).groupValues[1]
            assertTrue(assertFailsWith<JellyfinException> { JellyfinClient(url, "wrong").info() }.message!!.contains("rejected"))
            assertTrue(assertFailsWith<JellyfinException> { JellyfinClient("http://127.0.0.1:1", "k").info() }.message!!.contains("Cannot reach"))

            val client = JellyfinClient("$url/", "test-key")
            assertEquals("Mock Jellyfin" to "10.10.3", client.info())
            val session = assertNotNull(client.sessionFor("127.0.0.1"))
            assertEquals("tv-session", session.sessionId)

            client.control(session, JellyfinAction.SeekBy(30_000))
            expect(Regex("command /Sessions/tv-session/Playing/Seek\\?seekPositionTicks=6300000000 "))
            client.control(session, JellyfinAction.Subtitle(4))
            expect(Regex("^(?=.*SetSubtitleStreamIndex)(?=.*\"Index\":\"4\")"))
            client.control(session, JellyfinAction.Audio(2))
            expect(Regex("^(?=.*SetAudioStreamIndex)(?=.*\"Index\":\"2\")"))
            client.control(session, JellyfinAction.PlayPause)
            expect(Regex("Playing/PlayPause"))
            client.control(session, JellyfinAction.Message("Dinner"))
            expect(Regex("^(?=.*DisplayMessage)(?=.*Dinner)"))
            client.control(session, JellyfinAction.Seek(Long.MAX_VALUE / 20_000))
            expect(Regex("seekPositionTicks=26000000000 "))

            val after = assertNotNull(client.sessionFor("127.0.0.1"))
            assertTrue(after.paused)
            assertEquals(4, after.subtitles.single { it.selected }.index)
            assertTrue(client.image("item-42", "abc123").isNotEmpty())

            // Library browsing and play-on-TV
            assertEquals("user-1", client.userId(after))
            assertEquals("user-1", client.userId(null))
            val home = client.home("user-1")
            assertEquals(listOf("Continue watching", "Latest", "Libraries"), home.map { it.title })
            assertEquals(35.5, home[0].items[0].progress)
            assertTrue(home[2].items.all { it.browsable })
            assertEquals(listOf("Sintel"), client.search("user-1", "sintel")[0].items.map { it.name })
            val series = home[1].items.first { it.type == "Series" }
            assertEquals(listOf(1 to 1, 2 to 5), client.children("user-1", series)[0].items.map { it.season to it.episode })
            assertEquals(null, Jellyfin.pickPlayback(client.sessions(), listOf("10.9.9.9")))
            val tv = assertNotNull(client.sessionFor("127.0.0.1", strict = true))
            client.play(tv, "movie-2")
            expect(Regex("Playing\\?playCommand=PlayNow&itemIds=movie-2"))
            assertEquals("Sintel", client.sessionFor("127.0.0.1")?.item?.name)
        } finally {
            process.destroy()
        }
    }
}
