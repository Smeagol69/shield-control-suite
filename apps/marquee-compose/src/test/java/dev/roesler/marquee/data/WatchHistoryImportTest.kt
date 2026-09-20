package dev.roesler.marquee.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Re-importing the same Trakt play must not look like a new viewing.
 *
 * History is re-imported on every home load. When those records were stamped with "now", any two
 * loads more than [NEW_VIEWING_GAP_MS] apart satisfied mergeWatchedTitle's new-viewing test and
 * incremented the play count, so a title merely sitting in recent history drifted up into
 * rewatch territory and the model learned a preference the viewer never expressed.
 */
class WatchHistoryImportTest {
    private val watchedAt = 1_700_000_000_000L

    private fun title(id: Int) = MediaItem(
        id = id,
        type = MediaType.MOVIE,
        title = "Title $id",
        year = "2020",
        posterUrl = null,
        backdropUrl = null,
        overview = "",
        rating = 7.0,
        genreIds = listOf(28),
    )

    private fun imported(id: Int, stamp: Long, plays: Int = 1) = WatchedTitle(
        item = title(id),
        source = WatchSource.TRAKT,
        firstWatchedAtEpochMillis = stamp,
        lastWatchedAtEpochMillis = stamp,
        playCount = plays,
        completed = true,
        progressPercent = 100.0,
    )

    @Test
    fun reimportingTheSamePlayKeepsThePlayCountAtOne() {
        var entry = imported(1, watchedAt)
        repeat(5) { entry = mergeWatchedTitle(entry, imported(1, watchedAt)) }
        assertEquals(1, entry.playCount)
    }

    @Test
    fun stampingImportsWithNowWouldHaveInflatedThePlayCount() {
        // Reproduces the old behaviour: each refresh a fresh "now", well past the 6h gap.
        var entry = imported(1, watchedAt)
        var clock = watchedAt
        repeat(4) {
            clock += NEW_VIEWING_GAP_MS + 1
            entry = mergeWatchedTitle(entry, imported(1, clock))
        }
        assertEquals(5, entry.playCount)
    }

    @Test
    fun agenuinelyLaterPlayStillCounts() {
        val first = imported(1, watchedAt)
        val later = imported(1, watchedAt + NEW_VIEWING_GAP_MS + 1)
        assertEquals(2, mergeWatchedTitle(first, later).playCount)
    }

    @Test
    fun authoritativePlayCountsSurviveAReimport() {
        // watchedLibrary() reports real counts; a later recent-history import must not lower them.
        val library = imported(1, watchedAt, plays = 4)
        val recent = imported(1, watchedAt, plays = 1)
        assertEquals(4, mergeWatchedTitle(library, recent).playCount)
    }
}
