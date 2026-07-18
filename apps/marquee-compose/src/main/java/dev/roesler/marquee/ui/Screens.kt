package dev.roesler.marquee.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.roesler.marquee.DetailUiState
import dev.roesler.marquee.HomeUiState
import dev.roesler.marquee.LaunchResult
import dev.roesler.marquee.MarqueeController
import dev.roesler.marquee.PeopleUiState
import dev.roesler.marquee.SearchUiState
import dev.roesler.marquee.data.MarqueeSettings
import dev.roesler.marquee.data.MediaItem
import dev.roesler.marquee.data.MediaRow
import dev.roesler.marquee.data.WatchProvider

@Composable
fun HomeScreen(state: HomeUiState, controller: MarqueeController) {
    var hero by remember { mutableStateOf<MediaItem?>(null) }
    LaunchedEffect(state.rows) {
        if (hero == null || state.rows.none { row -> hero in row.items }) {
            hero = state.rows.firstOrNull()?.items?.firstOrNull()
        }
    }

    when {
        state.loading && state.rows.isEmpty() -> BusyState("Building your home screen")
        state.error != null && state.rows.isEmpty() -> EmptyState("Couldn’t load Marquee", state.error)
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(25.dp),
        ) {
            hero?.let { heroItem ->
                item {
                    Hero(heroItem, onOpen = { controller.openDetails(heroItem) })
                }
            }
            items(state.rows, key = MediaRow::title) { row ->
                MediaShelf(row, controller, onFocused = { hero = it })
            }
        }
    }
}

@Composable
private fun Hero(item: MediaItem, onOpen: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(285.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MarqueePalette.Border, RoundedCornerShape(20.dp)),
    ) {
        RemoteImage(
            url = item.backdropUrl,
            description = item.title,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xF008090C),
                            Color(0xA008090C),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(560.dp)
                .padding(30.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            AppText("FEATURED", 10.sp, MarqueePalette.Gold, FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            AppText(
                item.title,
                34.sp,
                MarqueePalette.Text,
                FontWeight.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            AppText(
                listOf(item.year, "★ %.1f".format(item.rating))
                    .filter(String::isNotBlank)
                    .joinToString("  ·  "),
                13.sp,
                MarqueePalette.Gold,
                FontWeight.Bold,
            )
            Spacer(Modifier.height(9.dp))
            AppText(
                item.overview.ifBlank { "Open for details, recommendations, and provider options." },
                13.sp,
                MarqueePalette.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(15.dp))
            ActionButton("View details", onOpen, primary = true)
        }
    }
}

@Composable
private fun MediaShelf(
    row: MediaRow,
    controller: MarqueeController,
    onFocused: (MediaItem) -> Unit,
) {
    Column {
        SectionHeading(row.title, "${row.items.size} titles")
        Spacer(Modifier.height(10.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 7.dp),
        ) {
            items(row.items, key = { "${it.type.apiName}:${it.id}" }) { item ->
                MediaPoster(
                    item = item,
                    onClick = { controller.openDetails(item) },
                    onFocused = onFocused,
                )
            }
        }
    }
}

@Composable
fun SearchScreen(state: SearchUiState, controller: MarqueeController) {
    Column(Modifier.fillMaxSize()) {
        SectionHeading("Search", "Movies and television")
        Spacer(Modifier.height(13.dp))
        AppInput(
            value = state.query,
            onValueChange = controller::searchTitles,
            placeholder = "Search movies and shows…",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(15.dp))
        when {
            state.loading -> BusyState("Searching")
            state.error != null -> EmptyState("Search failed", state.error)
            state.query.isBlank() -> EmptyState("Find your next watch", "Use the Shield keyboard or your remote app to search.")
            state.results.isEmpty() -> EmptyState("No matches", "Try a different title.")
            else -> MediaGrid(state.results, controller)
        }
    }
}

@Composable
fun PeopleScreen(state: PeopleUiState, controller: MarqueeController) {
    Column(Modifier.fillMaxSize()) {
        SectionHeading("People", "Search actors and directors")
        Spacer(Modifier.height(13.dp))
        AppInput(
            value = state.query,
            onValueChange = controller::searchPeople,
            placeholder = "Search by actor or director…",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(15.dp))

        when {
            state.loading -> BusyState(if (state.selectedName == null) "Searching people" else "Loading filmography")
            state.error != null -> EmptyState("People search failed", state.error)
            state.people.isEmpty() && state.query.isBlank() ->
                EmptyState("Explore by cast and crew", "Search a name, then browse their filmography.")
            else -> {
                if (state.people.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 7.dp),
                    ) {
                        items(state.people, key = { it.id }) { person ->
                            PersonPoster(person, onClick = { controller.selectPerson(person) })
                        }
                    }
                }
                state.selectedName?.let {
                    Spacer(Modifier.height(12.dp))
                    SectionHeading("$it · Filmography")
                    Spacer(Modifier.height(10.dp))
                }
                if (state.credits.isNotEmpty()) MediaGrid(state.credits, controller)
            }
        }
    }
}

