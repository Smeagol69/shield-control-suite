package dev.roesler.shieldhooks.storage

import android.content.Context
import androidx.core.content.edit

data class ScriptSettings(
    val enabled: Boolean,
    val source: String,
)

object ScriptStore {
    const val DEFAULT_SCRIPT = """if (api.equalsText(eventType, "activity.resumed")) {
    api.log("Opened " + packageName + " / " + className);
}"""

    private const val PREFS = "shield_hooks_script"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SOURCE = "source"

    fun load(context: Context): ScriptSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ScriptSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            source = prefs.getString(KEY_SOURCE, DEFAULT_SCRIPT) ?: DEFAULT_SCRIPT,
        )
    }

    fun save(context: Context, settings: ScriptSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_ENABLED, settings.enabled)
                putString(KEY_SOURCE, settings.source)
            }
    }
}
