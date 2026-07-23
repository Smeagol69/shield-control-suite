package dev.roesler.marquee

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.roesler.marquee.data.LegacySettingsMigrator
import dev.roesler.marquee.data.SettingsStore
import dev.roesler.marquee.ui.AppText
import dev.roesler.marquee.ui.DetailScreen
import dev.roesler.marquee.ui.HomeScreen
import dev.roesler.marquee.ui.LocalMarqueeLayout
import dev.roesler.marquee.ui.MarqueePalette
import dev.roesler.marquee.ui.PeopleScreen
import dev.roesler.marquee.ui.ProvidersScreen
import dev.roesler.marquee.ui.SearchScreen
import dev.roesler.marquee.ui.SettingsScreen
import dev.roesler.marquee.ui.marqueeLayout
import dev.roesler.marquee.ui.marqueeBackground
import dev.roesler.marquee.ui.marqueeSizeClass

class MainActivity : ComponentActivity() {
    private var controller: MarqueeController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .marqueeBackground(),
                contentAlignment = Alignment.Center,
            ) {
                AppText(
                    "Preparing Marquee…",
                    14.sp,
                    MarqueePalette.Muted,
                    FontWeight.Medium,
                )
            }
        }

        LegacySettingsMigrator(this, SettingsStore(applicationContext)).run {
            if (!isFinishing && !isDestroyed) initializeApp()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onDestroy() {
        controller?.close()
        super.onDestroy()
    }

    private fun initializeApp() {
        if (controller != null) return
        val appController = MarqueeController(applicationContext)
        controller = appController

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!appController.onBack()) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
        setContent {
            MarqueeApp(appController)
        }
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
}

@Composable
private fun MarqueeApp(controller: MarqueeController) {
    val destination by controller.destination.collectAsState()
    val home by controller.home.collectAsState()
    val providers by controller.providers.collectAsState()
    val search by controller.search.collectAsState()
    val people by controller.people.collectAsState()
    val detail by controller.detail.collectAsState()
    val settings by controller.settings.collectAsState()
    val trakt by controller.trakt.collectAsState()
    val livePlayback by controller.livePlayback.collectAsState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = remember(maxWidth, maxHeight) {
            marqueeLayout(marqueeSizeClass(maxWidth.value, maxHeight.value))
        }
        CompositionLocalProvider(LocalMarqueeLayout provides layout) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .marqueeBackground(),
            ) {
                if (detail.visible) {
                    DetailScreen(detail, controller)
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                horizontal = layout.outerHorizontalPadding,
                                vertical = layout.outerVerticalPadding,
                            ),
                    ) {
                        MarqueeHeader(destination, controller)
                        Spacer(Modifier.height(layout.headerContentGap))
                        Box(Modifier.fillMaxSize()) {
                            when (destination) {
                                Destination.HOME -> HomeScreen(home, livePlayback, controller)
                                Destination.PROVIDERS ->
                                    ProvidersScreen(providers, livePlayback, controller)
                                Destination.SEARCH -> SearchScreen(search, controller)
                                Destination.PEOPLE -> PeopleScreen(people, controller)
                                Destination.SETTINGS -> SettingsScreen(settings, trakt, controller)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarqueeHeader(destination: Destination, controller: MarqueeController) {
    val layout = LocalMarqueeLayout.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(layout.headerLogoSize)
                .height(layout.headerLogoSize)
                .clip(RoundedCornerShape(12.dp))
                .background(MarqueePalette.GoldDark)
                .border(
                    1.dp,
                    MarqueePalette.Gold.copy(alpha = 0.5f),
                    RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            AppText("▶", 15.sp, MarqueePalette.Gold, FontWeight.Black)
        }
        Spacer(Modifier.width(11.dp))
        Column {
            AppText(
                "MARQUEE",
                if (layout.compact) 17.sp else 18.sp,
                MarqueePalette.Text,
                FontWeight.Black,
            )
            AppText("Discovery, without the wandering", 9.sp, MarqueePalette.Muted)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Destination.entries.forEach { item ->
                NavItem(
                    item = item,
                    selected = destination == item,
                    onClick = { controller.navigate(item) },
                )
            }
        }
    }
}

@Composable
private fun NavItem(item: Destination, selected: Boolean, onClick: () -> Unit) {
    val layout = LocalMarqueeLayout.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) MarqueePalette.GoldDark else Color.Transparent)
            .border(
                1.dp,
                when {
                    focused -> MarqueePalette.Gold
                    selected -> MarqueePalette.Gold.copy(alpha = 0.45f)
                    else -> Color.Transparent
                },
                shape,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(
                horizontal = if (layout.compact) 12.dp else 14.dp,
                vertical = if (layout.compact) 7.dp else 8.dp,
            ),
    ) {
        AppText(
            item.label,
            11.sp,
            if (selected || focused) MarqueePalette.Text else MarqueePalette.Muted,
            FontWeight.Bold,
        )
    }
}
