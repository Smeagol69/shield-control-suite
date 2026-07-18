package dev.roesler.marquee.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackUiParserTest {
    @Test
    fun `prime overlay captures title episode and elapsed plus remaining duration`() {
        val result = PlaybackUiParser.parse(
            packageName = "com.amazon.amazonvideo.livingroom",
            rawLabels = listOf(
                "Silo",
                "S1 E6 The Relic",
                "In scene",
                "Cast",
                "36:32",
                "15:53",
                "Episodes",
            ),
            currentPositionMs = 2_191_857L,
        )

        assertEquals("Silo", result.title)
        assertEquals("S1 E6 The Relic", result.episodeLabel)
        assertEquals(3_145_000L, result.durationMs)
    }

    @Test
    fun `generic overlay treats larger clock as total duration`() {
        val result = PlaybackUiParser.parse(
            packageName = "com.netflix.ninja",
            rawLabels = listOf("Example Movie", "12:03", "1:42:00", "Pause"),
            currentPositionMs = 723_400L,
        )

        assertEquals("Example Movie", result.title)
        assertNull(result.episodeLabel)
        assertEquals(6_120_000L, result.durationMs)
    }

    @Test
    fun `provider chrome is not mistaken for a title`() {
        val result = PlaybackUiParser.parse(
            packageName = "com.amazon.amazonvideo.livingroom",
            rawLabels = listOf("Prime Video", "Silo", "S1 E6 The Relic", "36:32", "15:53"),
            currentPositionMs = 2_191_857L,
        )

        assertEquals("Silo", result.title)
        assertEquals("S1 E6 The Relic", result.episodeLabel)
    }

    @Test
    fun `zero elapsed clock still permits duration inference`() {
        val result = PlaybackUiParser.parse(
            packageName = "com.netflix.ninja",
            rawLabels = listOf("Example Movie", "00:00", "1:42:00"),
            currentPositionMs = 0L,
        )

        assertEquals(6_120_000L, result.durationMs)
    }

    @Test
    fun `invalid clock values are ignored`() {
        val result = PlaybackUiParser.parse(
            packageName = "com.netflix.ninja",
            rawLabels = listOf("Example Movie", "12:99", "1:80:00"),
            currentPositionMs = 12_000L,
        )

        assertNull(result.durationMs)
    }

    @Test
    fun `controls alone do not create an identity`() {
        val result = PlaybackUiParser.parse(
            packageName = "com.wbd.stream",
            rawLabels = listOf("Play", "Back", "Settings", "00:30"),
            currentPositionMs = 30_000L,
        )

        assertNull(result.title)
        assertNull(result.episodeLabel)
        assertNull(result.durationMs)
    }
}
