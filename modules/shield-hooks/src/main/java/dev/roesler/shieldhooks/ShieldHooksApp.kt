package dev.roesler.shieldhooks

import android.app.Application
import dev.roesler.shieldhooks.script.ScriptRuntime
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArraySet

data class FrameworkStatus(
    val connected: Boolean = false,
    val frameworkName: String = "LSPosed service unavailable",
    val frameworkVersion: String = "—",
    val apiVersion: Int = 0,
    val scope: List<String> = emptyList(),
)

class ShieldHooksApp : Application(), XposedServiceHelper.OnServiceListener {
    private val scopeListeners = CopyOnWriteArraySet<(Result<List<String>>) -> Unit>()

    override fun onCreate() {
        super.onCreate()
        ScriptRuntime.initialize(this)
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        publish(service)
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService === service) {
            xposedService = null
            _frameworkStatus.value = FrameworkStatus()
            val failure = Result.failure<List<String>>(
                IllegalStateException("LSPosed disconnected before the scope request completed."),
            )
            scopeListeners.toList().forEach { callback ->
                completeScopeRequest(callback, failure)
            }
        }
    }

    fun requestScope(packageName: String, callback: (Result<List<String>>) -> Unit) {
        val normalized = packageName.trim()
        if (!PACKAGE_NAME.matches(normalized)) {
            callback(Result.failure(IllegalArgumentException("Enter a valid Android package name.")))
            return
        }

        val service = xposedService
        if (service == null) {
            callback(Result.failure(IllegalStateException("Connect LSPosed before requesting scope.")))
            return
        }

        scopeListeners += callback
        service.requestScope(
            listOf(normalized),
            object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(approved: List<String>) {
                    publish(service)
                    completeScopeRequest(callback, Result.success(approved))
                }

                override fun onScopeRequestFailed(message: String) {
                    publish(service)
                    completeScopeRequest(
                        callback,
                        Result.failure(IllegalStateException(message.ifBlank { "Scope request failed." })),
                    )
                }
            },
        )
    }

    private fun completeScopeRequest(
        callback: (Result<List<String>>) -> Unit,
        result: Result<List<String>>,
    ) {
        if (scopeListeners.remove(callback)) callback(result)
    }

    private fun publish(service: XposedService) {
        _frameworkStatus.value = FrameworkStatus(
            connected = true,
            frameworkName = service.frameworkName,
            frameworkVersion = service.frameworkVersion,
            apiVersion = service.apiVersion,
            scope = service.scope.sorted(),
        )
    }

    companion object {
        private val PACKAGE_NAME =
            Regex("""^[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z][a-zA-Z0-9_]*)+$""")

        @Volatile
        private var xposedService: XposedService? = null

        private val _frameworkStatus = MutableStateFlow(FrameworkStatus())
        val frameworkStatus: StateFlow<FrameworkStatus> = _frameworkStatus.asStateFlow()

        fun isApprovedPackage(packageName: String): Boolean {
            val status = _frameworkStatus.value
            return status.connected && packageName in status.scope
        }
    }
}
