package dev.roesler.marquee.playback

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class PlaybackMonitorService : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private val store by lazy { PlaybackStore(applicationContext) }
    private val sessionManager by lazy {
        getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }
    private val componentName by lazy {
        ComponentName(this, PlaybackMonitorService::class.java)
    }
    private var controllers: List<MediaController> = emptyList()

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            captureBestSession()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            captureBestSession()
        }

        override fun onSessionDestroyed() {
            refreshControllers()
        }
    }

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener(::replaceControllers)

    private val periodicCapture = object : Runnable {
        override fun run() {
            captureBestSession()
            handler.postDelayed(this, CORRECTION_INTERVAL_MS)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        sessionManager.addOnActiveSessionsChangedListener(
            sessionsChanged,
            componentName,
            handler,
        )
        refreshControllers()
        handler.removeCallbacks(periodicCapture)
        handler.post(periodicCapture)
    }

    override fun onListenerDisconnected() {
        stopMonitoring()
        requestRebind(componentName)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        stopMonitoring()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val packageName = sbn?.packageName ?: return
        if (!PlaybackPackages.isTracked(packageName)) return
        val extras = sbn.notification?.extras ?: return
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val rawSubtitle = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val providerTitle = PlaybackPackages.isProviderLabel(packageName, rawTitle)
        val title = if (providerTitle) rawSubtitle else rawTitle
        val subtitle = rawSubtitle.takeUnless { providerTitle || it == title }
        if (title.isNullOrBlank() && subtitle.isNullOrBlank()) return
        store.captureIdentity(packageName, title, subtitle, null)
    }

    private fun refreshControllers() {
        val active = runCatching {
            sessionManager.getActiveSessions(componentName)
        }.getOrDefault(emptyList())
        replaceControllers(active)
    }

    private fun replaceControllers(active: List<MediaController>?) {
        controllers.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        controllers = active.orEmpty()
            .filter { PlaybackPackages.isTracked(it.packageName) }
        controllers.forEach { it.registerCallback(controllerCallback, handler) }
        captureBestSession()
    }

    private fun captureBestSession() {
        val controller = controllers.minByOrNull { stateRank(it.playbackState?.state) } ?: return
        val state = controller.playbackState ?: return
        val metadata = controller.metadata
        val position = extrapolatedPosition(state)
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)
            ?.takeIf { it > 0L }
        val rawTitle = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val title = rawTitle.takeUnless {
            PlaybackPackages.isProviderLabel(controller.packageName, it)
        }
        val subtitle = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        store.updateSession(
            packageName = controller.packageName,
            state = state.state,
            positionMs = position,
            durationMs = duration,
            speed = state.playbackSpeed,
            metadataTitle = title,
            metadataSubtitle = subtitle,
        )
    }

    private fun extrapolatedPosition(state: PlaybackState): Long {
        val base = state.position.coerceAtLeast(0L)
        if (
            state.state != PlaybackState.STATE_PLAYING ||
            state.playbackSpeed <= 0f ||
            state.lastPositionUpdateTime <= 0L
        ) {
            return base
        }
        val elapsed = (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime)
            .coerceAtLeast(0L)
        return base + (elapsed.toDouble() * state.playbackSpeed.toDouble()).toLong()
    }

    private fun stopMonitoring() {
        handler.removeCallbacks(periodicCapture)
        runCatching {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsChanged)
        }
        controllers.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        controllers = emptyList()
    }

    private fun stateRank(state: Int?): Int =
        when (state) {
            PlaybackState.STATE_PLAYING -> 0
            PlaybackState.STATE_BUFFERING -> 1
            PlaybackState.STATE_PAUSED -> 2
            else -> INACTIVE_RANK
        }

    companion object {
        private const val CORRECTION_INTERVAL_MS = 5_000L
        private const val INACTIVE_RANK = 100

        fun requestConnection(context: Context) {
            requestRebind(
                ComponentName(context, PlaybackMonitorService::class.java),
            )
        }
    }
}