@Composable
private fun MediaGrid(items: List<MediaItem>, controller: MarqueeController) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { "${it.type.apiName}:${it.id}" }) { item ->
            MediaPoster(item, onClick = { controller.openDetails(item) })
        }
    }
}

@Composable
fun SettingsScreen(
    saved: MarqueeSettings,
    controller: MarqueeController,
) {
    var credential by remember(saved) { mutableStateOf(saved.tmdbCredential) }
    var region by remember(saved) { mutableStateOf(saved.region) }
    var resolver by remember(saved) { mutableStateOf(saved.preferredResolverPackage) }
    var feedback by remember { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        GlassPanel(Modifier.weight(1.5f)) {
            Column {
                AppText("SETTINGS", 10.sp, MarqueePalette.Gold, FontWeight.ExtraBold)
                Spacer(Modifier.height(7.dp))
                AppText("Connect your discovery services", 24.sp, MarqueePalette.Text, FontWeight.Black)
                Spacer(Modifier.height(20.dp))

                FieldLabel("TMDB API key or read-access token")
                AppInput(
                    value = credential,
                    onValueChange = { credential = it.take(512) },
                    placeholder = "Paste your TMDB credential",
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(15.dp))

                FieldLabel("Provider region")
                AppInput(
                    value = region,
                    onValueChange = { region = it.uppercase().take(2) },
                    placeholder = "US",
                    modifier = Modifier.width(120.dp),
                )
                Spacer(Modifier.height(15.dp))

                FieldLabel("Preferred resolver package")
                AppInput(
                    value = resolver,
                    onValueChange = { resolver = it.take(180) },
                    placeholder = "com.stremio.one",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    ActionButton("Stremio", onClick = { resolver = "com.stremio.one" })
                    ActionButton("Kodi", onClick = { resolver = "org.xbmc.kodi" })
                    ActionButton("None", onClick = { resolver = "" })
                }
                Spacer(Modifier.height(20.dp))
                ActionButton(
                    label = "Save and load Marquee",
                    primary = true,
                    onClick = {
                        feedback = controller.saveSettings(
                            MarqueeSettings(
                                tmdbCredential = credential,
                                region = region,
                                preferredResolverPackage = resolver,
                            ),
                        ).fold(
                            onSuccess = { "Settings saved." },
                            onFailure = { it.message ?: "Could not save settings." },
                        )
                    },
                )
                if (feedback.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    AppText(feedback, 13.sp, MarqueePalette.Blue, FontWeight.Medium)
                }
            }
        }

        Column(
            modifier = Modifier.weight(0.8f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GlassPanel {
                Column {
                    AppText("RESOLVER STATUS", 10.sp, MarqueePalette.Gold, FontWeight.ExtraBold)
                    Spacer(Modifier.height(12.dp))
                    ResolverStatus("Stremio", "com.stremio.one", controller)
                    ResolverStatus("Kodi", "org.xbmc.kodi", controller)
                    if (resolver.isNotBlank() && resolver !in setOf("com.stremio.one", "org.xbmc.kodi")) {
                        ResolverStatus("Custom", resolver, controller)
                    }
                }
            }
            GlassPanel {
                Column {
                    AppText("PRIVACY", 10.sp, MarqueePalette.Gold, FontWeight.ExtraBold)
                    Spacer(Modifier.height(10.dp))
                    AppText(
                        "Your TMDB credential and watchlist stay in this app’s private storage. Marquee has no analytics and does not upload them anywhere else.",
                        13.sp,
                        MarqueePalette.Muted,
                    )
                }
            }
            GlassPanel {
                Column {
                    AppText("TMDB NOTICE", 10.sp, MarqueePalette.Gold, FontWeight.ExtraBold)
                    Spacer(Modifier.height(10.dp))
                    AppText(
                        "This product uses the TMDB API but is not endorsed or certified by TMDB.",
                        12.sp,
                        MarqueePalette.Muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    AppText(text, 12.sp, MarqueePalette.Muted, FontWeight.SemiBold)
    Spacer(Modifier.height(7.dp))
}

@Composable
private fun ResolverStatus(label: String, packageName: String, controller: MarqueeController) {
    val installed = controller.isInstalled(packageName)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(if (installed) MarqueePalette.Green else MarqueePalette.Red),
        )
        Spacer(Modifier.width(9.dp))
        Column {
            AppText(label, 13.sp, MarqueePalette.Text, FontWeight.Bold)
            AppText(
                if (installed) "Installed" else "Not installed",
                10.sp,
                MarqueePalette.Muted,
            )
        }
    }
}

@Composable
fun DetailScreen(state: DetailUiState, controller: MarqueeController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings by controller.settings.collectAsState()
    val media = state.details?.item ?: state.seed ?: return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MarqueePalette.Background),
    ) {
        RemoteImage(
            url = media.backdropUrl,
            description = null,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xA008090C),
                            Color(0xF008090C),
                            MarqueePalette.Background,
                        ),
                    ),
                ),
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 46.dp),
            contentPadding = PaddingValues(top = 30.dp, bottom = 50.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                ActionButton("← Back", controller::closeDetails)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(25.dp)) {
                    RemoteImage(
                        url = media.posterUrl,
                        description = media.title,
                        modifier = Modifier
                            .width(170.dp)
                            .height(245.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, MarqueePalette.Border, RoundedCornerShape(14.dp)),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 10.dp),
                    ) {
                        AppText(
                            media.title,
                            38.sp,
                            MarqueePalette.Text,
                            FontWeight.Black,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(9.dp))
                        AppText(
                            buildMetadata(state),
                            13.sp,
                            MarqueePalette.Gold,
                            FontWeight.Bold,
                        )
                        Spacer(Modifier.height(12.dp))
                        AppText(
                            media.overview.ifBlank { "No synopsis is available." },
                            14.sp,
                            MarqueePalette.Muted,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val resolver = settings.preferredResolverPackage
                            ActionButton(
                                label = if (resolver.isBlank()) "Resolver not set" else "Open preferred",
                                primary = true,
                                enabled = resolver.isNotBlank(),
                                onClick = {
                                    controller.openPreferredResolver(media).show(context)
                                },
                            )
                            ActionButton(
                                label = if (state.inWatchlist) "✓ In watchlist" else "+ Watchlist",
                                onClick = {
                                    val saved = controller.toggleWatchlist()
                                    Toast.makeText(
                                        context,
                                        if (saved) "Added to watchlist" else "Removed from watchlist",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                            ActionButton(
                                label = "All options",
                                enabled = state.watchOptions.webLink != null,
                                onClick = { controller.openWebOptions().show(context) },
                            )
                        }
                    }
                }
            }

            if (state.loading) {
                item { BusyState("Loading details and providers") }
            }
            state.error?.let { error ->
                item { EmptyState("Details unavailable", error) }
            }

            if (state.watchOptions.providers.isNotEmpty()) {
                item { SectionHeading("Where to watch", "Availability for your region") }
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 7.dp),
                    ) {
                        items(state.watchOptions.providers, key = WatchProvider::id) { provider ->
                            ProviderCard(provider, controller) {
                                controller.openProvider(provider).show(context)
                            }
                        }
                    }
                }
            }

            if (state.recommendations.isNotEmpty()) {
                item { SectionHeading("More like this") }
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 7.dp),
                    ) {
                        items(
                            state.recommendations,
                            key = { "${it.type.apiName}:${it.id}" },
                        ) { recommendation ->
                            MediaPoster(
                                recommendation,
                                onClick = { controller.openDetails(recommendation) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: WatchProvider,
    controller: MarqueeController,
    onClick: () -> Unit,
) {
    val installed = controller.isInstalled(provider.packageName)
    FocusBox(
        onClick = onClick,
        modifier = Modifier.width(132.dp),
        focusedScale = 1.06f,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RemoteImage(
                url = provider.logoUrl,
                description = provider.name,
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(15.dp)),
            )
            Spacer(Modifier.height(8.dp))
            AppText(
                provider.name,
                11.sp,
                MarqueePalette.Text,
                FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AppText(
                if (installed) "${provider.access} · Installed" else provider.access,
                9.sp,
                if (installed) MarqueePalette.Green else MarqueePalette.Muted,
                maxLines = 1,
            )
        }
    }
}

private fun buildMetadata(state: DetailUiState): String {
    val details = state.details
    val item = details?.item ?: state.seed ?: return ""
    return buildList {
        item.year.takeIf(String::isNotBlank)?.let(::add)
        item.rating.takeIf { it > 0.0 }?.let { add("★ %.1f".format(it)) }
        details?.runtimeMinutes?.let { add("$it min") }
        details?.seasons?.let { add("$it season${if (it == 1) "" else "s"}") }
        details?.genres?.takeIf { it.isNotEmpty() }?.let { add(it.joinToString()) }
    }.joinToString("  ·  ")
}

private fun LaunchResult.show(context: android.content.Context) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
