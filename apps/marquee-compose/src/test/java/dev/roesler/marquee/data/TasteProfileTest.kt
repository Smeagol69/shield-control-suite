package dev.roesler.marquee.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SCIFI = 878
private const val DRAMA = 18
private const val HORROR = 27
private const val DAY_MS = 24L * 60L * 60L * 1_000L
private const val NOW = 1_800_000_000_000L

class TasteProfileTest {
    @Test
    fun `an unrated profile leaves a row exactly as the source returned it`() {
        val row = listOf(media(1), media(2, genres = listOf(SCIFI)), media(3))

        assertSame(row, buildTasteProfile(emptyList(), NOW).rank(row))
    }

    @Test
    fun `one or two ratings are not enough to reorder a catalog row`() {
        val profile = profileOf(
            verdict(media(90, genres = listOf(SCIFI)), Verdict.LIKED),
            verdict(media(91, genres = listOf(SCIFI)), Verdict.LIKED),
        )
        val row = listOf(media(1), media(2), media(3, genres = listOf(SCIFI)))

        assertEquals(row, profile.rank(row))
    }

    @Test
    fun `a liked genre lifts matching titles up the row without re-sorting it`() {
        val profile = profileOf(
            *Array(12) { verdict(media(100 + it, genres = listOf(SCIFI)), Verdict.LIKED) },
        )
        val row = List(8) { index ->
            media(index, genres = if (index == 5) listOf(SCIFI) else listOf(DRAMA))
        }

        val ranked = profile.rank(row)

        val movedTo = ranked.indexOfFirst { it.id == 5 }
        assertTrue("expected the sci-fi title to move up from index 5, got $movedTo", movedTo < 5)
        // The row still resembles what the source sent: the rest keeps its relative order.
        assertEquals(
            row.filter { it.id != 5 }.map(MediaItem::id),
            ranked.filter { it.id != 5 }.map(MediaItem::id),
        )
    }

    @Test
    fun `a disliked title never appears in a ranked row`() {
        val unwanted = media(7, genres = listOf(HORROR))
        val profile = profileOf(verdict(unwanted, Verdict.DISLIKED))

        assertFalse(profile.rank(listOf(media(1), unwanted, media(2))).contains(unwanted))
        assertFalse(profile.rankByAffinity(listOf(unwanted, media(1))).contains(unwanted))
    }

    @Test
    fun `disliking a genre pushes it below an unrated one`() {
        val profile = profileOf(
            *Array(6) { verdict(media(200 + it, genres = listOf(HORROR)), Verdict.DISLIKED) },
        )

        val ranked = profile.rankByAffinity(
            listOf(media(1, genres = listOf(HORROR)), media(2, genres = listOf(DRAMA))),
        )

        assertEquals(listOf(2, 1), ranked.map(MediaItem::id))
    }

    @Test
    fun `affinity ranking puts the strongest genre match first regardless of source order`() {
        val profile = profileOf(
            *Array(5) { verdict(media(300 + it, genres = listOf(SCIFI)), Verdict.LIKED) },
        )
        val pool = listOf(
            media(1, genres = listOf(DRAMA)),
            media(2, genres = listOf(DRAMA)),
            media(3, genres = listOf(SCIFI)),
        )

        assertEquals(3, profile.rankByAffinity(pool).first().id)
    }

    @Test
    fun `a title already watched sinks below an equivalent one that is not`() {
        val profile = profileOf(
            *Array(5) { verdict(media(400 + it, genres = listOf(SCIFI)), Verdict.LIKED) },
        )
        val seen = media(1, genres = listOf(SCIFI))
        val fresh = media(2, genres = listOf(SCIFI))

        val ranked = profile.rankByAffinity(listOf(seen, fresh), watchedKeys = setOf(seen.key))

        assertEquals(listOf(2, 1), ranked.map(MediaItem::id))
    }

    @Test
    fun `an old rating counts for less than a fresh one`() {
        val fresh = profileOf(verdict(media(1, genres = listOf(SCIFI)), Verdict.LIKED))
        val stale = profileOf(
            verdict(
                media(1, genres = listOf(SCIFI)),
                Verdict.LIKED,
                ratedAt = NOW - 360 * DAY_MS,
            ),
        )

        assertTrue(fresh.likedWeight > stale.likedWeight)
        // Two half-lives back, so about a quarter of the influence is left.
        assertEquals(0.25, stale.likedWeight, 0.02)
        assertTrue(
            checkNotNull(fresh.genreWeights[SCIFI]) >
                checkNotNull(stale.genreWeights[SCIFI]),
        )
    }

    @Test
    fun `confidence rises with evidence and never reaches certainty`() {
        assertEquals(0.0, buildTasteProfile(emptyList(), NOW).confidence, 1e-9)
        assertEquals(
            0.5,
            profileOf(*Array(6) { verdict(media(it), Verdict.LIKED) }).confidence,
            1e-6,
        )
        val many = profileOf(*Array(200) { verdict(media(it), Verdict.LIKED) })
        assertTrue(many.confidence > 0.9)
        assertTrue(many.confidence < 1.0)
    }

    @Test
    fun `a six-genre verdict does not outvote a single-genre one`() {
        val broad = profileOf(
            verdict(
                media(1, genres = listOf(SCIFI, DRAMA, HORROR, 12, 14, 35)),
                Verdict.LIKED,
            ),
        )
        val focused = profileOf(verdict(media(2, genres = listOf(SCIFI)), Verdict.LIKED))

        assertTrue(
            checkNotNull(focused.genreWeights[SCIFI]) >
                checkNotNull(broad.genreWeights[SCIFI]),
        )
    }

    @Test
    fun `liked years set the era the profile prefers`() {
        val profile = profileOf(
            verdict(media(1, year = "1998"), Verdict.LIKED),
            verdict(media(2, year = "2002"), Verdict.LIKED),
            // A dislike says nothing about which era you enjoy.
            verdict(media(3, year = "2024"), Verdict.DISLIKED),
        )

        assertEquals(2000.0, checkNotNull(profile.preferredYear), 0.01)
    }

    @Test
    fun `verdict lookup and top genres describe what the profile learned`() {
        val liked = media(1, genres = listOf(SCIFI))
        val disliked = media(2, genres = listOf(HORROR))
        val profile = profileOf(
            verdict(liked, Verdict.LIKED),
            verdict(disliked, Verdict.DISLIKED),
        )

        assertEquals(Verdict.LIKED, profile.verdictOf(liked))
        assertEquals(Verdict.DISLIKED, profile.verdictOf(disliked))
        assertEquals(2, profile.ratingCount)
        assertEquals(setOf(liked.key), profile.likedKeys)
        assertEquals(listOf(SCIFI), profile.topGenreIds())
    }

    private fun profileOf(vararg verdicts: TitleVerdict): TasteProfile =
        buildTasteProfile(verdicts.toList(), NOW)

    private fun verdict(
        item: MediaItem,
        verdict: Verdict,
        ratedAt: Long = NOW,
    ): TitleVerdict = TitleVerdict(item, verdict, ratedAt)

    private fun media(
        id: Int,
        genres: List<Int> = emptyList(),
        rating: Double = 7.0,
        year: String = "2024",
        type: MediaType = MediaType.MOVIE,
    ): MediaItem = MediaItem(
        id = id,
        type = type,
        title = "Title $id",
        year = year,
        posterUrl = "https://image.tmdb.org/t/p/w500/$id.jpg",
        backdropUrl = null,
        overview = "",
        rating = rating,
        genreIds = genres,
    )
}
