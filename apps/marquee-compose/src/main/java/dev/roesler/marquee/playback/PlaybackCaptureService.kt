package dev.roesler.marquee.playback

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

class PlaybackCaptureService : AccessibilityService() {
    private val store by lazy { PlaybackStore(applicationContext) }
    private var lastCaptureAtElapsed = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (!PlaybackPackages.isTracked(packageName)) return
        val current = store.current()
            ?.takeIf { it.packageName == packageName }
            ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastCaptureAtElapsed < CAPTURE_THROTTLE_MS) return
        lastCaptureAtElapsed = now

        val labels = linkedSetOf<String>()
        rootInActiveWindow?.let { labels += collectLabels(it) }
        event.source?.let { labels += collectLabels(it) }
        if (labels.isEmpty()) return
        val identity = PlaybackUiParser.parse(
            packageName = packageName,
            rawLabels = labels.toList(),
            currentPositionMs = current.positionAt(System.currentTimeMillis()),
        )
        if (
            identity.title != null ||
            identity.episodeLabel != null ||
            identity.durationMs != null
        ) {
            store.captureIdentity(
                packageName = packageName,
                title = identity.title,
                episodeLabel = identity.episodeLabel,
                durationMs = identity.durationMs,
                allowMediaReplacement = true,
            )
        }
    }

    override fun onInterrupt() = Unit

    private fun collectLabels(root: AccessibilityNodeInfo): List<String> {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val labels = linkedSetOf<String>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES && labels.size < MAX_LABELS) {
            val node = queue.removeFirst()
            visited += 1
            node.text?.toString()?.trim()?.takeIf(String::isNotBlank)?.let(labels::add)
            node.contentDescription?.toString()?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let(labels::add)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return labels.toList()
    }

    companion object {
        private const val CAPTURE_THROTTLE_MS = 750L
        private const val MAX_NODES = 300
        private const val MAX_LABELS = 64
    }
}
