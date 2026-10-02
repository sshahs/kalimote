package dev.kalimote.atvremote

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder

/**
 * Jellyfin integration, mirroring server/src/jellyfin.js: detects Jellyfin
 * running on the TV and talks to the Jellyfin server API for now playing,
 * seeking and track selection.
 *
 * Uses org.json, which Android provides; on the JVM it is a test dependency.
 */
data class JellyfinApp(val pkg: String, val debug: Boolean, val flavor: String)

data class JellyfinTrack(val index: Int, val label: String, val selected: Boolean)

data class JellyfinItem(
    val id: String,
    val name: String,
    val seriesName: String?,
    val season: Int?,
    val episode: Int?,
    val type: String,
    val year: Int?,
    val runtimeMs: Long,
    val imageTag: String?,
)

data class JellyfinSession(
    val sessionId: String,
    val userId: String? = null,
    val client: String,
    val deviceName: String,
    val appVersion: String,
    val remoteIp: String,
    val item: JellyfinItem?,
    val positionMs: Long,
    val paused: Boolean,
    val muted: Boolean,
    val audio: List<JellyfinTrack>,
    val subtitles: List<JellyfinTrack>,
    val subtitleIndex: Int,
)

/** A library entry for the browser: playable, or [browsable] (series, library, folder). */
data class JellyfinLibraryItem(
    val id: String,
    val name: String,
    val type: String,
    val seriesName: String?,
    val season: Int?,
    val episode: Int?,
    val year: Int?,
    val runtimeMs: Long,
    val imageTag: String?,
    val progress: Double?,
    val browsable: Boolean,
)

data class JellyfinSection(val title: String, val items: List<JellyfinLibraryItem>)

sealed interface JellyfinAction {
    data object PlayPause : JellyfinAction
    data object Stop : JellyfinAction
    data object Next : JellyfinAction
    data object Previous : JellyfinAction
    data class Seek(val positionMs: Long) : JellyfinAction
    data class SeekBy(val deltaMs: Long) : JellyfinAction
    data class Subtitle(val index: Int) : JellyfinAction
    data class Audio(val index: Int) : JellyfinAction
    data class Message(val text: String) : JellyfinAction
}

class JellyfinException(message: String) : IOException(message)

object Jellyfin {
    private const val TICKS_PER_MS = 10_000L

    /** Recognises Jellyfin apps, including debug builds (package suffix ".debug"). */
    fun detect(pkg: String?): JellyfinApp? {
        if (pkg == null || !pkg.startsWith("org.jellyfin.")) return null
        return JellyfinApp(
            pkg = pkg,
            debug = pkg.endsWith(".debug"),
            flavor = if (pkg.startsWith("org.jellyfin.androidtv")) "androidtv" else "mobile",
        )
    }

