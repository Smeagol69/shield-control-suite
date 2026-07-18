package dev.roesler.shieldhooks

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.roesler.shieldhooks.script.ScriptPolicy
import dev.roesler.shieldhooks.script.ScriptRecipes
import dev.roesler.shieldhooks.script.ScriptResult
import dev.roesler.shieldhooks.script.ScriptRuntime
import dev.roesler.shieldhooks.storage.AuditEntry
import dev.roesler.shieldhooks.storage.AuditStore
import dev.roesler.shieldhooks.storage.ScriptSettings
import dev.roesler.shieldhooks.storage.ScriptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            ShieldHooksScreen(application as ShieldHooksApp)
        }
    }
}

private enum class Section(val label: String) {
    OVERVIEW("Overview"),
    AUTOMATION("Automation"),
    AUDIT("Audit"),
}

private object Palette {
    val Background = Color(0xFF070A0D)
    val BackgroundGlow = Color(0xFF11271E)
    val Panel = Color(0xEA11171A)
    val PanelRaised = Color(0xFF182024)
    val Border = Color(0xFF2A3539)
    val Text = Color(0xFFF2F7F4)
    val Muted = Color(0xFF94A29C)
    val Green = Color(0xFF72E6A3)
    val GreenDark = Color(0xFF163B28)
    val Amber = Color(0xFFF7C96B)
    val Red = Color(0xFFFF7A84)
    val Blue = Color(0xFF80BFFF)
}

@Composable
private fun ShieldHooksScreen(app: ShieldHooksApp) {
    val framework by ShieldHooksApp.frameworkStatus.collectAsState()
    val runtime by ScriptRuntime.status.collectAsState()
    var section by remember { mutableStateOf(Section.OVERVIEW) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Palette.BackgroundGlow, Palette.Background),
                    radius = 1_150f,
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 42.dp, vertical = 30.dp),
        ) {
            Header(framework, runtime.circuitOpen)
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Section.entries.forEach { item ->
                    NavButton(
                        label = item.label,
                        selected = section == item,
                        onClick = { section = item },
                    )
                }
            }
            Spacer(Modifier.height(22.dp))

            when (section) {
                Section.OVERVIEW -> OverviewSection(app, framework)
                Section.AUTOMATION -> AutomationSection()
                Section.AUDIT -> AuditSection()
            }
        }
    }
}

@Composable
private fun Header(framework: FrameworkStatus, circuitOpen: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Palette.GreenDark)
                .border(1.dp, Palette.Green.copy(alpha = 0.45f), RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Label("S", 24.sp, Palette.Green, FontWeight.Black)
        }
        Spacer(Modifier.width(15.dp))
        Column {
            Label("SHIELD HOOKS", 22.sp, Palette.Text, FontWeight.ExtraBold, letterSpacing = 1.8.sp)
            Label(
                "Package-scoped automation for Android TV",
                13.sp,
                Palette.Muted,
                FontWeight.Medium,
            )
        }
        Spacer(Modifier.weight(1f))
        StatusPill(
            text = when {
                circuitOpen -> "AUTOMATION PAUSED"
                framework.connected -> "${framework.frameworkName} ONLINE"
                else -> "FRAMEWORK OFFLINE"
            },
            color = when {
                circuitOpen -> Palette.Amber
                framework.connected -> Palette.Green
                else -> Palette.Red
            },
        )
    }
}

