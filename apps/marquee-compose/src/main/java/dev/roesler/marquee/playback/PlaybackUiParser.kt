package dev.roesler.marquee.playback

import kotlin.math.abs

data class PlaybackUiIdentity(
    val title: String?,
    val episodeLabel: String?,
    val durationMs: Long?,
)

object PlaybackUiParser {
    fun parse(
        packageName: String,
        rawLabels: List<String>,
        currentPositionMs: Long,
    ): PlaybackUiIdentity {
        val labels = rawLabels.asSequence()
            .map(String::trim)
            .filter { it.length in 1..160 }
            .distinct()
            .take(MAX_LABELS)
            .toList()
        val episode = labels.firstOrNull(::looksLikeEpisode)
        val times = labels.mapNotNull(::parseTime)
            .distinct()
        val title = if (times.isNotEmpty() || episode != null) {
            labels.firstOrNull { label ->
                label != episode &&
                    !looksLikeTime(label) &&
                    !looksLikeEpisode(label) &&
                    !isIgnored(packageName, label)
            }
        } else {
            null
        }
        return PlaybackUiIdentity(
            title = title,
            episodeLabel = episode,
            durationMs = inferDuration(packageName, times, currentPositionMs),
        )
    }

    private fun inferDuration(
        packageName: String,
        times: List<Long>,
        currentPositionMs: Long,
    ): Long? {
        if (times.size < 2) return null
        val elapsed = times.minByOrNull { abs(it - currentPositionMs) } ?: return null
        val others = times.filter { it != elapsed }
        val candidates = when {
            packageName in ELAPSED_AND_REMAINING_PACKAGES ->
                others.map { elapsed + it }
            else ->
                others.filter { it >= elapsed } +
                    others.filter { it < elapsed }.map { elapsed + it }
        }
        return candidates
            .filter { it > currentPositionMs && it <= MAX_DURATION_MS }
            .minOrNull()
    }

    private fun parseTime(value: String): Long? {
        val match = TIME.matchEntire(value) ?: return null
        val parts = match.groupValues.drop(1).filter(String::isNotBlank).map(String::toLong)
        val seconds = when (parts.size) {
            2 -> {
                val (minutes, seconds) = parts
                if (seconds >= 60L) return null
                minutes * 60L + seconds
            }
            3 -> {
                val (hours, minutes, seconds) = parts
                if (minutes >= 60L || seconds >= 60L) return null
                hours * 3_600L + minutes * 60L + seconds
            }
            else -> return null
        }
        return seconds.times(1_000L)
    }

    private fun looksLikeTime(value: String): Boolean = TIME.matches(value)

    private fun looksLikeEpisode(value: String): Boolean =
        EPISODE.containsMatchIn(value) || SEASON_EPISODE.containsMatchIn(value)

    private fun isIgnored(packageName: String, value: String): Boolean {
        val normalized = value.lowercase()
        return PlaybackPackages.isProviderLabel(packageName, value) ||
            normalized in IGNORED ||
            normalized.endsWith("%") ||
            normalized.all { it.isDigit() || it.isWhitespace() || it in "/.-" }
    }

    private val TIME = Regex("""(?:(\d{1,2}):)?(\d{1,2}):(\d{2})""")
    private val EPISODE = Regex("""(?i)\bS\d{1,2}\s*E\d{1,3}\b""")
    private val SEASON_EPISODE =
        Regex("""(?i)\bSeason\s+\d{1,2}\b.*\bEpisode\s+\d{1,3}\b""")
    private const val MAX_LABELS = 64
    private const val MAX_DURATION_MS = 12 * 60 * 60 * 1_000L

    private val ELAPSED_AND_REMAINING_PACKAGES = setOf(
        "com.amazon.amazonvideo.livingroom",
        "com.amazon.amazonvideo.livingroom.nvidia",
    )

    private val IGNORED = setOf(
        "back",
        "cast",
        "close",
        "episodes",
        "forward",
        "in scene",
        "more",
        "next",
        "pause",
        "play",
        "previous",
        "replay",
        "rewind",
        "settings",
        "skip intro",
        "subtitles",
    )
}
