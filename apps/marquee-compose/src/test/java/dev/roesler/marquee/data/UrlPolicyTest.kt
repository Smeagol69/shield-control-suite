package dev.roesler.marquee.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {
    @Test
    fun acceptsExpectedTmdbHosts() {
        assertTrue(UrlPolicy.isTmdbImage("https://image.tmdb.org/t/p/w500/poster.jpg"))
        assertTrue(UrlPolicy.isTmdbWeb("https://www.themoviedb.org/movie/42/watch"))
        assertTrue(UrlPolicy.isTmdbWeb("https://themoviedb.org/tv/42/watch"))
    }

    @Test
    fun rejectsLookalikeAndInsecureHosts() {
        assertFalse(UrlPolicy.isTmdbImage("http://image.tmdb.org/poster.jpg"))
        assertFalse(UrlPolicy.isTmdbImage("https://image.tmdb.org.evil.example/poster.jpg"))
        assertFalse(UrlPolicy.isTmdbWeb("https://evilthemoviedb.org/movie/42"))
        assertFalse(UrlPolicy.isTmdbWeb("javascript:alert(1)"))
        assertFalse(UrlPolicy.isTmdbWeb("https://themoviedb.org@evil.example/movie/42"))
    }

    @Test
    fun allowsOnlyKnownMediaImageHosts() {
        assertTrue(UrlPolicy.isMediaImage("https://image.tmdb.org/t/p/w500/a.jpg"))
        assertTrue(UrlPolicy.isMediaImage("https://walter.trakt.tv/images/a.webp"))
        assertTrue(UrlPolicy.isMediaImage("https://walter-r2.trakt.tv/images/a.webp"))
        assertTrue(UrlPolicy.isMediaImage("https://static.tvmaze.com/uploads/images/a.jpg"))
        assertFalse(UrlPolicy.isMediaImage("https://trakt.tv.example.com/a.jpg"))
        assertFalse(UrlPolicy.isMediaImage("https://evil.walter.trakt.tv/images/a.webp"))
        assertFalse(UrlPolicy.isMediaImage("http://walter.trakt.tv/images/a.webp"))
    }

    @Test
    fun canonicalizesTraktImagePathsWithoutWeakeningTheAllowlist() {
        assertTrue(
            UrlPolicy.canonicalMediaImage("walter-r2.trakt.tv/images/a.webp") ==
                "https://walter-r2.trakt.tv/images/a.webp",
        )
        assertTrue(
            UrlPolicy.canonicalMediaImage("//image.tmdb.org/t/p/w500/a.jpg") ==
                "https://image.tmdb.org/t/p/w500/a.jpg",
        )
        assertFalse(UrlPolicy.canonicalMediaImage("evil.example/images/a.webp") != null)
        assertFalse(
            UrlPolicy.canonicalMediaImage("http://walter.trakt.tv/images/a.webp") != null,
        )
    }

    @Test
    fun servicePagesStayOnAllowlistedDomains() {
        assertTrue(UrlPolicy.isTrustedServiceWeb("https://trakt.tv/activate/ABC"))
        assertTrue(UrlPolicy.isTrustedServiceWeb("https://www.tvmaze.com"))
        assertFalse(UrlPolicy.isTrustedServiceWeb("https://tvmaze.com.example.org"))
    }
}
