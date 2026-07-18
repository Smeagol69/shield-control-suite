package dev.roesler.shieldhooks.event

data class EventSnapshot(
    val type: String,
    val packageName: String,
    val className: String,
    val timestamp: Long,
) {
    companion object {
        const val ACTIVITY_RESUMED = "activity.resumed"
        const val ACTIVITY_PAUSED = "activity.paused"

        val allowedTypes = setOf(ACTIVITY_RESUMED, ACTIVITY_PAUSED)
    }
}
