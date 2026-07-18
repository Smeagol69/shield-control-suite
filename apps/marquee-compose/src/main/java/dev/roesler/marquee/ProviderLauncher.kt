package dev.roesler.marquee

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import dev.roesler.marquee.data.MediaItem
import dev.roesler.marquee.data.UrlPolicy
import dev.roesler.marquee.data.WatchProvider

class ProviderLauncher(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    fun isInstalled(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return runCatching {
            packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }

    fun openProvider(provider: WatchProvider): LaunchResult {
        val packageName = provider.packageName
            ?: return LaunchResult.Unavailable("${provider.name} has no Android TV package mapping.")
        if (!isInstalled(packageName)) {
            return LaunchResult.Unavailable("${provider.name} is not installed on this Shield.")
        }
        return launchPackage(packageName, provider.name)
    }

    fun openResolver(packageName: String, item: MediaItem): LaunchResult {
        if (packageName.isBlank()) {
            return LaunchResult.Unavailable("Choose a preferred resolver in Settings.")
        }
        if (!isInstalled(packageName)) {
            return LaunchResult.Unavailable("$packageName is not installed on this Shield.")
        }

        val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage(packageName)
            putExtra(SearchManager.QUERY, item.title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (searchIntent.resolveActivity(packageManager) != null) {
            return start(searchIntent, "Searching ${item.title}")
        }
        return launchPackage(packageName, packageName)
    }

    fun openWebOptions(link: String?): LaunchResult {
        if (!UrlPolicy.isTmdbWeb(link)) {
            return LaunchResult.Unavailable("TMDB did not return a provider page.")
        }
        return start(
            Intent(Intent.ACTION_VIEW, checkNotNull(link).toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            "Opening provider options",
        )
    }

    private fun launchPackage(packageName: String, label: String): LaunchResult {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                setPackage(packageName)
            }.takeIf { it.resolveActivity(packageManager) != null }
            ?: return LaunchResult.Unavailable("$label cannot be launched.")

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(intent, "Opening $label")
    }

    private fun start(intent: Intent, message: String): LaunchResult =
        try {
            appContext.startActivity(intent)
            LaunchResult.Launched(message)
        } catch (_: ActivityNotFoundException) {
            LaunchResult.Unavailable("No compatible Android TV activity was found.")
        } catch (error: SecurityException) {
            LaunchResult.Unavailable(error.message ?: "Android blocked that launch request.")
        }
}

sealed interface LaunchResult {
    val message: String

    data class Launched(override val message: String) : LaunchResult
    data class Unavailable(override val message: String) : LaunchResult
}
