package dev.roesler.marquee.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_800_000_000_000L
private const val HOUR_MS = 60L * 60L * 1_000L

class WatchHistoryTest {
    @Test
    fun `the first observation of a title is stored as it arrived`() {
        val entry = watched(1, progress = 12.0)

        assertSame(entry, mergeWatchedTitle(null, entry))
    }

    @Test
    fun `merging keeps the earliest first watch and the latest last watch`() {
        val first = watched(1, at = NOW - 10 * HOUR_MS, progress = 20.0)
        val second = watched(1, at = NOW, progress = 55.0)

        val merged = mergeWatchedTitle(first, second)

        assertEquals(NOW - 10 * HOUR_MS, merged.firstWatchedAtEpochMillis)
        assertEquals(NOW, merged.lastWatchedAtEpochMillis)
        assertEquals(55.0, checkNotNull(merged.progressPercent), 1e-9)
    }

    @Test
    fun `a later sitting counts as another viewing`() {
        val first = watched(1, at = NOW - 8 * HOUR_MS, completed = true)
        val second = watched(1, at = NOW, completed = true)

        assertEquals(2, mergeWatchedTitle(first, second).playCount)
    }

    @Test
    fun `repeated reports inside one sitting stay a single viewing`() {
        val start = watched(1, at = NOW - 60_000L, progress = 40.0)
        val later = watched(1, at = NOW, progress = 41.0)

        assertEquals(1, mergeWatchedTitle(start, later).playCount)
    }

    @Test
    fun `finishing a title sticks even if a later report is only partial`() {
        val finished = watched(1, at = NOW - 60_000L, completed = true)
        val partial = watched(1, at = NOW, progress = 4.0)

        val merged = mergeWatchedTitle(finished, partial)

        assertTrue(merged.completed)
        assertEquals(100.0, checkNotNull(merged.progressPercent), 1e-9)
    }

    @Test
    fun `an observation without genres inherits the ones already known`() {
        val known = watched(1, genres = listOf(878))
        val bare = watched(1, at = NOW + 1_000L, genres = emptyList())

        assertEquals(listOf(878), mergeWatchedTitle(known, bare).item.genreIds)
    }

    @Test
    fun `an older report does not overwrite newer provider and episode labels`() {
        val newest = watched(1, at = NOW, provider = "Netflix", episode = "S02 E04")
        val stale = watched(1, at = NOW - HOUR_MS, provider = null, episode = null)

        val merged = mergeWatchedTitle(newest, stale)

        assertEquals("Netflix", merged.providerName)
        assertEquals("S02 E04", merged.episodeLabel)
    }

    @Test
    fun `a new title is always worth persisting`() {
        assertTrue(isWorthPersisting(null, watched(1)))
    }

    @Test
    fun `progress that moved less than a percent is not worth a write`() {
        val stored = watched(1, progress = 40.0)
        val merged = watched(1, at = NOW + 1_000L, progress = 40.4)

        assertFalse(isWorthPersisting(stored, mergeWatchedTitle(stored, merged)))
    }

    @Test
    fun `progress that moved a full percent is worth a write`() {
        val stored = watched(1, progress = 40.0)
        val merged = watched(1, at = NOW + 1_000L, progress = 41.5)

        assertTrue(isWorthPersisting(stored, mergeWatchedTitle(stored, merged)))
    }

    @Test
    fun `crossing the finish line is always worth a write`() {
        val stored = watched(1, progress = 99.6)
        val merged = watched(1, at = NOW + 1_000L, progress = 99.9, completed = true)

        assertTrue(isWorthPersisting(stored, mergeWatchedTitle(stored, merged)))
    }

    @Test
    fun `a second viewing is worth a write even at the same progress`() {
        val stored = watched(1, at = NOW - 8 * HOUR_MS, completed = true)
        val merged = mergeWatchedTitle(stored, watched(1, at = NOW, completed = true))

        assertTrue(isWorthPersisting(stored, merged))
    }

    @Test
    fun `trimming keeps the most recently watched titles`() {
        val entries = listOf(
            watched(1, at = NOW - 3 * HOUR_MS),
            watched(2, at = NOW),
            watched(3, at = NOW - HOUR_MS),
        )

        assertEquals(listOf(2, 3), trimWatchHistory(entries, 2).map { it.item.id })
    }

    @Test
    fun `stored history drops the live playback decorations`() {
        val live = media(1).copy(progressPercent = 42.0, contextLabel = "LIVE · 0:42")

        val stored = live.forHistory()

        assertNull(stored.progressPercent)
        assertNull(stored.contextLabel)
        assertEquals(live.id, stored.id)
        // Nothing to strip means nothing to copy.
        assertSame(stored, stored.forHistory())
    }

    @Test
    fun `the shelf caption describes provider, episode, and completion`() {
        val entry = watched(
            1,
            provider = "Netflix",
            episode = "S02 E04",
            completed = true,
        ).copy(playCount = 3)

        assertEquals("Netflix · S02 E04 · finished · watched 3 times", entry.summaryLabel())
    }

    private fun watched(
        id: Int,
        at: Long = NOW,
        progress: Double? = null,
        completed: Boolean = false,
        genres: List<Int> = emptyList(),
        provider: String? = null,
        episode: String? = null,
    ): WatchedTitle = WatchedTitle(
        item = media(id, genres),
        source = WatchSource.LOCAL_PLAYBACK,
        firstWatchedAtEpochMillis = at,
        lastWatchedAtEpochMillis = at,
        playCount = 1,
        completed = completed,
        progressPercent = if (completed) 100.0 else progress,
        episodeLabel = episode,
        providerName = provider,
    )

    private fun media(id: Int, genres: List<Int> = emptyList()): MediaItem = MediaItem(
        id = id,
        type = MediaType.MOVIE,
        title = "Title $id",
        year = "2024",
        posterUrl = "https://image.tmdb.org/t/p/w500/$id.jpg",
        backdropUrl = null,
        overview = "",
        rating = 7.0,
        genreIds = genres,
    )
}
