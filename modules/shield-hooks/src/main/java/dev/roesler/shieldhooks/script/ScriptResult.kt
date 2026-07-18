package dev.roesler.shieldhooks.script

sealed interface ScriptResult {
    data class Success(val elapsedMs: Long) : ScriptResult
    data class Rejected(val reasons: List<String>) : ScriptResult
    data class Failed(val message: String) : ScriptResult
    data object TimedOut : ScriptResult
    data object DisabledByCircuitBreaker : ScriptResult
}

data class RuntimeStatus(
    val enabled: Boolean = false,
    val consecutiveFailures: Int = 0,
    val circuitOpen: Boolean = false,
    val lastResult: String = "Idle",
)
