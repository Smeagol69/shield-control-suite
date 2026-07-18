package dev.roesler.shieldhooks.script

data class ScriptRecipe(
    val id: String,
    val title: String,
    val description: String,
    val source: String,
)

object ScriptRecipes {
    val all: List<ScriptRecipe> = listOf(
        ScriptRecipe(
            id = "lifecycle_audit",
            title = "Lifecycle audit",
            description = "Record each approved app’s activity transitions without reading app data.",
            source = """api.tag(eventType, packageName + " / " + className);""",
        ),
        ScriptRecipe(
            id = "marquee_ready",
            title = "Marquee ready",
            description = "Show a small confirmation when Marquee reaches the foreground.",
            source = """if (api.equalsText(packageName, "dev.roesler.marquee")) {
    if (api.equalsText(eventType, "activity.resumed")) {
        api.toast("Marquee is ready");
        api.log("Marquee foreground: " + className);
    }
}""",
        ),
        ScriptRecipe(
            id = "provider_handoff",
            title = "Provider handoff trail",
            description = "Log opens and exits for provider packages you explicitly add to scope.",
            source = """if (api.equalsText(eventType, "activity.resumed")) {
    api.tag("provider-open", packageName + " / " + className);
}
if (api.equalsText(eventType, "activity.user_leave")) {
    api.tag("provider-exit", packageName + " / " + className);
}""",
        ),
        ScriptRecipe(
            id = "session_boundaries",
            title = "Session boundaries",
            description = "Capture clean created/destroyed boundaries for troubleshooting TV apps.",
            source = """if (api.equalsText(eventType, "activity.created")) {
    api.log("Session created: " + packageName + " / " + className);
}
if (api.equalsText(eventType, "activity.destroyed")) {
    api.log("Session destroyed: " + packageName + " / " + className);
}""",
        ),
        ScriptRecipe(
            id = "foreground_trace",
            title = "Foreground trace",
            description = "Record only foreground/background transitions with minimal audit noise.",
            source = """if (api.equalsText(eventType, "activity.resumed")) {
    api.tag("foreground", packageName + " / " + className);
}
if (api.equalsText(eventType, "activity.paused")) {
    api.tag("background", packageName + " / " + className);
}""",
        ),
    )

    fun find(id: String): ScriptRecipe? = all.firstOrNull { it.id == id }
}
