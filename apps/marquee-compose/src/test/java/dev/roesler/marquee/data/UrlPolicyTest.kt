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
}
