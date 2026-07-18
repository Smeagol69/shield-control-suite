package dev.roesler.marquee.data

import java.net.URI

object UrlPolicy {
    fun isTmdbImage(url: String?): Boolean =
        matchesHttpsHost(url) { host -> host == "image.tmdb.org" }

    fun isTmdbWeb(url: String?): Boolean =
        matchesHttpsHost(url) { host ->
            host == "themoviedb.org" || host.endsWith(".themoviedb.org")
        }

    private fun matchesHttpsHost(url: String?, predicate: (String) -> Boolean): Boolean {
        if (url.isNullOrBlank()) return false
        return runCatching {
            val uri = URI.create(url)
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host?.lowercase()?.let(predicate) == true
        }.getOrDefault(false)
    }
}
