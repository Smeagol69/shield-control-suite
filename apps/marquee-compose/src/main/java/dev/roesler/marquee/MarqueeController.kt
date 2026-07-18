package dev.roesler.marquee

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import dev.roesler.marquee.data.CatalogProvider
import dev.roesler.marquee.data.MarqueeSettings
import dev.roesler.marquee.data.MediaDetails
import dev.roesler.marquee.data.MediaItem
import dev.roesler.marquee.data.MediaRow
import dev.roesler.marquee.data.MediaRowAction
import dev.roesler.marquee.data.MediaType
import dev.roesler.marquee.data.Person
import dev.roesler.marquee.data.ProviderSort
import dev.roesler.marquee.data.SettingsStore
import dev.roesler.marquee.data.TmdbClient
import dev.roesler.marquee.data.TraktClient
import dev.roesler.marquee.data.TraktDeviceCode
import dev.roesler.marquee.data.TraktPollResult
import dev.roesler.marquee.data.TraktStore
import dev.roesler.marquee.data.TvMazeClient
import dev.roesler.marquee.data.WatchOptions
import dev.roesler.marquee.data.WatchProvider
import dev.roesler.marquee.data.WatchlistStore
import dev.roesler.marquee.playback.PlaybackMonitorService
import dev.roesler.marquee.playback.PlaybackRecord
import dev.roesler.marquee.playback.PlaybackCaptureService
import dev.roesler.marquee.playback.PlaybackStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

enum class Destination(val label: String) {
    HOME("Home"),
    PROVIDERS("Providers"),
    SEARCH("Search"),
    PEOPLE("People"),
    SETTINGS("Settings"),
}

enum class TraktPhase {
    NOT_CONFIGURED,
    DISCONNECTED,
    REQUESTING_CODE,
    AWAITING_AUTHORIZATION,
    CONNECTED,
    DISCONNECTING,
    ERROR,
}

data class TraktUiState(
    val phase: TraktPhase,
    val accountName: String? = null,
    val userCode: String? = null,
    val verificationUrl: String? = null,
    val expiresAtEpochMillis: Long? = null,
    val message: String? = null,
) {
    val connected: Boolean
        get() = phase == TraktPhase.CONNECTED
}

data class HomeUiState(
    val loading: Boolean = false,
    val rows: List<MediaRow> = emptyList(),
    val notice: String? = null,
    val error: String? = null,
)

data class ProvidersUiState(
    val loading: Boolean = false,
    val providers: List<CatalogProvider> = emptyList(),
    val selectedProvider: CatalogProvider? = null,
    val rows: List<MediaRow> = emptyList(),
    val notice: String? = null,
    val error: String? = null,
)

data class PlaybackBridgeUiState(
    val mediaSessionEnabled: Boolean,
    val visibleLabelCaptureEnabled: Boolean,
    val currentProvider: String?,
    val currentPosition: String?,
) {
    val fullyEnabled: Boolean
        get() = mediaSessionEnabled && visibleLabelCaptureEnabled
}

data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<MediaItem> = emptyList(),
    val error: String? = null,
)

data class LivePlaybackUiState(
    val providerName: String,
    val title: String?,
    val episodeLabel: String?,
    val stateLabel: String,
    val positionLabel: String,
    val durationLabel: String?,
    val progressFraction: Float?,
)

data class PeopleUiState(
    val query: String = "",
    val loading: Boolean = false,
    val people: List<Person> = emptyList(),
    val selectedName: String? = null,
    val credits: List<MediaItem> = emptyList(),
    val error: String? = null,
)

data class DetailUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val seed: MediaItem? = null,
    val details: MediaDetails? = null,
    val watchOptions: WatchOptions = WatchOptions(emptyList(), null),
    val recommendations: List<MediaItem> = emptyList(),
    val inWatchlist: Boolean = false,
    val inTraktWatchlist: Boolean = false,
    val traktConnected: Boolean = false,
    val traktActionLoading: Boolean = false,
    val traktFeedback: String? = null,
    val error: String? = null,
)

class MarqueeController(context: Context) {
    private val appContext = context.applicationContext
    private val settingsStore = SettingsStore(appContext)
    private val watchlistStore = WatchlistStore(appContext)
    private val traktStore = TraktStore(appContext)
    private val tmdbClient = TmdbClient(settingsStore)
    private val traktClient = TraktClient(settingsStore, traktStore)
    private val tvMazeClient = TvMazeClient()
    private val launcher = ProviderLauncher(appContext)
    private val playbackStore = PlaybackStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var homeJob: Job? = null
    private var providerJob: Job? = null
    private var searchJob: Job? = null
    private var peopleJob: Job? = null
    private var detailJob: Job? = null
    private var traktAuthJob: Job? = null
    private var traktProfileJob: Job? = null
    private var traktActionJob: Job? = null
    private var becauseYouLiked: MediaRow? = null
    private var traktWatchlistKeys: Set<String> = emptySet()

    private val _destination = MutableStateFlow(Destination.HOME)
    val destination: StateFlow<Destination> = _destination.asStateFlow()

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _providers = MutableStateFlow(ProvidersUiState())
    val providers: StateFlow<ProvidersUiState> = _providers.asStateFlow()

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private val _people = MutableStateFlow(PeopleUiState())
    val people: StateFlow<PeopleUiState> = _people.asStateFlow()

    private val _detail = MutableStateFlow(DetailUiState())
    val detail: StateFlow<DetailUiState> = _detail.asStateFlow()

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<MarqueeSettings> = _settings.asStateFlow()

