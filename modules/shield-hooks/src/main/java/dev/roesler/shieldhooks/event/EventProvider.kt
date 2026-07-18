package dev.roesler.shieldhooks.event

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import dev.roesler.shieldhooks.ShieldHooksApp
import dev.roesler.shieldhooks.script.ScriptRuntime
import dev.roesler.shieldhooks.storage.AuditStore
import java.util.concurrent.ConcurrentHashMap

class EventProvider : ContentProvider() {
    private val rateLimits = ConcurrentHashMap<Int, RateWindow>()

    override fun onCreate(): Boolean = context != null

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method != METHOD_REPORT_EVENT) return response(false, "Unsupported operation.")

        val appContext = context?.applicationContext
            ?: return response(false, "Provider unavailable.")
        val callingUid = Binder.getCallingUid()
        if (!consumeRateLimit(callingUid)) return response(false, "Rate limit exceeded.")

        val packageName = extras?.getString(KEY_PACKAGE).orEmpty().trim()
        val className = extras?.getString(KEY_CLASS).orEmpty().trim()
        val eventType = extras?.getString(KEY_EVENT).orEmpty().trim()
        val ownedPackages = appContext.packageManager.getPackagesForUid(callingUid)?.toSet().orEmpty()
        val attributedPackage = runCatching { callingPackage }.getOrNull()

        if (packageName !in ownedPackages || attributedPackage != packageName) {
            AuditStore.append(appContext, "WARN", "provider", "Rejected UID/package mismatch.")
            return response(false, "Caller identity mismatch.")
        }
        if (!ShieldHooksApp.isApprovedPackage(packageName)) {
            AuditStore.append(appContext, "WARN", "provider", "Rejected event outside approved scope.")
            return response(false, "Package is outside the approved hook scope.")
        }
        if (eventType !in EventSnapshot.allowedTypes) {
            return response(false, "Unsupported event type.")
        }
        if (!VALID_CLASS_NAME.matches(className) || className.length > MAX_CLASS_LENGTH) {
            return response(false, "Invalid activity class.")
        }

        val event = EventSnapshot(
            type = eventType,
            packageName = packageName,
            className = className,
            timestamp = System.currentTimeMillis(),
        )
        AuditStore.append(
            appContext,
            "EVENT",
            packageName,
            "$eventType · $className",
        )
        ScriptRuntime.submit(event)
        return response(true, "Accepted.")
    }

    private fun consumeRateLimit(uid: Int): Boolean {
        val now = SystemClock.elapsedRealtime()
        val window = rateLimits.compute(uid) { _, current ->
            if (current == null || now - current.startedAt >= WINDOW_MS) {
                RateWindow(now, 1)
            } else {
                current.copy(count = current.count + 1)
            }
        } ?: return false
        return window.count <= MAX_EVENTS_PER_WINDOW
    }

    private fun response(accepted: Boolean, message: String) = Bundle().apply {
        putBoolean(KEY_ACCEPTED, accepted)
        putString(KEY_MESSAGE, message)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private data class RateWindow(val startedAt: Long, val count: Int)

    companion object {
        const val AUTHORITY = "dev.roesler.shieldhooks.events"
        const val METHOD_REPORT_EVENT = "report_event"
        const val KEY_EVENT = "event"
        const val KEY_PACKAGE = "package"
        const val KEY_CLASS = "class"
        const val KEY_ACCEPTED = "accepted"
        const val KEY_MESSAGE = "message"

        private const val WINDOW_MS = 1_000L
        private const val MAX_EVENTS_PER_WINDOW = 24
        private const val MAX_CLASS_LENGTH = 240
        private val VALID_CLASS_NAME = Regex("""^[A-Za-z_$][A-Za-z0-9_$.]*$""")
    }
}
