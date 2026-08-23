package dev.roesler.marquee

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import dev.roesler.marquee.data.CatalogFilter
import dev.roesler.marquee.data.CatalogProvider
import dev.roesler.marquee.data.ExpiringLruCache
import dev.roesler.marquee.data.MarqueeSettings
import dev.roesler.marquee.data.MediaDetails
import dev.roesler.marquee.data.MediaItem
import dev.roesler.marquee.data.MediaRow
import dev.roesler.marquee.data.MediaRowAction
import dev.roesler.marquee.data.MediaType
import dev.roesler.marquee.data.Person
import dev.roesler.marquee.data.ProviderSort
import dev.roesler.marquee.data.SettingsStore
import dev.roesler.marquee.data.TasteModel
import dev.roesler.marquee.data.TasteModelStore
import dev.roesler.marquee.data.TasteStore
import dev.roesler.marquee.data.buildTasteSignals
import dev.roesler.marquee.data.TmdbClient
import dev.roesler.marquee.data.TraktClient
import dev.roesler.marquee.data.TraktDeviceCode
import dev.roesler.marquee.data.TraktPollResult
import dev.roesler.marquee.data.TraktStore
import dev.roesler.marquee.data.TvMazeClient
import dev.roesler.marquee.data.Verdict
import dev.roesler.marquee.data.WatchOptions
import dev.roesler.marquee.data.WatchProvider
import dev.roesler.marquee.data.WatchSource
import dev.roesler.marquee.data.WatchedTitle
import dev.roesler.marquee.data.WatchHistoryStore
import dev.roesler.marquee.data.WatchlistStore
import dev.roesler.marquee.data.filterCatalogRows
import dev.roesler.marquee.data.forHistory
import dev.roesler.marquee.data.key
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
    val filter: CatalogFilter = CatalogFilter.ALL,
    val loadedCategoryCount: Int = 0,
    val totalCategoryCount: Int = 0,
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
    val showingPopular: Boolean = false,
    val selectedName: String? = null,
    val credits: List<MediaItem> = emptyList(),
    val error: String? = null,
)

/** Asks for a verdict once a title has finished, so the taste profile keeps learning. */
data class RatingPromptUiState(
    val item: MediaItem,
    val providerName: String?,
    val episodeLabel: String?,
    val completed: Boolean,
) {
    val question: String
        get() = if (completed) {
            "Finished ${item.title}. Did you like it?"
        } else {
            "You stopped watching ${item.title}. Did you like it?"
        }
}

/** What the taste profile currently knows, for the Settings summary. */
data class TasteUiState(
    val ratingCount: Int,
    val likedCount: Int,
    val dislikedCount: Int,
    val watchedCount: Int,
    val topGenres: List<String>,
    val personalizing: Boolean,
    /** Distinct titles the learned model has trained on. */
    val signalCount: Int = 0,
    /** How far the learned model is trusted, in `[0, 1)`. */
    val modelConfidence: Double = 0.0,
    val modelTrained: Boolean = false,
)

data class DetailUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val seed: MediaItem? = null,
    val details: MediaDetails? = null,
    val watchOptions: WatchOptions = WatchOptions(emptyList(), null),
    val recommendations: List<MediaItem> = emptyList(),
    val becauseYouLiked: List<MediaItem> = emptyList(),
    val inWatchlist: Boolean = false,
    val inTraktWatchlist: Boolean = false,
    val traktConnected: Boolean = false,
    val traktActionLoading: Boolean = false,
    val traktFeedback: String? = null,
    val verdict: Verdict? = null,
    val watched: WatchedTitle? = null,
    val error: String? = null,
)

class MarqueeController(context: Context) {
    private val appContext = context.applicationContext
    private val settingsStore = SettingsStore(appContext)
    private val watchlistStore = WatchlistStore(appContext)
    private val traktStore = TraktStore(appContext)
    private val tasteStore = TasteStore(appContext)
    private val tasteModelStore = TasteModelStore(appContext)
    private val watchHistoryStore = WatchHistoryStore(appContext)
    private val tmdbClient = TmdbClient(settingsStore)
    private val traktClient = TraktClient(settingsStore, traktStore)
    private val tvMazeClient = TvMazeClient()
    private val launcher = ProviderLauncher(appContext)
    private val playbackStore = PlaybackStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val providerShelfCache = ExpiringLruCache<String, ProviderShelfLoad>(
        maxEntries = PROVIDER_SHELF_CACHE_ENTRIES,
        ttlMillis = PROVIDER_CACHE_TTL_MS,
    )

    private var homeJob: Job? = null
    private var providerJob: Job? = null
    private var searchJob: Job? = null
    private var peopleJob: Job? = null
    private var detailJob: Job? = null
    private var traktAuthJob: Job? = null
    private var traktProfileJob: Job? = null
    private var traktActionJob: Job? = null
    private var becauseYouLikedJob: Job? = null
    private var traktWatchlistKeys: Set<String> = emptySet()
    private var genreNames: Map<Int, String> = emptyMap()

    /** Home rows exactly as the services returned them, before taste or live playback. */
    private var homeSourceRows: List<MediaRow> = emptyList()
    private var homeNotice: String? = null
    private var homeError: String? = null

    /** Home rows after taste ranking; the live playback row is layered on top of these. */
    private var homeRankedRows: List<MediaRow> = emptyList()
    private var providerSourceRows: List<MediaRow> = emptyList()
    private var providerRankedRows: List<MediaRow> = emptyList()
    private var becauseYouLikedRows: List<MediaRow> = emptyList()

    /** The learned taste model, refit in the background whenever the signal set moves. */
    @Volatile
    private var tasteModel: TasteModel = TasteModel()
    private var tasteTrainingJob: Job? = null

    /** Identity of the last live-playback frame published into the shelves. */
    private var lastLiveSignature: String? = null
    private val pendingRatingPrompts = ArrayDeque<RatingPromptUiState>()

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

    private val _ratingPrompt = MutableStateFlow<RatingPromptUiState?>(null)
    val ratingPrompt: StateFlow<RatingPromptUiState?> = _ratingPrompt.asStateFlow()

    private val _taste = MutableStateFlow(tasteSnapshot())
    val taste: StateFlow<TasteUiState> = _taste.asStateFlow()

    init {
        PlaybackMonitorService.requestConnection(appContext)
        scope.launch { monitorLivePlayback() }
        tasteModel = tasteModelStore.load()
        retrainTasteModel()
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
        if (
            destination == Destination.PEOPLE &&
            _people.value.people.isEmpty() &&
            !_people.value.loading
        ) {
            loadPopularPeople()
        }
    }

