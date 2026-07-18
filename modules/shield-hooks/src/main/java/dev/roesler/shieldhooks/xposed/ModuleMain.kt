package dev.roesler.shieldhooks.xposed

import android.app.Activity
import android.os.Bundle
import android.util.Log
import androidx.core.net.toUri
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class ModuleMain : XposedModule() {
    private val lifecycleHooksInstalled = AtomicBoolean(false)
    private val eventDispatcher = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(32),
        { runnable -> Thread(runnable, "shield-hooks-events").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(
            Log.INFO,
            TAG,
            "Loaded in ${param.processName} via $frameworkName API $apiVersion",
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName == MODULE_PACKAGE) return
        if (!lifecycleHooksInstalled.compareAndSet(false, true)) return

        try {
            val onResume = Activity::class.java.getDeclaredMethod("onResume")
            val onPause = Activity::class.java.getDeclaredMethod("onPause")

            hook(onResume).intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? Activity)?.let { dispatch(it, EVENT_RESUMED) }
                result
            }

            hook(onPause).intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? Activity)?.let { dispatch(it, EVENT_PAUSED) }
                result
            }

            log(Log.INFO, TAG, "Lifecycle hooks active for package ${param.packageName}")
        } catch (error: Throwable) {
            lifecycleHooksInstalled.set(false)
            log(Log.ERROR, TAG, "Unable to install lifecycle hooks", error)
        }
    }

    private fun dispatch(activity: Activity, eventType: String) {
        val resolver = activity.applicationContext.contentResolver
        val packageName = activity.packageName
        val className = activity.javaClass.name
        eventDispatcher.execute {
            report(resolver, packageName, className, eventType)
        }
    }

    private fun report(
        resolver: android.content.ContentResolver,
        packageName: String,
        className: String,
        eventType: String,
    ) {
        try {
            val event = Bundle().apply {
                putString(KEY_EVENT, eventType)
                putString(KEY_PACKAGE, packageName)
                putString(KEY_CLASS, className)
            }
            resolver.call(
                EVENT_ENDPOINT,
                METHOD_REPORT_EVENT,
                null,
                event,
            )
        } catch (error: Throwable) {
            log(Log.WARN, TAG, "Event delivery failed: ${error.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "ShieldHooks"
        private const val MODULE_PACKAGE = "dev.roesler.shieldhooks"
        private const val AUTHORITY = "dev.roesler.shieldhooks.events"
        private const val METHOD_REPORT_EVENT = "report_event"
        private const val KEY_EVENT = "event"
        private const val KEY_PACKAGE = "package"
        private const val KEY_CLASS = "class"
        private const val EVENT_RESUMED = "activity.resumed"
        private const val EVENT_PAUSED = "activity.paused"
        private val EVENT_ENDPOINT = "content://$AUTHORITY".toUri()
    }
}
