package dev.roesler.shieldhooks.script

import bsh.Interpreter
import dev.roesler.shieldhooks.event.EventSnapshot
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.system.measureNanoTime

class ScriptEngine {
    private val evaluator = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(8),
        { runnable -> Thread(runnable, "shield-hooks-beanshell").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun execute(
        source: String,
        event: EventSnapshot,
        api: ScriptApi,
    ): ScriptResult {
        val policy = ScriptPolicy.validate(source)
        if (!policy.accepted) return ScriptResult.Rejected(policy.reasons)

        val future = try {
            evaluator.submit<Unit> {
                val interpreter = Interpreter().apply {
                    setStrictJava(true)
                    set("eventType", event.type)
                    set("packageName", event.packageName)
                    set("className", event.className)
                    set("timestamp", event.timestamp)
                    set("api", api)
                }
                interpreter.eval(source)
            }
        } catch (_: RuntimeException) {
            return ScriptResult.Failed("Script executor is busy.")
        }

        var elapsed = 0L
        return try {
            elapsed = measureNanoTime {
                future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            ScriptResult.Success(TimeUnit.NANOSECONDS.toMillis(elapsed))
        } catch (_: TimeoutException) {
            future.cancel(true)
            ScriptResult.TimedOut
        } catch (error: Exception) {
            val root = error.cause ?: error
            ScriptResult.Failed(root.message?.take(240) ?: root.javaClass.simpleName)
        }
    }

    fun close() {
        evaluator.shutdownNow()
    }

    companion object {
        const val TIMEOUT_MS = 150L
    }
}
