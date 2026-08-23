package dev.roesler.marquee.data

import android.content.Context
import androidx.core.content.edit

data class TraktTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
)

class TraktStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun loadTokens(): TraktTokens? {
        val accessToken = preferences.getString(KEY_ACCESS_TOKEN, "").orEmpty()
        val refreshToken = preferences.getString(KEY_REFRESH_TOKEN, "").orEmpty()
        if (accessToken.isBlank() || refreshToken.isBlank()) return null
        return TraktTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochSeconds = preferences.getLong(KEY_EXPIRES_AT, 0L),
        )
    }

    fun saveTokens(tokens: TraktTokens) {
        preferences.edit {
            putString(KEY_ACCESS_TOKEN, tokens.accessToken)
            putString(KEY_REFRESH_TOKEN, tokens.refreshToken)
            putLong(KEY_EXPIRES_AT, tokens.expiresAtEpochSeconds)
        }
    }

    fun accountName(): String? =
        preferences.getString(KEY_ACCOUNT_NAME, null)?.takeIf(String::isNotBlank)

    fun saveAccountName(name: String) {
        preferences.edit { putString(KEY_ACCOUNT_NAME, name.trim().take(120)) }
    }

    /** Whether local history/ratings have been pushed up once since connecting. */
    fun hasBackfilled(): Boolean = preferences.getBoolean(KEY_BACKFILLED, false)

    fun markBackfilled() {
        preferences.edit { putBoolean(KEY_BACKFILLED, true) }
    }

    /** Whether the account's full history has been paged down once since connecting. */
    fun hasDeepImported(): Boolean = preferences.getBoolean(KEY_DEEP_IMPORTED, false)

    fun markDeepImported() {
        preferences.edit { putBoolean(KEY_DEEP_IMPORTED, true) }
    }

    fun clear() {
        preferences.edit { clear() }
    }

    companion object {
        private const val PREFERENCES = "marquee_trakt_session"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_ACCOUNT_NAME = "account_name"
        private const val KEY_BACKFILLED = "history_backfilled"
        private const val KEY_DEEP_IMPORTED = "history_deep_imported"
    }
}