    /** Strips IPv4-mapped prefixes and ports from a Jellyfin RemoteEndPoint. */
    fun endpointIp(endpoint: String?): String {
        var ip = endpoint.orEmpty().trim()
        if (ip.startsWith("[")) ip = ip.substring(1, ip.indexOf(']').takeIf { it > 0 } ?: ip.length)
        ip = ip.replace(Regex("^::ffff:", RegexOption.IGNORE_CASE), "")
        if (Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+$").matches(ip)) ip = ip.substringBeforeLast(':')
        return ip
    }

    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
    private fun JSONObject.int(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null

    fun parseSessions(json: String): List<JellyfinSession> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { parseSession(arr.getJSONObject(it)) }
    }

    fun parseSession(s: JSONObject): JellyfinSession {
        val ps = s.optJSONObject("PlayState") ?: JSONObject()
        val raw = s.optJSONObject("NowPlayingItem")
        val streams = raw?.optJSONArray("MediaStreams") ?: JSONArray()
        fun tracks(type: String, selected: Int?): List<JellyfinTrack> =
            (0 until streams.length()).map { streams.getJSONObject(it) }
                .filter { it.optString("Type") == type }
                .map { m ->
                    val index = m.optInt("Index")
                    JellyfinTrack(index, m.str("DisplayTitle") ?: m.str("Language") ?: "$type $index", index == selected)
                }
        val item = raw?.let {
            JellyfinItem(
                id = it.optString("Id"),
                name = it.optString("Name"),
                seriesName = it.str("SeriesName"),
                season = it.int("ParentIndexNumber"),
                episode = it.int("IndexNumber"),
                type = it.optString("Type"),
                year = it.int("ProductionYear"),
                runtimeMs = it.optLong("RunTimeTicks") / TICKS_PER_MS,
                imageTag = it.optJSONObject("ImageTags")?.str("Primary"),
            )
        }
        return JellyfinSession(
            sessionId = s.optString("Id"),
            userId = s.str("UserId"),
            client = s.optString("Client"),
            deviceName = s.optString("DeviceName"),
            appVersion = s.optString("ApplicationVersion"),
            remoteIp = endpointIp(s.str("RemoteEndPoint")),
            item = item,
            positionMs = ps.optLong("PositionTicks") / TICKS_PER_MS,
            paused = ps.optBoolean("IsPaused"),
            muted = ps.optBoolean("IsMuted"),
            audio = tracks("Audio", ps.int("AudioStreamIndex")),
            subtitles = tracks("Subtitle", ps.int("SubtitleStreamIndex")),
            subtitleIndex = ps.int("SubtitleStreamIndex") ?: -1,
        )
    }

    fun parseItem(i: JSONObject): JellyfinLibraryItem {
        val type = i.optString("Type")
        return JellyfinLibraryItem(
            id = i.optString("Id"),
            name = i.optString("Name"),
            type = type,
            seriesName = i.str("SeriesName"),
            season = i.int("ParentIndexNumber"),
            episode = i.int("IndexNumber"),
            year = i.int("ProductionYear"),
            runtimeMs = i.optLong("RunTimeTicks") / TICKS_PER_MS,
            imageTag = i.optJSONObject("ImageTags")?.str("Primary"),
            progress = i.optJSONObject("UserData")?.let { u -> if (u.has("PlayedPercentage") && !u.isNull("PlayedPercentage")) u.optDouble("PlayedPercentage") else null },
            browsable = type in setOf("Series", "Season", "Folder", "CollectionFolder", "BoxSet", "UserView"),
        )
    }

    fun parseItems(arr: JSONArray?): List<JellyfinLibraryItem> =
        if (arr == null) emptyList() else (0 until arr.length()).map { parseItem(arr.getJSONObject(it)) }

    /** Strict match for starting playback: only the TV's own session. */
    fun pickPlayback(sessions: List<JellyfinSession>, tvIps: Collection<String>): JellyfinSession? =
        sessions.firstOrNull { it.remoteIp in tvIps }

    /** Same IP and playing, then same IP, then a playing Android TV client, then anything playing. */
    fun pick(sessions: List<JellyfinSession>, tvIps: Collection<String>): JellyfinSession? {
        val playing = sessions.filter { it.item != null }
        return sessions.firstOrNull { it.remoteIp in tvIps && it.item != null }
            ?: sessions.firstOrNull { it.remoteIp in tvIps }
            ?: playing.firstOrNull { Regex("android ?tv", RegexOption.IGNORE_CASE).containsMatchIn(it.client) }
            ?: playing.firstOrNull()
    }
}

/** Blocking client for the Jellyfin server API; call from a background thread. */
class JellyfinClient(url: String, private val apiKey: String, private val version: String = "1.0.0") {
    val url: String = url.trim().trimEnd('/')

    init {
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(this.url)) {
            throw JellyfinException("Jellyfin URL must start with http:// or https://")
        }
        if (apiKey.isBlank()) throw JellyfinException("Jellyfin API key is required")
    }

    private val auth: String
        get() = "MediaBrowser Client=\"Kalimote\", Device=\"Kalimote Android\", DeviceId=\"kalimote-android\", " +
            "Version=\"$version\", Token=\"${apiKey.trim()}\""

    private fun request(method: String, path: String, body: JSONObject? = null): HttpURLConnection {
        val conn = try {
            (URL(url + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Authorization", auth)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toString().toByteArray()) }
                }
            }.also { it.responseCode }
        } catch (e: IOException) {
            throw JellyfinException("Cannot reach Jellyfin at $url (${e.message})")
        }
        when (conn.responseCode) {
            401, 403 -> throw JellyfinException("Jellyfin rejected the API key")
            in 200..299 -> return conn
            else -> throw JellyfinException("Jellyfin returned HTTP ${conn.responseCode}")
        }
    }

    private fun text(conn: HttpURLConnection) = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

    /** Server name and version, for "Test connection". */
    fun info(): Pair<String, String> {
        val j = JSONObject(text(request("GET", "/System/Info")))
        return j.optString("ServerName") to j.optString("Version")
    }

    fun sessions(): List<JellyfinSession> =
        Jellyfin.parseSessions(text(request("GET", "/Sessions?activeWithinSeconds=960")))

    private fun ipsFor(tvHost: String): Set<String> {
        val ips = mutableSetOf(tvHost)
        runCatching { InetAddress.getAllByName(tvHost).forEach { ips += it.hostAddress.orEmpty() } }
        return ips
    }

    /** The session for a TV given its host name or IP; [strict] = the TV's own session only. */
    fun sessionFor(tvHost: String, strict: Boolean = false): JellyfinSession? {
        val ips = ipsFor(tvHost)
        val sessions = sessions()
        return if (strict) Jellyfin.pickPlayback(sessions, ips) else Jellyfin.pick(sessions, ips)
    }