@Composable
private fun OverviewSection(app: ShieldHooksApp, framework: FrameworkStatus) {
    var packageInput by remember { mutableStateOf("dev.roesler.marquee") }
    var scopeFeedback by remember { mutableStateOf("") }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Framework",
                    value = if (framework.connected) framework.frameworkName else "Not connected",
                    detail = if (framework.connected) {
                        "${framework.frameworkVersion} · API ${framework.apiVersion}"
                    } else {
                        "Install and enable the module in LSPosed"
                    },
                    healthy = framework.connected,
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Active scope",
                    value = "${framework.scope.size} package${if (framework.scope.size == 1) "" else "s"}",
                    detail = framework.scope.take(3).joinToString().ifBlank { "No packages approved yet" },
                    healthy = framework.scope.isNotEmpty(),
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Hook profile",
                    value = "Observe only",
                    detail = "Five generic lifecycle events",
                    healthy = true,
                )
            }
        }

        item {
            Panel {
                Eyebrow("SCOPE CONTROL")
                Spacer(Modifier.height(8.dp))
                Label(
                    "Approve one package at a time",
                    22.sp,
                    Palette.Text,
                    FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Label(
                    "LSPosed remains the authority. Every new target requires an explicit framework approval.",
                    14.sp,
                    Palette.Muted,
                )
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextInput(
                        value = packageInput,
                        onValueChange = { packageInput = it.take(180) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    FocusButton(
                        label = "Request scope",
                        accent = true,
                        onClick = {
                            scopeFeedback = "Waiting for LSPosed approval…"
                            app.requestScope(packageInput) { result ->
                                mainHandler.post {
                                    scopeFeedback = result.fold(
                                        onSuccess = { approved ->
                                            if (approved.isEmpty()) "No package was approved."
                                            else "Approved: ${approved.joinToString()}"
                                        },
                                        onFailure = { it.message ?: "Scope request failed." },
                                    )
                                }
                            }
                        },
                    )
                }
                if (scopeFeedback.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Label(scopeFeedback, 13.sp, Palette.Blue, FontWeight.Medium)
                }
            }
        }

        item {
            Panel {
                Eyebrow("SECURITY MODEL")
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Capability(
                        modifier = Modifier.weight(1f),
                        number = "01",
                        title = "Scoped hooks",
                        detail = "Hooks load only where LSPosed says they may load.",
                    )
                    Capability(
                        modifier = Modifier.weight(1f),
                        number = "02",
                        title = "Sanitized events",
                        detail = "Only package, activity, event type, and time cross the boundary.",
                    )
                    Capability(
                        modifier = Modifier.weight(1f),
                        number = "03",
                        title = "Local scripts",
                        detail = "BeanShell runs in this app—not inside the target process.",
                    )
                    Capability(
                        modifier = Modifier.weight(1f),
                        number = "04",
                        title = "Fail closed",
                        detail = "Policy checks, timeout, rate limit, and circuit breaker are always active.",
                    )
                }
            }
        }
    }
}

