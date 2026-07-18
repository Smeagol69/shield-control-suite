package dev.roesler.shieldhooks.script

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.roesler.shieldhooks.storage.AuditStore

class ScriptApi internal constructor(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    fun log(message: String?) {
        AuditStore.append(appContext, "SCRIPT", "log", sanitize(message))
    }

    fun toast(message: String?) {
        val safeMessage = sanitize(message)
        AuditStore.append(appContext, "SCRIPT", "toast", safeMessage)
        mainHandler.post {
            Toast.makeText(appContext, safeMessage, Toast.LENGTH_SHORT).show()
        }
    }

    fun tag(name: String?, value: String?) {
        val safeName = sanitize(name).take(48).ifBlank { "tag" }
        AuditStore.append(appContext, "TAG", safeName, sanitize(value))
    }

    fun contains(value: String?, fragment: String?): Boolean =
        value.orEmpty().contains(fragment.orEmpty(), ignoreCase = true)

    fun startsWith(value: String?, prefix: String?): Boolean =
        value.orEmpty().startsWith(prefix.orEmpty(), ignoreCase = true)

    fun equalsText(left: String?, right: String?): Boolean =
        left.orEmpty().equals(right.orEmpty(), ignoreCase = true)

    private fun sanitize(value: String?): String =
        value.orEmpty()
            .replace('\r', ' ')
            .replace('\n', ' ')
            .take(320)
            .ifBlank { "(empty)" }
}