    private val _trakt = MutableStateFlow(initialTraktState())
    val trakt: StateFlow<TraktUiState> = _trakt.asStateFlow()

    private val _livePlayback = MutableStateFlow<LivePlaybackUiState?>(null)
    val livePlayback: StateFlow<LivePlaybackUiState?> = _livePlayback.asStateFlow()

    init {
        PlaybackMonitorService.requestConnection(appContext)
        scope.launch { monitorLivePlayback() }
        if (_settings.value.tmdbCredential.isBlank()) {
            _destination.value = Destination.SETTINGS
        } else {
            refreshHome()
        }
        if (traktStore.loadTokens() != null) refreshTraktProfile()
    }

    fun navigate(destination: Destination) {
        _destination.value = destination
        if (destination == Destination.HOME && _home.value.rows.isEmpty()) refreshHome()
        if (destination == Destination.PROVIDERS && _providers.value.providers.isEmpty()) {
            refreshProviders()
        }
    }

    fun refreshHome() {
        if (_settings.value.tmdbCredential.isBlank()) {
            _home.value = HomeUiState(error = "Add a TMDB credential in Settings.")
            return
        }
        homeJob?.cancel()
        val includeTrakt = _trakt.value.connected
        homeJob = scope.launch {
            _home.value = _home.value.copy(loading = true, error = null, notice = null)
            serviceResult {
                withContext(Dispatchers.IO) { loadHome(includeTrakt) }
            }.onSuccess { result ->
                traktWatchlistKeys = result.traktWatchlistKeys
                val rows = buildList {
                    result.localWatchlist.takeIf(List<MediaItem>::isNotEmpty)?.let {
                        add(MediaRow("My watchlist", it, "Saved on this Shield"))
                    }
                    result.localPlayback.takeIf(List<MediaItem>::isNotEmpty)?.let {
                        add(
                            MediaRow(
                                "Continue watching on this Shield",
                                it,
                                "Second-level progress captured locally",
                                MediaRowAction.CONTINUE_LOCAL,
                            ),
                        )
                    }
                    addAll(result.traktRows)
                    result.tvMazeRow?.takeIf { it.items.isNotEmpty() }?.let(::add)
                    becauseYouLiked?.let(::add)
                    addAll(result.tmdbRows)
                }
                _home.value = HomeUiState(
                    rows = rows,
                    notice = result.warnings.takeIf(List<String>::isNotEmpty)
                        ?.joinToString("  ·  "),
                    error = if (rows.isEmpty()) {
                        result.warnings.firstOrNull() ?: "No discovery rows are available."
                    } else {
                        null
                    },
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _home.value = HomeUiState(error = error.userMessage())
            }
        }
    }

    fun searchTitles(query: String) {
        _search.value = _search.value.copy(query = query, error = null)
        searchJob?.cancel()
        if (query.isBlank()) {
            _search.value = SearchUiState()
            return
        }
        searchJob = scope.launch {
            delay(350)
            _search.value = _search.value.copy(loading = true)
            serviceResult {
                withContext(Dispatchers.IO) { tmdbClient.searchTitles(query.trim()) }
            }.onSuccess {
                _search.value = _search.value.copy(loading = false, results = it)
            }.onFailure {
                if (it is CancellationException) throw it
                _search.value = _search.value.copy(
                    loading = false,
                    error = it.userMessage(),
                )
            }
        }
    }

    fun refreshProviders() {
        if (_settings.value.tmdbCredential.isBlank()) {
            _providers.value = ProvidersUiState(
                error = "Add a TMDB credential in Settings.",
            )
            return
        }
        providerJob?.cancel()
        providerJob = scope.launch {
            _providers.value = _providers.value.copy(
                loading = true,
                error = null,
                notice = null,
            )
            val available = serviceResult {
                withContext(Dispatchers.IO) { tmdbClient.catalogProviders() }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                _providers.value = _providers.value.copy(
                    loading = false,
                    error = error.userMessage(),
                )
                return@launch
            }
            if (available.isEmpty()) {
                _providers.value = ProvidersUiState(
                    error = "TMDB returned no streaming providers for this region.",
                )
                return@launch
            }

            val providers = available.withIndex()
                .sortedWith(
                    compareByDescending<IndexedValue<CatalogProvider>> {
                        launcher.isInstalled(it.value.packageName)
                    }.thenBy(IndexedValue<CatalogProvider>::index),
                )
                .map(IndexedValue<CatalogProvider>::value)
            val rememberedId = settingsStore.lastProviderId()
            val selected = providers.firstOrNull { it.id == rememberedId }
                ?: providers.firstOrNull { launcher.isInstalled(it.packageName) }
                ?: providers.first()
            _providers.value = ProvidersUiState(
                loading = true,
                providers = providers,
                selectedProvider = selected,
            )
            settingsStore.saveLastProviderId(selected.id)
            loadProviderCatalog(selected)
        }
    }

    fun selectProvider(provider: CatalogProvider) {
        if (_providers.value.selectedProvider?.id == provider.id && _providers.value.rows.isNotEmpty()) {
            return
        }
        settingsStore.saveLastProviderId(provider.id)
        providerJob?.cancel()
        providerJob = scope.launch {
            loadProviderCatalog(provider)
        }
    }

    fun searchPeople(query: String) {
        _people.value = _people.value.copy(
            query = query,
            selectedName = null,
            credits = emptyList(),
            error = null,
        )
        peopleJob?.cancel()
        if (query.isBlank()) {
            _people.value = PeopleUiState()
            return
        }
        peopleJob = scope.launch {
            delay(350)
            _people.value = _people.value.copy(loading = true)
            serviceResult {
                withContext(Dispatchers.IO) { tmdbClient.searchPeople(query.trim()) }
            }.onSuccess {
                _people.value = _people.value.copy(loading = false, people = it)
            }.onFailure {
                if (it is CancellationException) throw it
                _people.value = _people.value.copy(
                    loading = false,
                    error = it.userMessage(),
                )
            }
        }
    }

    fun selectPerson(person: Person) {
        peopleJob?.cancel()
        peopleJob = scope.launch {
            _people.value = _people.value.copy(
                loading = true,
                selectedName = person.name,
                credits = emptyList(),
                error = null,
            )
            serviceResult {
                withContext(Dispatchers.IO) { tmdbClient.personCredits(person.id) }
            }.onSuccess {
                _people.value = _people.value.copy(loading = false, credits = it)
            }.onFailure {
                if (it is CancellationException) throw it
                _people.value = _people.value.copy(
                    loading = false,
                    error = it.userMessage(),
                )
            }
        }
    }

    fun openDetails(item: MediaItem) {
        detailJob?.cancel()
        _detail.value = DetailUiState(
            visible = true,
            loading = true,
            seed = item,
            inWatchlist = watchlistStore.contains(item),
            inTraktWatchlist = item.key() in traktWatchlistKeys,
            traktConnected = _trakt.value.connected,
        )
        detailJob = scope.launch {
            serviceResult {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val details = async { tmdbClient.details(item) }
                        val providers = async { tmdbClient.watchOptions(item) }
                        val recommendations = async { tmdbClient.recommendations(item) }
                        Triple(details.await(), providers.await(), recommendations.await())
                    }
                }
            }.onSuccess { (details, providers, recommendations) ->
                _detail.value = _detail.value.copy(
                    loading = false,
                    details = details,
                    watchOptions = providers,
                    recommendations = recommendations,
                )
                if (recommendations.isNotEmpty()) {
                    becauseYouLiked = MediaRow(
                        "Because you liked ${details.item.title}",
                        recommendations,
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _detail.value = _detail.value.copy(
                    loading = false,
                    error = error.userMessage(),
                )
            }
        }
    }

    fun closeDetails() {
        detailJob?.cancel()
        traktActionJob?.cancel()
        detailJob = null
        traktActionJob = null
        _detail.value = DetailUiState()
    }

    fun toggleWatchlist(): Boolean {
        val item = currentItem() ?: return false
        val saved = watchlistStore.toggle(item)
        _detail.value = _detail.value.copy(inWatchlist = saved)
        return saved
    }

    fun toggleTraktWatchlist() {
        val item = currentItem() ?: return
        if (!_trakt.value.connected) {
            _detail.value = _detail.value.copy(traktFeedback = "Connect Trakt in Settings first.")
            return
        }
        if (_detail.value.traktActionLoading) return
        val shouldSave = !_detail.value.inTraktWatchlist
        traktActionJob?.cancel()
        traktActionJob = scope.launch {
            _detail.value = _detail.value.copy(
                traktActionLoading = true,
                traktFeedback = null,
            )
            serviceResult {
                withContext(Dispatchers.IO) {
                    traktClient.setWatchlisted(item, shouldSave)
                }
            }.onSuccess {
                traktWatchlistKeys = if (shouldSave) {
                    traktWatchlistKeys + item.key()
                } else {
                    traktWatchlistKeys - item.key()
                }
                _detail.value = _detail.value.copy(
                    inTraktWatchlist = shouldSave,
                    traktActionLoading = false,
                    traktFeedback = if (shouldSave) {
                        "Added to your Trakt watchlist."
                    } else {
                        "Removed from your Trakt watchlist."
                    },
                )
            }.onFailure {
                if (it is CancellationException) throw it
                _detail.value = _detail.value.copy(
                    traktActionLoading = false,
                    traktFeedback = it.userMessage(),
                )
            }
        }
    }

    fun markWatchedOnTrakt() {
        val item = currentItem() ?: return
        if (!_trakt.value.connected) {
            _detail.value = _detail.value.copy(traktFeedback = "Connect Trakt in Settings first.")
            return
        }
        if (_detail.value.traktActionLoading) return
        traktActionJob?.cancel()
        traktActionJob = scope.launch {
            _detail.value = _detail.value.copy(
                traktActionLoading = true,
                traktFeedback = null,
            )
            serviceResult {
                withContext(Dispatchers.IO) { traktClient.markWatched(item) }
            }.onSuccess {
                _detail.value = _detail.value.copy(
                    traktActionLoading = false,
                    traktFeedback = "Marked watched on Trakt.",
                )
            }.onFailure {
                if (it is CancellationException) throw it
                _detail.value = _detail.value.copy(
                    traktActionLoading = false,
                    traktFeedback = it.userMessage(),
                )
            }
        }
    }

    fun saveSettings(settings: MarqueeSettings): Result<Unit> {
        val previous = _settings.value
        val normalized = settings.copy(
            tmdbCredential = settings.tmdbCredential.trim(),
            region = settings.region.trim().uppercase(),
            preferredResolverPackage = settings.preferredResolverPackage.trim(),
            traktClientId = settings.traktClientId.trim(),
            traktClientSecret = settings.traktClientSecret.trim(),
            traktRedirectUri = settings.traktRedirectUri.trim(),
        )
        validateSettings(normalized)?.let {
            return Result.failure(IllegalArgumentException(it))
        }

        val traktCredentialsChanged =
            previous.traktClientId != normalized.traktClientId ||
                previous.traktClientSecret != normalized.traktClientSecret ||
                previous.traktRedirectUri != normalized.traktRedirectUri
        if (traktCredentialsChanged) {
            traktAuthJob?.cancel()
            traktStore.clear()
            traktWatchlistKeys = emptySet()
        }

        settingsStore.save(normalized)
        _settings.value = settingsStore.load()
        if (
            previous.tmdbCredential != normalized.tmdbCredential ||
            previous.region != normalized.region
        ) {
            providerJob?.cancel()
            _providers.value = ProvidersUiState()
        }
        if (traktCredentialsChanged || normalized.traktClientId.isBlank()) {
            _trakt.value = initialTraktState()
        }
        _destination.value = Destination.HOME
        refreshHome()
        return Result.success(Unit)
    }

    fun connectTrakt() {
        val settings = _settings.value
        if (settings.traktClientId.isBlank() || settings.traktClientSecret.isBlank()) {
            _trakt.value = TraktUiState(
                phase = TraktPhase.NOT_CONFIGURED,
                message = "Save a Trakt client ID and secret first.",
            )
            return
        }
        traktAuthJob?.cancel()
        traktAuthJob = scope.launch {
            _trakt.value = TraktUiState(
                phase = TraktPhase.REQUESTING_CODE,
                message = "Requesting a secure Trakt device code…",
            )
            val deviceCode = serviceResult {
                withContext(Dispatchers.IO) { traktClient.requestDeviceCode() }
            }.getOrElse {
                if (it is CancellationException) throw it
                _trakt.value = TraktUiState(TraktPhase.ERROR, message = it.userMessage())
                return@launch
            }
            awaitTraktAuthorization(deviceCode)
        }
    }

    fun disconnectTrakt() {
        traktAuthJob?.cancel()
        traktProfileJob?.cancel()
        _trakt.value = _trakt.value.copy(
            phase = TraktPhase.DISCONNECTING,
            message = "Disconnecting Trakt…",
            userCode = null,
            verificationUrl = null,
        )
        traktAuthJob = scope.launch {
            val result = serviceResult {
                withContext(Dispatchers.IO) { traktClient.revokeAndClear() }
            }
            traktWatchlistKeys = emptySet()
            _trakt.value = TraktUiState(
                phase = if (_settings.value.traktClientId.isBlank()) {
                    TraktPhase.NOT_CONFIGURED
                } else {
                    TraktPhase.DISCONNECTED
                },
                message = result.exceptionOrNull()?.let {
                    "Local connection cleared; remote revoke was not confirmed."
                },
            )
            refreshHome()
        }
    }

    fun openProvider(provider: WatchProvider): LaunchResult =
        launcher.openProvider(provider)

    fun openCatalogProvider(provider: CatalogProvider): LaunchResult =
        launcher.openCatalogProvider(provider)

    fun openPreferredResolver(item: MediaItem): LaunchResult =
        launcher.openResolver(_settings.value.preferredResolverPackage, item)

    fun continueLocalPlayback(item: MediaItem): LaunchResult {
        val record = buildList {
            playbackStore.current()?.let(::add)
            addAll(playbackStore.history())
        }.firstOrNull { playback ->
            playback.media?.let { it.id == item.id && it.type == item.type } == true
        } ?: return openPreferredResolver(item)
        return launcher.openResolver(record.packageName, item)
    }

    fun openWebOptions(): LaunchResult =
        launcher.openWebOptions(_detail.value.watchOptions.webLink)

    fun openTraktActivation(): LaunchResult {
        val state = _trakt.value
        val baseUrl = state.verificationUrl
            ?: return LaunchResult.Unavailable("Start Trakt connection first.")
        val url = state.userCode?.let { "$baseUrl/$it" } ?: baseUrl
        return launcher.openServicePage(url, "Trakt activation")
    }

    fun openServicePage(url: String, label: String): LaunchResult =
        launcher.openServicePage(url, label)

    fun isInstalled(packageName: String?): Boolean = launcher.isInstalled(packageName)

    fun playbackBridgeStatus(): PlaybackBridgeUiState {
        val notificationAccess = NotificationManagerCompat
            .getEnabledListenerPackages(appContext)
            .contains(appContext.packageName)
        val captureComponent = ComponentName(
            appContext,
            PlaybackCaptureService::class.java,
        ).flattenToString()
        val accessibilityServices = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val captureEnabled = accessibilityServices
            .split(':')
            .any { it.equals(captureComponent, ignoreCase = true) }
        val current = playbackStore.current()
            ?.takeIf {
                it.active &&
                    System.currentTimeMillis() - it.observedAtEpochMillis <=
                    LIVE_SESSION_MAX_AGE_MS
            }
        return PlaybackBridgeUiState(
            mediaSessionEnabled = notificationAccess,
            visibleLabelCaptureEnabled = captureEnabled,
            currentProvider = current?.providerName,
            currentPosition = current?.positionAt(System.currentTimeMillis())
                ?.let(::formatPlaybackTime),
        )
    }

    fun onBack(): Boolean =
        when {
            _detail.value.visible -> {
                closeDetails()
                true
            }
            _destination.value != Destination.HOME -> {
                navigate(Destination.HOME)
                true
            }
            else -> false
        }

    fun close() {
        scope.cancel()
    }

    private suspend fun loadProviderCatalog(provider: CatalogProvider) {
        val providerList = _providers.value.providers
        _providers.value = ProvidersUiState(
            loading = true,
            providers = providerList,
            selectedProvider = provider,
        )
        serviceResult {
            withContext(Dispatchers.IO) { buildProviderCatalog(provider) }
        }.onSuccess { result ->
            _providers.value = ProvidersUiState(
                providers = providerList,
                selectedProvider = provider,
                rows = result.rows,
                notice = result.warnings.takeIf(List<String>::isNotEmpty)
                    ?.joinToString(" · "),
                error = if (result.rows.isEmpty()) {
                    result.warnings.firstOrNull()
                        ?: "No ${provider.name} catalog rows are available."
                } else {
                    null
                },
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            _providers.value = ProvidersUiState(
                providers = providerList,
                selectedProvider = provider,
                error = error.userMessage(),
            )
        }
    }

    private suspend fun monitorLivePlayback() {
        var lastResolveAttempt: String? = null
        while (true) {
            var record = playbackStore.current()
            val now = System.currentTimeMillis()
            val fresh = record?.active == true &&
                now - record.observedAtEpochMillis <= LIVE_SESSION_MAX_AGE_MS
            if (
                fresh &&
                record.media == null &&
                !record.capturedTitle.isNullOrBlank() &&
                record.identityKey != lastResolveAttempt
            ) {
                lastResolveAttempt = record.identityKey
                val resolved = withContext(Dispatchers.IO) {
                    serviceResult { resolvePlaybackRecord(record) }.getOrNull()
                }
                if (resolved != null) {
                    playbackStore.attachMedia(record, resolved)
                    record = playbackStore.current()
                }
            }
            val liveRecord = record.takeIf { fresh }
            _livePlayback.value = liveRecord?.toLivePlaybackUiState()
            applyLivePlayback(liveRecord)
            delay(LIVE_REFRESH_INTERVAL_MS)
        }
    }

    private fun PlaybackRecord.toLivePlaybackUiState(): LivePlaybackUiState {
        val now = System.currentTimeMillis()
        val effectivePosition = positionAt(now)
        return LivePlaybackUiState(
            providerName = providerName,
            title = media?.title ?: capturedTitle,
            episodeLabel = episodeLabel,
            stateLabel = when (state) {
                android.media.session.PlaybackState.STATE_PLAYING -> "PLAYING"
                android.media.session.PlaybackState.STATE_PAUSED -> "PAUSED"
                android.media.session.PlaybackState.STATE_BUFFERING -> "BUFFERING"
                else -> "ACTIVE"
            },
            positionLabel = formatPlaybackTime(effectivePosition),
            durationLabel = durationMs?.let(::formatPlaybackTime),
            progressFraction = durationMs
                ?.takeIf { it > 0L }
                ?.let {
                    (effectivePosition.toDouble() / it.toDouble())
                        .coerceIn(0.0, 1.0)
                        .toFloat()
                },
        )
    }

    private fun applyLivePlayback(record: PlaybackRecord?) {
        val liveItem = record?.asMediaItem()
        _home.value = _home.value.copy(
            rows = withLivePlayback(_home.value.rows, record, liveItem),
        )
        val selected = _providers.value.selectedProvider
        val providerRecord = record.takeIf {
            selected?.packageName != null && selected.packageName == it?.packageName
        }
        _providers.value = _providers.value.copy(
            rows = withLivePlayback(
                _providers.value.rows,
                providerRecord,
                providerRecord?.asMediaItem(),
            ),
        )
    }

    private fun withLivePlayback(
        rows: List<MediaRow>,
        record: PlaybackRecord?,
        liveItem: MediaItem?,
    ): List<MediaRow> {
        val withoutLive = rows.filterNot { it.title == LIVE_ROW_TITLE }
        if (record == null || liveItem == null) return withoutLive
        val key = liveItem.key()
        val refreshed = withoutLive.map { row ->
            row.copy(
                items = row.items.map { item ->
                    if (item.key() == key) liveItem else item
                },
            )
        }
        return listOf(
            MediaRow(
                title = LIVE_ROW_TITLE,
                items = listOf(liveItem),
                subtitle = "${record.providerName} · live from this Shield",
                action = MediaRowAction.CONTINUE_LOCAL,
            ),
        ) + refreshed
    }

    private fun resolvePlaybackRecord(record: PlaybackRecord): MediaItem? {
        record.media?.let { return it }
        val title = record.capturedTitle?.trim().orEmpty()
        if (title.isBlank()) return null
        return tmdbClient.searchTitles(title)
            .asSequence()
            .sortedWith(
                compareByDescending<MediaItem> {
                    it.title.equals(title, ignoreCase = true)
                }.thenByDescending(MediaItem::rating),
            )
            .firstOrNull()
    }

    private fun resolvedPlaybackItems(records: List<PlaybackRecord>): List<MediaItem> =
        records.asSequence()
            .filter { it.isContinuable() }
            .take(LOCAL_PLAYBACK_LIMIT)
            .mapNotNull { record ->
                val resolved = resolvePlaybackRecord(record) ?: return@mapNotNull null
                if (record.media == null) playbackStore.attachMedia(record, resolved)
                record.copy(media = resolved).asMediaItem()
            }
            .distinctBy { it.key() }
            .toList()

    private suspend fun buildProviderCatalog(provider: CatalogProvider): ProviderLoad =
        coroutineScope {
            fun discoveryRow(
                title: String,
                type: MediaType,
                sort: ProviderSort,
                genreId: Int? = null,
            ) = async {
                serviceResult {
                    MediaRow(
                        title = title,
                        items = tmdbClient.providerTitles(provider.id, type, sort, genreId),
                        subtitle = "${provider.name} · ${_settings.value.region}",
                    )
                }
            }

            val discoveryTasks = listOf(
                "Popular movies" to discoveryRow(
                    "Popular movies",
                    MediaType.MOVIE,
                    ProviderSort.POPULAR,
                ),
                "Popular series" to discoveryRow(
                    "Popular series",
                    MediaType.TV,
                    ProviderSort.POPULAR,
                ),
                "New movies" to discoveryRow(
                    "New movies",
                    MediaType.MOVIE,
                    ProviderSort.NEWEST,
                ),
                "New series" to discoveryRow(
                    "New series",
                    MediaType.TV,
                    ProviderSort.NEWEST,
                ),
                "Top rated movies" to discoveryRow(
                    "Top rated movies",
                    MediaType.MOVIE,
                    ProviderSort.TOP_RATED,
                ),
                "Top rated series" to discoveryRow(
                    "Top rated series",
                    MediaType.TV,
                    ProviderSort.TOP_RATED,
                ),
                "Family night" to discoveryRow(
                    "Family night",
                    MediaType.MOVIE,
                    ProviderSort.POPULAR,
                    MOVIE_GENRE_FAMILY,
                ),
                "Comedy" to discoveryRow(
                    "Comedy",
                    MediaType.MOVIE,
                    ProviderSort.POPULAR,
                    MOVIE_GENRE_COMEDY,
                ),
            )

            val includeTrakt = _trakt.value.connected
            val playbackTask = includeTrakt.takeIf { it }?.let {
                async { serviceResult { traktClient.playbackProgress() } }
            }
            val recommendationMoviesTask = includeTrakt.takeIf { it }?.let {
                async { serviceResult { traktClient.recommendations(MediaType.MOVIE) } }
            }
            val recommendationShowsTask = includeTrakt.takeIf { it }?.let {
                async { serviceResult { traktClient.recommendations(MediaType.TV) } }
            }

            val warnings = mutableListOf<String>()
            val localPlayback = provider.packageName
                ?.let { packageName ->
                    resolvedPlaybackItems(
                        playbackStore.history().filter { it.packageName == packageName },
                    )
                }
                .orEmpty()
            val discoveryRows = discoveryTasks.mapNotNull { (label, task) ->
                task.await().fold(
                    onSuccess = { it.takeIf { row -> row.items.isNotEmpty() } },
                    onFailure = {
                        warnings += "$label unavailable"
                        null
                    },
                )
            }

            val personalizedRows = mutableListOf<MediaRow>()
            localPlayback.takeIf(List<MediaItem>::isNotEmpty)?.let {
                personalizedRows += MediaRow(
                    title = "Continue watching on this Shield",
                    items = it,
                    subtitle = "Second-level local progress · ${provider.name}",
                    action = MediaRowAction.CONTINUE_LOCAL,
                )
            }
            if (includeTrakt) {
                val playback = checkNotNull(playbackTask).await().getOrElse {
                    warnings += "Trakt playback progress unavailable"
                    emptyList()
                }
                val continueWatching = availableOnProvider(
                    playback,
                    provider.id,
                    PLAYBACK_FILTER_LIMIT,
                ).filterNot { traktItem ->
                    localPlayback.any { it.key() == traktItem.key() }
                }
                continueWatching.takeIf(List<MediaItem>::isNotEmpty)?.let {
                    personalizedRows += MediaRow(
                        title = "Continue watching",
                        items = it,
                        subtitle = "Synced progress from Trakt · available on ${provider.name}",
                    )
                }

                val recommendations = (
                    checkNotNull(recommendationMoviesTask).await().getOrElse {
                        warnings += "Trakt movie recommendations unavailable"
                        emptyList()
                    } +
                        checkNotNull(recommendationShowsTask).await().getOrElse {
                            warnings += "Trakt show recommendations unavailable"
                            emptyList()
                        }
                    ).distinctBy { it.key() }
                val availableRecommendations = availableOnProvider(
                    recommendations,
                    provider.id,
                    RECOMMENDATION_FILTER_LIMIT,
                )
                availableRecommendations.takeIf(List<MediaItem>::isNotEmpty)?.let {
                    personalizedRows += MediaRow(
                        title = "For you",
                        items = it,
                        subtitle = "Recommended by Trakt · available on ${provider.name}",
                    )
                }
            } else {
                warnings += "Connect Trakt for Continue Watching and personalized rows"
            }

            ProviderLoad(
                rows = personalizedRows + discoveryRows,
                warnings = warnings,
            )
        }

    private suspend fun availableOnProvider(
        items: List<MediaItem>,
        providerId: Int,
        limit: Int,
    ): List<MediaItem> {
        val unique = items.distinctBy { it.key() }.take(limit)
        return buildList {
            unique.chunked(AVAILABILITY_CONCURRENCY).forEach { chunk ->
                addAll(
                    coroutineScope {
                        chunk.map { item ->
                            async {
                                item.takeIf {
                                    serviceResult {
                                        tmdbClient.isAvailableOn(item, providerId)
                                    }.getOrDefault(false)
                                }
                            }
                        }.awaitAll().filterNotNull()
                    },
                )
            }
        }
    }

    private suspend fun loadHome(includeTrakt: Boolean): HomeLoad = coroutineScope {
        val localPlaybackTask = async {
            serviceResult { resolvedPlaybackItems(playbackStore.history()) }
        }
        val tmdbTasks = listOf(
            "Trending" to async {
                serviceResult { MediaRow("Trending this week", tmdbClient.trending()) }
            },
            "Popular movies" to async {
                serviceResult { MediaRow("Popular movies", tmdbClient.popularMovies()) }
            },
            "Popular TV" to async {
                serviceResult { MediaRow("Popular on TV", tmdbClient.popularTv()) }
            },
            "Now playing" to async {
                serviceResult { MediaRow("Now playing", tmdbClient.nowPlaying()) }
            },
            "Top rated" to async {
                serviceResult { MediaRow("Top rated", tmdbClient.topRatedMovies()) }
            },
        )
        val tvMazeTask = async { serviceResult { streamingTodayRow() } }

        val traktRecommendationMovies = includeTrakt.takeIf { it }?.let {
            async { serviceResult { traktClient.recommendations(MediaType.MOVIE) } }
        }
        val traktRecommendationShows = includeTrakt.takeIf { it }?.let {
            async { serviceResult { traktClient.recommendations(MediaType.TV) } }
        }
        val traktWatchlistMovies = includeTrakt.takeIf { it }?.let {
            async { serviceResult { traktClient.watchlist(MediaType.MOVIE) } }
        }
        val traktWatchlistShows = includeTrakt.takeIf { it }?.let {
            async { serviceResult { traktClient.watchlist(MediaType.TV) } }
        }
        val traktHistory = includeTrakt.takeIf { it }?.let {
            async { serviceResult { traktClient.recentHistory() } }
        }
        val traktPlayback = includeTrakt.takeIf { it }?.let {
            async { serviceResult { traktClient.playbackProgress() } }
        }

        val warnings = mutableListOf<String>()
        val localPlayback = localPlaybackTask.await().getOrElse {
            warnings += "Local playback history unavailable"
            emptyList()
        }
        val tmdbRows = tmdbTasks.mapNotNull { (label, task) ->
            task.await().fold(
                onSuccess = { it.takeIf { row -> row.items.isNotEmpty() } },
                onFailure = {
                    warnings += "$label unavailable"
                    null
                },
            )
        }
        val tvMazeRow = tvMazeTask.await().fold(
            onSuccess = { it.takeIf { row -> row.items.isNotEmpty() } },
            onFailure = {
                warnings += "TVmaze schedule unavailable"
                null
            },
        )

        val traktRows = mutableListOf<MediaRow>()
        var traktWatchlist = emptyList<MediaItem>()
        if (includeTrakt) {
            fun collectTrakt(result: Result<List<MediaItem>>, label: String): List<MediaItem> =
                result.getOrElse {
                    warnings += "$label unavailable"
                    emptyList()
                }

            val recommendedMovies = collectTrakt(
                checkNotNull(traktRecommendationMovies).await(),
                "Trakt movie recommendations",
            )
            val recommendedShows = collectTrakt(
                checkNotNull(traktRecommendationShows).await(),
                "Trakt show recommendations",
            )
            traktWatchlist = (
                collectTrakt(
                    checkNotNull(traktWatchlistMovies).await(),
                    "Trakt movie watchlist",
                ) +
                    collectTrakt(
                        checkNotNull(traktWatchlistShows).await(),
                        "Trakt show watchlist",
                    )
                ).distinctBy { it.key() }
            val recent = collectTrakt(
                checkNotNull(traktHistory).await(),
                "Trakt history",
            )
            val playback = collectTrakt(
                checkNotNull(traktPlayback).await(),
                "Trakt playback progress",
            ).filterNot { traktItem ->
                localPlayback.any { it.key() == traktItem.key() }
            }

            playback.takeIf(List<MediaItem>::isNotEmpty)?.let {
                traktRows += MediaRow(
                    "Continue watching",
                    it,
                    "Synced playback progress from Trakt",
                )
            }
            traktWatchlist.takeIf(List<MediaItem>::isNotEmpty)?.let {
                traktRows += MediaRow("Your Trakt watchlist", it, "Synced with Trakt")
            }
            recommendedMovies.takeIf(List<MediaItem>::isNotEmpty)?.let {
                traktRows += MediaRow("Movies for you", it, "Personalized by Trakt")
            }
            recommendedShows.takeIf(List<MediaItem>::isNotEmpty)?.let {
                traktRows += MediaRow("Shows for you", it, "Personalized by Trakt")
            }
            recent.takeIf(List<MediaItem>::isNotEmpty)?.let {
                traktRows += MediaRow("Recently watched", it, "From your Trakt history")
            }
        }

        HomeLoad(
            localWatchlist = watchlistStore.load(),
            localPlayback = localPlayback,
            tmdbRows = tmdbRows,
            traktRows = traktRows,
            tvMazeRow = tvMazeRow,
            traktWatchlistKeys = traktWatchlist.mapTo(linkedSetOf()) { it.key() },
            warnings = warnings,
        )
    }

    private fun streamingTodayRow(): MediaRow {
        val items = tvMazeClient.streamingToday().mapNotNull { scheduled ->
            serviceResult {
                tmdbClient.findTv(scheduled.imdbId, scheduled.title, scheduled.year)
            }.getOrNull()?.let { item ->
                val scheduleLine = listOfNotNull(
                    scheduled.service,
                    scheduled.episodeLabel,
                ).joinToString(" · ")
                item.copy(
                    overview = if (scheduleLine.isBlank()) {
                        item.overview
                    } else {
                        "$scheduleLine — ${item.overview}".trimEnd(' ', '—')
                    },
                )
            }
        }.distinctBy { it.key() }
        return MediaRow(
            title = "Streaming today",
            items = items,
            subtitle = "Schedule data by TVmaze",
        )
    }

    private fun refreshTraktProfile() {
        traktProfileJob?.cancel()
        traktProfileJob = scope.launch {
            serviceResult {
                withContext(Dispatchers.IO) { traktClient.profile() }
            }.onSuccess { profile ->
                _trakt.value = TraktUiState(
                    phase = TraktPhase.CONNECTED,
                    accountName = profile.displayName ?: profile.username,
                    message = "History, watchlist, and recommendations are synced.",
                )
            }.onFailure {
                if (it is CancellationException) throw it
                val stillHasTokens = traktStore.loadTokens() != null
                _trakt.value = TraktUiState(
                    phase = if (stillHasTokens) TraktPhase.CONNECTED else TraktPhase.DISCONNECTED,
                    accountName = traktStore.accountName(),
                    message = it.userMessage(),
                )
            }
        }
    }

    private suspend fun awaitTraktAuthorization(code: TraktDeviceCode) {
        _trakt.value = TraktUiState(
            phase = TraktPhase.AWAITING_AUTHORIZATION,
            userCode = code.userCode,
            verificationUrl = code.verificationUrl,
            expiresAtEpochMillis = code.expiresAtEpochMillis,
            message = "Open the activation page on your phone and approve this code.",
        )
        var intervalSeconds = code.pollingIntervalSeconds
        while (System.currentTimeMillis() < code.expiresAtEpochMillis) {
            delay(intervalSeconds * 1_000L)
            when (
                val result = serviceResult {
                    withContext(Dispatchers.IO) {
                        traktClient.pollDeviceToken(code.deviceCode)
                    }
                }.getOrElse {
                    if (it is CancellationException) throw it
                    _trakt.value = TraktUiState(TraktPhase.ERROR, message = it.userMessage())
                    return
                }
            ) {
                TraktPollResult.Pending -> Unit
                is TraktPollResult.SlowDown -> {
                    intervalSeconds = (intervalSeconds + result.additionalSeconds).coerceAtMost(60)
                }
                is TraktPollResult.Failed -> {
                    _trakt.value = TraktUiState(TraktPhase.ERROR, message = result.message)
                    return
                }
                is TraktPollResult.Authorized -> {
                    val profile = serviceResult {
                        withContext(Dispatchers.IO) { traktClient.profile() }
                    }.getOrNull()
                    _trakt.value = TraktUiState(
                        phase = TraktPhase.CONNECTED,
                        accountName = profile?.displayName ?: profile?.username,
                        message = "Trakt connected.",
                    )
                    refreshHome()
                    return
                }
            }
        }
        _trakt.value = TraktUiState(
            phase = TraktPhase.ERROR,
            message = "The Trakt device code expired. Start again.",
        )
    }

    private fun initialTraktState(): TraktUiState {
        val configured =
            _settings.value.traktClientId.isNotBlank() &&
                _settings.value.traktClientSecret.isNotBlank()
        val connected = traktStore.loadTokens() != null
        return TraktUiState(
            phase = when {
                !configured -> TraktPhase.NOT_CONFIGURED
                connected -> TraktPhase.CONNECTED
                else -> TraktPhase.DISCONNECTED
            },
            accountName = traktStore.accountName(),
        )
    }

    private fun validateSettings(settings: MarqueeSettings): String? {
        if (settings.tmdbCredential.isBlank()) return "A TMDB credential is required."
        if (!Regex("^[A-Z]{2}$").matches(settings.region)) {
            return "Region must be a two-letter country code."
        }
        if (
            settings.preferredResolverPackage.isNotBlank() &&
            !ANDROID_PACKAGE.matches(settings.preferredResolverPackage)
        ) {
            return "Resolver must be an Android package name."
        }
        val hasTraktId = settings.traktClientId.isNotBlank()
        val hasTraktSecret = settings.traktClientSecret.isNotBlank()
        if (hasTraktId != hasTraktSecret) {
            return "Trakt client ID and secret must be entered together."
        }
        if (hasTraktId && !isValidRedirectUri(settings.traktRedirectUri)) {
            return "Trakt redirect URI must include a valid scheme."
        }
        return null
    }

    private fun isValidRedirectUri(value: String): Boolean =
        value.length <= 512 &&
            !value.any(Char::isWhitespace) &&
            runCatching { URI.create(value).scheme?.isNotBlank() == true }.getOrDefault(false)

    private fun currentItem(): MediaItem? = _detail.value.details?.item ?: _detail.value.seed

    private fun MediaItem.key(): String = "${type.apiName}:$id"

    private fun formatPlaybackTime(milliseconds: Long): String {
        val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    private inline fun <T> serviceResult(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }

    private fun Throwable.userMessage(): String =
        message?.takeIf(String::isNotBlank) ?: "Something went wrong."

    private data class HomeLoad(
        val localWatchlist: List<MediaItem>,
        val localPlayback: List<MediaItem>,
        val tmdbRows: List<MediaRow>,
        val traktRows: List<MediaRow>,
        val tvMazeRow: MediaRow?,
        val traktWatchlistKeys: Set<String>,
        val warnings: List<String>,
    )

    private data class ProviderLoad(
        val rows: List<MediaRow>,
        val warnings: List<String>,
    )

    companion object {
        private const val MOVIE_GENRE_COMEDY = 35
        private const val MOVIE_GENRE_FAMILY = 10_751
        private const val PLAYBACK_FILTER_LIMIT = 18
        private const val RECOMMENDATION_FILTER_LIMIT = 14
        private const val AVAILABILITY_CONCURRENCY = 4
        private const val LOCAL_PLAYBACK_LIMIT = 30
        private const val LIVE_REFRESH_INTERVAL_MS = 1_000L
        private const val LIVE_SESSION_MAX_AGE_MS = 15_000L
        private const val LIVE_ROW_TITLE = "Playing now"
        private val ANDROID_PACKAGE =
            Regex("""^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$""")
    }
}