    fun refreshHome() {
        if (_settings.value.tmdbCredential.isBlank()) {
            homeSourceRows = emptyList()
            homeRankedRows = emptyList()
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
                genreNames = result.genreNames.ifEmpty { genreNames }
                becauseYouLikedRows = result.becauseYouLikedRows
                homeSourceRows = buildList {
                    result.localWatchlist.takeIf(List<MediaItem>::isNotEmpty)?.let {
                        add(
                            MediaRow(
                                title = "My watchlist",
                                items = it,
                                subtitle = "Saved on this Shield",
                                personalize = false,
                            ),
                        )
                    }
                    result.localPlayback.takeIf(List<MediaItem>::isNotEmpty)?.let {
                        add(
                            MediaRow(
                                title = "Continue watching on this Shield",
                                items = it,
                                subtitle = "Second-level progress captured locally",
                                action = MediaRowAction.CONTINUE_LOCAL,
                                personalize = false,
                            ),
                        )
                    }
                    result.freeRow?.let(::add)
                    addAll(result.traktRows)
                    result.tvMazeRow?.takeIf { it.items.isNotEmpty() }?.let(::add)
                    addAll(result.becauseYouLikedRows)
                    result.watchedRow?.takeIf { it.items.isNotEmpty() }?.let(::add)
                    addAll(result.tmdbRows)
                    addAll(result.genreRows)
                }
                homeNotice = result.warnings.takeIf(List<String>::isNotEmpty)
                    ?.joinToString("  ·  ")
                homeError = if (homeSourceRows.isEmpty()) {
                    result.warnings.firstOrNull() ?: "No discovery rows are available."
                } else {
                    null
                }
                publishHome()
                _taste.value = tasteSnapshot()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                homeSourceRows = emptyList()
                homeRankedRows = emptyList()
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
        providerShelfCache.clear()
        providerJob?.cancel()
        providerJob = scope.launch {
            val activeFilter = _providers.value.filter
            _providers.value = _providers.value.copy(
                loading = true,
                error = null,
                notice = null,
                loadedCategoryCount = 0,
                totalCategoryCount = PROVIDER_SHELVES.size,
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
                filter = activeFilter,
                totalCategoryCount = PROVIDER_SHELVES.size,
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

    fun setProviderFilter(filter: CatalogFilter) {
        _providers.value = _providers.value.copy(filter = filter)
    }

    fun surpriseMe(): Boolean {
        val item = filterCatalogRows(_providers.value.rows, _providers.value.filter)
            .asSequence()
            .flatMap { it.items.asSequence() }
            .distinctBy { it.key }
            .toList()
            .randomOrNull()
            ?: return false
        openDetails(item)
        return true
    }

    fun searchPeople(query: String) {
        _people.value = _people.value.copy(
            query = query,
            showingPopular = false,
            selectedName = null,
            credits = emptyList(),
            error = null,
        )
        peopleJob?.cancel()
        if (query.isBlank()) {
            loadPopularPeople()
            return
        }
        launchPeopleSearch(query.trim(), debounceMillis = 350L)
    }

    fun submitPeopleSearch() {
        val query = _people.value.query.trim()
        if (query.isBlank()) {
            loadPopularPeople()
        } else {
            launchPeopleSearch(query, debounceMillis = 0L)
        }
    }

    fun clearPersonSelection() {
        if (_people.value.people.isEmpty()) {
            loadPopularPeople()
        } else {
            _people.value = _people.value.copy(
                loading = false,
                selectedName = null,
                credits = emptyList(),
                error = null,
            )
        }
    }

    private fun launchPeopleSearch(query: String, debounceMillis: Long) {
        peopleJob?.cancel()
        peopleJob = scope.launch {
            if (debounceMillis > 0L) delay(debounceMillis)
            _people.value = _people.value.copy(
                loading = true,
                showingPopular = false,
                error = null,
            )
            serviceResult {
                withContext(Dispatchers.IO) { tmdbClient.searchPeople(query) }
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

    private fun loadPopularPeople() {
        peopleJob?.cancel()
        peopleJob = scope.launch {
            _people.value = PeopleUiState(loading = true, showingPopular = true)
            serviceResult {
                withContext(Dispatchers.IO) { tmdbClient.popularPeople() }
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
            inTraktWatchlist = item.key in traktWatchlistKeys,
            traktConnected = _trakt.value.connected,
            verdict = tasteStore.verdictOf(item),
            watched = watchHistoryStore.entryFor(item),
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
                // Genre ids only arrive with the detail response; feed them back so a title
                // rated from a poster row still teaches the taste profile its genres.
                tasteStore.enrich(details.item)
                val profile = tasteStore.profile()
                val watched = watchHistoryStore.watchedKeys()
                _detail.value = _detail.value.copy(
                    loading = false,
                    details = details,
                    watchOptions = providers,
                    recommendations = rankPool(recommendations, watched),
                )
                if (tasteStore.verdictOf(details.item) == Verdict.LIKED) {
                    loadDetailSimilar(details.item)
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

    /** Jump from a detail-screen cast member to the People tab showing their filmography. */
    fun openPersonFilmography(person: Person) {
        closeDetails()
        _people.value = PeopleUiState(
            people = listOf(person),
            selectedName = person.name,
        )
        _destination.value = Destination.PEOPLE
        selectPerson(person)
    }

    fun toggleWatchlist(): Boolean {
        val item = currentItem() ?: return false
        val saved = watchlistStore.toggle(item)
        _detail.value = _detail.value.copy(inWatchlist = saved)
        return saved
    }

    /**
     * Records a like or a dislike for the open title. Choosing the verdict already in force
     * clears it. The taste profile, the `Because you liked …` rows, and every ranked shelf
     * update from the same call.
     */
    fun rateCurrentTitle(verdict: Verdict): Verdict? {
        val item = currentItem() ?: return null
        val applied = applyVerdict(item, verdict)
        _detail.value = _detail.value.copy(
            verdict = applied,
            watched = watchHistoryStore.entryFor(item),
            becauseYouLiked = if (applied == Verdict.LIKED) {
                _detail.value.becauseYouLiked
            } else {
                emptyList()
            },
        )
        if (applied == Verdict.LIKED) loadDetailSimilar(item)
        return applied
    }

    fun answerRatingPrompt(verdict: Verdict) {
        val prompt = _ratingPrompt.value ?: return
        applyVerdict(prompt.item, verdict)
        advanceRatingPrompt()
    }

    fun dismissRatingPrompt() {
        _ratingPrompt.value?.let { playbackStore.markRatingHandled(it.item.key) }
        advanceRatingPrompt()
    }

    /** Opens the detail screen for the title being asked about, keeping the prompt answered. */
    fun openRatingPromptDetails() {
        val prompt = _ratingPrompt.value ?: return
        dismissRatingPrompt()
        openDetails(prompt.item)
    }

    fun clearTasteProfile() {
        tasteStore.clear()
        forgetLearnedModel()
        _ratingPrompt.value = null
        pendingRatingPrompts.clear()
        _detail.value = _detail.value.copy(verdict = null, becauseYouLiked = emptyList())
        becauseYouLikedRows = emptyList()
        _taste.value = tasteSnapshot()
        publishHome()
        publishProviderRows()
        retrainTasteModel(force = true)
    }

    fun clearWatchHistory() {
        watchHistoryStore.clear()
        forgetLearnedModel()
        _detail.value = _detail.value.copy(watched = null)
        _taste.value = tasteSnapshot()
        publishHome()
        publishProviderRows()
        retrainTasteModel(force = true)
    }

    /** Drops the learned weights so cleared data cannot keep voting through the model. */
    private fun forgetLearnedModel() {
        tasteTrainingJob?.cancel()
        tasteTrainingJob = null
        tasteModel = TasteModel()
        tasteModelStore.clear()
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
                    traktWatchlistKeys + item.key
                } else {
                    traktWatchlistKeys - item.key
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
                markWatched(item, WatchSource.TRAKT)
                _detail.value = _detail.value.copy(
                    traktActionLoading = false,
                    traktFeedback = "Marked watched on Trakt.",
                    watched = watchHistoryStore.entryFor(item),
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

    /** Adds the open title to the local watch history without involving Trakt. */
    fun markWatchedLocally(): Boolean {
        val item = currentItem() ?: return false
        markWatched(item, WatchSource.MANUAL)
        _detail.value = _detail.value.copy(watched = watchHistoryStore.entryFor(item))
        return true
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
            providerShelfCache.clear()
            _providers.value = ProvidersUiState()
        }
        if (traktCredentialsChanged || normalized.traktClientId.isBlank()) {
            _trakt.value = initialTraktState()
        }
        if (!normalized.ratingPrompts) {
            pendingRatingPrompts.clear()
            _ratingPrompt.value = null
        }
        _taste.value = tasteSnapshot()
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

    fun openTrailer(): LaunchResult =
        launcher.openTrailer(_detail.value.details?.trailerUrl)

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
            // The detail screen covers the banner, so it is what Back is aiming at.
            _detail.value.visible -> {
                closeDetails()
                true
            }
            _ratingPrompt.value != null -> {
                dismissRatingPrompt()
                true
            }
            _destination.value == Destination.PEOPLE &&
                _people.value.selectedName != null -> {
                clearPersonSelection()
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

    // --- Taste, ratings, and watch tracking -------------------------------------------------

    private fun applyVerdict(item: MediaItem, verdict: Verdict): Verdict? {
        val applied = tasteStore.rate(item, verdict)
        playbackStore.markRatingHandled(item.key)
        // Rating something is a statement that you watched it, whatever the bridge saw.
        markWatched(item, WatchSource.MANUAL)
        _taste.value = tasteSnapshot()
        publishHome()
        publishProviderRows()
        refreshBecauseYouLiked()
        syncRatingToTrakt(item, applied)
        // A fresh opinion is the strongest signal there is; refit immediately rather than
        // waiting for the daily pass.
        retrainTasteModel(force = true)
        return applied
    }

    private fun markWatched(item: MediaItem, source: WatchSource) {
        watchHistoryStore.recordWatched(item.forHistory(), source)
        // Watches that originated on this Shield sync up to Trakt; imports from Trakt don't echo back.
        if (source != WatchSource.TRAKT) syncWatchToTrakt(item)
        retrainTasteModel()
    }

    /** Pushes a watch to the connected Trakt history in the background; failures are non-fatal. */
    private fun syncWatchToTrakt(item: MediaItem) {
        if (!_trakt.value.connected) return
        scope.launch {
            serviceResult { withContext(Dispatchers.IO) { traktClient.markWatched(item) } }
        }
    }

    /** Mirrors a like/dislike (or its removal) to the connected Trakt account in the background. */
    private fun syncRatingToTrakt(item: MediaItem, verdict: Verdict?) {
        if (!_trakt.value.connected) return
        scope.launch {
            serviceResult {
                withContext(Dispatchers.IO) {
                    when (verdict) {
                        Verdict.LIKED -> traktClient.setRating(item, TRAKT_LIKE_RATING)
                        Verdict.DISLIKED -> traktClient.setRating(item, TRAKT_DISLIKE_RATING)
                        null -> traktClient.removeRating(item)
                    }
                }
            }
        }
    }

    /**
     * Pulls the account's full watch history down in pages, once per connection.
     *
     * Kept separate from the upload backfill because the two are independent: an account that
     * has already had local history pushed to it may still be holding years of plays this
     * Shield has never seen, and every one of them is a training example. The shelf query tops
     * out at eighteen titles, which would leave the model learning from a rounding error of the
     * evidence actually available.
     */
    private fun maybeDeepImportTraktHistory() {
        if (!_trakt.value.connected || traktStore.hasDeepImported()) return
        scope.launch {
            serviceResult {
                withContext(Dispatchers.IO) { traktClient.watchedLibrary() }
            }.onSuccess { library ->
                if (library.isEmpty()) return@onSuccess
                traktStore.markDeepImported()
                watchHistoryStore.recordAll(
                    library.map { entry ->
                        WatchedTitle(
                            item = entry.item.forHistory(),
                            source = WatchSource.TRAKT,
                            firstWatchedAtEpochMillis = entry.lastWatchedAtEpochMillis,
                            lastWatchedAtEpochMillis = entry.lastWatchedAtEpochMillis,
                            playCount = entry.plays,
                            completed = true,
                            progressPercent = 100.0,
                        )
                    },
                )
                retrainTasteModel(force = true)
                _taste.value = tasteSnapshot()
            }
        }
    }

    /**
     * On first connect, pushes the watches and ratings recorded locally before Trakt was
     * linked up to the account, so history isn't one-directional. Runs once per connection.
     */
    private fun maybeBackfillTrakt() {
        if (!_trakt.value.connected || traktStore.hasBackfilled()) return
        scope.launch {
            val result = serviceResult {
                withContext(Dispatchers.IO) {
                    watchHistoryStore.recent(Int.MAX_VALUE)
                        .filter { it.source != WatchSource.TRAKT }
                        .forEach { runCatching { traktClient.markWatched(it.item) } }
                    tasteStore.verdicts().forEach { rated ->
                        runCatching {
                            traktClient.setRating(
                                rated.item,
                                if (rated.verdict == Verdict.LIKED) {
                                    TRAKT_LIKE_RATING
                                } else {
                                    TRAKT_DISLIKE_RATING
                                },
                            )
                        }
                    }
                }
            }
            if (result.isSuccess) traktStore.markBackfilled()
        }
    }

    private fun advanceRatingPrompt() {
        _ratingPrompt.value = pendingRatingPrompts.removeFirstOrNull()
    }

    private fun tasteSnapshot(): TasteUiState {
        val profile = tasteStore.profile()
        val model = tasteModel
        // The learned model reads genre preference off its own weights; fall back to the
        // hand-tuned profile's ordering until it has trained.
        val topGenres = if (model.trained) {
            model.topFeatures("g:", TOP_GENRE_LABELS)
                .mapNotNull { it.toIntOrNull()?.let(genreNames::get) }
                .ifEmpty { profile.topGenreIds(TOP_GENRE_LABELS).mapNotNull { genreNames[it] } }
        } else {
            profile.topGenreIds(TOP_GENRE_LABELS).mapNotNull { genreNames[it] }
        }
        return TasteUiState(
            ratingCount = profile.ratingCount,
            likedCount = profile.likedKeys.size,
            dislikedCount = profile.ratingCount - profile.likedKeys.size,
            watchedCount = watchHistoryStore.size(),
            topGenres = topGenres,
            personalizing = _settings.value.personalizedRanking &&
                (profile.established || model.trained),
            signalCount = model.coverage,
            modelConfidence = model.confidence,
            modelTrained = model.trained,
        )
    }

    /** Applies taste ranking to a set of freshly loaded rows. */
    private fun personalize(rows: List<MediaRow>): List<MediaRow> {
        if (!_settings.value.personalizedRanking) return rows
        val profile = tasteStore.profile()
        val model = tasteModel
        if (profile.ratingCount == 0 && !model.trained) return rows
        val watched = watchHistoryStore.watchedKeys()
        return rows.mapNotNull { row ->
            if (!row.personalize) {
                row
            } else {
                val candidates = profile.withoutDisliked(row.items)
                val ranked = if (model.trained) {
                    model.rank(candidates, watched, profile, preserveSourceOrder = true)
                } else {
                    profile.rank(candidates, watched)
                }
                row.copy(items = ranked).takeIf { ranked.isNotEmpty() }
            }
        }
    }

    /**
     * Orders a pool whose source order carries no meaning of its own — a merged
     * `Because you liked …` set, or the recommendations under a title — on appetite alone.
     */
    private fun rankPool(items: List<MediaItem>, watched: Set<String>): List<MediaItem> {
        val profile = tasteStore.profile()
        val model = tasteModel
        return if (model.trained) {
            model.rank(
                items = profile.withoutDisliked(items),
                watchedKeys = watched,
                profile = profile,
                preserveSourceOrder = false,
            )
        } else {
            profile.rankByAffinity(items, watched)
        }
    }

    /**
     * Refits the learned model from every signal on record.
     *
     * Runs off the main thread and only when something actually moved, since a fit replays the
     * whole history. The result is swapped in atomically and the shelves republished, so a new
     * rating visibly reshapes discovery without a restart.
     */
    private fun retrainTasteModel(force: Boolean = false) {
        // A forced refit carries data the in-flight one has not seen — the import that just
        // landed, or the rating just given — so it replaces that job rather than being dropped.
        if (tasteTrainingJob?.isActive == true) {
            if (!force) return
            tasteTrainingJob?.cancel()
        }
        tasteTrainingJob = scope.launch {
            val refreshed = serviceResult {
                withContext(Dispatchers.Default) {
                    val signals = buildTasteSignals(
                        verdicts = tasteStore.verdicts(),
                        watched = watchHistoryStore.recent(Int.MAX_VALUE),
                        watchlist = watchlistStore.load(),
                    )
                    if (signals.isEmpty()) return@withContext null
                    if (!force && !tasteModelStore.isStale(signals.size)) return@withContext null
                    // Always fit from a clean slate: replaying history over stale weights would
                    // let a retired opinion keep half a vote forever.
                    TasteModel().trainedOn(signals).also(tasteModelStore::save)
                }
            }.getOrNull() ?: return@launch
            tasteModel = refreshed
            publishHome()
            publishProviderRows()
            _taste.value = tasteSnapshot()
        }
    }

    private fun publishHome() {
        homeRankedRows = personalize(homeSourceRows)
        val record = liveRecord()
        _home.value = HomeUiState(
            rows = withLivePlayback(homeRankedRows, record, record?.asMediaItem()),
            notice = homeNotice,
            error = homeError,
        )
    }

    private fun publishProviderRows() {
        val current = _providers.value
        if (current.rows.isEmpty()) return
        val provider = current.selectedProvider
        val record = liveRecord()?.takeIf {
            provider?.packageName != null && provider.packageName == it.packageName
        }
        providerRankedRows = personalize(providerSourceRows)
        _providers.value = current.copy(
            rows = withLivePlayback(providerRankedRows, record, record?.asMediaItem()),
        )
    }

    private fun liveRecord(): PlaybackRecord? =
        playbackStore.current()?.takeIf {
            it.active &&
                System.currentTimeMillis() - it.observedAtEpochMillis <= LIVE_SESSION_MAX_AGE_MS
        }

    /** Rebuilds `Because you liked …` rows from the most recent likes. */
    private fun refreshBecauseYouLiked() {
        if (_settings.value.tmdbCredential.isBlank()) return
        // A home refresh already rebuilds these, and it owns the row order while it runs.
        if (homeJob?.isActive == true || homeSourceRows.isEmpty()) return
        becauseYouLikedJob?.cancel()
        becauseYouLikedJob = scope.launch {
            val rows = serviceResult {
                withContext(Dispatchers.IO) { buildBecauseYouLikedRows() }
            }.getOrElse {
                if (it is CancellationException) throw it
                return@launch
            }
            if (rows == becauseYouLikedRows || homeSourceRows.isEmpty()) return@launch
            val previousTitles = becauseYouLikedRows.mapTo(hashSetOf(), MediaRow::title)
            becauseYouLikedRows = rows
            val remaining = homeSourceRows.filterNot { it.title in previousTitles }
            // Keep the personalized rows just above the generic TMDB shelves.
            val anchor = remaining.indexOfFirst { it.title == FIRST_TMDB_ROW_TITLE }
            homeSourceRows = if (anchor < 0) {
                remaining + rows
            } else {
                remaining.take(anchor) + rows + remaining.drop(anchor)
            }
            publishHome()
        }
    }

    private fun buildBecauseYouLikedRows(): List<MediaRow> {
        val seeds = tasteStore.recentlyLiked(BECAUSE_YOU_LIKED_ROWS)
        if (seeds.isEmpty()) return emptyList()
        val profile = tasteStore.profile()
        val watched = watchHistoryStore.watchedKeys()
        val used = hashSetOf<String>()
        return seeds.mapNotNull { seed ->
            val pool = serviceResult { tmdbClient.similarTo(seed) }.getOrDefault(emptyList())
            val items = rankPool(pool, watched)
                .filter { it.key != seed.key && used.add(it.key) }
                .take(BECAUSE_YOU_LIKED_ITEMS)
            items.takeIf(List<MediaItem>::isNotEmpty)?.let {
                MediaRow(
                    title = "Because you liked ${seed.title}",
                    items = it,
                    subtitle = becauseYouLikedSubtitle(seed),
                    personalize = false,
                )
            }
        }
    }

    private fun becauseYouLikedSubtitle(seed: MediaItem): String {
        val genres = seed.genreIds.mapNotNull { genreNames[it] }.take(2)
        return if (genres.isEmpty()) {
            "Similar titles, ranked by your ratings"
        } else {
            "${genres.joinToString(" · ")} · ranked by your ratings"
        }
    }

    private fun loadDetailSimilar(seed: MediaItem) {
        scope.launch {
            val pool = serviceResult {
                withContext(Dispatchers.IO) { tmdbClient.similarTo(seed) }
            }.getOrElse {
                if (it is CancellationException) throw it
                return@launch
            }
            if (currentItem()?.key != seed.key) return@launch
            _detail.value = _detail.value.copy(
                becauseYouLiked = rankPool(pool, watchHistoryStore.watchedKeys())
                    .take(BECAUSE_YOU_LIKED_ITEMS),
            )
        }
    }

    // --- Provider catalog ------------------------------------------------------------------

    private suspend fun loadProviderCatalog(provider: CatalogProvider) {
        val providerList = _providers.value.providers
        val activeFilter = _providers.value.filter
        providerSourceRows = emptyList()
        providerRankedRows = emptyList()
        _providers.value = ProvidersUiState(
            loading = true,
            providers = providerList,
            selectedProvider = provider,
            filter = activeFilter,
            totalCategoryCount = PROVIDER_SHELVES.size,
        )

        coroutineScope {
            val personalizedTask = async(Dispatchers.IO) {
                serviceResult { buildProviderPersonalization(provider) }
            }
            val rows = mutableListOf<MediaRow>()
            val warnings = mutableListOf<String>()
            var loadedCategories = 0

            suspend fun loadAndPublish(shelves: List<ProviderShelfSpec>) {
                val result = withContext(Dispatchers.IO) {
                    loadProviderShelves(provider, shelves)
                }
                rows += result.rows
                warnings += result.warnings
                loadedCategories += shelves.size
                publishProviderCatalog(
                    provider = provider,
                    rows = rows,
                    warnings = warnings,
                    loadedCategories = loadedCategories,
                    loading = true,
                )
            }

            try {
                loadAndPublish(PROVIDER_SHELVES.take(CORE_PROVIDER_CATEGORY_COUNT))

                personalizedTask.await().fold(
                    onSuccess = { personalized ->
                        rows.addAll(0, personalized.rows)
                        warnings += personalized.warnings
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        warnings += "Personalized rows unavailable"
                    },
                )
                publishProviderCatalog(
                    provider = provider,
                    rows = rows,
                    warnings = warnings,
                    loadedCategories = loadedCategories,
                    loading = true,
                )

                PROVIDER_SHELVES
                    .drop(CORE_PROVIDER_CATEGORY_COUNT)
                    .chunked(PROVIDER_DISCOVERY_CONCURRENCY)
                    .forEach { loadAndPublish(it) }

                publishProviderCatalog(
                    provider = provider,
                    rows = rows,
                    warnings = warnings,
                    loadedCategories = PROVIDER_SHELVES.size,
                    loading = false,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                publishProviderCatalog(
                    provider = provider,
                    rows = rows,
                    warnings = warnings,
                    loadedCategories = loadedCategories,
                    loading = false,
                    error = error.userMessage(),
                )
            }
        }
    }

    private fun publishProviderCatalog(
        provider: CatalogProvider,
        rows: List<MediaRow>,
        warnings: List<String>,
        loadedCategories: Int,
        loading: Boolean,
        error: String? = null,
    ) {
        val current = _providers.value
        if (current.selectedProvider?.id != provider.id) return
        val uniqueWarnings = warnings.distinct()
        providerSourceRows = rows.toList()
        providerRankedRows = personalize(providerSourceRows)
        val record = liveRecord()?.takeIf {
            provider.packageName != null && provider.packageName == it.packageName
        }
        _providers.value = current.copy(
            loading = loading,
            rows = withLivePlayback(providerRankedRows, record, record?.asMediaItem()),
            loadedCategoryCount = loadedCategories,
            totalCategoryCount = PROVIDER_SHELVES.size,
            notice = uniqueWarnings.takeIf(List<String>::isNotEmpty)?.joinToString(" | "),
            error = when {
                error != null && rows.isEmpty() -> error
                !loading && rows.isEmpty() -> uniqueWarnings.firstOrNull()
                    ?: "No ${provider.name} catalog rows are available."
                else -> null
            },
        )
    }

    // --- Live playback ---------------------------------------------------------------------

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
            liveRecord?.let { trackWatchProgress(it, now) }
            applyLivePlayback(liveRecord)
            offerRatingPrompt(now)
            // Nothing is playing, so back off instead of sweeping storage every second.
            delay(if (fresh) LIVE_REFRESH_INTERVAL_MS else IDLE_REFRESH_INTERVAL_MS)
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

    /**
     * Publishes the live title into the shelves.
     *
     * The banner above the rows carries the second-by-second clock, so the shelves only need
     * rebuilding when something a poster can show has actually changed: a different title,
     * another whole percent of progress, or a play/pause transition. Without that guard this
     * rebuilt every row on the screen once a second.
     */
    private fun applyLivePlayback(record: PlaybackRecord?) {
        val liveItem = record?.asMediaItem()
        val signature = if (record == null || liveItem == null) {
            null
        } else {
            listOf(
                liveItem.key,
                liveItem.progressPercent?.toInt(),
                record.state,
                record.episodeLabel,
            ).joinToString("|")
        }
        if (signature == lastLiveSignature) return
        lastLiveSignature = signature

        _home.value = _home.value.copy(
            rows = withLivePlayback(homeRankedRows, record, liveItem),
        )
        val selected = _providers.value.selectedProvider
        val providerRecord = record.takeIf {
            selected?.packageName != null && selected.packageName == it?.packageName
        }
        if (providerRankedRows.isNotEmpty()) {
            _providers.value = _providers.value.copy(
                rows = withLivePlayback(
                    providerRankedRows,
                    providerRecord,
                    providerRecord?.asMediaItem(),
                ),
            )
        }
    }

    private fun withLivePlayback(
        rows: List<MediaRow>,
        record: PlaybackRecord?,
        liveItem: MediaItem?,
    ): List<MediaRow> {
        if (record == null || liveItem == null) return rows
        val key = liveItem.key
        val refreshed = rows.map { row ->
            if (row.items.none { it.key == key }) {
                row
            } else {
                row.copy(items = row.items.map { item -> if (item.key == key) liveItem else item })
            }
        }
        return listOf(
            MediaRow(
                title = LIVE_ROW_TITLE,
                items = listOf(liveItem),
                subtitle = "${record.providerName} · live from this Shield",
                action = MediaRowAction.CONTINUE_LOCAL,
                personalize = false,
            ),
        ) + refreshed
    }

    /** Folds the active session into the durable watch history. */
    private fun trackWatchProgress(record: PlaybackRecord, now: Long) {
        val media = record.media ?: return
        val changed = watchHistoryStore.record(
            WatchedTitle(
                item = media.forHistory(),
                source = WatchSource.LOCAL_PLAYBACK,
                firstWatchedAtEpochMillis = now,
                lastWatchedAtEpochMillis = now,
                playCount = 1,
                completed = record.isCompleted(now),
                progressPercent = record.progressPercentAt(now),
                episodeLabel = record.episodeLabel,
                providerName = record.providerName,
            ),
        )
        if (changed) _taste.value = _taste.value.copy(watchedCount = watchHistoryStore.size())
    }

    private fun offerRatingPrompt(now: Long) {
        if (!_settings.value.ratingPrompts) return
        val finished = playbackStore.consumeRateablePlayback(now)
        if (finished.isEmpty()) return
        finished.forEach { record ->
            val media = record.media ?: return@forEach
            if (tasteStore.verdictOf(media) != null) return@forEach
            watchHistoryStore.record(
                WatchedTitle(
                    item = media.forHistory(),
                    source = WatchSource.LOCAL_PLAYBACK,
                    firstWatchedAtEpochMillis = record.observedAtEpochMillis,
                    lastWatchedAtEpochMillis = record.observedAtEpochMillis,
                    playCount = 1,
                    completed = record.isCompleted(now),
                    progressPercent = record.progressPercentAt(now),
                    episodeLabel = record.episodeLabel,
                    providerName = record.providerName,
                ),
            )
            pendingRatingPrompts.addLast(
                RatingPromptUiState(
                    item = media.forHistory(),
                    providerName = record.providerName,
                    episodeLabel = record.episodeLabel,
                    completed = record.isCompleted(now),
                ),
            )
        }
        if (_ratingPrompt.value == null) advanceRatingPrompt()
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

    /**
     * Resolves TMDB identities for local playback records. Each unidentified record costs a
     * search, so they run together rather than one after another.
     */
    private suspend fun resolvedPlaybackItems(
        records: List<PlaybackRecord>,
    ): List<MediaItem> = coroutineScope {
        records.asSequence()
            .filter { it.isContinuable() }
            .take(LOCAL_PLAYBACK_LIMIT)
            .toList()
            .chunked(RESOLVE_CONCURRENCY)
            .flatMap { chunk ->
                chunk.map { record ->
                    async(Dispatchers.IO) {
                        val resolved = serviceResult { resolvePlaybackRecord(record) }.getOrNull()
                            ?: return@async null
                        if (record.media == null) playbackStore.attachMedia(record, resolved)
                        record.copy(media = resolved).asMediaItem()
                    }
                }.awaitAll().filterNotNull()
            }
            .distinctBy { it.key }
    }

    // --- Loading ---------------------------------------------------------------------------

    private suspend fun buildProviderPersonalization(provider: CatalogProvider): ProviderLoad =
        coroutineScope {
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
            val personalizedRows = mutableListOf<MediaRow>()
            localPlayback.takeIf(List<MediaItem>::isNotEmpty)?.let {
                personalizedRows += MediaRow(
                    title = "Continue watching on this Shield",
                    items = it,
                    subtitle = "Second-level local progress · ${provider.name}",
                    action = MediaRowAction.CONTINUE_LOCAL,
                    personalize = false,
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
                    localPlayback.any { it.key == traktItem.key }
                }
                continueWatching.takeIf(List<MediaItem>::isNotEmpty)?.let {
                    personalizedRows += MediaRow(
                        title = "Continue watching",
                        items = it,
                        subtitle = "Synced progress from Trakt · available on ${provider.name}",
                        personalize = false,
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
                    ).distinctBy { it.key }
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
                rows = personalizedRows,
                warnings = warnings,
            )
        }

    private suspend fun loadProviderShelves(
        provider: CatalogProvider,
        shelves: List<ProviderShelfSpec>,
    ): ProviderLoad {
        val region = _settings.value.region
        val results = coroutineScope {
            shelves.map { shelf ->
                async {
                    loadProviderShelf(
                        provider = provider,
                        shelf = shelf,
                        region = region,
                    )
                }
            }.awaitAll()
        }
        return ProviderLoad(
            rows = results.mapNotNull(ProviderShelfLoad::row),
            warnings = results.mapNotNull(ProviderShelfLoad::warning),
        )
    }

    private fun loadProviderShelf(
        provider: CatalogProvider,
        shelf: ProviderShelfSpec,
        region: String,
    ): ProviderShelfLoad {
        val cacheKey = buildString {
            append(region)
            append(':')
            append(provider.id)
            append(':')
            append(shelf.type.apiName)
            append(':')
            append(shelf.sort)
            append(':')
            append(shelf.genreId ?: 0)
        }
        providerShelfCache.get(cacheKey)?.let { return it }
        return serviceResult {
            val items = tmdbClient.providerTitles(
                providerId = provider.id,
                type = shelf.type,
                sort = shelf.sort,
                genreId = shelf.genreId,
            )
            ProviderShelfLoad(
                row = MediaRow(
                    title = shelf.title,
                    items = items,
                    subtitle = "${items.size} titles | ${provider.name} | $region",
                ).takeIf { items.isNotEmpty() },
            )
        }.getOrElse {
            ProviderShelfLoad(
                row = null,
                warning = "${shelf.title} unavailable",
            )
        }.also { result ->
            if (result.warning == null) providerShelfCache.put(cacheKey, result)
        }
    }

    /** A shelf of titles you can stream free (with ads) on a free app already on this Shield. */
    private suspend fun buildFreeRow(): MediaRow? = coroutineScope {
        val installedIds = FREE_PROVIDER_PACKAGES
            .filterValues { launcher.isInstalled(it) }
            .keys
            .toList()
        if (installedIds.isEmpty()) return@coroutineScope null
        val movieTask = async {
            serviceResult { tmdbClient.freeTitles(installedIds, MediaType.MOVIE) }
                .getOrDefault(emptyList())
        }
        val tvTask = async {
            serviceResult { tmdbClient.freeTitles(installedIds, MediaType.TV) }
                .getOrDefault(emptyList())
        }
        interleaveByType(movieTask.await(), tvTask.await())
            .distinctBy { it.key }
            .take(FREE_ROW_LIMIT)
            .takeIf(List<MediaItem>::isNotEmpty)
            ?.let {
                MediaRow(
                    title = "Free on your apps",
                    items = it,
                    subtitle = "Free with ads on apps installed on this Shield",
                )
            }
    }

    private suspend fun availableOnProvider(
        items: List<MediaItem>,
        providerId: Int,
        limit: Int,
    ): List<MediaItem> {
        val unique = items.distinctBy { it.key }.take(limit)
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
        val genreNamesTask = async { serviceResult { tmdbClient.genreNames() } }
        val localPlaybackTask = async {
            serviceResult { resolvedPlaybackItems(playbackStore.history()) }
        }
        val tmdbTasks = listOf(
            "Trending" to async {
                serviceResult { MediaRow("Trending this week", tmdbClient.trending()) }
            },
            "Popular movies" to async {
                serviceResult { MediaRow(FIRST_TMDB_ROW_TITLE, tmdbClient.popularMovies()) }
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
        val becauseYouLikedTask = async { serviceResult { buildBecauseYouLikedRows() } }
        val genreTasks = HOME_GENRE_SHELVES.map { shelf ->
            shelf.label to async { serviceResult { buildGenreRow(shelf) } }
        }
        val freeRowTask = async { serviceResult { buildFreeRow() } }

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
        val genreRows = genreTasks.mapNotNull { (label, task) ->
            task.await().fold(
                onSuccess = { it.takeIf { row -> row.items.isNotEmpty() } },
                onFailure = {
                    warnings += "$label category unavailable"
                    null
                },
            )
        }
        val freeRow = freeRowTask.await().getOrElse {
            warnings += "Free-on-your-apps row unavailable"
            null
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
                ).distinctBy { it.key }
            val recent = collectTrakt(
                checkNotNull(traktHistory).await(),
                "Trakt history",
            )
            val playback = collectTrakt(
                checkNotNull(traktPlayback).await(),
                "Trakt playback progress",
            ).filterNot { traktItem ->
                localPlayback.any { it.key == traktItem.key }
            }

            // Trakt history is the record of everything watched away from this Shield.
            importTraktHistory(recent)

            playback.takeIf(List<MediaItem>::isNotEmpty)?.let {
                traktRows += MediaRow(
                    title = "Continue watching",
                    items = it,
                    subtitle = "Synced playback progress from Trakt",
                    personalize = false,
                )
            }
            traktWatchlist.takeIf(List<MediaItem>::isNotEmpty)?.let {
                traktRows += MediaRow(
                    title = "Your Trakt watchlist",
                    items = it,
                    subtitle = "Synced with Trakt",
                    personalize = false,
                )
            }
            recommendedMovies.takeIf(List<MediaItem>::isNotEmpty)?.let {
                traktRows += MediaRow("Movies for you", it, "Personalized by Trakt")
            }
            recommendedShows.takeIf(List<MediaItem>::isNotEmpty)?.let {
                traktRows += MediaRow("Shows for you", it, "Personalized by Trakt")
            }
            recent.takeIf(List<MediaItem>::isNotEmpty)?.let {
                traktRows += MediaRow(
                    title = "Recently watched",
                    items = it,
                    subtitle = "From your Trakt history",
                    personalize = false,
                )
            }
        }

        HomeLoad(
            localWatchlist = watchlistStore.load(),
            localPlayback = localPlayback,
            freeRow = freeRow,
            tmdbRows = tmdbRows,
            genreRows = genreRows,
            traktRows = traktRows,
            tvMazeRow = tvMazeRow,
            watchedRow = watchedRow(),
            becauseYouLikedRows = becauseYouLikedTask.await().getOrDefault(emptyList()),
            genreNames = genreNamesTask.await().getOrDefault(emptyMap()),
            traktWatchlistKeys = traktWatchlist.mapTo(linkedSetOf()) { it.key },
            warnings = warnings,
        )
    }

    private fun importTraktHistory(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        watchHistoryStore.recordAll(
            items.map { item ->
                WatchedTitle(
                    item = item.forHistory(),
                    source = WatchSource.TRAKT,
                    firstWatchedAtEpochMillis = now,
                    lastWatchedAtEpochMillis = now,
                    playCount = 1,
                    completed = true,
                    progressPercent = 100.0,
                )
            },
        )
    }

    /** Everything Marquee has seen you watch, newest first. */
    private fun watchedRow(): MediaRow? {
        val watched = watchHistoryStore.recent(WATCHED_ROW_LIMIT)
        if (watched.isEmpty()) return null
        return MediaRow(
            title = "Everything you've watched",
            items = watched.map { entry ->
                entry.item.copy(contextLabel = entry.summaryLabel().takeIf(String::isNotBlank))
            },
            subtitle = "${watchHistoryStore.size()} titles tracked on this Shield",
            personalize = false,
        )
    }

    /** One Home category shelf: popular movies and shows in a genre, merged and interleaved. */
    private suspend fun buildGenreRow(shelf: HomeGenreShelf): MediaRow = coroutineScope {
        val movieTask = async(Dispatchers.IO) {
            tmdbClient.genreTitles(shelf.movieGenreId, MediaType.MOVIE)
        }
        val showTask = shelf.tvGenreId?.let { tvId ->
            async(Dispatchers.IO) { tmdbClient.genreTitles(tvId, MediaType.TV) }
        }
        val movies = movieTask.await()
        val shows = showTask?.await() ?: emptyList()
        MediaRow(
            title = shelf.label,
            items = interleaveByType(movies, shows)
                .distinctBy { it.key }
                .take(HOME_GENRE_SHELF_ITEMS),
            subtitle = "Browse ${shelf.label}",
        )
    }

    /** Alternates two lists so a merged genre shelf mixes films and series instead of grouping them. */
    private fun interleaveByType(
        first: List<MediaItem>,
        second: List<MediaItem>,
    ): List<MediaItem> {
        if (first.isEmpty()) return second
        if (second.isEmpty()) return first
        val merged = ArrayList<MediaItem>(first.size + second.size)
        val a = first.iterator()
        val b = second.iterator()
        while (a.hasNext() || b.hasNext()) {
            if (a.hasNext()) merged += a.next()
            if (b.hasNext()) merged += b.next()
        }
        return merged
    }

    private suspend fun streamingTodayRow(): MediaRow = coroutineScope {
        val scheduled = tvMazeClient.streamingToday()
        val items = scheduled
            .chunked(RESOLVE_CONCURRENCY)
            .flatMap { chunk ->
                chunk.map { entry ->
                    async(Dispatchers.IO) {
                        serviceResult {
                            tmdbClient.findTv(entry.imdbId, entry.title, entry.year)
                        }.getOrNull()?.let { item ->
                            val scheduleLine = listOfNotNull(entry.service, entry.episodeLabel)
                                .joinToString(" · ")
                            if (scheduleLine.isBlank()) {
                                item
                            } else {
                                item.copy(
                                    overview = "$scheduleLine — ${item.overview}"
                                        .trimEnd(' ', '—'),
                                )
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            .distinctBy { it.key }
        MediaRow(
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
                maybeBackfillTrakt()
                maybeDeepImportTraktHistory()
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
                    maybeBackfillTrakt()
                    maybeDeepImportTraktHistory()
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
        val freeRow: MediaRow?,
        val tmdbRows: List<MediaRow>,
        val genreRows: List<MediaRow>,
        val traktRows: List<MediaRow>,
        val tvMazeRow: MediaRow?,
        val watchedRow: MediaRow?,
        val becauseYouLikedRows: List<MediaRow>,
        val genreNames: Map<Int, String>,
        val traktWatchlistKeys: Set<String>,
        val warnings: List<String>,
    )

    private data class HomeGenreShelf(
        val label: String,
        val movieGenreId: Int,
        val tvGenreId: Int?,
    )

    private data class ProviderLoad(
        val rows: List<MediaRow>,
        val warnings: List<String>,
    )

    private data class ProviderShelfLoad(
        val row: MediaRow?,
        val warning: String? = null,
    )

    private data class ProviderShelfSpec(
        val title: String,
        val type: MediaType,
        val sort: ProviderSort,
        val genreId: Int? = null,
    )

    companion object {
        private const val MOVIE_GENRE_COMEDY = 35
        private const val MOVIE_GENRE_FAMILY = 10_751
        private const val MOVIE_GENRE_ACTION = 28
        private const val MOVIE_GENRE_ADVENTURE = 12
        private const val MOVIE_GENRE_FANTASY = 14
        private const val MOVIE_GENRE_SCIFI = 878
        private const val MOVIE_GENRE_HORROR = 27
        private const val MOVIE_GENRE_THRILLER = 53
        private const val MOVIE_GENRE_ROMANCE = 10_749
        private const val TV_GENRE_ACTION_ADVENTURE = 10_759
        private const val TV_GENRE_SCIFI_FANTASY = 10_765
        private const val TV_GENRE_KIDS = 10_762
        private const val GENRE_CRIME = 80       // shared movie/TV id
        private const val GENRE_MYSTERY = 9_648  // shared movie/TV id
        private const val GENRE_DRAMA = 18       // shared movie/TV id
        private const val GENRE_DOCUMENTARY = 99 // shared movie/TV id
        private const val GENRE_ANIMATION = 16   // shared movie/TV id
        private const val PLAYBACK_FILTER_LIMIT = 18
        private const val RECOMMENDATION_FILTER_LIMIT = 14
        private const val AVAILABILITY_CONCURRENCY = 4
        private const val RESOLVE_CONCURRENCY = 4
        private const val PROVIDER_DISCOVERY_CONCURRENCY = 3
        private const val CORE_PROVIDER_CATEGORY_COUNT = 6
        private const val PROVIDER_SHELF_CACHE_ENTRIES = 160
        private const val PROVIDER_CACHE_TTL_MS = 30L * 60L * 1_000L
        private const val LOCAL_PLAYBACK_LIMIT = 30
        private const val LIVE_REFRESH_INTERVAL_MS = 1_000L
        private const val IDLE_REFRESH_INTERVAL_MS = 5_000L
        private const val LIVE_SESSION_MAX_AGE_MS = 15_000L
        private const val LIVE_ROW_TITLE = "Playing now"
        private const val FIRST_TMDB_ROW_TITLE = "Popular movies"
        private const val BECAUSE_YOU_LIKED_ROWS = 2
        private const val BECAUSE_YOU_LIKED_ITEMS = 20
        private const val WATCHED_ROW_LIMIT = 30
        private const val FREE_ROW_LIMIT = 20

        // Trakt uses a 1–10 scale; Marquee's like/dislike map to a clear positive/negative.
        private const val TRAKT_LIKE_RATING = 8
        private const val TRAKT_DISLIKE_RATING = 3

        /**
         * Free / ad-supported services (TMDB provider id → Android TV package). The Home
         * "Free on your apps" shelf discovers titles from whichever of these is installed.
         */
        private val FREE_PROVIDER_PACKAGES = mapOf(
            73 to "com.tubitv",                        // Tubi
            300 to "tv.pluto.android",                 // Pluto TV
            12 to "com.gotv.crackle.handset",          // Crackle
            538 to "com.plexapp.android",              // Plex
            613 to "com.amazon.amazonvideo.livingroom", // Amazon Freevee (in Prime Video)
            1049 to "com.xumo.xumo.tv",                // Xumo Play
        )
        private const val TOP_GENRE_LABELS = 3
        private val PROVIDER_SHELVES = listOf(
            ProviderShelfSpec("Popular movies", MediaType.MOVIE, ProviderSort.POPULAR),
            ProviderShelfSpec("Popular series", MediaType.TV, ProviderSort.POPULAR),
            ProviderShelfSpec("New movies", MediaType.MOVIE, ProviderSort.NEWEST),
            ProviderShelfSpec("New series", MediaType.TV, ProviderSort.NEWEST),
            ProviderShelfSpec("Top rated movies", MediaType.MOVIE, ProviderSort.TOP_RATED),
            ProviderShelfSpec("Top rated series", MediaType.TV, ProviderSort.TOP_RATED),
            ProviderShelfSpec(
                "Family night",
                MediaType.MOVIE,
                ProviderSort.POPULAR,
                MOVIE_GENRE_FAMILY,
            ),
            ProviderShelfSpec(
                "Comedy",
                MediaType.MOVIE,
                ProviderSort.POPULAR,
                MOVIE_GENRE_COMEDY,
            ),
            ProviderShelfSpec(
                "Action",
                MediaType.MOVIE,
                ProviderSort.POPULAR,
                MOVIE_GENRE_ACTION,
            ),
            ProviderShelfSpec(
                "Adventure",
                MediaType.MOVIE,
                ProviderSort.POPULAR,
                MOVIE_GENRE_ADVENTURE,
            ),
            ProviderShelfSpec(
                "Fantasy",
                MediaType.MOVIE,
                ProviderSort.POPULAR,
                MOVIE_GENRE_FANTASY,
            ),
            ProviderShelfSpec(
                "Sci-Fi",
                MediaType.MOVIE,
                ProviderSort.POPULAR,
                MOVIE_GENRE_SCIFI,
            ),
            ProviderShelfSpec(
                "Horror",
                MediaType.MOVIE,
                ProviderSort.POPULAR,
                MOVIE_GENRE_HORROR,
            ),
            ProviderShelfSpec(
                "Thrillers",
                MediaType.MOVIE,
                ProviderSort.POPULAR,
                MOVIE_GENRE_THRILLER,
            ),
            ProviderShelfSpec(
                "Mysteries",
                MediaType.MOVIE,
                ProviderSort.POPULAR,
                GENRE_MYSTERY,
            ),
            ProviderShelfSpec(
                "Crime movies",
                MediaType.MOVIE,
                ProviderSort.POPULAR,
                GENRE_CRIME,
            ),
            ProviderShelfSpec(
                "Romance",
                MediaType.MOVIE,
                ProviderSort.POPULAR,
                MOVIE_GENRE_ROMANCE,
            ),
            ProviderShelfSpec(
                "Drama series",
                MediaType.TV,
                ProviderSort.POPULAR,
                GENRE_DRAMA,
            ),
            ProviderShelfSpec(
                "Action & adventure series",
                MediaType.TV,
                ProviderSort.POPULAR,
                TV_GENRE_ACTION_ADVENTURE,
            ),
            ProviderShelfSpec(
                "Sci-Fi & fantasy series",
                MediaType.TV,
                ProviderSort.POPULAR,
                TV_GENRE_SCIFI_FANTASY,
            ),
            ProviderShelfSpec(
                "Crime series",
                MediaType.TV,
                ProviderSort.POPULAR,
                GENRE_CRIME,
            ),
            ProviderShelfSpec(
                "Comedy series",
                MediaType.TV,
                ProviderSort.POPULAR,
                MOVIE_GENRE_COMEDY,
            ),
            ProviderShelfSpec(
                "Mystery series",
                MediaType.TV,
                ProviderSort.POPULAR,
                GENRE_MYSTERY,
            ),
            ProviderShelfSpec(
                "Kids series",
                MediaType.TV,
                ProviderSort.POPULAR,
                TV_GENRE_KIDS,
            ),
            ProviderShelfSpec(
                "Documentaries",
                MediaType.MOVIE,
                ProviderSort.POPULAR,
                GENRE_DOCUMENTARY,
            ),
            ProviderShelfSpec(
                "Animation",
                MediaType.MOVIE,
                ProviderSort.POPULAR,
                GENRE_ANIMATION,
            ),
        )
        /**
         * Genre browse shelves for Home. Each merges a movie genre with its closest TV genre
         * so one "Comedy"/"Action"/… row spans both. Taste ranking reorders the items per row.
         */
        private val HOME_GENRE_SHELVES = listOf(
            HomeGenreShelf("Comedy", MOVIE_GENRE_COMEDY, MOVIE_GENRE_COMEDY),
            HomeGenreShelf("Action", MOVIE_GENRE_ACTION, TV_GENRE_ACTION_ADVENTURE),
            HomeGenreShelf("Sci-Fi & Fantasy", MOVIE_GENRE_SCIFI, TV_GENRE_SCIFI_FANTASY),
            HomeGenreShelf("Drama", GENRE_DRAMA, GENRE_DRAMA),
            HomeGenreShelf("Horror", MOVIE_GENRE_HORROR, null),
            HomeGenreShelf("Thriller", MOVIE_GENRE_THRILLER, null),
            HomeGenreShelf("Animation", GENRE_ANIMATION, GENRE_ANIMATION),
            HomeGenreShelf("Documentaries", GENRE_DOCUMENTARY, GENRE_DOCUMENTARY),
        )
        private const val HOME_GENRE_SHELF_ITEMS = 24
        private val ANDROID_PACKAGE =
            Regex("""^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$""")
    }
}
