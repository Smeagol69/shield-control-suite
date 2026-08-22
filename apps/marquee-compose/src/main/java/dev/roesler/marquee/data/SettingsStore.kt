package dev.roesler.marquee.data

import android.content.Context
import androidx.core.content.edit

class SettingsStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): MarqueeSettings = MarqueeSettings(
        tmdbCredential = preferences.getString(KEY_TMDB, "").orEmpty(),
        region = preferences.getString(KEY_REGION, "US").orEmpty().ifBlank { "US" },
        preferredResolverPackage = preferences
            .getString(KEY_RESOLVER, "com.stremio.one")
            .orEmpty(),
        traktClientId = preferences.getString(KEY_TRAKT_CLIENT_ID, "").orEmpty(),
        traktClientSecret = preferences.getString(KEY_TRAKT_CLIENT_SECRET, "").orEmpty(),
        traktRedirectUri = preferences
            .getString(KEY_TRAKT_REDIRECT_URI, DEFAULT_TRAKT_REDIRECT_URI)
            .orEmpty()
            .ifBlank { DEFAULT_TRAKT_REDIRECT_URI },
        personalizedRanking = preferences.getBoolean(KEY_PERSONALIZED_RANKING, true),
        ratingPrompts = preferences.getBoolean(KEY_RATING_PROMPTS, true),
    )

    fun save(settings: MarqueeSettings) {
        preferences.edit {
            putString(KEY_TMDB, settings.tmdbCredential.trim())
            putString(KEY_REGION, settings.region.trim().uppercase())
            putString(KEY_RESOLVER, settings.preferredResolverPackage.trim())
            putString(KEY_TRAKT_CLIENT_ID, settings.traktClientId.trim())
            putString(KEY_TRAKT_CLIENT_SECRET, settings.traktClientSecret.trim())
            putString(
                KEY_TRAKT_REDIRECT_URI,
                settings.traktRedirectUri.trim().ifBlank { DEFAULT_TRAKT_REDIRECT_URI },
            )
            putBoolean(KEY_PERSONALIZED_RANKING, settings.personalizedRanking)
            putBoolean(KEY_RATING_PROMPTS, settings.ratingPrompts)
        }
    }

    fun shouldAttemptLegacyMigration(): Boolean =
        preferences.getString(KEY_TMDB, "").isNullOrBlank() &&
            !preferences.getBoolean(KEY_LEGACY_MIGRATION_ATTEMPTED, false)

    fun markLegacyMigrationAttempted() {
        preferences.edit {
            putBoolean(KEY_LEGACY_MIGRATION_ATTEMPTED, true)
        }
    }

    fun lastProviderId(): Int = preferences.getInt(KEY_LAST_PROVIDER_ID, 0)

    fun saveLastProviderId(providerId: Int) {
        if (providerId <= 0) return
        preferences.edit { putInt(KEY_LAST_PROVIDER_ID, providerId) }
    }

    companion object {
        private const val PREFERENCES = "marquee_settings"
        private const val KEY_TMDB = "tmdb_credential"
        private const val KEY_REGION = "region"
        private const val KEY_RESOLVER = "preferred_resolver"
        private const val KEY_TRAKT_CLIENT_ID = "trakt_client_id"
        private const val KEY_TRAKT_CLIENT_SECRET = "trakt_client_secret"
        private const val KEY_TRAKT_REDIRECT_URI = "trakt_redirect_uri"
        private const val KEY_LEGACY_MIGRATION_ATTEMPTED = "legacy_migration_attempted"
        private const val KEY_LAST_PROVIDER_ID = "last_provider_id"
        private const val KEY_PERSONALIZED_RANKING = "personalized_ranking"
        private const val KEY_RATING_PROMPTS = "rating_prompts"
    }
}
