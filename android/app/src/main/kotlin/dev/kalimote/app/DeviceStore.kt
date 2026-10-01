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

    var volumeKeys: Boolean
        get() = prefs.getBoolean("volumeKeys", true)
        set(value) = prefs.edit().putBoolean("volumeKeys", value).apply()
}
