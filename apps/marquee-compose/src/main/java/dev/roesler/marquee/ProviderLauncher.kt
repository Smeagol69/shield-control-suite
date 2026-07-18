package dev.roesler.marquee

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import dev.roesler.marquee.data.CatalogProvider
import dev.roesler.marquee.data.MediaItem
import dev.roesler.marquee.data.ResolverLinks
import dev.roesler.marquee.data.UrlPolicy
import dev.roesler.marquee.data.WatchProvider
import dev.roesler.marquee.playback.PlaybackStore

class ProviderLauncher(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val playbackStore = PlaybackStore(appContext)

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

    fun openCatalogProvider(provider: CatalogProvider): LaunchResult {
        val packageName = provider.packageName
            ?: return LaunchResult.Unavailable(
                "${provider.name} has no verified Android TV package mapping.",
            )
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

        if (packageName == STREMIO_PACKAGE) {
            val deepLink = Intent(Intent.ACTION_VIEW, ResolverLinks.stremio(item).toUri()).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (deepLink.resolveActivity(packageManager) != null) {
                val result = start(
                    deepLink,
                    "Opening ${item.title} in Stremio. Choose a source if autoplay has no prior selection.",
                )
                if (result is LaunchResult.Launched) {
                    playbackStore.arm(packageName, "Stremio", item)
                }
                return result
            }
        }

        val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage(packageName)
            putExtra(
                SearchManager.QUERY,
                listOf(item.title, item.year).filter(String::isNotBlank).joinToString(" "),
            )
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

    fun openServicePage(link: String, label: String): LaunchResult {
        if (!UrlPolicy.isTrustedServiceWeb(link)) {
            return LaunchResult.Unavailable("That service link is not allowed.")
        }
        return start(
            Intent(Intent.ACTION_VIEW, link.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            "Opening $label",
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

    companion object {
        private const val STREMIO_PACKAGE = "com.stremio.one"
    }
}

sealed interface LaunchResult {
    val message: String

    data class Launched(override val message: String) : LaunchResult
    data class Unavailable(override val message: String) : LaunchResult
}