@Composable
private fun AutomationSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val runtime by ScriptRuntime.status.collectAsState()
    var settings by remember { mutableStateOf(ScriptStore.load(context)) }
    var source by remember { mutableStateOf(settings.source) }
    var feedback by remember { mutableStateOf("") }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val policy = remember(source) { ScriptPolicy.validate(source) }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Panel(modifier = Modifier.weight(1.65f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("BEANSHELL AUTOMATION")
                    Spacer(Modifier.height(6.dp))
                    Label("Safe script workspace", 22.sp, Palette.Text, FontWeight.Bold)
                }
                Toggle(
                    checked = settings.enabled,
                    label = if (settings.enabled) "Enabled" else "Disabled",
                    onClick = {
                        settings = settings.copy(enabled = !settings.enabled)
                        ScriptRuntime.updateSettings(settings)
                    },
                )
            }
            Spacer(Modifier.height(14.dp))
            TextInput(
                value = source,
                onValueChange = { source = it.take(ScriptPolicy.MAX_SOURCE_LENGTH) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                singleLine = false,
                code = true,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FocusButton(
                    label = "Save",
                    accent = true,
                    enabled = policy.accepted,
                    onClick = {
                        settings = settings.copy(source = source)
                        ScriptRuntime.updateSettings(settings)
                        feedback = "Saved. New events will use this revision."
                    },
                )
                FocusButton(
                    label = "Test event",
                    enabled = policy.accepted,
                    onClick = {
                        feedback = "Running test event…"
                        ScriptRuntime.test(source) { result ->
                            mainHandler.post {
                                feedback = result.describe()
                            }
                        }
                    },
                )
                FocusButton(
                    label = "Restore example",
                    onClick = {
                        source = ScriptStore.DEFAULT_SCRIPT
                        feedback = "Example restored in the editor. Save to apply it."
                    },
                )
                Spacer(Modifier.weight(1f))
                StatusPill(
                    text = if (policy.accepted) "POLICY PASSED" else "POLICY BLOCKED",
                    color = if (policy.accepted) Palette.Green else Palette.Red,
                )
            }
            if (feedback.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Label(feedback, 13.sp, Palette.Blue, FontWeight.Medium)
            }
        }

        Column(
            modifier = Modifier.weight(0.85f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Panel {
                Eyebrow("SAFE RECIPE LIBRARY")
                Spacer(Modifier.height(6.dp))
                Label(
                    "Package-scoped lifecycle recipes. No app content, credentials, or network data.",
                    11.sp,
                    Palette.Muted,
                )
                Spacer(Modifier.height(12.dp))
                ScriptRecipes.all.forEachIndexed { index, recipe ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Label(recipe.title, 13.sp, Palette.Text, FontWeight.Bold)
                            Spacer(Modifier.height(3.dp))
                            Label(recipe.description, 10.sp, Palette.Muted)
                        }
                        FocusButton(
                            label = "Load",
                            onClick = {
                                source = recipe.source
                                feedback = "${recipe.title} loaded. Review, test, then save."
                            },
                        )
                    }
                    if (index < ScriptRecipes.all.lastIndex) {
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            Panel {
                Eyebrow("RUNTIME")
                Spacer(Modifier.height(12.dp))
                KeyValue("State", if (runtime.circuitOpen) "Paused" else if (runtime.enabled) "Ready" else "Disabled")
                KeyValue("Failures", "${runtime.consecutiveFailures} / 3")
                KeyValue("Last result", runtime.lastResult)
                if (runtime.circuitOpen) {
                    Spacer(Modifier.height(12.dp))
                    FocusButton(
                        label = "Reset circuit",
                        accent = true,
                        onClick = ScriptRuntime::resetCircuitBreaker,
                    )
                }
            }

            Panel {
                Eyebrow("AVAILABLE VARIABLES")
                Spacer(Modifier.height(12.dp))
                CodeLine("eventType", "created | resumed | paused | user_leave | destroyed")
                CodeLine("packageName", "Calling package")
                CodeLine("className", "Activity class")
                CodeLine("timestamp", "Provider time in milliseconds")
            }

            Panel {
                Eyebrow("ALLOWLISTED API")
                Spacer(Modifier.height(12.dp))
                listOf(
                    "api.log(message)",
                    "api.toast(message)",
                    "api.tag(name, value)",
                    "api.contains(value, text)",
                    "api.startsWith(value, prefix)",
                    "api.equalsText(left, right)",
                ).forEach { CodeLine(it, null) }
            }

            if (!policy.accepted) {
                Panel(borderColor = Palette.Red.copy(alpha = 0.5f)) {
                    Eyebrow("POLICY FINDINGS", Palette.Red)
                    Spacer(Modifier.height(10.dp))
                    policy.reasons.forEach { reason ->
                        Label("• $reason", 13.sp, Palette.Text)
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val revision by AuditStore.revision.collectAsState()
    val entries by produceState<List<AuditEntry>>(emptyList(), revision) {
        value = withContext(Dispatchers.IO) { AuditStore.readRecent(context) }
    }

    Panel(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow("LOCAL AUDIT TRAIL")
                Spacer(Modifier.height(6.dp))
                Label("${entries.size} recent entries", 22.sp, Palette.Text, FontWeight.Bold)
            }
            FocusButton(
                label = "Clear audit",
                onClick = { AuditStore.clear(context) },
            )
        }
        Spacer(Modifier.height(14.dp))

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Label("No events yet. Open an approved target app to begin.", 15.sp, Palette.Muted)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(entries) { entry ->
                    AuditRow(entry)
                }
            }
        }
    }
}

@Composable
private fun AuditRow(entry: AuditEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.PanelRaised)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(entry.displayTime(), 12.sp, Palette.Muted, FontWeight.Medium, FontFamily.Monospace)
        Spacer(Modifier.width(13.dp))
        StatusPill(
            text = entry.level,
            color = when (entry.level) {
                "ERROR", "WARN" -> Palette.Red
                "EVENT" -> Palette.Blue
                else -> Palette.Green
            },
            compact = true,
        )
        Spacer(Modifier.width(12.dp))
        Label(entry.source, 12.sp, Palette.Green, FontWeight.SemiBold, FontFamily.Monospace)
        Spacer(Modifier.width(12.dp))
        Label(
            entry.message,
            13.sp,
            Palette.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier,
    title: String,
    value: String,
    detail: String,
    healthy: Boolean,
) {
    Panel(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (healthy) Palette.Green else Palette.Red),
            )
            Spacer(Modifier.width(8.dp))
            Eyebrow(title.uppercase())
        }
        Spacer(Modifier.height(14.dp))
        Label(value, 22.sp, Palette.Text, FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(5.dp))
        Label(detail, 12.sp, Palette.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Capability(
    modifier: Modifier,
    number: String,
    title: String,
    detail: String,
) {
    Column(modifier) {
        Label(number, 11.sp, Palette.Green, FontWeight.Bold, FontFamily.Monospace)
        Spacer(Modifier.height(7.dp))
        Label(title, 16.sp, Palette.Text, FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Label(detail, 12.sp, Palette.Muted)
    }
}

@Composable
private fun Panel(
    modifier: Modifier = Modifier,
    borderColor: Color = Palette.Border,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.Panel)
            .border(1.dp, borderColor, RoundedCornerShape(18.dp))
            .padding(20.dp),
        content = content,
    )
}

