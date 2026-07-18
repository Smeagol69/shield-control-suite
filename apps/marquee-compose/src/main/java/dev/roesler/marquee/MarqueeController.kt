package dev.roesler.marquee

import android.content.Context
import dev.roesler.marquee.data.MarqueeSettings
import dev.roesler.marquee.data.MediaDetails
import dev.roesler.marquee.data.MediaItem
import dev.roesler.marquee.data.MediaRow
import dev.roesler.marquee.data.Person
import dev.roesler.marquee.data.SettingsStore
import dev.roesler.marquee.data.TmdbClient
import dev.roesler.marquee.data.WatchOptions
import dev.roesler.marquee.data.WatchProvider
import dev.roesler.marquee.data.WatchlistStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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

enum class Destination(val label: String) {
    HOME("Home"),
    SEARCH("Search"),
    PEOPLE("People"),
    SETTINGS("Settings"),
}

data class HomeUiState(
    val loading: Boolean = false,
    val rows: List<MediaRow> = emptyList(),
    val error: String? = null,
)

data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<MediaItem> = emptyList(),
    val error: String? = null,
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
    val error: String? = null,
)

class MarqueeController(context: Context) {
    private val appContext = context.applicationContext
    private val settingsStore = SettingsStore(appContext)
    private val watchlistStore = WatchlistStore(appContext)
    private val client = TmdbClient(settingsStore)
    private val launcher = ProviderLauncher(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var homeJob: Job? = null
    private var searchJob: Job? = null
    private var peopleJob: Job? = null
    private var detailJob: Job? = null
    private var becauseYouLiked: MediaRow? = null

    private val _destination = MutableStateFlow(Destination.HOME)
    val destination: StateFlow<Destination> = _destination.asStateFlow()

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private val _people = MutableStateFlow(PeopleUiState())
    val people: StateFlow<PeopleUiState> = _people.asStateFlow()

    private val _detail = MutableStateFlow(DetailUiState())
    val detail: StateFlow<DetailUiState> = _detail.asStateFlow()

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<MarqueeSettings> = _settings.asStateFlow()

    init {
        if (_settings.value.tmdbCredential.isBlank()) {
            _destination.value = Destination.SETTINGS
        } else {
            refreshHome()
        }
    }

    fun navigate(destination: Destination) {
        _destination.value = destination
        if (destination == Destination.HOME && _home.value.rows.isEmpty()) refreshHome()
    }

    fun refreshHome() {
        homeJob?.cancel()
        homeJob = scope.launch {
            _home.value = _home.value.copy(loading = true, error = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        listOf(
                            async { MediaRow("Trending this week", client.trending()) },
                            async { MediaRow("Popular movies", client.popularMovies()) },
                            async { MediaRow("Popular on TV", client.popularTv()) },
                            async { MediaRow("Now playing", client.nowPlaying()) },
                            async { MediaRow("Top rated", client.topRatedMovies()) },
                        ).awaitAll()
                    }
                }
            }.onSuccess { remoteRows ->
                _home.value = HomeUiState(
                    rows = buildList {
                        watchlistStore.load().takeIf { it.isNotEmpty() }?.let {
                            add(MediaRow("My watchlist", it))
                        }
                        becauseYouLiked?.let(::add)
                        addAll(remoteRows.filter { it.items.isNotEmpty() })
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
            runCatching { withContext(Dispatchers.IO) { client.searchTitles(query.trim()) } }
                .onSuccess { _search.value = _search.value.copy(loading = false, results = it) }
                .onFailure {
                    if (it is CancellationException) throw it
                    _search.value = _search.value.copy(
                        loading = false,
                        error = it.userMessage(),
                    )
                }
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
            runCatching { withContext(Dispatchers.IO) { client.searchPeople(query.trim()) } }
                .onSuccess {
                    _people.value = _people.value.copy(loading = false, people = it)
                }
                .onFailure {
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
            runCatching { withContext(Dispatchers.IO) { client.personCredits(person.id) } }
                .onSuccess {
                    _people.value = _people.value.copy(loading = false, credits = it)
                }
                .onFailure {
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
        )
        detailJob = scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val details = async { client.details(item) }
                        val providers = async { client.watchOptions(item) }
                        val recommendations = async { client.recommendations(item) }
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
                    becauseYouLiked = MediaRow("Because you liked ${details.item.title}", recommendations)
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
        detailJob = null
        _detail.value = DetailUiState()
    }

    fun toggleWatchlist(): Boolean {
        val item = _detail.value.details?.item ?: _detail.value.seed ?: return false
        val saved = watchlistStore.toggle(item)
        _detail.value = _detail.value.copy(inWatchlist = saved)
        return saved
    }

    fun saveSettings(settings: MarqueeSettings): Result<Unit> {
        val normalized = settings.copy(
            tmdbCredential = settings.tmdbCredential.trim(),
            region = settings.region.trim().uppercase(),
            preferredResolverPackage = settings.preferredResolverPackage.trim(),
        )
        if (normalized.tmdbCredential.isBlank()) {
            return Result.failure(IllegalArgumentException("A TMDB credential is required."))
        }
        if (!Regex("^[A-Z]{2}$").matches(normalized.region)) {
            return Result.failure(IllegalArgumentException("Region must be a two-letter country code."))
        }
        if (
            normalized.preferredResolverPackage.isNotBlank() &&
            !Regex("""^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$""")
                .matches(normalized.preferredResolverPackage)
        ) {
            return Result.failure(IllegalArgumentException("Resolver must be an Android package name."))
        }

        settingsStore.save(normalized)
        _settings.value = normalized
        _destination.value = Destination.HOME
        refreshHome()
        return Result.success(Unit)
    }

    fun openProvider(provider: WatchProvider): LaunchResult = launcher.openProvider(provider)

    fun openPreferredResolver(item: MediaItem): LaunchResult =
        launcher.openResolver(_settings.value.preferredResolverPackage, item)

    fun openWebOptions(): LaunchResult =
        launcher.openWebOptions(_detail.value.watchOptions.webLink)

    fun isInstalled(packageName: String?): Boolean = launcher.isInstalled(packageName)

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

    private fun Throwable.userMessage(): String =
        message?.takeIf(String::isNotBlank) ?: "Something went wrong."
}
