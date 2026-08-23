package dev.roesler.marquee.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Holds the learned model between sessions.
 *
 * Training replays the whole signal history, so the stored weights are strictly a cache — losing
 * them costs one retrain, never the viewer's taste. That is deliberate: the durable record is
 * the ratings and watch history, and the model is a derived artefact that can be rebuilt or
 * re-shaped whenever the feature space changes.
 */
class TasteModelStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Volatile
    private var cached: TasteModel? = null

    @Synchronized
    fun load(): TasteModel {
        cached?.let { return it }
        val raw = preferences.getString(KEY_MODEL, null)
        val model = if (raw.isNullOrBlank()) {
            TasteModel()
        } else {
            runCatching { TasteModel.fromJson(JSONObject(raw)) }.getOrDefault(TasteModel())
        }
        cached = model
        return model
    }

    @Synchronized
    fun save(model: TasteModel) {
        cached = model
        preferences.edit { putString(KEY_MODEL, model.toJson().toString()) }
    }

    /** Whether enough has changed since the last fit to be worth retraining. */
    fun isStale(signalCount: Int, now: Long = System.currentTimeMillis()): Boolean {
        val model = load()
        if (!model.trained) return true
        if (signalCount != model.observations) return true
        return now - model.trainedAtEpochMillis >= RETRAIN_INTERVAL_MS
    }

    @Synchronized
    fun clear() {
        cached = null
        preferences.edit { clear() }
    }

    companion object {
        private const val PREFERENCES = "marquee_taste_model"
        private const val KEY_MODEL = "model"

        /**
         * Even with no new signals the model is refitted daily so recency decay keeps moving —
         * taste that has gone quiet should fade on its own rather than waiting for a new rating.
         */
        private const val RETRAIN_INTERVAL_MS = 24L * 60L * 60L * 1_000L
    }
}
