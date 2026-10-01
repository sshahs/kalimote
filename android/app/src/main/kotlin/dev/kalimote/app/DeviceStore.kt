package dev.kalimote.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class TvDevice(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val paired: Boolean = false,
    /** For Wake-on-LAN; entered by the user. */
    val mac: String? = null,
)

data class SavedMacro(val id: String = UUID.randomUUID().toString(), val name: String, val script: String)

data class AppShortcut(val name: String, val url: String)

val DEFAULT_APPS = listOf(
    AppShortcut("YouTube", "https://www.youtube.com"),
    AppShortcut("Netflix", "market://launch?id=com.netflix.ninja"),
    AppShortcut("Prime Video", "market://launch?id=com.amazon.amazonvideo.livingroom"),
    AppShortcut("Disney+", "market://launch?id=com.disney.disneyplus"),
    AppShortcut("Spotify", "market://launch?id=com.spotify.tv.android"),
    AppShortcut("Plex", "market://launch?id=com.plexapp.android"),
    AppShortcut("Jellyfin", "market://launch?id=org.jellyfin.androidtv"),
)

/** Persists TVs, app shortcuts and preferences in SharedPreferences. */
class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("kalimote", Context.MODE_PRIVATE)

    fun loadDevices(): List<TvDevice> = runCatching {
        val arr = JSONArray(prefs.getString("devices", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TvDevice(
                o.getString("id"),
                o.getString("name"),
                o.getString("host"),
                o.optBoolean("paired"),
                o.optString("mac").takeIf { it.isNotEmpty() },
            )
        }
    }.getOrDefault(emptyList())

    fun saveDevices(devices: List<TvDevice>) {
        val arr = JSONArray()
        devices.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("host", it.host)
                    .put("paired", it.paired).put("mac", it.mac ?: ""),
            )
        }
        prefs.edit().putString("devices", arr.toString()).apply()
    }

    fun loadApps(): List<AppShortcut> {
        val raw = prefs.getString("apps", null) ?: return DEFAULT_APPS
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AppShortcut(o.getString("name"), o.getString("url"))
            }
        }.getOrDefault(DEFAULT_APPS)
    }

    fun saveApps(apps: List<AppShortcut>) {
        val arr = JSONArray()
        apps.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url)) }
        prefs.edit().putString("apps", arr.toString()).apply()
    }

    fun loadMacros(): List<SavedMacro> = runCatching {
        val arr = JSONArray(prefs.getString("macros", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SavedMacro(o.getString("id"), o.getString("name"), o.getString("script"))
        }
    }.getOrDefault(emptyList())

    fun saveMacros(macros: List<SavedMacro>) {
        val arr = JSONArray()
        macros.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("script", it.script)) }
        prefs.edit().putString("macros", arr.toString()).apply()
    }

    /** Sleep timer: when (epoch ms) and which TV; 0 when not set. */
    var sleepAt: Long
        get() = prefs.getLong("sleepAt", 0)
        set(value) = prefs.edit().putLong("sleepAt", value).apply()

    var sleepDeviceId: String?
        get() = prefs.getString("sleepDevice", null)
        set(value) = prefs.edit().putString("sleepDevice", value).apply()

    var selectedId: String?
        get() = prefs.getString("selected", null)
        set(value) = prefs.edit().putString("selected", value).apply()

    var touchpad: Boolean
        get() = prefs.getBoolean("touchpad", false)
        set(value) = prefs.edit().putBoolean("touchpad", value).apply()

    /** Optional Jellyfin server for now playing / seeking / tracks. */
    var jellyfinUrl: String
        get() = prefs.getString("jellyfinUrl", "") ?: ""
        set(value) = prefs.edit().putString("jellyfinUrl", value).apply()

    var jellyfinKey: String
        get() = prefs.getString("jellyfinKey", "") ?: ""
        set(value) = prefs.edit().putString("jellyfinKey", value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keepScreenOn", false)
        set(value) = prefs.edit().putBoolean("keepScreenOn", value).apply()

    var volumeKeys: Boolean
        get() = prefs.getBoolean("volumeKeys", true)
        set(value) = prefs.edit().putBoolean("volumeKeys", value).apply()
}

/**
 * Backup format for macros and app shortcuts, shared with the web server:
 * `{"macros":[{"name","script"}], "apps":[{"name","url"}]}`. A bare array of
 * macros (as returned by the server's GET /api/devices "macros") also imports.
 */
object Backup {
    data class Contents(val macros: List<SavedMacro>, val apps: List<AppShortcut>)

    fun export(macros: List<SavedMacro>, apps: List<AppShortcut>): String {
        val m = JSONArray()
        macros.forEach { m.put(JSONObject().put("name", it.name).put("script", it.script)) }
        val a = JSONArray()
        apps.forEach { a.put(JSONObject().put("name", it.name).put("url", it.url)) }
        return JSONObject().put("kalimote", 1).put("macros", m).put("apps", a).toString(2)
    }

    /** Throws IllegalArgumentException with a readable message on bad input. */
    fun parse(text: String): Contents {
        val trimmed = text.trim()
        try {
            val (macros, apps) = if (trimmed.startsWith("[")) {
                JSONArray(trimmed) to JSONArray()
            } else {
                val o = JSONObject(trimmed)
                (o.optJSONArray("macros") ?: JSONArray()) to (o.optJSONArray("apps") ?: JSONArray())
            }
            return Contents(
                (0 until macros.length()).map { i ->
                    val o = macros.getJSONObject(i)
                    SavedMacro(name = o.getString("name"), script = o.getString("script"))
                },
                (0 until apps.length()).map { i ->
                    val o = apps.getJSONObject(i)
                    AppShortcut(o.getString("name"), o.getString("url"))
                },
            )
        } catch (e: org.json.JSONException) {
            throw IllegalArgumentException("That isn't a Kalimote backup (${e.message})")
        }
    }
}