@Composable
private fun NavButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) Palette.GreenDark else Palette.Panel)
            .border(
                1.dp,
                when {
                    focused -> Palette.Green
                    selected -> Palette.Green.copy(alpha = 0.55f)
                    else -> Palette.Border
                },
                shape,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 20.dp, vertical = 11.dp),
    ) {
        Label(label, 13.sp, if (selected || focused) Palette.Text else Palette.Muted, FontWeight.Bold)
    }
}

@Composable
private fun FocusButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(11.dp)
    val base = if (accent) Palette.GreenDark else Palette.PanelRaised
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (enabled) base else Palette.PanelRaised.copy(alpha = 0.45f))
            .border(
                1.dp,
                when {
                    !enabled -> Palette.Border.copy(alpha = 0.4f)
                    focused -> Palette.Green
                    accent -> Palette.Green.copy(alpha = 0.45f)
                    else -> Palette.Border
                },
                shape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .focusable(enabled = enabled, interactionSource = interaction)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(
            label,
            13.sp,
            if (enabled) Palette.Text else Palette.Muted.copy(alpha = 0.55f),
            FontWeight.Bold,
        )
    }
}

@Composable
private fun Toggle(checked: Boolean, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) Palette.GreenDark else Palette.PanelRaised)
            .border(
                1.dp,
                if (focused) Palette.Green else if (checked) Palette.Green.copy(alpha = 0.5f) else Palette.Border,
                RoundedCornerShape(12.dp),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(if (checked) Palette.Green else Palette.Muted),
        )
        Spacer(Modifier.width(8.dp))
        Label(label, 12.sp, Palette.Text, FontWeight.Bold)
    }
}

@Composable
private fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean,
    code: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF090D0F))
            .border(
                1.dp,
                if (focused) Palette.Green else Palette.Border,
                RoundedCornerShape(12.dp),
            )
            .padding(14.dp),
        textStyle = TextStyle(
            color = Palette.Text,
            fontSize = if (code) 13.sp else 14.sp,
            fontFamily = if (code) FontFamily.Monospace else FontFamily.SansSerif,
            lineHeight = if (code) 20.sp else 18.sp,
        ),
        cursorBrush = SolidColor(Palette.Green),
        singleLine = singleLine,
        interactionSource = interaction,
    )
}

@Composable
private fun StatusPill(text: String, color: Color, compact: Boolean = false) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.11f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(50))
            .padding(
                horizontal = if (compact) 9.dp else 13.dp,
                vertical = if (compact) 4.dp else 7.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(if (compact) 5.dp else 7.dp)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
        Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
        Label(
            text,
            if (compact) 10.sp else 11.sp,
            color,
            FontWeight.ExtraBold,
            letterSpacing = 0.7.sp,
        )
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Label(label, 11.sp, Palette.Muted, FontWeight.SemiBold)
        Spacer(Modifier.height(3.dp))
        Label(value, 13.sp, Palette.Text, FontWeight.Medium)
        Spacer(Modifier.height(11.dp))
    }
}

@Composable
private fun CodeLine(code: String, detail: String?) {
    Label(code, 12.sp, Palette.Green, FontWeight.Medium, FontFamily.Monospace)
    if (detail != null) {
        Spacer(Modifier.height(2.dp))
        Label(detail, 11.sp, Palette.Muted)
    }
    Spacer(Modifier.height(9.dp))
}

@Composable
private fun Eyebrow(text: String, color: Color = Palette.Muted) {
    Label(text, 10.sp, color, FontWeight.ExtraBold, letterSpacing = 1.5.sp)
}

@Composable
@SuppressLint("ModifierParameter")
private fun Label(
    text: String,
    size: androidx.compose.ui.unit.TextUnit,
    color: Color,
    weight: FontWeight = FontWeight.Normal,
    family: FontFamily = FontFamily.SansSerif,
    letterSpacing: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = weight,
            fontFamily = family,
            letterSpacing = letterSpacing,
            lineHeight = size * 1.35f,
        ),
        maxLines = maxLines,
        overflow = overflow,
    )
}

private fun ScriptResult.describe(): String = when (this) {
    is ScriptResult.Success -> "Test passed in $elapsedMs ms."
    is ScriptResult.Rejected -> "Policy blocked the test: ${reasons.joinToString(" ")}"
    is ScriptResult.Failed -> "Test failed: $message"
    ScriptResult.TimedOut -> "Test exceeded the execution timeout."
    ScriptResult.DisabledByCircuitBreaker -> "Test skipped because the circuit is open."
}
