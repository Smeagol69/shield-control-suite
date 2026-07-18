package dev.roesler.marquee.data

import java.net.URI

object UrlPolicy {
    fun isTmdbImage(url: String?): Boolean =
        matchesHttpsHost(url) { host -> host == "image.tmdb.org" }

    fun isMediaImage(url: String?): Boolean =
        matchesHttpsHost(url) { host ->
            host == "image.tmdb.org" ||
                host == "static.tvmaze.com" ||
                TRAKT_IMAGE_HOSTS.matches(host)
        }

    fun canonicalMediaImage(url: String?): String? {
        val value = url?.trim()?.takeIf(String::isNotBlank) ?: return null
        val normalized = when {
            value.startsWith("https://", ignoreCase = true) -> value
            value.startsWith("//") -> "https:$value"
            "://" !in value -> "https://$value"
            else -> return null
        }
        return normalized.takeIf(::isMediaImage)
    }

    fun isTmdbWeb(url: String?): Boolean =
        matchesHttpsHost(url) { host ->
            host == "themoviedb.org" || host.endsWith(".themoviedb.org")
        }

    fun isTrustedServiceWeb(url: String?): Boolean =
        matchesHttpsHost(url) { host ->
            host == "trakt.tv" ||
                host.endsWith(".trakt.tv") ||
                host == "tvmaze.com" ||
                host.endsWith(".tvmaze.com") ||
                host == "themoviedb.org" ||
                host.endsWith(".themoviedb.org")
        }

    private fun matchesHttpsHost(url: String?, predicate: (String) -> Boolean): Boolean {
        if (url.isNullOrBlank()) return false
        return runCatching {
            val uri = URI.create(url)
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host?.lowercase()?.let(predicate) == true
        }.getOrDefault(false)
    }

    private val TRAKT_IMAGE_HOSTS =
        Regex("""^(?:media|walter(?:-[a-z0-9]+)?)\.trakt\.tv$""")
}
