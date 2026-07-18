package dev.roesler.marquee.playback

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRecordTest {
    @Test
    fun `playing position advances from the last observed sample`() {
        val record = record(
            state = PlaybackState.STATE_PLAYING,
            positionMs = 30_000L,
            durationMs = 60_000L,
            observedAtEpochMillis = 100_000L,
        )

        assertEquals(40_000L, record.positionAt(110_000L))
    }

    @Test
    fun `extrapolated position never runs past duration`() {
        val record = record(
            state = PlaybackState.STATE_PLAYING,
            positionMs = 58_000L,
            durationMs = 60_000L,
            observedAtEpochMillis = 100_000L,
        )

        assertEquals(60_000L, record.positionAt(110_000L))
    }

    @Test
    fun `paused position stays fixed`() {
        val record = record(
            state = PlaybackState.STATE_PAUSED,
            positionMs = 30_000L,
            durationMs = 60_000L,
            observedAtEpochMillis = 100_000L,
        )

        assertEquals(30_000L, record.positionAt(110_000L))
    }

    @Test
    fun `continue row excludes barely started and effectively completed items`() {
        assertFalse(record(positionMs = 10_000L, durationMs = 3_600_000L).isContinuable(0L))
        assertTrue(record(positionMs = 1_800_000L, durationMs = 3_600_000L).isContinuable(0L))
        assertFalse(record(positionMs = 3_450_000L, durationMs = 3_600_000L).isContinuable(0L))
    }

    private fun record(
        state: Int = PlaybackState.STATE_PAUSED,
        positionMs: Long,
        durationMs: Long?,
        observedAtEpochMillis: Long = 0L,
    ) = PlaybackRecord(
        packageName = "com.netflix.ninja",
        providerName = "Netflix",
        media = null,
        capturedTitle = "Example",
        episodeLabel = null,
        positionMs = positionMs,
        durationMs = durationMs,
        state = state,
        speed = 1f,
        observedAtEpochMillis = observedAtEpochMillis,
    )
}
