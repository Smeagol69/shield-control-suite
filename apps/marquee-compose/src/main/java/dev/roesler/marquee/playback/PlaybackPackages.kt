package dev.roesler.marquee.playback

object PlaybackPackages {
    private val names = mapOf(
        "app.revanced.android.youtube" to "YouTube",
        "com.amazon.amazonvideo.livingroom" to "Amazon Prime Video",
        "com.amazon.amazonvideo.livingroom.nvidia" to "Amazon Prime Video",
        "com.apple.atve.androidtv.appletv" to "Apple TV",
        "com.cbs.ott" to "Paramount+",
        "com.crunchyroll.crunchyroid" to "Crunchyroll",
        "com.discovery.discoveryplus.androidtv" to "discovery+",
        "com.disney.disneyplus" to "Disney+",
        "com.google.android.videos" to "Google TV",
        "com.google.android.youtube.tv" to "YouTube",
        "com.hulu.livingroomplus" to "Hulu",
        "com.netflix.ninja" to "Netflix",
        "com.paramount.android.pplus" to "Paramount+",
        "com.peacocktv.peacockandroid" to "Peacock",
        "com.plexapp.android" to "Plex",
        "com.sling" to "Sling TV",
        "com.stremio.one" to "Stremio",
        "com.wbd.stream" to "Max",
        "org.jellyfin.androidtv" to "Jellyfin",
        "org.xbmc.kodi" to "Kodi",
        "tv.emby.embyatv" to "Emby",
        "tv.fubo.mobile" to "Fubo",
    )

    fun isTracked(packageName: String): Boolean = packageName in names

    fun providerName(packageName: String): String =
        names[packageName] ?: packageName.substringAfterLast('.')

    fun isProviderLabel(packageName: String, value: String?): Boolean {
        val normalized = value.normalizedLabel() ?: return false
        return normalized == providerName(packageName).normalizedLabel() ||
            normalized == packageName.normalizedLabel() ||
            normalized in GENERIC_PROVIDER_LABELS
    }

    val packageNames: Set<String>
        get() = names.keys

    private fun String?.normalizedLabel(): String? =
        this?.lowercase()
            ?.replace(NON_ALPHANUMERIC, " ")
            ?.trim()
            ?.replace(MULTIPLE_SPACES, " ")
            ?.takeIf(String::isNotBlank)

    private val NON_ALPHANUMERIC = Regex("""[^a-z0-9+]+""")
    private val MULTIPLE_SPACES = Regex("""\s+""")

    private val GENERIC_PROVIDER_LABELS = setOf(
        "amazon video",
        "apple tv",
        "disney+",
        "hbo max",
        "hulu",
        "max",
        "netflix",
        "paramount+",
        "peacock",
        "prime video",
        "stremio",
        "youtube",
    )
}
