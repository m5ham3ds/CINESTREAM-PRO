package com.example.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.models.Genre
import com.example.domain.models.Movie
import com.example.domain.models.Series
import com.example.domain.models.MediaDiscoverRequest
import com.example.domain.models.PaginatedResult
import com.example.domain.repository.MediaRepository
import com.example.utils.NetworkConnectivityObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

enum class HomeGenreKind { MOVIE, SERIES, ANIME }
enum class HomeGenreLayout { FEATURE, STILLS_AND_POSTERS, TWO_POSTER_ROWS, STILLS, POSTERS }
data class HomeGenreShelf(
    val genre: Genre,
    val kind: HomeGenreKind,
    val layout: HomeGenreLayout
) {
    val stableKey: String get() = "${kind.name.lowercase()}_${genre.id}"
}

data class HomeUiState(
    val isLoading: Boolean = true,
    val initialFeedsLoaded: Boolean = false,
    val trendingMovies: List<Movie> = emptyList(),
    val trendingSeries: List<Series> = emptyList(),
    val weeklyTrendingMovies: List<Movie> = emptyList(),
    val weeklyTrendingSeries: List<Series> = emptyList(),
    val topRatedMovies: List<Movie> = emptyList(),
    val topRatedSeries: List<Series> = emptyList(),
    val topRatedAnime: List<Series> = emptyList(),
    val homeGenreShelves: List<HomeGenreShelf> = emptyList(),
    val curatedFeedsLoaded: Boolean = false,
    val actionMovies: List<Movie> = emptyList(),
    val allMovies: List<Movie> = emptyList(),
    val allSeries: List<Series> = emptyList(),
    val animeSeries: List<Series> = emptyList(),
    val trendingAnime: List<Series> = emptyList(),
    val trendingAnimeToday: List<Series> = emptyList(),
    val popularMovies: List<Movie> = emptyList(),
    val popularSeries: List<Series> = emptyList(),
    val popularAnime: List<Series> = emptyList(),
    val upcomingSeries: List<Series> = emptyList(),
    val upcomingAnime: List<Series> = emptyList(),
    val newReleasesAnime: List<Series> = emptyList(),
    val upcomingMovies: List<Movie> = emptyList(),
    val newReleasesMovies: List<Movie> = emptyList(),
    val newReleasesSeries: List<Series> = emptyList(),
    val arabicMovies: List<Movie> = emptyList(),
    val arabicSeries: List<Series> = emptyList(),
    val error: String? = null
)

