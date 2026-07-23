package dev.roesler.marquee.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.roesler.marquee.DetailUiState
import dev.roesler.marquee.HomeUiState
import dev.roesler.marquee.LaunchResult
import dev.roesler.marquee.LivePlaybackUiState
import dev.roesler.marquee.MarqueeController
import dev.roesler.marquee.PeopleUiState
import dev.roesler.marquee.ProvidersUiState
import dev.roesler.marquee.SearchUiState
import dev.roesler.marquee.TraktPhase
import dev.roesler.marquee.TraktUiState
import dev.roesler.marquee.data.CatalogFilter
import dev.roesler.marquee.data.CatalogProvider
import dev.roesler.marquee.data.MarqueeSettings
import dev.roesler.marquee.data.MediaItem
import dev.roesler.marquee.data.MediaRow
import dev.roesler.marquee.data.MediaRowAction
import dev.roesler.marquee.data.Person
import dev.roesler.marquee.data.WatchProvider
import dev.roesler.marquee.data.filterCatalogRows
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    state: HomeUiState,
    livePlayback: LivePlaybackUiState?,
    controller: MarqueeController,
) {
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            livePlayback?.let { live ->
                item { LivePlaybackBanner(live) }
            }
            hero?.let { heroItem ->
                item {
                    Hero(heroItem, onOpen = { controller.openDetails(heroItem) })
                }
            }
            state.notice?.let { notice ->
                item {
                    AppText(notice, 11.sp, MarqueePalette.Muted, FontWeight.Medium)
                }
            }
            items(state.rows, key = MediaRow::title) { row ->
                MediaShelf(row, controller, onFocused = { hero = it })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProvidersScreen(
    state: ProvidersUiState,
    livePlayback: LivePlaybackUiState?,
    controller: MarqueeController,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val selected = state.selectedProvider
    val catalogActionsRequester = remember { FocusRequester() }
    val displayedRows = remember(state.rows, state.filter) {
        filterCatalogRows(state.rows, state.filter)
    }

    Column(Modifier.fillMaxSize()) {
        SectionHeading(
            "Streaming providers",
            selected?.let { "${it.name} · live regional catalog" }
                ?: "Live regional catalogs · installed apps first",
        )
        Spacer(Modifier.height(6.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        ) {
            items(state.providers, key = CatalogProvider::id) { provider ->
                ProviderTab(
                    provider = provider,
                    selected = provider.id == selected?.id,
                    installed = controller.isInstalled(provider.packageName),
                    downFocusRequester = catalogActionsRequester,
                    onClick = { controller.selectProvider(provider) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CatalogFilter.entries.forEach { filter ->
                ActionButton(
                    label = filter.label,
                    primary = state.filter == filter,
                    onClick = { controller.setProviderFilter(filter) },
                )
            }
            if (state.totalCategoryCount > 0) {
                Spacer(Modifier.width(4.dp))
                AppText(
                    text = if (state.loading) {
                        "Loading ${state.loadedCategoryCount}/${state.totalCategoryCount} categories"
                    } else {
                        "${state.totalCategoryCount} categories"
                    },
                    size = 10.sp,
                    color = MarqueePalette.Muted,
                    weight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.weight(1f))
            ActionButton(
                label = "Surprise me",
                enabled = displayedRows.isNotEmpty(),
                onClick = {
                    if (!controller.surpriseMe()) {
                        Toast.makeText(context, "No titles match this filter yet.", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            selected?.let { provider ->
                Spacer(Modifier.width(8.dp))
                val installed = controller.isInstalled(provider.packageName)
                ActionButton(
                    label = if (installed) "Open ${provider.name}" else "Not installed",
                    enabled = installed,
                    primary = installed,
                    onClick = { controller.openCatalogProvider(provider).show(context) },
                )
            }
            Spacer(Modifier.width(8.dp))
            ActionButton(
                label = "Refresh",
                onClick = controller::refreshProviders,
                modifier = Modifier.focusRequester(catalogActionsRequester),
            )
        }
        Spacer(Modifier.height(8.dp))
        livePlayback?.let {
            LivePlaybackBanner(it)
            Spacer(Modifier.height(8.dp))
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading && state.rows.isEmpty() ->
                    BusyState("Building ${selected?.name ?: "provider"} categories")
                state.error != null && state.rows.isEmpty() ->
                    EmptyState("Provider catalog unavailable", state.error)
                displayedRows.isEmpty() ->
                    EmptyState("No matching titles", "Try another catalog filter.")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    state.notice?.let { notice ->
                        item {
                            AppText(notice, 11.sp, MarqueePalette.Muted, FontWeight.Medium)
                        }
                    }
                    displayedRows.forEachIndexed { index, row ->
                        stickyHeader(key = "category:${row.title}") {
                            CategoryHeader(
                                row = row,
                                position = index + 1,
                                total = displayedRows.size,
                            )
                        }
                        item(key = "titles:${row.title}") {
                            MediaShelfRow(row, controller, onFocused = {})
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(row: MediaRow, position: Int, total: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MarqueePalette.Background.copy(alpha = 0.97f))
            .padding(top = 4.dp, bottom = 2.dp),
    ) {
        SectionHeading(
            row.title,
            "$position/$total · ${row.subtitle ?: "${row.items.size} titles"}",
        )
    }
}

@Composable
private fun LivePlaybackBanner(state: LivePlaybackUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(MarqueePalette.Panel)
            .border(1.dp, MarqueePalette.Green.copy(alpha = 0.45f), RoundedCornerShape(13.dp))
            .padding(horizontal = 17.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MarqueePalette.Green),
            )
            Spacer(Modifier.width(9.dp))
            AppText(
                "${state.stateLabel} · ${state.providerName}",
                10.sp,
                MarqueePalette.Green,
                FontWeight.ExtraBold,
            )
            Spacer(Modifier.width(13.dp))
            AppText(
                state.title ?: "Identifying title from the next visible player overlay",
                14.sp,
                MarqueePalette.Text,
                FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            state.episodeLabel?.let {
                Spacer(Modifier.width(12.dp))
                AppText(it, 11.sp, MarqueePalette.Muted, FontWeight.Medium)
            }
            Spacer(Modifier.width(14.dp))
            AppText(
                listOfNotNull(state.positionLabel, state.durationLabel)
                    .joinToString(" / "),
                14.sp,
                MarqueePalette.Gold,
                FontWeight.Black,
            )
        }
        state.progressFraction?.let { progress ->
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MarqueePalette.Border),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .background(MarqueePalette.Gold),
                )
            }
        }
    }
}

@Composable
private fun ProviderTab(
    provider: CatalogProvider,
    selected: Boolean,
    installed: Boolean,
    downFocusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    val layout = LocalMarqueeLayout.current
    FocusBox(
        onClick = onClick,
        modifier = Modifier
            .width(layout.providerTabWidth)
            .focusProperties { down = downFocusRequester },
        focusedScale = 1.03f,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(layout.providerTabHeight)
                .clip(RoundedCornerShape(11.dp))
                .background(
                    if (selected) MarqueePalette.GoldDark else MarqueePalette.PanelSolid,
                )
                .border(
                    1.dp,
                    if (selected) {
                        MarqueePalette.Gold.copy(alpha = 0.55f)
                    } else {
                        MarqueePalette.Border
                    },
                    RoundedCornerShape(11.dp),
                )
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteImage(
                url = provider.logoUrl,
                description = provider.name,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.width(9.dp))
            AppText(
                provider.name,
                10.sp,
                if (selected) MarqueePalette.Text else MarqueePalette.Muted,
                FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (installed) MarqueePalette.Green else MarqueePalette.Border,
                    ),
            )
        }
    }
}

@Composable
private fun Hero(item: MediaItem, onOpen: () -> Unit) {
    val layout = LocalMarqueeLayout.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(layout.heroHeight)
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
                .width(if (layout.compact) 430.dp else 500.dp)
                .padding(if (layout.compact) 20.dp else 25.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            AppText("FEATURED", 10.sp, MarqueePalette.Gold, FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            AppText(
                item.title,
                if (layout.compact) 26.sp else 30.sp,
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
                12.sp,
                MarqueePalette.Gold,
                FontWeight.Bold,
            )
            Spacer(Modifier.height(9.dp))
            AppText(
                item.overview.ifBlank { "Open for details, recommendations, and provider options." },
                12.sp,
                MarqueePalette.Muted,
                maxLines = 2,
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
        SectionHeading(row.title, row.subtitle ?: "${row.items.size} titles")
        Spacer(Modifier.height(6.dp))
        MediaShelfRow(row, controller, onFocused)
    }
}

@Composable
private fun MediaShelfRow(
    row: MediaRow,
    controller: MarqueeController,
    onFocused: (MediaItem) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
    ) {
        items(row.items, key = { "${it.type.apiName}:${it.id}" }) { item ->
            MediaPoster(
                item = item,
                onClick = {
                    when (row.action) {
                        MediaRowAction.DETAILS -> controller.openDetails(item)
                        MediaRowAction.CONTINUE_LOCAL ->
                            controller.continueLocalPlayback(item).show(context)
                    }
                },
                onFocused = onFocused,
            )
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
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading -> BusyState("Searching")
                state.error != null -> EmptyState("Search failed", state.error)
                state.query.isBlank() -> EmptyState("Find your next watch", "Use the Shield keyboard or your remote app to search.")
                state.results.isEmpty() -> EmptyState("No matches", "Try a different title.")
                else -> MediaGrid(state.results, controller)
            }
        }
    }
}

@Composable
fun PeopleScreen(state: PeopleUiState, controller: MarqueeController) {
    Column(Modifier.fillMaxSize()) {
        SectionHeading("People", "Search actors and directors")
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppInput(
                value = state.query,
                onValueChange = controller::searchPeople,
                placeholder = "Search by actor or director…",
                modifier = Modifier.weight(1f),
                onSubmit = controller::submitPeopleSearch,
            )
            ActionButton(
                label = "Search",
                primary = state.query.isNotBlank(),
                onClick = controller::submitPeopleSearch,
            )
        }
        Spacer(Modifier.height(10.dp))

        if (state.selectedName != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeading(
                    "${state.selectedName} · Filmography",
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    label = "Back to people",
                    onClick = controller::clearPersonSelection,
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading -> BusyState("Loading filmography")
                    state.error != null -> EmptyState("Filmography unavailable", state.error)
                    state.credits.isEmpty() ->
                        EmptyState("No credits found", "Try another person.")
                    else -> MediaGrid(state.credits, controller)
                }
            }
        } else {
            SectionHeading(
                if (state.showingPopular && state.query.isBlank()) "Popular people" else "Search results",
                if (state.loading) "Updating…" else "${state.people.size} people",
            )
            Spacer(Modifier.height(6.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading && state.people.isEmpty() -> BusyState("Loading people")
                    state.error != null && state.people.isEmpty() ->
                        EmptyState("People search failed", state.error)
                    state.people.isEmpty() && state.query.isBlank() ->
                        EmptyState("Explore by cast and crew", "Popular people will appear here.")
                    state.people.isEmpty() ->
                        EmptyState("No people found", "Try a full actor or director name.")
                    else -> PeopleGrid(state.people, controller)
                }
            }
        }
    }
}

@Composable
private fun MediaGrid(items: List<MediaItem>, controller: MarqueeController) {
    val layout = LocalMarqueeLayout.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(layout.gridMinimumWidth),
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
private fun PeopleGrid(people: List<Person>, controller: MarqueeController) {
    val layout = LocalMarqueeLayout.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(layout.personWidth + 8.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(people, key = { it.id }) { person ->
            PersonPoster(person, onClick = { controller.selectPerson(person) })
        }
    }
}

@Composable
fun SettingsScreen(
    saved: MarqueeSettings,
    trakt: TraktUiState,
    controller: MarqueeController,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var bridge by remember { mutableStateOf(controller.playbackBridgeStatus()) }
    LaunchedEffect(Unit) {
        while (true) {
            bridge = controller.playbackBridgeStatus()
            delay(1_000L)
        }
    }
    var credential by remember(saved) { mutableStateOf(saved.tmdbCredential) }
    var region by remember(saved) { mutableStateOf(saved.region) }
    var resolver by remember(saved) { mutableStateOf(saved.preferredResolverPackage) }
    var traktClientId by remember(saved) { mutableStateOf(saved.traktClientId) }
    var traktClientSecret by remember(saved) { mutableStateOf(saved.traktClientSecret) }
    var traktRedirectUri by remember(saved) { mutableStateOf(saved.traktRedirectUri) }
    var feedback by remember { mutableStateOf("") }

    fun editedSettings() = MarqueeSettings(
        tmdbCredential = credential,
        region = region,
        preferredResolverPackage = resolver,
        traktClientId = traktClientId,
        traktClientSecret = traktClientSecret,
        traktRedirectUri = traktRedirectUri,
    )

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

                AppText("METADATA", 10.sp, MarqueePalette.Gold, FontWeight.ExtraBold)
                Spacer(Modifier.height(10.dp))
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

                AppText("TRAKT SYNC", 10.sp, MarqueePalette.Gold, FontWeight.ExtraBold)
                Spacer(Modifier.height(10.dp))
                FieldLabel("Trakt client ID")
                AppInput(
                    value = traktClientId,
                    onValueChange = { traktClientId = it.take(512) },
                    placeholder = "Client ID from your Trakt application",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                FieldLabel("Trakt client secret")
                AppInput(
                    value = traktClientSecret,
                    onValueChange = { traktClientSecret = it.take(512) },
                    placeholder = "Client secret",
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(12.dp))
                FieldLabel("Trakt redirect URI")
                AppInput(
                    value = traktRedirectUri,
                    onValueChange = { traktRedirectUri = it.take(512) },
                    placeholder = "urn:ietf:wg:oauth:2.0:oob",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))

                AppText("PLAYBACK HANDOFF", 10.sp, MarqueePalette.Gold, FontWeight.ExtraBold)
                Spacer(Modifier.height(10.dp))
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
                        feedback = controller.saveSettings(editedSettings()).fold(
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
                    AppText("TRAKT ACCOUNT", 10.sp, MarqueePalette.Gold, FontWeight.ExtraBold)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(trakt.statusColor()),
                        )
                        Spacer(Modifier.width(9.dp))
                        Column {
                            AppText(trakt.statusLabel(), 14.sp, MarqueePalette.Text, FontWeight.Bold)
                            trakt.accountName?.let {
                                AppText(it, 11.sp, MarqueePalette.Green, FontWeight.SemiBold)
                            }
                        }
                    }
                    trakt.message?.let {
                        Spacer(Modifier.height(10.dp))
                        AppText(it, 12.sp, MarqueePalette.Muted)
                    }
                    trakt.userCode?.let { code ->
                        Spacer(Modifier.height(13.dp))
                        AppText("ACTIVATION CODE", 9.sp, MarqueePalette.Muted, FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        AppText(code, 26.sp, MarqueePalette.Gold, FontWeight.Black)
                        trakt.verificationUrl?.let {
                            AppText(it, 10.sp, MarqueePalette.Muted)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            trakt.connected -> {
                                ActionButton(
                                    label = "Disconnect",
                                    onClick = controller::disconnectTrakt,
                                )
                            }
                            trakt.phase == TraktPhase.AWAITING_AUTHORIZATION -> {
                                ActionButton(
                                    label = "Open activation",
                                    primary = true,
                                    onClick = { controller.openTraktActivation().show(context) },
                                )
                            }
                            else -> {
                                ActionButton(
                                    label = "Save & connect",
                                    primary = true,
                                    enabled = trakt.phase !in setOf(
                                        TraktPhase.REQUESTING_CODE,
                                        TraktPhase.DISCONNECTING,
                                    ),
                                    onClick = {
                                        feedback = controller.saveSettings(editedSettings()).fold(
                                            onSuccess = {
                                                controller.navigate(dev.roesler.marquee.Destination.SETTINGS)
                                                controller.connectTrakt()
                                                "Settings saved. Starting Trakt connection."
                                            },
                                            onFailure = {
                                                it.message ?: "Could not save settings."
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
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
                    AppText("REAL-TIME PLAYBACK", 10.sp, MarqueePalette.Gold, FontWeight.ExtraBold)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (bridge.fullyEnabled) {
                                        MarqueePalette.Green
                                    } else {
                                        MarqueePalette.Gold
                                    },
                                ),
                        )
                        Spacer(Modifier.width(9.dp))
                        AppText(
                            if (bridge.fullyEnabled) {
                                "Second-level bridge active"
                            } else {
                                "Playback bridge needs access"
                            },
                            13.sp,
                            MarqueePalette.Text,
                            FontWeight.Bold,
                        )
                    }
                    bridge.currentProvider?.let { provider ->
                        Spacer(Modifier.height(8.dp))
                        AppText(
                            listOfNotNull(provider, bridge.currentPosition)
                                .joinToString(" · "),
                            13.sp,
                            MarqueePalette.Green,
                            FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    AppText(
                        "MediaSession position is extrapolated every second. Visible title/episode labels are captured only from supported TV players; account data and network traffic are never read.",
                        11.sp,
                        MarqueePalette.Muted,
                    )
                }
            }
            GlassPanel {
                Column {
                    AppText("PRIVACY", 10.sp, MarqueePalette.Gold, FontWeight.ExtraBold)
                    Spacer(Modifier.height(10.dp))
                    AppText(
                        "TMDB and Trakt credentials, OAuth tokens, and your local watchlist stay in app-private storage. Marquee has no analytics.",
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
            GlassPanel {
                Column {
                    AppText("DATA SOURCES", 10.sp, MarqueePalette.Gold, FontWeight.ExtraBold)
                    Spacer(Modifier.height(10.dp))
                    AppText(
                        "TVmaze supplies the keyless streaming schedule under CC BY-SA. TMDB supplies metadata and regional provider availability.",
                        12.sp,
                        MarqueePalette.Muted,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(
                            "TVmaze",
                            onClick = {
                                controller.openServicePage(
                                    "https://www.tvmaze.com",
                                    "TVmaze",
                                ).show(context)
                            },
                        )
                        ActionButton(
                            "TMDB",
                            onClick = {
                                controller.openServicePage(
                                    "https://www.themoviedb.org",
                                    "TMDB",
                                ).show(context)
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun TraktUiState.statusLabel(): String = when (phase) {
    TraktPhase.NOT_CONFIGURED -> "Not configured"
    TraktPhase.DISCONNECTED -> "Ready to connect"
    TraktPhase.REQUESTING_CODE -> "Requesting code"
    TraktPhase.AWAITING_AUTHORIZATION -> "Waiting for approval"
    TraktPhase.CONNECTED -> "Connected"
    TraktPhase.DISCONNECTING -> "Disconnecting"
    TraktPhase.ERROR -> "Needs attention"
}

private fun TraktUiState.statusColor(): Color = when (phase) {
    TraktPhase.CONNECTED -> MarqueePalette.Green
    TraktPhase.REQUESTING_CODE, TraktPhase.AWAITING_AUTHORIZATION ->
        MarqueePalette.Gold
    TraktPhase.ERROR -> MarqueePalette.Red
    else -> MarqueePalette.Muted
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
                .padding(horizontal = 36.dp),
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
                            .width(150.dp)
                            .height(225.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, MarqueePalette.Border, RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Fit,
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 10.dp),
                    ) {
                        AppText(
                            media.title,
                            34.sp,
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
                                label = when (resolver) {
                                    "" -> "Resolver not set"
                                    "com.stremio.one" -> "Play in Stremio"
                                    else -> "Open preferred"
                                },
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
                            state.details?.trailerUrl?.let {
                                ActionButton(
                                    label = "▶ Trailer",
                                    onClick = { controller.openTrailer().show(context) },
                                )
                            }
                        }
                        if (state.traktConnected) {
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ActionButton(
                                    label = when {
                                        state.traktActionLoading -> "Updating Trakt…"
                                        state.inTraktWatchlist -> "✓ Trakt watchlist"
                                        else -> "+ Trakt watchlist"
                                    },
                                    enabled = !state.traktActionLoading,
                                    onClick = controller::toggleTraktWatchlist,
                                )
                                ActionButton(
                                    label = "Mark watched on Trakt",
                                    enabled = !state.traktActionLoading,
                                    onClick = controller::markWatchedOnTrakt,
                                )
                            }
                        }
                        state.traktFeedback?.let { feedback ->
                            Spacer(Modifier.height(9.dp))
                            AppText(
                                feedback,
                                11.sp,
                                if (feedback.contains("failed", ignoreCase = true)) {
                                    MarqueePalette.Red
                                } else {
                                    MarqueePalette.Blue
                                },
                                FontWeight.Medium,
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

            val castMembers = state.details?.cast.orEmpty()
            if (castMembers.isNotEmpty()) {
                item { SectionHeading("Cast", "Select an actor for their filmography") }
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 7.dp),
                    ) {
                        items(castMembers) { person ->
                            PersonPoster(
                                person,
                                onClick = { controller.openPersonFilmography(person) },
                            )
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
        modifier = Modifier.width(120.dp),
        focusedScale = 1.06f,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RemoteImage(
                url = provider.logoUrl,
                description = provider.name,
                modifier = Modifier
                    .size(68.dp)
                    .clip(RoundedCornerShape(15.dp)),
                contentScale = ContentScale.Fit,
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