    private fun get(path: String) = text(request("GET", path))
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** The user to browse as: the TV session's user, else the first enabled user. */
    fun userId(session: JellyfinSession?): String {
        session?.userId?.let { return it }
        val users = JSONArray(get("/Users"))
        val list = (0 until users.length()).map { users.getJSONObject(it) }
        val user = list.firstOrNull { it.optJSONObject("Policy")?.optBoolean("IsDisabled") != true } ?: list.firstOrNull()
        return user?.optString("Id") ?: throw JellyfinException("No Jellyfin users found")
    }

    /** Continue watching, latest and libraries. */
    fun home(userId: String): List<JellyfinSection> {
        val u = enc(userId)
        val resume = JSONObject(get("/Users/$u/Items/Resume?Limit=12&MediaTypes=Video"))
        val latestRaw = get("/Users/$u/Items/Latest?Limit=16").trim()
        val latest = if (latestRaw.startsWith("[")) JSONArray(latestRaw) else JSONObject(latestRaw).optJSONArray("Items")
        val views = JSONObject(get("/Users/$u/Views"))
        return listOf(
            JellyfinSection("Continue watching", Jellyfin.parseItems(resume.optJSONArray("Items"))),
            JellyfinSection("Latest", Jellyfin.parseItems(latest)),
            JellyfinSection("Libraries", Jellyfin.parseItems(views.optJSONArray("Items")).map { it.copy(browsable = true) }),
        ).filter { it.items.isNotEmpty() }
    }

    fun search(userId: String, query: String): List<JellyfinSection> {
        val res = JSONObject(
            get("/Users/${enc(userId)}/Items?searchTerm=${enc(query)}&Recursive=true&IncludeItemTypes=Movie,Series,Episode,Video,MusicVideo&Limit=40"),
        )
        return listOf(JellyfinSection("Results for \u201c$query\u201d", Jellyfin.parseItems(res.optJSONArray("Items"))))
    }

    /** Contents of a library/folder, or a series' episodes. */
    fun children(userId: String, item: JellyfinLibraryItem): List<JellyfinSection> {
        val res = if (item.type == "Series") {
            JSONObject(get("/Shows/${enc(item.id)}/Episodes?userId=${enc(userId)}"))
        } else {
            JSONObject(get("/Users/${enc(userId)}/Items?ParentId=${enc(item.id)}&Recursive=true&IncludeItemTypes=Movie,Series,Video,MusicVideo&SortBy=SortName&Limit=200"))
        }
        return listOf(JellyfinSection(item.name, Jellyfin.parseItems(res.optJSONArray("Items"))))
    }

    /** Starts playing [itemId] on the session ("cast" it to the TV). */
    fun play(session: JellyfinSession, itemId: String) {
        request("POST", "/Sessions/${enc(session.sessionId)}/Playing?playCommand=PlayNow&itemIds=${enc(itemId)}").disconnect()
    }

    fun control(session: JellyfinSession, action: JellyfinAction) {
        val id = URLEncoder.encode(session.sessionId, "UTF-8")
        fun playing(cmd: String, query: String = "") = request("POST", "/Sessions/$id/Playing/$cmd$query").disconnect()
        fun general(name: String, args: JSONObject) =
            request("POST", "/Sessions/$id/Command", JSONObject().put("Name", name).put("Arguments", args)).disconnect()
        when (action) {
            JellyfinAction.PlayPause -> playing("PlayPause")
            JellyfinAction.Stop -> playing("Stop")
            JellyfinAction.Next -> playing("NextTrack")
            JellyfinAction.Previous -> playing("PreviousTrack")
            is JellyfinAction.Seek, is JellyfinAction.SeekBy -> {
                var ms = if (action is JellyfinAction.Seek) action.positionMs else session.positionMs + (action as JellyfinAction.SeekBy).deltaMs
                val max = session.item?.runtimeMs?.takeIf { it > 0 } ?: Long.MAX_VALUE
                ms = ms.coerceIn(0, max)
                playing("Seek", "?seekPositionTicks=${ms * 10_000}")
            }
            is JellyfinAction.Subtitle -> general("SetSubtitleStreamIndex", JSONObject().put("Index", action.index.toString()))
            is JellyfinAction.Audio -> general("SetAudioStreamIndex", JSONObject().put("Index", action.index.toString()))
            is JellyfinAction.Message -> general(
                "DisplayMessage",
                JSONObject().put("Header", "Kalimote").put("Text", action.text).put("TimeoutMs", "5000"),
            )
        }
    }

    /** The item's primary image (JPEG/WebP from a real server). */
    fun image(itemId: String, tag: String?, maxHeight: Int = 360): ByteArray {
        val q = "maxHeight=$maxHeight&quality=85&format=Jpg" + (tag?.let { "&tag=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        return request("GET", "/Items/${URLEncoder.encode(itemId, "UTF-8")}/Images/Primary?$q").inputStream.use { it.readBytes() }
    }
}
