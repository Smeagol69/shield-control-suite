package dev.roesler.marquee.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class WatchSource(val storageName: String, val label: String) {
    LOCAL_PLAYBACK("local", "This Shield"),
    TRAKT("trakt", "Trakt"),
    MANUAL("manual", "Marked watched"),
    ;

    companion object {
        fun from(value: String?): WatchSource =
            entries.firstOrNull { it.storageName == value } ?: LOCAL_PLAYBACK
    }
}

/** One title the viewer has watched, folded across every session and every source. */
data class WatchedTitle(
    val item: MediaItem,
    val source: WatchSource,
    val firstWatchedAtEpochMillis: Long,
    val lastWatchedAtEpochMillis: Long,
    val playCount: Int,
    val completed: Boolean,
    val progressPercent: Double?,
    val episodeLabel: String? = null,
    val providerName: String? = null,
) {
    val key: String
        get() = item.key

    /** Shelf caption such as `Netflix · S02 E04 · finished · watched twice`. */
    fun summaryLabel(): String = listOfNotNull(
        providerName,
        episodeLabel,
        if (completed) "finished" else progressPercent?.let { "${it.toInt()}% watched" },
        "watched $playCount times".takeIf { playCount > 1 },
    ).joinToString(" · ")
}

/**
 * Two observations of the same title separated by at least this long count as separate
 * viewings. Anything closer is the same sitting reported again by the playback bridge.
 */
const val NEW_VIEWING_GAP_MS = 6L * 60L * 60L * 1_000L

/** Progress moves smaller than this are not worth a rewrite of the history file. */
private const val PROGRESS_WRITE_EPSILON = 1.0

/** Folds a new observation into what is already known about a title. */
fun mergeWatchedTitle(existing: WatchedTitle?, incoming: WatchedTitle): WatchedTitle {
    if (existing == null) return incoming
    val newerIsIncoming =
        incoming.lastWatchedAtEpochMillis >= existing.lastWatchedAtEpochMillis
    val newViewing =
        incoming.lastWatchedAtEpochMillis - existing.lastWatchedAtEpochMillis >= NEW_VIEWING_GAP_MS
    val completed = existing.completed || incoming.completed
    return WatchedTitle(
        // Keep whichever record carries real metadata; a locally resolved title beats a stub.
        item = if (newerIsIncoming && incoming.item.posterUrl != null) {
            incoming.item.copy(
                genreIds = incoming.item.genreIds.ifEmpty { existing.item.genreIds },
            )
        } else {
            existing.item
        },
        source = if (newerIsIncoming) incoming.source else existing.source,
        firstWatchedAtEpochMillis = min(
            existing.firstWatchedAtEpochMillis,
            incoming.firstWatchedAtEpochMillis,
        ),
        lastWatchedAtEpochMillis = max(
            existing.lastWatchedAtEpochMillis,
            incoming.lastWatchedAtEpochMillis,
        ),
        playCount = if (newViewing) {
            existing.playCount + incoming.playCount
        } else {
            max(existing.playCount, incoming.playCount)
        },
        completed = completed,
        progressPercent = when {
            completed -> 100.0
            newerIsIncoming -> incoming.progressPercent ?: existing.progressPercent
            else -> existing.progressPercent ?: incoming.progressPercent
        },
        episodeLabel = if (newerIsIncoming) {
            incoming.episodeLabel ?: existing.episodeLabel
        } else {
            existing.episodeLabel ?: incoming.episodeLabel
        },
        providerName = if (newerIsIncoming) {
            incoming.providerName ?: existing.providerName
        } else {
            existing.providerName ?: incoming.providerName
        },
    )
}

/**
 * Whether a merged entry differs enough from the stored one to be worth persisting. The
 * playback bridge reports the active title every second, and rewriting the history file at
 * that rate would burn storage for progress moves nobody can see.
 */
fun isWorthPersisting(stored: WatchedTitle?, merged: WatchedTitle): Boolean {
    if (stored == null) return true
    if (stored.completed != merged.completed) return true
    if (stored.playCount != merged.playCount) return true
    if (stored.item != merged.item) return true
    if (stored.episodeLabel != merged.episodeLabel) return true
    if (stored.providerName != merged.providerName) return true
    val storedProgress = stored.progressPercent
    val mergedProgress = merged.progressPercent
    if (storedProgress == null || mergedProgress == null) {
        return storedProgress != mergedProgress
    }
    return abs(storedProgress - mergedProgress) >= PROGRESS_WRITE_EPSILON
}

/** Newest first, capped, so the store stays a bounded working set rather than a ledger. */
fun trimWatchHistory(entries: Collection<WatchedTitle>, maxEntries: Int): List<WatchedTitle> =
    entries
        .sortedByDescending(WatchedTitle::lastWatchedAtEpochMillis)
        .take(maxEntries)

/** Strips the live playback decorations so stored history never shows a stale progress bar. */
fun MediaItem.forHistory(): MediaItem =
    if (progressPercent == null && contextLabel == null) {
        this
    } else {
        copy(progressPercent = null, contextLabel = null)
    }
