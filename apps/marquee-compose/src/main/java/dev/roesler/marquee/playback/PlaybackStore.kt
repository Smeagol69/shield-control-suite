package dev.roesler.marquee.playback

import android.content.Context
import android.media.session.PlaybackState
import androidx.core.content.edit
import dev.roesler.marquee.data.MediaItem
import dev.roesler.marquee.data.forHistory
import dev.roesler.marquee.data.key
import dev.roesler.marquee.data.optNullableString
import dev.roesler.marquee.data.parseJsonArray
import dev.roesler.marquee.data.toJson
import dev.roesler.marquee.data.toJsonArrayString
import dev.roesler.marquee.data.toMediaItemOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private const val MIN_HISTORY_POSITION_MS = 15_000L
private const val MIN_COMPLETION_TAIL_MS = 30_000L
private const val MAX_COMPLETION_TAIL_MS = 3 * 60 * 1_000L
private const val COMPLETION_TAIL_PERCENT = 5

/** Watch this much of something before Marquee will ask whether you liked it. */
private const val MIN_RATEABLE_POSITION_MS = 10 * 60 * 1_000L

/** A session that has not reported in this long is treated as over, not merely paused. */
private const val SESSION_STALE_MS = 3 * 60 * 1_000L

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
            media?.key
                ?: capturedTitle?.lowercase(Locale.ROOT)
                ?: "unknown",
            episodeLabel.orEmpty().lowercase(Locale.ROOT),
        ).joinToString("|")

    /** Identity used for rating prompts and watch history: the title, not the episode. */
    val titleKey: String
        get() = media?.key ?: identityKey

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

    fun progressPercentAt(epochMillis: Long): Double? =
        durationMs
            ?.takeIf { it > 0L }
            ?.let { positionAt(epochMillis).toDouble() * 100.0 / it.toDouble() }
            ?.coerceIn(0.0, 100.0)

    fun isContinuable(epochMillis: Long = System.currentTimeMillis()): Boolean {
        val position = positionAt(epochMillis)
        if (position < MIN_HISTORY_POSITION_MS) return false
        val duration = durationMs?.takeIf { it > 0L } ?: return true
        return position < duration - completionTail(duration)
    }

    /** True once playback has run past the credits threshold for a known duration. */
    fun isCompleted(epochMillis: Long = System.currentTimeMillis()): Boolean {
        val duration = durationMs?.takeIf { it > 0L } ?: return false
        val position = positionAt(epochMillis)
        return position >= MIN_HISTORY_POSITION_MS &&
            position >= duration - completionTail(duration)
    }

    /**
     * True when this viewing is over and long enough to have an opinion about: either it ran to
     * the end, or the session stopped or went silent after a substantial stretch of playback.
     */
    fun isRateable(epochMillis: Long = System.currentTimeMillis()): Boolean {
        if (media == null) return false
        if (isCompleted(epochMillis)) return true
        val ended = !active || epochMillis - observedAtEpochMillis >= SESSION_STALE_MS
        return ended && positionAt(epochMillis) >= MIN_RATEABLE_POSITION_MS
    }

    private fun completionTail(duration: Long): Long = minOf(
        MAX_COMPLETION_TAIL_MS,
        maxOf(MIN_COMPLETION_TAIL_MS, duration * COMPLETION_TAIL_PERCENT / 100L),
    )
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
                    .put("media", item.forHistory().toJson())
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

    fun attachMedia(record: PlaybackRecord, item: MediaItem): Unit = synchronized(LOCK) {
        val current = currentUnlocked()
        if (current?.identityKey == record.identityKey) {
            saveCurrentUnlocked(current.copy(media = item))
        }
        val history = historyUnlocked()
        if (history.none { it.identityKey == record.identityKey }) return
        saveHistoryUnlocked(
            history.map {
                if (it.identityKey == record.identityKey) it.copy(media = item) else it
            },
        )
    }

    fun current(): PlaybackRecord? =
        synchronized(LOCK) { currentUnlocked() }

    fun history(): List<PlaybackRecord> =
        synchronized(LOCK) { historyUnlocked() }

    /**
     * Viewings that have finished since the last call and have never been offered for rating.
     * Each title is returned once; skipping a prompt is an answer, so it is not repeated.
     */
    fun consumeRateablePlayback(
        epochMillis: Long = System.currentTimeMillis(),
    ): List<PlaybackRecord> = synchronized(LOCK) {
        val seen = promptedKeysUnlocked()
        val finished = buildList {
            currentUnlocked()?.let(::add)
            addAll(historyUnlocked())
        }
            .asSequence()
            .filter { it.isRateable(epochMillis) }
            .filter { it.titleKey !in seen }
            .distinctBy(PlaybackRecord::titleKey)
            .take(MAX_PROMPTS_PER_SWEEP)
            .toList()
        if (finished.isEmpty()) return emptyList()
        savePromptedKeysUnlocked(seen + finished.map(PlaybackRecord::titleKey))
        finished
    }

    /** Marks a title as already asked about, so an explicit rating suppresses the prompt. */
    fun markRatingHandled(titleKey: String): Unit = synchronized(LOCK) {
        val seen = promptedKeysUnlocked()
        if (titleKey in seen) return
        savePromptedKeysUnlocked(seen + titleKey)
    }

    private fun currentUnlocked(): PlaybackRecord? {
        if (!currentLoaded) {
            cachedCurrent = preferences.getString(KEY_CURRENT, null)
                ?.let { raw -> runCatching { JSONObject(raw).toPlaybackRecord() }.getOrNull() }
            currentLoaded = true
        }
        return cachedCurrent
    }

    private fun historyUnlocked(): List<PlaybackRecord> {
        cachedHistory?.let { return it }
        return parseJsonArray(preferences.getString(KEY_HISTORY, null)) { it.toPlaybackRecord() }
            .sortedByDescending(PlaybackRecord::observedAtEpochMillis)
            .also { cachedHistory = it }
    }

    private fun promptedKeysUnlocked(): Set<String> {
        cachedPrompted?.let { return it }
        val raw = preferences.getString(KEY_PROMPTED, null)
        val parsed = runCatching {
            val array = JSONArray(raw.orEmpty())
            buildSet(array.length()) {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.getOrDefault(emptySet())
        return parsed.also { cachedPrompted = it }
    }

    private fun savePromptedKeysUnlocked(keys: Collection<String>) {
        val bounded = keys.toList().takeLast(MAX_PROMPTED_KEYS)
        cachedPrompted = bounded.toSet()
        val serialized = JSONArray().also { array -> bounded.forEach { array.put(it) } }
        preferences.edit { putString(KEY_PROMPTED, serialized.toString()) }
    }

    private fun saveCurrentUnlocked(record: PlaybackRecord) {
        cachedCurrent = record
        currentLoaded = true
        preferences.edit { putString(KEY_CURRENT, record.toJson().toString()) }
    }

    private fun upsertHistoryUnlocked(record: PlaybackRecord) {
        val records = historyUnlocked().toMutableList()
        records.removeAll { it.identityKey == record.identityKey }
        records.add(0, record)
        saveHistoryUnlocked(records.take(MAX_HISTORY_ITEMS))
    }

    private fun saveHistoryUnlocked(records: List<PlaybackRecord>) {
        cachedHistory = records
        preferences.edit { putString(KEY_HISTORY, records.toJsonArrayString { it.toJson() }) }
    }

    private fun loadArmedUnlocked(): ArmedPlayback? =
        preferences.getString(KEY_ARMED, null)?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                ArmedPlayback(
                    packageName = json.getString("package"),
                    providerName = json.getString("provider"),
                    media = json.getJSONObject("media").toMediaItemOrNull() ?: return@runCatching null,
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
        private const val KEY_PROMPTED = "rating_prompted"
        private const val KEY_IDENTITY_GUARD_UNTIL = "identity_guard_until"
        private const val MAX_HISTORY_ITEMS = 100
        private const val MAX_PROMPTED_KEYS = 240
        private const val MAX_PROMPTS_PER_SWEEP = 3
        private const val ARMED_MAX_AGE_MS = 4 * 60 * 60 * 1_000L
        private const val IDENTITY_REPLACEMENT_GRACE_MS = 30_000L
        private const val MIN_IDENTITY_TITLE_LENGTH = 2
        private val LOCK = Any()
        private val NON_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]+""")
        private val MULTIPLE_SPACES = Regex("""\s+""")

        // The notification listener, the accessibility service, and the UI each construct a
        // store, but they share one process. Caching here rather than per instance keeps every
        // reader on the same view of the data and keeps the 1 Hz UI sweep off the JSON parser.
        private var cachedCurrent: PlaybackRecord? = null
        private var currentLoaded = false
        private var cachedHistory: List<PlaybackRecord>? = null
        private var cachedPrompted: Set<String>? = null
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

private fun JSONObject.toPlaybackRecord(): PlaybackRecord? {
    val packageName = optNullableString("package") ?: return null
    return PlaybackRecord(
        packageName = packageName,
        providerName = optString("provider")
            .ifBlank { PlaybackPackages.providerName(packageName) },
        media = optJSONObject("media")?.toMediaItemOrNull(),
        capturedTitle = optNullableString("captured_title"),
        episodeLabel = optNullableString("episode"),
        positionMs = optLong("position_ms").coerceAtLeast(0L),
        durationMs = optLong("duration_ms").takeIf { it > 0L },
        state = optInt("state"),
        speed = optDouble("speed").toFloat().takeIf(Float::isFinite) ?: 0f,
        observedAtEpochMillis = optLong("observed_at"),
    )
}

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
