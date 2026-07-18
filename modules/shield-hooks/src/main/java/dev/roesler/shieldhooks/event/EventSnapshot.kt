package dev.roesler.shieldhooks.event

data class EventSnapshot(
    val type: String,
    val packageName: String,
    val className: String,
    val timestamp: Long,
) {
    companion object {
        const val ACTIVITY_CREATED = "activity.created"
        const val ACTIVITY_RESUMED = "activity.resumed"
        const val ACTIVITY_PAUSED = "activity.paused"
        const val ACTIVITY_USER_LEAVE = "activity.user_leave"
        const val ACTIVITY_DESTROYED = "activity.destroyed"

        val allowedTypes = setOf(
            ACTIVITY_CREATED,
            ACTIVITY_RESUMED,
            ACTIVITY_PAUSED,
            ACTIVITY_USER_LEAVE,
            ACTIVITY_DESTROYED,
        )
    }
}
