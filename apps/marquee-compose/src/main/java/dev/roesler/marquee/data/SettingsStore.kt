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
    )

    fun save(settings: MarqueeSettings) {
        preferences.edit {
            putString(KEY_TMDB, settings.tmdbCredential.trim())
            putString(KEY_REGION, settings.region.trim().uppercase())
            putString(KEY_RESOLVER, settings.preferredResolverPackage.trim())
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

    companion object {
        private const val PREFERENCES = "marquee_settings"
        private const val KEY_TMDB = "tmdb_credential"
        private const val KEY_REGION = "region"
        private const val KEY_RESOLVER = "preferred_resolver"
        private const val KEY_LEGACY_MIGRATION_ATTEMPTED = "legacy_migration_attempted"
    }
}
