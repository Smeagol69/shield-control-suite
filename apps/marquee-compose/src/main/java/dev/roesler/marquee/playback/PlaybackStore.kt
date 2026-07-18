package dev.roesler.marquee.playback

import android.content.Context
import android.media.session.PlaybackState
import androidx.core.content.edit
import dev.roesler.marquee.data.MediaItem
import dev.roesler.marquee.data.MediaType
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private const val MIN_HISTORY_POSITION_MS = 15_000L
private const val MIN_COMPLETION_TAIL_MS = 30_000L
private const val MAX_COMPLETION_TAIL_MS = 3 * 60 * 1_000L
private const val COMPLETION_TAIL_PERCENT = 5

data class PlaybackRecord(
    val packageName: String,
    val providerName: String,
    val media: MediaItem?,
    val capturedTitle: String?,
    val episodeLabel: String?,
    val positionMs: Long,
    val durationMs: Long?,
    val state: Int,
    val speed: Float,
    val observedAtEpochMillis: Long,
) {
    val active: Boolean
        get() = state in setOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_BUFFERING,
        )

    val identityKey: String
        get() = listOf(
            packageName,
            media?.let { "${it.type.apiName}:${it.id}" }
                ?: capturedTitle?.lowercase(Locale.ROOT)
                ?: "unknown",
            episodeLabel.orEmpty().lowercase(Locale.ROOT),
        ).joinToString("|")

    fun asMediaItem(): MediaItem? {
        val base = media ?: return null
        val effectivePositionMs = positionAt(System.currentTimeMillis())
        val progress = durationMs
            ?.takeIf { it > 0L }
            ?.let { effectivePositionMs.toDouble() * 100.0 / it.toDouble() }
            ?.coerceIn(0.0, 100.0)
            ?: base.progressPercent
        val stateLabel = when (state) {
            PlaybackState.STATE_PLAYING -> "LIVE"
            PlaybackState.STATE_PAUSED -> "Paused"
            PlaybackState.STATE_BUFFERING -> "Buffering"
            else -> null
        }
        val timeLabel = buildString {
            append(formatElapsed(effectivePositionMs))
            durationMs?.takeIf { it > 0L }?.let {
                append(" / ")
                append(formatElapsed(it))
            }
        }
        return base.copy(
            progressPercent = progress,
            contextLabel = listOfNotNull(stateLabel, episodeLabel, timeLabel)
                .joinToString(" · "),
        )
    }

    fun positionAt(epochMillis: Long): Long {
        val estimated = if (state != PlaybackState.STATE_PLAYING || speed <= 0f) {
            positionMs
        } else {
            val elapsed = (epochMillis - observedAtEpochMillis).coerceAtLeast(0L)
            positionMs + (elapsed.toDouble() * speed.toDouble()).toLong()
        }
        return durationMs
            ?.takeIf { it > 0L }
            ?.let(estimated::coerceAtMost)
            ?: estimated
    }

    fun isContinuable(epochMillis: Long = System.currentTimeMillis()): Boolean {
        val position = positionAt(epochMillis)
        if (position < MIN_HISTORY_POSITION_MS) return false
        val duration = durationMs?.takeIf { it > 0L } ?: return true
        val completionTail = minOf(
            MAX_COMPLETION_TAIL_MS,
            maxOf(MIN_COMPLETION_TAIL_MS, duration * COMPLETION_TAIL_PERCENT / 100L),
        )
        return position < duration - completionTail
    }
}

class PlaybackStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun arm(
        packageName: String,
        providerName: String,
        item: MediaItem,
    ): Unit = synchronized(LOCK) {
        if (!PlaybackPackages.isTracked(packageName)) return
        preferences.edit {
            putString(
                KEY_ARMED,
                JSONObject()
                    .put("package", packageName)
                    .put("provider", providerName)
                    .put("armed_at", System.currentTimeMillis())
                    .put("media", item.toJson())
                    .toString(),
            )
        }
    }

    fun updateSession(
        packageName: String,
        state: Int,
        positionMs: Long,
        durationMs: Long?,
        speed: Float,
        metadataTitle: String?,
        metadataSubtitle: String?,
    ): Unit = synchronized(LOCK) {
        if (!PlaybackPackages.isTracked(packageName)) return
        val now = System.currentTimeMillis()
        val previous = currentUnlocked()?.takeIf { it.packageName == packageName }
        val armed = loadArmedUnlocked()
            ?.takeIf {
                it.packageName == packageName &&
                    now - it.armedAtEpochMillis <= ARMED_MAX_AGE_MS
            }
        val applyArmed = armed != null &&
            (previous == null || armed.armedAtEpochMillis >= previous.observedAtEpochMillis)
        val record = PlaybackRecord(
            packageName = packageName,
            providerName = armed?.providerName.takeIf { applyArmed }
                ?: previous?.providerName
                ?: PlaybackPackages.providerName(packageName),
            media = armed?.media.takeIf { applyArmed } ?: previous?.media,
            capturedTitle = armed?.media?.title.takeIf { applyArmed }
                ?: metadataTitle?.cleanLabel()
                ?: previous?.capturedTitle
                ?: armed?.media?.title,
            episodeLabel = if (applyArmed) {
                null
            } else {
                metadataSubtitle?.cleanLabel() ?: previous?.episodeLabel
            },
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = if (applyArmed) {
                null
            } else {
                durationMs?.takeIf { it > 0L } ?: previous?.durationMs
            },
            state = state,
            speed = speed.takeIf(Float::isFinite) ?: 0f,
            observedAtEpochMillis = now,
        )
        saveCurrentUnlocked(record)
        if (applyArmed) {
            preferences.edit {
                remove(KEY_ARMED)
                putLong(KEY_IDENTITY_GUARD_UNTIL, now + IDENTITY_REPLACEMENT_GRACE_MS)
            }
        }
        if (record.hasIdentity() && record.positionMs >= MIN_HISTORY_POSITION_MS) {
            upsertHistoryUnlocked(record)
        }
    }

    fun captureIdentity(
        packageName: String,
        title: String?,
        episodeLabel: String?,
        durationMs: Long?,
        allowMediaReplacement: Boolean = false,
    ): Unit = synchronized(LOCK) {
        val current = currentUnlocked()
            ?.takeIf { it.packageName == packageName && it.active }
            ?: return
        val now = System.currentTimeMillis()
        val capturedTitle = title?.cleanLabel()
        val replaceMedia = allowMediaReplacement &&
            now >= preferences.getLong(KEY_IDENTITY_GUARD_UNTIL, 0L) &&
            capturedTitle.isDifferentTitleFrom(current)
        val updated = current.copy(
            media = current.media.takeUnless { replaceMedia },
            capturedTitle = capturedTitle ?: current.capturedTitle,
            episodeLabel = episodeLabel?.cleanLabel()
                ?: current.episodeLabel.takeUnless { replaceMedia },
            durationMs = durationMs?.takeIf { it > 0L }
                ?: current.durationMs.takeUnless { replaceMedia },
            positionMs = current.positionAt(now),
            observedAtEpochMillis = now,
        )
        saveCurrentUnlocked(updated)
        if (updated.hasIdentity() && updated.positionMs >= MIN_HISTORY_POSITION_MS) {
            upsertHistoryUnlocked(updated)
        }
    }

    fun attachMedia(record: PlaybackRecord, item: MediaItem) = synchronized(LOCK) {
        val current = currentUnlocked()
        if (current?.identityKey == record.identityKey) {
            saveCurrentUnlocked(current.copy(media = item))
        }
        val history = historyUnlocked().map {
            if (it.identityKey == record.identityKey) it.copy(media = item) else it
        }
        saveHistoryUnlocked(history)
    }

    fun current(): PlaybackRecord? =
        synchronized(LOCK) { currentUnlocked() }

    fun history(): List<PlaybackRecord> =
        synchronized(LOCK) { historyUnlocked() }

    private fun currentUnlocked(): PlaybackRecord? =
        preferences.getString(KEY_CURRENT, null)
            ?.let { raw -> runCatching { JSONObject(raw).toPlaybackRecord() }.getOrNull() }

    private fun historyUnlocked(): List<PlaybackRecord> {
        val raw = preferences.getString(KEY_HISTORY, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.toPlaybackRecord()?.let(::add)
                }
            }.sortedByDescending(PlaybackRecord::observedAtEpochMillis)
        }.getOrDefault(emptyList())
    }

    private fun saveCurrentUnlocked(record: PlaybackRecord) {
        preferences.edit { putString(KEY_CURRENT, record.toJson().toString()) }
    }

    private fun upsertHistoryUnlocked(record: PlaybackRecord) {
        val records = historyUnlocked().toMutableList()
        records.removeAll { it.identityKey == record.identityKey }
        records.add(0, record)
        saveHistoryUnlocked(records.take(MAX_HISTORY_ITEMS))
    }

    private fun saveHistoryUnlocked(records: List<PlaybackRecord>) {
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        preferences.edit { putString(KEY_HISTORY, array.toString()) }
    }

    private fun loadArmedUnlocked(): ArmedPlayback? =
        preferences.getString(KEY_ARMED, null)?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                ArmedPlayback(
                    packageName = json.getString("package"),
                    providerName = json.getString("provider"),
                    media = json.getJSONObject("media").toMediaItem(),
                    armedAtEpochMillis = json.getLong("armed_at"),
                )
            }.getOrNull()
        }

    private fun PlaybackRecord.hasIdentity(): Boolean =
        media != null || !capturedTitle.isNullOrBlank()

    private fun String?.isDifferentTitleFrom(record: PlaybackRecord): Boolean {
        val candidate = this?.normalizedTitle() ?: return false
        val knownTitles = listOfNotNull(record.media?.title, record.capturedTitle)
            .mapNotNull { it.normalizedTitle() }
        return knownTitles.isNotEmpty() && knownTitles.none { it == candidate }
    }

    private fun String.normalizedTitle(): String? =
        lowercase(Locale.ROOT)
            .replace(NON_ALPHANUMERIC, " ")
            .trim()
            .replace(MULTIPLE_SPACES, " ")
            .takeIf { it.length >= MIN_IDENTITY_TITLE_LENGTH }

    private data class ArmedPlayback(
        val packageName: String,
        val providerName: String,
        val media: MediaItem,
        val armedAtEpochMillis: Long,
    )

    companion object {
        private const val PREFERENCES = "marquee_playback"
        private const val KEY_ARMED = "armed"
        private const val KEY_CURRENT = "current"
        private const val KEY_HISTORY = "history"
        private const val KEY_IDENTITY_GUARD_UNTIL = "identity_guard_until"
        private const val MAX_HISTORY_ITEMS = 100
        private const val ARMED_MAX_AGE_MS = 4 * 60 * 60 * 1_000L
        private const val IDENTITY_REPLACEMENT_GRACE_MS = 30_000L
        private const val MIN_IDENTITY_TITLE_LENGTH = 2
        private val LOCK = Any()
        private val NON_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]+""")
        private val MULTIPLE_SPACES = Regex("""\s+""")
    }
}

private fun PlaybackRecord.toJson(): JSONObject =
    JSONObject()
        .put("package", packageName)
        .put("provider", providerName)
        .put("media", media?.toJson())
        .put("captured_title", capturedTitle)
        .put("episode", episodeLabel)
        .put("position_ms", positionMs)
        .put("duration_ms", durationMs)
        .put("state", state)
        .put("speed", speed.toDouble())
        .put("observed_at", observedAtEpochMillis)

private fun JSONObject.toPlaybackRecord(): PlaybackRecord =
    PlaybackRecord(
        packageName = getString("package"),
        providerName = optString("provider")
            .ifBlank { PlaybackPackages.providerName(getString("package")) },
        media = optJSONObject("media")?.toMediaItem(),
        capturedTitle = optNullableString("captured_title"),
        episodeLabel = optNullableString("episode"),
        positionMs = optLong("position_ms").coerceAtLeast(0L),
        durationMs = optLong("duration_ms").takeIf { it > 0L },
        state = optInt("state"),
        speed = optDouble("speed").toFloat().takeIf(Float::isFinite) ?: 0f,
        observedAtEpochMillis = optLong("observed_at"),
    )

private fun MediaItem.toJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("type", type.apiName)
        .put("title", title)
        .put("year", year)
        .put("poster", posterUrl)
        .put("backdrop", backdropUrl)
        .put("overview", overview)
        .put("rating", rating)
        .put("imdb", imdbId)

private fun JSONObject.toMediaItem(): MediaItem =
    MediaItem(
        id = getInt("id"),
        type = MediaType.from(optString("type")),
        title = getString("title"),
        year = optString("year"),
        posterUrl = optNullableString("poster"),
        backdropUrl = optNullableString("backdrop"),
        overview = optString("overview"),
        rating = optDouble("rating"),
        imdbId = optNullableString("imdb"),
    )

private fun JSONObject.optNullableString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

private fun String.cleanLabel(): String? =
    trim().takeIf { it.length in 1..160 }

private fun formatElapsed(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
    } else {
        "%d:%02d".format(Locale.ROOT, minutes, seconds)
    }
}