class HomeViewModel(
    private val repository: MediaRepository,
    private val connectivityObserver: NetworkConnectivityObserver? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // Phase 05Y-C: Real TMDB Paginated List Controllers
    val trendingPaginator = com.example.data.repository.TmdbListPaginator(
        fetchMoviesPage = { repository.getTrendingMoviesPage(it) },
        fetchSeriesPage = { repository.getTrendingSeriesPage(it) },
        fetchAnimePage = { repository.getTrendingAnimePage(it) },
        coroutineScope = viewModelScope
    )

    val popularPaginator = com.example.data.repository.TmdbListPaginator(
        fetchMoviesPage = { repository.getPopularMoviesPage(it) },
        fetchSeriesPage = { repository.getPopularSeriesPage(it) },
        fetchAnimePage = { repository.getPopularAnimePage(it) },
        coroutineScope = viewModelScope
    )

    val upcomingPaginator = com.example.data.repository.TmdbListPaginator(
        fetchMoviesPage = { repository.getUpcomingMoviesPage(it) },
        fetchSeriesPage = { repository.getUpcomingSeriesPage(it) },
        fetchAnimePage = { repository.getUpcomingAnimePage(it) },
        coroutineScope = viewModelScope
    )

    val newReleasesPaginator = com.example.data.repository.TmdbListPaginator(
        fetchMoviesPage = { repository.getNewReleasesMoviesPage(it) },
        fetchSeriesPage = { repository.getNewReleasesSeriesPage(it) },
        fetchAnimePage = { repository.getNewReleasesAnimePage(it) },
        coroutineScope = viewModelScope
    )


    // Home's genre grids have independent server-filtered cursors so choosing a genre
    // never falls through to unrelated Popular/Trending records.
    private var homeMovieGenreId: Int? = null
    private var homeSeriesGenreId: Int? = null
    private var homeAnimeGenreId: Int? = null

    val movieGenrePaginator = com.example.data.repository.TmdbListPaginator(
        fetchMoviesPage = { page -> homeMovieGenreId?.let { repository.getMoviesByGenrePage(it, page) }
            ?: repository.getPopularMoviesPage(page) },
        fetchSeriesPage = { repository.getPopularSeriesPage(it) },
        fetchAnimePage = { repository.getPopularAnimePage(it) },
        coroutineScope = viewModelScope
    )
    val seriesGenrePaginator = com.example.data.repository.TmdbListPaginator(
        fetchMoviesPage = { repository.getPopularMoviesPage(it) },
        fetchSeriesPage = { page -> homeSeriesGenreId?.let { repository.getSeriesByGenrePage(it, page) }
            ?: repository.getPopularSeriesPage(page) },
        fetchAnimePage = { repository.getPopularAnimePage(it) },
        coroutineScope = viewModelScope
    )
    val animeGenrePaginator = com.example.data.repository.TmdbListPaginator(
        fetchMoviesPage = { repository.getPopularMoviesPage(it) },
        fetchSeriesPage = { repository.getPopularSeriesPage(it) },
        fetchAnimePage = { page -> homeAnimeGenreId?.let { repository.getAnimeByGenrePage(it, page) }
            ?: repository.getPopularAnimePage(page) },
        coroutineScope = viewModelScope
    )

    fun selectHomeMovieGenre(genre: Genre?) {
        homeMovieGenreId = genre?.id
        movieGenrePaginator.resetTab(1)
        if (genre == null) popularPaginator.ensureTabLoaded(1) else movieGenrePaginator.loadFirstPage(1)
    }

    fun selectHomeSeriesGenre(genre: Genre?) {
        homeSeriesGenreId = genre?.id
        seriesGenrePaginator.resetTab(2)
        if (genre == null) popularPaginator.ensureTabLoaded(2) else seriesGenrePaginator.loadFirstPage(2)
    }

    fun selectHomeAnimeGenre(genre: Genre?) {
        homeAnimeGenreId = genre?.id
        animeGenrePaginator.resetTab(3)
        if (genre == null) popularPaginator.ensureTabLoaded(3) else animeGenrePaginator.loadFirstPage(3)
    }

    private var loadJob: Job? = null
    private var loadGeneration: Long = 0L
    private var initialLoadStarted = false
    private var curatedFeedsStarted = false
    private var curatedFeedsJob: Job? = null

    // Share cold repository flows across a Home shelf and its details sheet. This avoids
    // firing duplicate TMDB requests when Compose recomposes or the same provider is opened.
    private val movieGenreFeeds = mutableMapOf<Int, StateFlow<List<Movie>>>()
    private val seriesGenreFeeds = mutableMapOf<Int, StateFlow<List<Series>>>()
    private val animeGenreFeeds = mutableMapOf<Int, StateFlow<List<Series>>>()
    private val providerMovieFeeds = mutableMapOf<Pair<Int, String>, StateFlow<List<Movie>>>()
    private val providerSeriesFeeds = mutableMapOf<Pair<Int, String>, StateFlow<List<Series>>>()

    private suspend fun <T> loadCuratedOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    init {
        connectivityObserver?.let { observer ->
            viewModelScope.launch {
                var wasOffline = false
                observer.observe()
                    .distinctUntilChanged()
                    .collect { isConnected ->
                        if (!isConnected) {
                            wasOffline = true
                        } else if (wasOffline) {
                            wasOffline = false
                            if (initialLoadStarted) {
                                loadData(forceRefresh = true)
                                trendingPaginator.retryFailedTabs()
                                popularPaginator.retryFailedTabs()
                                upcomingPaginator.retryFailedTabs()
                                newReleasesPaginator.retryFailedTabs()
                            }
                        }
                    }
            }
        }
    }

    private fun handleLoadError(e: Throwable) {
        val state = _uiState.value
        val hasData = state.trendingMovies.isNotEmpty() || state.allMovies.isNotEmpty()
        if (!hasData) {
            val isOfflineOrCacheMiss = e is com.example.domain.models.TmdbException.NetworkException ||
                    e is java.io.IOException ||
                    !com.example.utils.NetworkUtils.isInternetAvailable(com.example.MyApplication.appContext)
            _uiState.update { 
                it.copy(
                    error = if (isOfflineOrCacheMiss) null else (e.message ?: "Failed to load content"),
                    isLoading = false
                ) 
            }
        }
    }

    fun ensureLoaded() {
        if (initialLoadStarted) return
        initialLoadStarted = true
        loadData()
    }

    fun loadData(forceRefresh: Boolean = false) {
        initialLoadStarted = true
        if (forceRefresh) {
            curatedFeedsJob?.cancel()
            curatedFeedsStarted = false
            _uiState.update { it.copy(initialFeedsLoaded = false, curatedFeedsLoaded = false) }
        }
        val generation = ++loadGeneration
        loadJob?.cancel()
        val hasExistingData = _uiState.value.trendingMovies.isNotEmpty() || _uiState.value.allMovies.isNotEmpty()
        if (!hasExistingData || forceRefresh) {
            _uiState.update { it.copy(isLoading = true, error = null) }
        }
        loadJob = viewModelScope.launch {
            // Preserves existing cached data on refresh without destructive clearCache()
            try {
                supervisorScope {
                    launch {
                        repository.getTrendingMovies().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update { it.copy(trendingMovies = list.take(15), isLoading = false, error = null) }
                        }
                    }
                    launch {
                        repository.getAnimeSeries().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update { it.copy(animeSeries = list.take(15), isLoading = false, error = null) }
                        }
                    }
                    launch {
                        repository.getTrendingSeries().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update { it.copy(trendingSeries = list.take(15), isLoading = false, error = null) }
                        }
                    }
                    launch {
                        repository.getMovies().catch { handleLoadError(it) }.collect { list ->
                            val action = list.filter { m -> m.genreIds.contains(28) || m.genres.any { name -> name.equals("Action", ignoreCase = true) || name.equals("أكشن", ignoreCase = true) } }
                            _uiState.update {
                                it.copy(
                                    allMovies = list.take(15),
                                    popularMovies = list.take(15),
                                    actionMovies = action.take(15),
                                    isLoading = false,
                                    error = null
                                )
                            }
                        }
                    }
                    launch {
                        repository.getUpcomingMovies().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update { it.copy(upcomingMovies = list.take(15), isLoading = false, error = null) }
                        }
                    }
                    launch {
                        repository.getNewReleasesMovies().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update { it.copy(newReleasesMovies = list.take(15), isLoading = false, error = null) }
                        }
                    }
                    launch {
                        repository.getNewReleasesSeries().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update { it.copy(newReleasesSeries = list.take(15), isLoading = false, error = null) }
                        }
                    }
                    launch {
                        repository.getTrendingAnime().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update { it.copy(trendingAnime = list.take(20), isLoading = false, error = null) }
                        }
                    }
                    launch {
                        repository.getTrendingAnimeToday().catch { }.collect { list ->
                            _uiState.update { it.copy(trendingAnimeToday = list.take(10)) }
                        }
                    }
                    launch {
                        repository.getSeries().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update {
                                it.copy(
                                    allSeries = list.take(15),
                                    popularSeries = list.take(15),
                                    isLoading = false,
                                    error = null
                                )
                            }
                        }
                    }
                    launch {
                        repository.getUpcomingSeries().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update { it.copy(upcomingSeries = list.take(15), isLoading = false, error = null) }
                        }
                    }
                    launch {
                        repository.getUpcomingAnime().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update { it.copy(upcomingAnime = list.take(15), isLoading = false, error = null) }
                        }
                    }
                    launch {
                        repository.getNewReleasesAnime().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update { it.copy(newReleasesAnime = list.take(15), isLoading = false, error = null) }
                        }
                    }
}
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == loadGeneration) handleLoadError(e)
            } finally {
                // The previous job's finally block must not stop a newer refresh spinner.
                if (generation == loadGeneration) _uiState.update {
                    it.copy(isLoading = false, initialFeedsLoaded = true)
                }
            }
        }
    }

    /**
     * Loads FlixQuest-inspired shelves after the first screen has data. Each request is
     * independent; a slow provider or ranking feed must not block the initial Home UI.
     * Genre/provider shelf content itself is requested lazily only when its row is composed.
     */
    fun ensureCuratedFeedsLoaded() {
        if (curatedFeedsStarted || curatedFeedsJob?.isActive == true) return
        curatedFeedsStarted = true
        curatedFeedsJob = viewModelScope.launch {
            supervisorScope {
                launch {
                    repository.getTrendingMoviesWeekly().catch { }.collect { list ->
                        _uiState.update { it.copy(weeklyTrendingMovies = list.take(20)) }
                    }
                }
                launch {
                    repository.getTrendingSeriesWeekly().catch { }.collect { list ->
                        _uiState.update { it.copy(weeklyTrendingSeries = list.take(20)) }
                    }
                }
                launch {
                    loadCuratedOrNull { repository.getPopularAnimePage(1).items.take(20) }
                        ?.let { anime -> _uiState.update { it.copy(popularAnime = anime) } }
                }
                launch {
                    loadCuratedOrNull { repository.getTopRatedMoviesPage(1).items.take(20) }
                        ?.let { movies -> _uiState.update { it.copy(topRatedMovies = movies) } }
                }
                launch {
                    loadCuratedOrNull { repository.getTopRatedSeriesPage(1).items.take(20) }
                        ?.let { series -> _uiState.update { it.copy(topRatedSeries = series) } }
                }
                launch {
                    loadCuratedOrNull { repository.getTopRatedAnimePage(1).items.take(20) }
                        ?.let { anime -> _uiState.update { it.copy(topRatedAnime = anime) } }
                }
                launch {
                    // Select and retain a small set of varied shelves; no requests for their
                    // catalog contents are made until the corresponding LazyColumn item appears.
                    val genreGroups = loadCuratedOrNull {
                        repository.getMovieGenres() to repository.getTvGenres()
                    }
                    if (genreGroups != null) {
                        val (movieGenres, seriesGenres) = genreGroups
                        fun findGenre(
                            genres: List<Genre>,
                            id: Int,
                            vararg names: String
                        ): Genre? = genres.firstOrNull { it.id == id }
                            ?: genres.firstOrNull { genre -> names.any { name -> genre.name.contains(name, ignoreCase = true) } }

                        // These four genre shelves are intentionally fixed and type-safe.
                        // Their layouts mirror FlixQuest's four distinct category treatments.
                        val action = findGenre(movieGenres, 28, "Action", "أكشن")
                        val reality = findGenre(seriesGenres, 10764, "Reality", "واقعي")
                        val mystery = findGenre(seriesGenres, 9648, "Mystery", "غموض")
                        val history = findGenre(movieGenres, 36, "History", "تاريخ")
                        val selected = buildList {
                            action?.let { add(HomeGenreShelf(it, HomeGenreKind.MOVIE, HomeGenreLayout.STILLS)) }
                            reality?.let { add(HomeGenreShelf(it, HomeGenreKind.SERIES, HomeGenreLayout.STILLS_AND_POSTERS)) }
                            mystery?.let { add(HomeGenreShelf(it, HomeGenreKind.SERIES, HomeGenreLayout.TWO_POSTER_ROWS)) }
                            history?.let { add(HomeGenreShelf(it, HomeGenreKind.MOVIE, HomeGenreLayout.FEATURE)) }
                        }
                        _uiState.update { it.copy(homeGenreShelves = selected) }
                    }
                }
            }
            _uiState.update { it.copy(curatedFeedsLoaded = true) }
        }
    }

    fun moviesByGenre(genreId: Int): StateFlow<List<Movie>> = synchronized(movieGenreFeeds) {
        movieGenreFeeds.getOrPut(genreId) {
            repository.getMoviesByGenre(genreId)
                .catch { emit(emptyList()) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        }
    }

    fun seriesByGenre(genreId: Int): StateFlow<List<Series>> = synchronized(seriesGenreFeeds) {
        seriesGenreFeeds.getOrPut(genreId) {
            repository.getSeriesByGenre(genreId)
                .catch { emit(emptyList()) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        }
    }

    fun animeByGenre(genreId: Int): StateFlow<List<Series>> = synchronized(animeGenreFeeds) {
        animeGenreFeeds.getOrPut(genreId) {
            repository.getAnimeByGenre(genreId)
                .catch { emit(emptyList()) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        }
    }

    fun moviesByProvider(providerId: Int, region: String): StateFlow<List<Movie>> {
        val key = providerId to region.uppercase()
        return synchronized(providerMovieFeeds) {
            providerMovieFeeds.getOrPut(key) {
                repository.getMoviesByProvider(key.first, key.second)
                    .catch { emit(emptyList()) }
                    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
            }
        }
    }

    fun seriesByProvider(providerId: Int, region: String): StateFlow<List<Series>> {
        val key = providerId to region.uppercase()
        return synchronized(providerSeriesFeeds) {
            providerSeriesFeeds.getOrPut(key) {
                repository.getSeriesByProvider(key.first, key.second)
                    .catch { emit(emptyList()) }
                    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
            }
        }
    }

    /** Executes an actual server-side TMDB Discover query for the Discover tab. */
    suspend fun discoverMovies(request: MediaDiscoverRequest): PaginatedResult<Movie> =
        repository.discoverMovies(request)

    /** animeOnly constrains TV results to Japanese animation, independently of TV series. */
    suspend fun discoverSeries(request: MediaDiscoverRequest): PaginatedResult<Series> =
        repository.discoverSeries(request)
}
