package dev.roesler.marquee.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Durable like/dislike history plus the [TasteProfile] derived from it.
 *
 * Verdicts are held in a keyed index so the detail screen can ask "did I rate this?" without
 * re-parsing the store, and the profile is rebuilt only when a rating changes or its decay has
 * had time to matter.
 */
class TasteStore(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private var index: LinkedHashMap<String, TitleVerdict>? = null
    private var profile: TasteProfile? = null
    private var profileBuiltAtMillis = 0L

    @Synchronized
    fun verdicts(): List<TitleVerdict> = index().values.toList()

    @Synchronized
    fun verdictOf(item: MediaItem): Verdict? = index()[item.key]?.verdict

    @Synchronized
    fun profile(): TasteProfile {
        val cached = profile
        if (cached != null && now() - profileBuiltAtMillis < PROFILE_TTL_MS) return cached
        return buildTasteProfile(index().values, now()).also {
            profile = it
            profileBuiltAtMillis = now()
        }
    }

    /**
     * Applies a rating. Choosing the verdict a title already carries clears it, so the same
     * remote button both sets and undoes an opinion. Returns the verdict now in force.
     */
    @Synchronized
    fun rate(item: MediaItem, verdict: Verdict): Verdict? {
        val entries = index()
        val key = item.key
        val existing = entries[key]
        if (existing?.verdict == verdict) {
            entries.remove(key)
        } else {
            entries.remove(key)
            entries[key] = TitleVerdict(
                item = item.forHistory(),
                verdict = verdict,
                ratedAtEpochMillis = now(),
            )
        }
        persist(entries)
        return entries[key]?.verdict
    }

    /** Backfills genre ids once a title's details arrive, without disturbing its rating age. */
    @Synchronized
    fun enrich(item: MediaItem) {
        if (item.genreIds.isEmpty()) return
        val entries = index()
        val existing = entries[item.key] ?: return
        if (existing.item.genreIds == item.genreIds) return
        entries[item.key] = existing.copy(
            item = existing.item.copy(genreIds = item.genreIds),
        )
        persist(entries)
    }

    /** Most recently liked titles, newest first, used to seed `Because you liked …` rows. */
    @Synchronized
    fun recentlyLiked(limit: Int): List<MediaItem> =
        index().values
            .asSequence()
            .filter { it.verdict == Verdict.LIKED }
            .sortedByDescending(TitleVerdict::ratedAtEpochMillis)
            .take(limit)
            .map(TitleVerdict::item)
            .toList()

    @Synchronized
    fun clear() {
        index = LinkedHashMap()
        profile = null
        profileBuiltAtMillis = 0L
        preferences.edit { clear() }
    }

    private fun index(): LinkedHashMap<String, TitleVerdict> =
        index ?: load().also { index = it }

    private fun load(): LinkedHashMap<String, TitleVerdict> {
        val parsed = parseJsonArray(preferences.getString(KEY_VERDICTS, null)) { entry ->
            val item = entry.optJSONObject("item")?.toMediaItemOrNull() ?: return@parseJsonArray null
            val verdict = Verdict.from(entry.optString("verdict")) ?: return@parseJsonArray null
            TitleVerdict(
                item = item,
                verdict = verdict,
                ratedAtEpochMillis = entry.optLong("rated_at"),
            )
        }
        return LinkedHashMap<String, TitleVerdict>(parsed.size.coerceAtLeast(8)).apply {
            parsed.forEach { put(it.item.key, it) }
        }
    }

    private fun persist(entries: LinkedHashMap<String, TitleVerdict>) {
        if (entries.size > MAX_VERDICTS) {
            val keep = entries.values
                .sortedByDescending(TitleVerdict::ratedAtEpochMillis)
                .take(MAX_VERDICTS)
                .associateBy { it.item.key }
            entries.keys.retainAll(keep.keys)
        }
        profile = null
        profileBuiltAtMillis = 0L
        val serialized = entries.values.toJsonArrayString { verdict ->
            JSONObject()
                .put("item", verdict.item.toJson())
                .put("verdict", verdict.verdict.storageName)
                .put("rated_at", verdict.ratedAtEpochMillis)
        }
        preferences.edit { putString(KEY_VERDICTS, serialized) }
    }

    companion object {
        private const val PREFERENCES = "marquee_taste"
        private const val KEY_VERDICTS = "verdicts"
        private const val MAX_VERDICTS = 500
        private const val PROFILE_TTL_MS = 60L * 60L * 1_000L
    }
}
