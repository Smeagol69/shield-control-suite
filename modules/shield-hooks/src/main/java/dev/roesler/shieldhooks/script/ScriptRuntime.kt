package dev.roesler.shieldhooks.script

import android.content.Context
import dev.roesler.shieldhooks.event.EventSnapshot
import dev.roesler.shieldhooks.storage.AuditStore
import dev.roesler.shieldhooks.storage.ScriptSettings
import dev.roesler.shieldhooks.storage.ScriptStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object ScriptRuntime {
    private const val FAILURE_LIMIT = 3

    @Volatile
    private var appContext: Context? = null
    private val engine = ScriptEngine()
    private val dispatcher = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(64),
        { runnable -> Thread(runnable, "shield-hooks-dispatch").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    private val _status = MutableStateFlow(RuntimeStatus())
    val status: StateFlow<RuntimeStatus> = _status.asStateFlow()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val settings = ScriptStore.load(context)
        _status.value = RuntimeStatus(enabled = settings.enabled)
    }

    fun updateSettings(settings: ScriptSettings) {
        val context = appContext ?: return
        ScriptStore.save(context, settings)
        _status.value = RuntimeStatus(
            enabled = settings.enabled,
            lastResult = if (settings.enabled) "Ready" else "Disabled",
        )
        AuditStore.append(
            context,
            "SYSTEM",
            "script",
            if (settings.enabled) "Automation enabled." else "Automation disabled.",
        )
    }

    fun submit(event: EventSnapshot) {
        val context = appContext ?: return
        val settings = ScriptStore.load(context)
        val current = _status.value
        if (!settings.enabled || current.circuitOpen) return

        dispatcher.execute {
            val result = engine.execute(settings.source, event, ScriptApi(context))
            recordResult(context, result)
        }
    }

    fun test(source: String, callback: (ScriptResult) -> Unit) {
        val context = appContext ?: run {
            callback(ScriptResult.Failed("Runtime is not initialized."))
            return
        }
        dispatcher.execute {
            val result = engine.execute(
                source = source,
                event = EventSnapshot(
                    type = EventSnapshot.ACTIVITY_RESUMED,
                    packageName = "dev.roesler.marquee",
                    className = "dev.roesler.marquee.MainActivity",
                    timestamp = System.currentTimeMillis(),
                ),
                api = ScriptApi(context),
            )
            recordResult(context, result)
            callback(result)
        }
    }

    fun resetCircuitBreaker() {
        val enabled = appContext?.let { ScriptStore.load(it).enabled } ?: false
        _status.value = RuntimeStatus(enabled = enabled, lastResult = "Circuit reset")
        appContext?.let {
            AuditStore.append(it, "SYSTEM", "script", "Circuit breaker reset.")
        }
    }

    private fun recordResult(context: Context, result: ScriptResult) {
        val previous = _status.value
        val failed = result !is ScriptResult.Success
        val failures = if (failed) previous.consecutiveFailures + 1 else 0
        val circuitOpen = failures >= FAILURE_LIMIT
        val summary = when (result) {
            is ScriptResult.Success -> "Completed in ${result.elapsedMs} ms"
            is ScriptResult.Rejected -> "Rejected: ${result.reasons.joinToString(" ")}"
            is ScriptResult.Failed -> "Failed: ${result.message}"
            ScriptResult.TimedOut -> "Timed out after ${ScriptEngine.TIMEOUT_MS} ms"
            ScriptResult.DisabledByCircuitBreaker -> "Circuit breaker is open"
        }
        _status.value = RuntimeStatus(
            enabled = previous.enabled,
            consecutiveFailures = failures,
            circuitOpen = circuitOpen,
            lastResult = summary,
        )
        AuditStore.append(
            context,
            if (failed) "ERROR" else "SCRIPT",
            "runtime",
            summary + if (circuitOpen) " Automation paused after $FAILURE_LIMIT failures." else "",
        )
    }
}
