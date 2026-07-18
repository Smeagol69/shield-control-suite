package dev.roesler.marquee.data

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object ResolverLinks {
    fun stremio(item: MediaItem): String {
        val imdbId = item.imdbId?.takeIf(IMDB_ID::matches)
        if (imdbId != null) {
            return when (item.type) {
                MediaType.MOVIE ->
                    "stremio:///detail/movie/$imdbId/$imdbId?autoPlay=true"
                MediaType.TV ->
                    "stremio:///detail/series/$imdbId"
            }
        }

        val query = listOf(item.title, item.year)
            .filter(String::isNotBlank)
            .joinToString(" ")
        return "stremio:///search?search=${query.urlEncode()}"
    }

    private fun String.urlEncode(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private val IMDB_ID = Regex("""tt\d{5,12}""")
}
