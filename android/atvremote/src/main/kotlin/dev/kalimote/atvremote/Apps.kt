package dev.kalimote.atvremote

import java.util.Locale

data class TvApp(val name: String, val pkg: String)

/** Same catalog and naming as server/src/apps.js. */
object Apps {
    val CATALOG: List<Pair<String, List<String>>> = listOf(
        "YouTube" to listOf("com.google.android.youtube.tv", "com.amazon.firetv.youtube"),
        "Netflix" to listOf("com.netflix.ninja"),
        "Prime Video" to listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod", "com.amazon.avod.thirdpartyclient"),
        "Disney+" to listOf("com.disney.disneyplus"),
        "Max" to listOf("com.wbd.stream", "com.hbo.hbonow"),
        "Hulu" to listOf("com.hulu.livingroomplus"),
        "Apple TV" to listOf("com.apple.atve.androidtv.appletv", "com.apple.atve.amazon.appletv"),
        "Paramount+" to listOf("com.cbs.ott"),
        "Peacock" to listOf("com.peacocktv.peacockandroid"),
        "Spotify" to listOf("com.spotify.tv.android"),
        "YouTube Music" to listOf("com.google.android.youtube.tvmusic"),
        "Plex" to listOf("com.plexapp.android"),
        "Jellyfin" to listOf("org.jellyfin.androidtv"),
        "Kodi" to listOf("org.xbmc.kodi"),
        "Twitch" to listOf("tv.twitch.android.app", "tv.twitch.android.viewer"),
        "Crunchyroll" to listOf("com.crunchyroll.crunchyroid"),
        "VLC" to listOf("org.videolan.vlc"),
        "SmartTube" to listOf("com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv"),
        "Play Store" to listOf("com.android.vending"),
        "Settings" to listOf("com.android.tv.settings", "com.amazon.tv.settings.v2"),
    )

    /** Popular apps offered on Google TV, whose protocol can't list installed apps. */
    val catalog: List<TvApp> get() = CATALOG.map { (name, pkgs) -> TvApp(name, pkgs.first()) }

    private val NAMES: Map<String, String> = buildMap {
        CATALOG.forEach { (name, pkgs) -> pkgs.forEach { put(it, name) } }
        put("com.google.android.tvlauncher", "Home")
        put("com.google.android.apps.tv.launcherx", "Home")
        put("com.amazon.tv.launcher", "Home")
        put("com.amazon.cloud9", "Silk Browser")
        put("org.jellyfin.androidtv.debug", "Jellyfin (debug)")
    }

    private val NOISE = setOf(
        "com", "org", "net", "tv", "android", "androidtv", "app", "apps", "firetv", "amazon", "google",
        "mobile", "client", "ott", "leanback", "atv", "beta", "release", "free", "pro",
    )

    fun name(pkg: String?): String {
        if (pkg.isNullOrEmpty()) return ""
        NAMES[pkg]?.let { return it }
        val debug = pkg.endsWith(".debug")
        val base = pkg.removeSuffix(".debug")
        NAMES[base]?.let { return "$it (debug)" }
        val parts = base.split('.').filter { it.lowercase(Locale.ROOT) !in NOISE }
        val word = parts.lastOrNull() ?: base.substringAfterLast('.')
        val name = word.replaceFirstChar { it.titlecase(Locale.ROOT) }
        return if (debug) "$name (debug)" else name
    }

    fun launchUrl(pkg: String) = "market://launch?id=$pkg"
}
