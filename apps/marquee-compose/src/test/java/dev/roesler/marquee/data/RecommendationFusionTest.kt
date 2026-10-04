package dev.roesler.marquee.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationFusionTest {
    @Test
    fun `agreement across seeds beats a single first place`() {
        val single = title(1)
        val shared = title(2)

        val fused = reciprocalRankFusion(
            listOf(
                WeightedRecommendationList(listOf(single, shared)),
                WeightedRecommendationList(listOf(shared, title(3))),
            ),
        )

        assertEquals(shared.key, fused.first().item.key)
        assertEquals(2, fused.first().sourceCount)
    }

    @Test
    fun `newer weighted seed contributes more short term context`() {
        val recent = title(1)
        val older = title(2)

        val fused = reciprocalRankFusion(
            listOf(
                WeightedRecommendationList(listOf(recent), weight = 1.0),
                WeightedRecommendationList(listOf(older), weight = 0.5),
            ),
        )

        assertEquals(recent.key, fused.first().item.key)
        assertTrue(fused.first().score > fused.last().score)
    }

    @Test
    fun `duplicates inside one endpoint vote only once`() {
        val repeated = title(1)

        val fused = reciprocalRankFusion(
            listOf(WeightedRecommendationList(listOf(repeated, repeated, repeated))),
        )

        assertEquals(1, fused.size)
        assertEquals(1, fused.single().sourceCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rank constant must be positive`() {
        reciprocalRankFusion(emptyList(), rankConstant = 0.0)
    }

    private fun title(id: Int) = MediaItem(
        id = id,
        type = MediaType.MOVIE,
        title = "Title $id",
        year = "2020",
        posterUrl = null,
        backdropUrl = null,
        overview = "",
        rating = 7.0,
    )
}
