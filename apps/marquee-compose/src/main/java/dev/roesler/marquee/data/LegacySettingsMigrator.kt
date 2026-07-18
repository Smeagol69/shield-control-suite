package dev.roesler.marquee.data

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicBoolean

class LegacySettingsMigrator(
    private val activity: Activity,
    private val settingsStore: SettingsStore,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val finished = AtomicBoolean(false)
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun run(onComplete: () -> Unit) {
        if (!settingsStore.shouldAttemptLegacyMigration()) {
            onComplete()
            return
        }

        val migrationView = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowContentAccess = false
            settings.allowFileAccess = true
            settings.blockNetworkLoads = true
            addJavascriptInterface(MigrationBridge(onComplete), BRIDGE_NAME)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = request.url.toString() != MIGRATION_URL

                override fun onPageFinished(view: WebView, url: String) {
                    if (url != MIGRATION_URL) {
                        complete(onComplete)
                        return
                    }
                    view.evaluateJavascript(
                        """
                        $BRIDGE_NAME.importSettings(
                            localStorage.getItem('tmdb_key') || '',
                            localStorage.getItem('region') || 'US'
                        );
                        """.trimIndent(),
                        null,
                    )
                }
            }
        }
        webView = migrationView
        migrationView.loadUrl(MIGRATION_URL)
        mainHandler.postDelayed({ complete(onComplete) }, TIMEOUT_MS)
    }

    private inner class MigrationBridge(private val onComplete: () -> Unit) {
        @JavascriptInterface
        fun importSettings(credential: String?, region: String?) {
            mainHandler.post {
                val safeCredential = credential.orEmpty().trim()
                if (safeCredential.isNotBlank()) {
                    val current = settingsStore.load()
                    settingsStore.save(
                        current.copy(
                            tmdbCredential = safeCredential,
                            region = region.orEmpty()
                                .trim()
                                .uppercase()
                                .takeIf { REGION.matches(it) }
                                ?: current.region,
                        ),
                    )
                }
                complete(onComplete)
            }
        }
    }

    private fun complete(onComplete: () -> Unit) {
        if (!finished.compareAndSet(false, true)) return
        mainHandler.removeCallbacksAndMessages(null)
        settingsStore.markLegacyMigrationAttempted()
        webView?.apply {
            stopLoading()
            removeJavascriptInterface(BRIDGE_NAME)
            destroy()
        }
        webView = null
        onComplete()
    }

    companion object {
        private const val MIGRATION_URL = "file:///android_asset/www/index.html"
        private const val BRIDGE_NAME = "NativeMigration"
        private const val TIMEOUT_MS = 2_500L
        private val REGION = Regex("^[A-Z]{2}$")
    }
}
