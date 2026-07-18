package dev.roesler.marquee.ui

import android.annotation.SuppressLint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.roesler.marquee.data.MediaItem
import dev.roesler.marquee.data.Person

object MarqueePalette {
    val Background = Color(0xFF08090C)
    val BackgroundSoft = Color(0xFF12141A)
    val Panel = Color(0xE616181E)
    val PanelSolid = Color(0xFF181B21)
    val Border = Color(0xFF343840)
    val Text = Color(0xFFF7F4EC)
    val Muted = Color(0xFFAAA8A3)
    val Gold = Color(0xFFFFCE55)
    val GoldDark = Color(0xFF483812)
    val Blue = Color(0xFF8DC5FF)
    val Red = Color(0xFFFF7D85)
    val Green = Color(0xFF70DEA0)
}

@Composable
fun MediaPoster(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (MediaItem) -> Unit = {},
) {
    FocusBox(
        modifier = modifier.width(142.dp),
        onClick = onClick,
        onFocused = { onFocused(item) },
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.07f,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .width(142.dp)
                    .height(204.dp)
                    .clip(RoundedCornerShape(10.dp)),
            ) {
                RemoteImage(
                    url = item.posterUrl,
                    description = item.title,
                    modifier = Modifier.matchParentSize(),
                )
                if (item.rating > 0.0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(7.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xE6111318))
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                    ) {
                        AppText(
                            text = "★ %.1f".format(item.rating),
                            size = 10.sp,
                            color = MarqueePalette.Gold,
                            weight = FontWeight.Bold,
                        )
                    }
                }
                item.progressPercent?.coerceIn(0.0, 100.0)?.let { progress ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(5.dp)
                            .background(Color(0xB20A0B0E)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((progress / 100.0).toFloat())
                                .height(5.dp)
                                .background(MarqueePalette.Gold),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            AppText(
                text = item.title,
                size = 13.sp,
                color = MarqueePalette.Text,
                weight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AppText(
                text = item.contextLabel ?: listOf(item.year, item.type.apiName.uppercase())
                    .filter(String::isNotBlank)
                    .joinToString(" · "),
                size = 10.sp,
                color = if (item.progressPercent != null) {
                    MarqueePalette.Gold
                } else {
                    MarqueePalette.Muted
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun PersonPoster(person: Person, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusBox(
        modifier = modifier.width(156.dp),
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column {
            RemoteImage(
                url = person.photoUrl,
                description = person.name,
                modifier = Modifier
                    .width(156.dp)
                    .height(156.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.height(8.dp))
            AppText(
                person.name,
                13.sp,
                MarqueePalette.Text,
                FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AppText(
                person.knownFor,
                10.sp,
                MarqueePalette.Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                when {
                    !enabled -> MarqueePalette.PanelSolid.copy(alpha = 0.45f)
                    primary -> MarqueePalette.GoldDark
                    else -> MarqueePalette.PanelSolid
                },
            )
            .border(
                1.dp,
                when {
                    !enabled -> MarqueePalette.Border.copy(alpha = 0.4f)
                    focused -> MarqueePalette.Gold
                    primary -> MarqueePalette.Gold.copy(alpha = 0.5f)
                    else -> MarqueePalette.Border
                },
                shape,
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .focusable(enabled = enabled, interactionSource = interaction)
            .padding(horizontal = 17.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            text = label,
            size = 13.sp,
            color = if (enabled) MarqueePalette.Text else MarqueePalette.Muted.copy(alpha = 0.5f),
            weight = FontWeight.Bold,
        )
    }
}

@Composable
fun AppInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            // A BasicTextField swallows D-pad up/down for the caret, trapping focus on TV.
            // Move focus out so the remote can reach the results below (and the nav above).
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                    Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                    else -> false
                }
            }
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0D0F13))
            .border(
                1.dp,
                if (focused) MarqueePalette.Gold else MarqueePalette.Border,
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 15.dp, vertical = 13.dp),
        textStyle = TextStyle(
            color = MarqueePalette.Text,
            fontSize = 14.sp,
            fontFamily = FontFamily.SansSerif,
        ),
        cursorBrush = SolidColor(MarqueePalette.Gold),
        interactionSource = interaction,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        decorationBox = { input ->
            Box {
                if (value.isBlank()) {
                    AppText(placeholder, 14.sp, MarqueePalette.Muted)
                }
                input()
            }
        },
    )
}

@Composable
fun SectionHeading(title: String, subtitle: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        AppText(title, 20.sp, MarqueePalette.Text, FontWeight.ExtraBold)
        subtitle?.let {
            Spacer(Modifier.width(10.dp))
            AppText(it, 11.sp, MarqueePalette.Muted, FontWeight.Medium)
        }
    }
}

@Composable
fun GlassPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MarqueePalette.Panel)
            .border(1.dp, MarqueePalette.Border, RoundedCornerShape(18.dp))
            .padding(20.dp),
    ) {
        content()
    }
}

@Composable
fun EmptyState(title: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 46.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppText(title, 20.sp, MarqueePalette.Text, FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        AppText(detail, 13.sp, MarqueePalette.Muted)
    }
}

@Composable
fun BusyState(label: String = "Loading") {
    Row(
        modifier = Modifier.padding(vertical = 30.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(MarqueePalette.Gold),
        )
        AppText("$label…", 13.sp, MarqueePalette.Muted, FontWeight.Medium)
    }
}

@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    focusedScale: Float = 1.04f,
    onFocused: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, label = "focusScale")
    LaunchedEffect(focused) {
        if (focused) onFocused()
    }

    Box(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) MarqueePalette.Gold else Color.Transparent,
                shape = shape,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(if (focused) 4.dp else 6.dp),
    ) {
        content()
    }
}

@Composable
@SuppressLint("ModifierParameter")
fun AppText(
    text: String,
    size: TextUnit,
    color: Color,
    weight: FontWeight = FontWeight.Normal,
    family: FontFamily = FontFamily.SansSerif,
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
            lineHeight = size * 1.32f,
        ),
        maxLines = maxLines,
        overflow = overflow,
    )
}

fun Modifier.marqueeBackground(): Modifier =
    background(
        Brush.radialGradient(
            colors = listOf(Color(0xFF252012), MarqueePalette.Background),
            radius = 1_200f,
        ),
    )
