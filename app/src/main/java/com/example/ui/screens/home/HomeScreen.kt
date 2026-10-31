package com.example.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ArrowBack

import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.foundation.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.draw.*
import androidx.compose.foundation.shape.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.res.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.unit.*
import com.example.ui.components.*
import coil.compose.AsyncImage
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.example.data.model.*
import com.example.data.util.ContentTypeResolver
import com.example.data.repository.*
import com.example.domain.models.*
import com.example.ui.ViewModelFactory
import kotlinx.coroutines.launch
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.lazy.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import androidx.compose.material3.pulltorefresh.*
import com.example.ui.screens.home.HomeViewModel


internal data class HomeMixedMediaItem(
    val id: String,
    val title: String,
    val posterUrl: String,
    val year: Int,
    val rating: Double,
    val isMovie: Boolean,
    val contentType: String,
    val backdropUrl: String = posterUrl,
    val genreIds: List<Int> = emptyList()
)

enum class HomeNavCategory(val titleRes: Int, val id: String) {
    HOME(R.string.home, "home"),
    MOVIES(R.string.category_movies, "movies"),
    ANIME(R.string.category_anime, "anime"),
    SERIES(R.string.category_series, "series")
}







@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onMovieClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    onPlayMovie: ((String) -> Unit)? = null,
    onPlaySeries: ((String) -> Unit)? = null,
    onNavigateToTrending: () -> Unit = {},
    onNavigateToWatching: () -> Unit = {},
    onNavigateToPopular: () -> Unit = {},
    onNavigateToNewReleases: () -> Unit = {},
    onNavigateToUpcoming: () -> Unit = {},
    onNavigateToAnime: () -> Unit = {},
    onNavigateToLibrary: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onNavigateToDownloads: () -> Unit = {},
    onNavigateToNotifications: () -> Unit = {},
    onOpenMenu: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(factory = ViewModelFactory())
) {
    val uiState by viewModel.uiState.collectAsState()
    val homeMoviesPageState by viewModel.popularPaginator.tab1State.collectAsState()
    val homeSeriesPageState by viewModel.popularPaginator.tab2State.collectAsState()
    val homeAnimePageState by viewModel.popularPaginator.tab3State.collectAsState()
    val homeMovieGenrePageState by viewModel.movieGenrePaginator.tab1State.collectAsState()
    val homeSeriesGenrePageState by viewModel.seriesGenrePaginator.tab2State.collectAsState()
    val homeAnimeGenrePageState by viewModel.animeGenrePaginator.tab3State.collectAsState()
    val homeMoviesPageItems = remember(homeMoviesPageState.items) {
        homeMoviesPageState.items.mapNotNull { it.first as? Movie }.distinctBy { it.id }
    }
    val homeSeriesPageItems = remember(homeSeriesPageState.items) {
        homeSeriesPageState.items.mapNotNull { it.first as? Series }.distinctBy { it.id }
    }
    val homeAnimePageItems = remember(homeAnimePageState.items) {
        homeAnimePageState.items.mapNotNull { it.first as? Series }.distinctBy { it.id }
    }
    val homeMovieGenreItems = remember(homeMovieGenrePageState.items) {
        homeMovieGenrePageState.items.mapNotNull { it.first as? Movie }.distinctBy { it.id }
    }
    val homeSeriesGenreItems = remember(homeSeriesGenrePageState.items) {
        homeSeriesGenrePageState.items.mapNotNull { it.first as? Series }.distinctBy { it.id }
    }
    val homeAnimeGenreItems = remember(homeAnimeGenrePageState.items) {
        homeAnimeGenrePageState.items.mapNotNull { it.first as? Series }.distinctBy { it.id }
    }
    LaunchedEffect(viewModel) {
        viewModel.ensureLoaded()
    }
    LaunchedEffect(uiState.initialFeedsLoaded) {
        if (uiState.initialFeedsLoaded) viewModel.ensureCuratedFeedsLoaded()
    }
    val context = LocalContext.current
    val historyRepository = remember { HistoryRepository(context) }
    val historyItems by historyRepository.getHistoryItems().collectAsState(initial = emptyList())
    LaunchedEffect(historyItems) {
        if (historyItems.isNotEmpty()) {
            com.example.data.repository.ContinueWatchingMetadataManager.preload(historyItems, this)
        }
    }
    val libraryRepository = remember { LibraryRepository(context) }
    val libraryItems by libraryRepository.getLibraryItems().collectAsState(initial = emptyList())
    val downloadRepository = remember { DownloadRepository(context) }
    val scope = rememberCoroutineScope()
    var homeFeedFilter by rememberSaveable { mutableStateOf(HomeFeedFilter.ALL) }
    var selectedHomeProvider by remember { mutableStateOf<HomeStreamingService?>(null) }
    val providerRegion = remember {
        java.util.Locale.getDefault().country.uppercase().takeIf { it.length == 2 } ?: "US"
    }
    val visibleHistoryItems = remember(historyItems, homeFeedFilter) {
        when (homeFeedFilter) {
            HomeFeedFilter.ALL -> historyItems
            HomeFeedFilter.MOVIES -> historyItems.filter { it.isMovie }
            HomeFeedFilter.SERIES -> historyItems.filter { !it.isMovie }
        }
    }

    val hasRemoteContent = uiState.trendingMovies.isNotEmpty() ||
            uiState.allMovies.isNotEmpty() ||
            uiState.popularMovies.isNotEmpty() ||
            uiState.trendingSeries.isNotEmpty() ||
            uiState.popularSeries.isNotEmpty() ||
            uiState.allSeries.isNotEmpty() ||
            uiState.actionMovies.isNotEmpty() ||
            uiState.upcomingMovies.isNotEmpty() ||
            uiState.newReleasesMovies.isNotEmpty() ||
            uiState.trendingAnime.isNotEmpty() ||
            uiState.trendingAnimeToday.isNotEmpty() ||
            uiState.animeSeries.isNotEmpty()

    var showBottomSheet by remember { mutableStateOf(false) }
    var bottomSheetIsMovie by remember { mutableStateOf(true) }
    var bottomSheetContentType by remember { mutableStateOf(ContentType.MOVIE) }
    var selectedMediaId by remember { mutableStateOf("") }
    var selectedMediaTitle by remember { mutableStateOf("") }
    var selectedMediaPoster by remember { mutableStateOf("") }

    val onMediaLongClick: (id: String, title: String, posterUrl: String, isMovie: Boolean, contentType: String) -> Unit =
        remember {
            { id, title, posterUrl, isMovie, contentType ->
                bottomSheetIsMovie = isMovie
                bottomSheetContentType = contentType
                selectedMediaId = id
                selectedMediaTitle = title
                selectedMediaPoster = posterUrl
                showBottomSheet = true
            }
        }

    var selectedCategory by rememberSaveable { mutableStateOf(HomeNavCategory.HOME) }
    var selectedMovieGenre by remember { mutableStateOf<Genre?>(null) }
    var selectedSeriesGenre by remember { mutableStateOf<Genre?>(null) }
    var selectedAnimeGenre by remember { mutableStateOf<Genre?>(null) }
    var isGenreDropdownExpanded by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && (
                    selectedCategory != HomeNavCategory.HOME || selectedMovieGenre != null ||
                    selectedSeriesGenre != null || selectedAnimeGenre != null
                )) {
                selectedCategory = HomeNavCategory.HOME
                selectedMovieGenre = null
                selectedSeriesGenre = null
                selectedAnimeGenre = null
                isGenreDropdownExpanded = false
                viewModel.selectHomeMovieGenre(null)
                viewModel.selectHomeSeriesGenre(null)
                viewModel.selectHomeAnimeGenre(null)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val movieGenres = remember { com.example.data.util.TmdbGenreHelper.getMovieGenres() }
    val tvGenres = remember { com.example.data.util.TmdbGenreHelper.getTvGenres() }
    val animeGenres = remember { com.example.data.util.TmdbGenreHelper.getAnimeGenres() }
    val allGenreStr = stringResource(R.string.genre_all)

    val movieGenreOptions = remember(movieGenres, allGenreStr) {
        listOf(DropdownOption<Genre?>(null, allGenreStr)) + movieGenres.map {
            DropdownOption<Genre?>(it, it.name)
        }
    }
    val tvGenreOptions = remember(tvGenres, allGenreStr) {
        listOf(DropdownOption<Genre?>(null, allGenreStr)) + tvGenres.map {
            DropdownOption<Genre?>(it, it.name)
        }
    }
    val animeGenreOptions = remember(animeGenres, allGenreStr) {
        listOf(DropdownOption<Genre?>(null, allGenreStr)) + animeGenres.map {
            DropdownOption<Genre?>(it, it.name)
        }
    }

    // Each category uses its own paginated query. Genre selections use server-side
    // discovery filters and remain isolated from other media types and catalogues.
    val displayCategoryMovies = remember(homeMoviesPageItems, homeMovieGenreItems, selectedMovieGenre) {
        if (selectedMovieGenre == null) homeMoviesPageItems else homeMovieGenreItems
    }
    val displayCategorySeries = remember(homeSeriesPageItems, homeSeriesGenreItems, selectedSeriesGenre) {
        if (selectedSeriesGenre == null) homeSeriesPageItems else homeSeriesGenreItems
    }
    val displayCategoryAnime = remember(homeAnimePageItems, homeAnimeGenreItems, selectedAnimeGenre) {
        if (selectedAnimeGenre == null) homeAnimePageItems else homeAnimeGenreItems
    }

    val ptrState = rememberPullToRefreshState()
    
    val newReleasesMoviesMix = remember(uiState.newReleasesMovies) {
        uiState.newReleasesMovies.map { movie ->
            HomeMixedMediaItem(movie.id, movie.title, movie.posterUrl, movie.year, movie.rating, true, ContentType.MOVIE, movie.backdropUrl)
        }.distinctBy { it.id }
    }
    val newReleasesSeriesMix = remember(uiState.newReleasesSeries) {
        uiState.newReleasesSeries.map { series ->
            HomeMixedMediaItem(series.id, series.title, series.posterUrl, series.year, series.rating, false, ContentTypeResolver.resolveSeries(series), series.backdropUrl)
        }.distinctBy { it.id }
    }
    val newReleasesAnimeMix = remember(uiState.newReleasesAnime) {
        uiState.newReleasesAnime.map { anime ->
            HomeMixedMediaItem(anime.id, anime.title, anime.posterUrl, anime.year, anime.rating, false, ContentType.ANIME, anime.backdropUrl)
        }.distinctBy { it.id }
    }
    val newEpisodesMix = remember(newReleasesSeriesMix, newReleasesAnimeMix) {
        (newReleasesSeriesMix + newReleasesAnimeMix).distinctBy { "${it.contentType}:${it.id}" }
    }
    val libraryMediaMix = remember(libraryItems) {
        libraryItems.filter { it.id.isNotBlank() && it.posterUrl.isNotBlank() }.map { item ->
            val normalizedType = item.contentType.takeIf { ContentType.isValid(it) }
                ?: if (item.isMovie) ContentType.MOVIE else ContentType.TV
            HomeMixedMediaItem(item.id, item.title, item.posterUrl, 0, 0.0, item.isMovie, normalizedType, item.posterUrl)
        }.distinctBy { "${it.contentType}:${it.id}" }
    }
    val topTenMoviesMix = remember(uiState.trendingMovies) {
        uiState.trendingMovies.distinctBy { it.id }.take(10).map { movie ->
            HomeMixedMediaItem(movie.id, movie.title, movie.posterUrl, movie.year, movie.rating, true, ContentType.MOVIE, movie.backdropUrl)
        }
    }
    val topTenSeriesMix = remember(uiState.trendingSeries) {
        uiState.trendingSeries.filter { ContentTypeResolver.resolveSeries(it) != ContentType.ANIME }.distinctBy { it.id }.take(10).map { series ->
            HomeMixedMediaItem(series.id, series.title, series.posterUrl, series.year, series.rating, false, ContentTypeResolver.resolveSeries(series), series.backdropUrl)
        }
    }
    val topTenAnimeMix = remember(uiState.trendingAnimeToday) {
        uiState.trendingAnimeToday.distinctBy { it.id }.take(10).map { anime ->
            HomeMixedMediaItem(anime.id, anime.title, anime.posterUrl, anime.year, anime.rating, false, ContentType.ANIME, anime.backdropUrl)
        }
    }
    val popularMoviesMix = remember(uiState.popularMovies) {
        uiState.popularMovies.distinctBy { it.id }.map { movie ->
            HomeMixedMediaItem(movie.id, movie.title, movie.posterUrl, movie.year, movie.rating, true, ContentType.MOVIE, movie.backdropUrl)
        }
    }
    val popularSeriesMix = remember(uiState.popularSeries) {
        uiState.popularSeries.filter { ContentTypeResolver.resolveSeries(it) != ContentType.ANIME }.distinctBy { it.id }.map { series ->
            HomeMixedMediaItem(series.id, series.title, series.posterUrl, series.year, series.rating, false, ContentTypeResolver.resolveSeries(series), series.backdropUrl)
        }
    }
    val popularAnimeMix = remember(uiState.popularAnime) {
        uiState.popularAnime.distinctBy { it.id }.map { anime ->
            HomeMixedMediaItem(anime.id, anime.title, anime.posterUrl, anime.year, anime.rating, false, ContentType.ANIME, anime.backdropUrl)
        }
    }
    val topRatedMoviesMix = remember(uiState.topRatedMovies) {
        uiState.topRatedMovies.map { movie -> HomeMixedMediaItem(movie.id, movie.title, movie.posterUrl, movie.year, movie.rating, true, ContentType.MOVIE, movie.backdropUrl) }
    }
    val topRatedSeriesMix = remember(uiState.topRatedSeries) {
        uiState.topRatedSeries.map { series -> HomeMixedMediaItem(series.id, series.title, series.posterUrl, series.year, series.rating, false, ContentTypeResolver.resolveSeries(series), series.backdropUrl) }
    }
    val topRatedAnimeMix = remember(uiState.topRatedAnime) {
        uiState.topRatedAnime.map { series -> HomeMixedMediaItem(series.id, series.title, series.posterUrl, series.year, series.rating, false, ContentType.ANIME, series.backdropUrl) }
    }
    val upcomingMix = remember(uiState.upcomingMovies) {
        uiState.upcomingMovies.take(10).distinctBy { it.id }
    }
    val filteredTrendingMovies = remember(uiState.weeklyTrendingMovies) {
        uiState.weeklyTrendingMovies.distinctBy { it.id }
    }
    val filteredTrendingSeries = remember(uiState.weeklyTrendingSeries) {
        uiState.weeklyTrendingSeries.distinctBy { it.id }
    }
    // Keep Trending Anime separate from the daily Top 10 row and remove duplicates
    // that appeared in today's chart.
    val filteredAnimeSeries = remember(uiState.trendingAnime, uiState.trendingAnimeToday) {
        val todayIds = uiState.trendingAnimeToday.mapTo(mutableSetOf()) { it.id }
        uiState.trendingAnime.filterNot { it.id in todayIds }.distinctBy { it.id }
    }

    // One stable, mixed hero feed: recent history leads when it was played within
    // three days, then the weekly/popular catalogs fill the remaining spotlight slots.
    val homeHeroItems = remember(
        uiState.trendingMovies, uiState.trendingSeries, uiState.weeklyTrendingMovies,
        uiState.weeklyTrendingSeries, uiState.popularMovies, uiState.popularSeries,
        uiState.trendingAnime, uiState.trendingAnimeToday, uiState.popularAnime,
        historyItems, homeFeedFilter
    ) {
        buildList {
            val now = System.currentTimeMillis()
            val recent = historyItems
                .filter { now - it.timestamp in 0..(3L * 24 * 60 * 60 * 1000) }
                .filter { item ->
                    when (homeFeedFilter) {
                        HomeFeedFilter.ALL -> true
                        HomeFeedFilter.MOVIES -> item.isMovie
                        HomeFeedFilter.SERIES -> !item.isMovie
                    }
                }
                .maxByOrNull { it.timestamp }
                ?.takeIf { it.posterUrl.isNotBlank() }
            if (recent != null) {
                add(HeroItem(
                    id = recent.id,
                    title = recent.title,
                    backdropUrl = recent.posterUrl,
                    isMovie = recent.isMovie,
                    contentType = if (recent.isMovie) ContentType.MOVIE else ContentType.TV,
                    posterUrl = recent.posterUrl,
                    isContinueWatching = true
                ))
            }
            val movieCandidates = (uiState.weeklyTrendingMovies + uiState.trendingMovies + uiState.popularMovies.shuffled())
                .distinctBy { it.id }
                .map { movie -> HeroItem(
                    id = movie.id, title = movie.title,
                    backdropUrl = movie.backdropUrl.ifBlank { movie.posterUrl },
                    isMovie = true, contentType = ContentType.MOVIE,
                    posterUrl = movie.posterUrl, year = movie.year.takeIf { it > 0 }?.toString(),
                    rating = movie.rating, genres = movie.genres
                ) }
            val regularSeriesCandidates = (uiState.weeklyTrendingSeries + uiState.trendingSeries + uiState.popularSeries.shuffled())
                .filter { ContentTypeResolver.resolveSeries(it) != ContentType.ANIME }
                .distinctBy { it.id }
                .map { series -> HeroItem(
                    id = series.id, title = series.title,
                    backdropUrl = series.backdropUrl.ifBlank { series.posterUrl },
                    isMovie = false, contentType = ContentTypeResolver.resolveSeries(series),
                    posterUrl = series.posterUrl, year = series.year.takeIf { it > 0 }?.toString(),
                    rating = series.rating, genres = series.genres
                ) }
            val animeCandidates = (uiState.trendingAnimeToday + uiState.trendingAnime + uiState.popularAnime)
                .distinctBy { it.id }
                .map { anime -> HeroItem(
                    id = anime.id, title = anime.title,
                    backdropUrl = anime.backdropUrl.ifBlank { anime.posterUrl },
                    isMovie = false, contentType = ContentType.ANIME,
                    posterUrl = anime.posterUrl, year = anime.year.takeIf { it > 0 }?.toString(),
                    rating = anime.rating, genres = anime.genres
                ) }
            val seriesCandidates = (regularSeriesCandidates + animeCandidates)
                .distinctBy { it.id }
            val candidates = when (homeFeedFilter) {
                HomeFeedFilter.MOVIES -> movieCandidates
                HomeFeedFilter.SERIES -> seriesCandidates
                HomeFeedFilter.ALL -> buildList {
                    val count = maxOf(movieCandidates.size, seriesCandidates.size)
                    repeat(count) { index ->
                        movieCandidates.getOrNull(index)?.let(::add)
                        seriesCandidates.getOrNull(index)?.let(::add)
                    }
                }
            }
            val alreadyAdded = mapNotNull { it.id }.toSet()
            addAll(candidates.filterNot { it.id in alreadyAdded }.take((10 - size).coerceAtLeast(0)))
        }.take(10)
    }

    val homeListState = rememberLazyListState()
    val onHomeFilterSelected: (HomeFeedFilter) -> Unit = { filter ->
        homeFeedFilter = filter
        selectedCategory = HomeNavCategory.HOME
        selectedMovieGenre = null
        selectedSeriesGenre = null
        selectedAnimeGenre = null
        isGenreDropdownExpanded = false
        viewModel.selectHomeMovieGenre(null)
        viewModel.selectHomeSeriesGenre(null)
        viewModel.selectHomeAnimeGenre(null)
        scope.launch { homeListState.animateScrollToItem(0) }
    }
    val onHomeCategories: () -> Unit = {
        homeFeedFilter = HomeFeedFilter.ALL
        selectedCategory = HomeNavCategory.MOVIES
        selectedMovieGenre = null
        selectedSeriesGenre = null
        selectedAnimeGenre = null
        isGenreDropdownExpanded = true
        viewModel.selectHomeMovieGenre(null)
        viewModel.selectHomeSeriesGenre(null)
        viewModel.selectHomeAnimeGenre(null)
        scope.launch { homeListState.animateScrollToItem(2) }
    }
    val activeHomeTab = when (selectedCategory) {
        HomeNavCategory.MOVIES -> 1
        HomeNavCategory.SERIES -> 2
        HomeNavCategory.ANIME -> 3
        HomeNavCategory.HOME -> 0
    }
    val activeHomePaginator = when (selectedCategory) {
        HomeNavCategory.MOVIES -> if (selectedMovieGenre != null) viewModel.movieGenrePaginator else viewModel.popularPaginator
        HomeNavCategory.SERIES -> if (selectedSeriesGenre != null) viewModel.seriesGenrePaginator else viewModel.popularPaginator
        HomeNavCategory.ANIME -> if (selectedAnimeGenre != null) viewModel.animeGenrePaginator else viewModel.popularPaginator
        HomeNavCategory.HOME -> viewModel.popularPaginator
    }
    val activeHomePageState = when (selectedCategory) {
        HomeNavCategory.MOVIES -> if (selectedMovieGenre != null) homeMovieGenrePageState else homeMoviesPageState
        HomeNavCategory.SERIES -> if (selectedSeriesGenre != null) homeSeriesGenrePageState else homeSeriesPageState
        HomeNavCategory.ANIME -> if (selectedAnimeGenre != null) homeAnimeGenrePageState else homeAnimePageState
        HomeNavCategory.HOME -> homeMoviesPageState
    }

    LaunchedEffect(
        selectedCategory, selectedMovieGenre?.id, selectedSeriesGenre?.id, selectedAnimeGenre?.id,
        isGenreDropdownExpanded
    ) {
        // The toolbar's Categories shortcut opens the genre selector directly;
        // normal category navigation preserves the original hero/category-row entry.
        if (selectedCategory == HomeNavCategory.MOVIES && isGenreDropdownExpanded) {
            homeListState.scrollToItem(2)
        } else {
            homeListState.scrollToItem(0)
        }
    }
    LaunchedEffect(selectedCategory, selectedMovieGenre?.id, selectedSeriesGenre?.id, selectedAnimeGenre?.id) {
        when (selectedCategory) {
            HomeNavCategory.MOVIES -> if (selectedMovieGenre != null) viewModel.movieGenrePaginator.ensureTabLoaded(1) else viewModel.popularPaginator.ensureTabLoaded(1)
            HomeNavCategory.SERIES -> if (selectedSeriesGenre != null) viewModel.seriesGenrePaginator.ensureTabLoaded(2) else viewModel.popularPaginator.ensureTabLoaded(2)
            HomeNavCategory.ANIME -> if (selectedAnimeGenre != null) viewModel.animeGenrePaginator.ensureTabLoaded(3) else viewModel.popularPaginator.ensureTabLoaded(3)
            HomeNavCategory.HOME -> Unit
        }
    }
    // Home's list is tall and contains header/genre rows, so auto-pagination must be
    // armed only by a real user scroll near the end. A short result set must not spin
    // through all pages just because the viewport starts at the bottom.
    var homePaginationArmed by remember(
        selectedCategory, selectedMovieGenre?.id, selectedSeriesGenre?.id, selectedAnimeGenre?.id
    ) { mutableStateOf(true) }
    LaunchedEffect(
        selectedCategory, selectedMovieGenre?.id, selectedSeriesGenre?.id, selectedAnimeGenre?.id,
        activeHomePageState.currentPage, activeHomePageState.items.size,
        activeHomePageState.isLoadingFirstPage, activeHomePageState.isLoadingMore,
        activeHomePageState.hasNextPage, activeHomePageState.loadMoreError
    ) {
        if (selectedCategory != HomeNavCategory.HOME && activeHomePageState.hasNextPage && activeHomePageState.loadMoreError == null) {
            snapshotFlow {
                val layout = homeListState.layoutInfo
                val total = layout.totalItemsCount
                val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
                val atEnd = total > 0 && lastVisible >= total - 5
                atEnd to homeListState.isScrollInProgress
            }.collect { (atEnd, userScrolling) ->
                if (!atEnd) {
                    homePaginationArmed = true
                } else if (
                    userScrolling && homePaginationArmed &&
                    !activeHomePageState.isLoadingFirstPage && !activeHomePageState.isLoadingMore &&
                    activeHomePageState.hasNextPage && activeHomePageState.loadMoreError == null
                ) {
                    homePaginationArmed = false
                    activeHomePaginator.loadMore(activeHomeTab)
                }
            }
        }
    }

    BackHandler(enabled = selectedCategory != HomeNavCategory.HOME) {
        selectedCategory = HomeNavCategory.HOME
        isGenreDropdownExpanded = false
        selectedMovieGenre = null
        selectedSeriesGenre = null
        selectedAnimeGenre = null
        viewModel.selectHomeMovieGenre(null)
        viewModel.selectHomeSeriesGenre(null)
        viewModel.selectHomeAnimeGenre(null)
    }

    val pullRefreshIndicator = com.example.ui.components.rememberBriefPullRefreshIndicator()
    PullToRefreshBox(
        isRefreshing = pullRefreshIndicator.visible,
        onRefresh = {
            pullRefreshIndicator.start()
            when (selectedCategory) {
                HomeNavCategory.HOME -> viewModel.loadData(forceRefresh = true)
                HomeNavCategory.MOVIES -> if (selectedMovieGenre != null) viewModel.movieGenrePaginator.refresh(1) else viewModel.popularPaginator.refresh(1)
                HomeNavCategory.SERIES -> if (selectedSeriesGenre != null) viewModel.seriesGenrePaginator.refresh(2) else viewModel.popularPaginator.refresh(2)
                HomeNavCategory.ANIME -> if (selectedAnimeGenre != null) viewModel.animeGenrePaginator.refresh(3) else viewModel.popularPaginator.refresh(3)
            }
        },
        state = ptrState,
        modifier = Modifier.fillMaxSize()
    ) {
        if (!hasRemoteContent) {
            val isOffline = !com.example.utils.NetworkUtils.isInternetAvailable(context)
            Column(modifier = Modifier.fillMaxSize()) {
                // Keep the brand and filter bar available while the first feed is loading,
                // matching FlixQuest's persistent header even before catalog data arrives.
                FlixQuestHomeHeader(
                    selectedFilter = homeFeedFilter,
                    onFilterSelected = onHomeFilterSelected,
                    onCategories = onHomeCategories,
                    onDownloads = onNavigateToDownloads,
                    onSearch = onNavigateToSearch,
                    onNotifications = onNavigateToNotifications,
                    onMenu = onOpenMenu,
                    isScrolled = false
                )
                if (uiState.isLoading || isOffline) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        MediaScreenSkeleton()
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            val isAuthError = uiState.error?.contains("401") == true ||
                                    uiState.error?.contains("Auth") == true ||
                                    uiState.error?.contains("API key") == true
                            val errorText = when {
                                isAuthError -> "TMDB API key configuration required in Secrets panel."
                                !uiState.error.isNullOrBlank() -> uiState.error!!
                                else -> stringResource(R.string.check_net_retry)
                            }
                            Text(
                                text = errorText,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { viewModel.loadData(forceRefresh = true) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text(stringResource(R.string.retry), color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = homeListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                // FlixQuest-inspired pinned brand/header/filter controls. The hero now leads
                // directly into the requested Home section sequence.
                stickyHeader(key = "flixquest_home_pinned_header") {
                    FlixQuestHomeHeader(
                        selectedFilter = homeFeedFilter,
                        onFilterSelected = onHomeFilterSelected,
                        onCategories = onHomeCategories,
                        onDownloads = onNavigateToDownloads,
                        onSearch = onNavigateToSearch,
                        onNotifications = onNavigateToNotifications,
                        onMenu = onOpenMenu,
                        isScrolled = homeListState.firstVisibleItemIndex > 0 || homeListState.firstVisibleItemScrollOffset > 80
                    )
                }

                // Hero Spotlight: up to ten stable mixed candidates, with a recent watch
                // leading when the history record is no older than three days.
                item(key = "home_hero_section") {
                    if (homeHeroItems.isNotEmpty()) {
                        HeroCarousel(
                            items = homeHeroItems,
                            onClick = { id -> onMovieClick(id) },
                            homeStyle = true,
                            pauseAutoAdvance = homeListState.isScrollInProgress,
                            onItemClick = { item -> if (item.isMovie) onMovieClick(item.id) else onSeriesClick(item.id) },
                            onItemPlay = { item ->
                                if (item.isMovie) {
                                    if (onPlayMovie != null) onPlayMovie(item.id) else onMovieClick(item.id)
                                } else {
                                    if (onPlaySeries != null) onPlaySeries(item.id) else onSeriesClick(item.id)
                                }
                            }
                        )
                    }
                }

                when (selectedCategory) {
                    HomeNavCategory.HOME -> {
                        // Ordered Home feed: every catalogue is separated by media kind.
                        // The hero carousel above this block is the moving banner.

                        // 1. Continue Watching
                        if (visibleHistoryItems.isNotEmpty()) {
                            item(key = "section_continue_watching") {
                                SectionTitle(stringResource(R.string.continue_watching), onSeeAllClick = onNavigateToWatching)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    items(visibleHistoryItems, key = { "${it.isMovie}_${it.id}" }) { item ->
                                        ContinueWatchingCardShared(
                                            item = item,
                                            onClick = { if (item.isMovie) onMovieClick(item.id) else onSeriesClick(item.id) },
                                            onPlayClick = {
                                                if (item.isMovie) {
                                                    if (onPlayMovie != null) onPlayMovie(item.id) else onMovieClick(item.id)
                                                } else {
                                                    if (onPlaySeries != null) onPlaySeries(item.id) else onSeriesClick(item.id)
                                                }
                                            }
                                        )
                                    }
                                }
                                Spacer(Modifier.height(24.dp))
                            }
                        }

                        // 2–4. Daily Top 10 shelves (Movies, Series, Anime).
                        if (homeFeedFilter != HomeFeedFilter.SERIES && topTenMoviesMix.isNotEmpty()) {
                            item(key = "home_top_10_movies_today") {
                                HomePosterShelf(
                                    title = stringResource(R.string.home_top_ten_movies),
                                    items = topTenMoviesMix,
                                    onMovieClick = onMovieClick,
                                    onSeriesClick = onSeriesClick,
                                    onLongClick = { item -> onMediaLongClick(item.id, item.title, item.posterUrl, item.isMovie, item.contentType) },
                                    onSeeAll = onNavigateToTrending,
                                    ranked = true,
                                    maxItems = 10
                                )
                            }
                        }
                        if (homeFeedFilter != HomeFeedFilter.MOVIES && topTenSeriesMix.isNotEmpty()) {
                            item(key = "home_top_10_series_today") {
                                HomePosterShelf(
                                    title = stringResource(R.string.home_top_ten_series),
                                    items = topTenSeriesMix,
                                    onMovieClick = onMovieClick,
                                    onSeriesClick = onSeriesClick,
                                    onLongClick = { item -> onMediaLongClick(item.id, item.title, item.posterUrl, item.isMovie, item.contentType) },
                                    onSeeAll = onNavigateToTrending,
                                    ranked = true,
                                    maxItems = 10
                                )
                            }
                        }
                        if (homeFeedFilter != HomeFeedFilter.MOVIES && topTenAnimeMix.isNotEmpty()) {
                            item(key = "home_top_10_anime_today") {
                                HomePosterShelf(
                                    title = stringResource(R.string.home_top_ten_anime),
                                    items = topTenAnimeMix,
                                    onMovieClick = onMovieClick,
                                    onSeriesClick = onSeriesClick,
                                    onLongClick = { item -> onMediaLongClick(item.id, item.title, item.posterUrl, false, ContentType.ANIME) },
                                    onSeeAll = onNavigateToAnime,
                                    ranked = true,
                                    maxItems = 10
                                )
                            }
                        }

                        // 5. Library (the viewer's saved titles, never a static catalogue).
                        val visibleLibrary = when (homeFeedFilter) {
                            HomeFeedFilter.ALL -> libraryMediaMix
                            HomeFeedFilter.MOVIES -> libraryMediaMix.filter { it.isMovie }
                            HomeFeedFilter.SERIES -> libraryMediaMix.filter { !it.isMovie }
                        }
                        if (visibleLibrary.isNotEmpty()) {
                            item(key = "home_library") {
                                HomePosterShelf(
                                    title = stringResource(R.string.library),
                                    items = visibleLibrary,
                                    onMovieClick = onMovieClick,
                                    onSeriesClick = onSeriesClick,
                                    onLongClick = { item -> onMediaLongClick(item.id, item.title, item.posterUrl, item.isMovie, item.contentType) },
                                    onSeeAll = onNavigateToLibrary,
                                    maxItems = 20
                                )
                            }
                        }

                        // 6–8. Trending Movies, Series, Anime.
                        if (homeFeedFilter != HomeFeedFilter.SERIES && filteredTrendingMovies.isNotEmpty()) {
                            item(key = "home_trending_movies") {
                                HomePosterShelf(
                                    title = stringResource(R.string.trending_movies),
                                    items = filteredTrendingMovies.map { movie -> HomeMixedMediaItem(movie.id, movie.title, movie.posterUrl, movie.year, movie.rating, true, ContentType.MOVIE, movie.backdropUrl) },
                                    onMovieClick = onMovieClick,
                                    onSeriesClick = onSeriesClick,
                                    onLongClick = { item -> onMediaLongClick(item.id, item.title, item.posterUrl, true, ContentType.MOVIE) },
                                    onSeeAll = onNavigateToTrending
                                )
                            }
                        }
                        if (homeFeedFilter != HomeFeedFilter.MOVIES && filteredTrendingSeries.isNotEmpty()) {
                            item(key = "home_trending_series") {
                                val seriesItems = filteredTrendingSeries
                                    .filter { ContentTypeResolver.resolveSeries(it) != ContentType.ANIME }
                                    .map { series -> HomeMixedMediaItem(series.id, series.title, series.posterUrl, series.year, series.rating, false, ContentTypeResolver.resolveSeries(series), series.backdropUrl) }
                                HomePosterShelf(
                                    title = stringResource(R.string.trending_series),
                                    items = seriesItems,
                                    onMovieClick = onMovieClick,
                                    onSeriesClick = onSeriesClick,
                                    onLongClick = { item -> onMediaLongClick(item.id, item.title, item.posterUrl, false, item.contentType) },
                                    onSeeAll = onNavigateToTrending
                                )
                            }
                        }
                        if (homeFeedFilter != HomeFeedFilter.MOVIES && filteredAnimeSeries.isNotEmpty()) {
                            item(key = "home_trending_anime") {
                                HomePosterShelf(
                                    title = stringResource(R.string.trending_anime),
                                    items = filteredAnimeSeries.map { anime -> HomeMixedMediaItem(anime.id, anime.title, anime.posterUrl, anime.year, anime.rating, false, ContentType.ANIME, anime.backdropUrl) },
                                    onMovieClick = onMovieClick,
                                    onSeriesClick = onSeriesClick,
                                    onLongClick = { item -> onMediaLongClick(item.id, item.title, item.posterUrl, false, ContentType.ANIME) },
                                    onSeeAll = onNavigateToAnime
                                )
                            }
                        }

                        // 9–10. New Movies followed by New Episodes (TV + Anime episodes).
                        if (homeFeedFilter != HomeFeedFilter.SERIES && newReleasesMoviesMix.isNotEmpty()) {
                            item(key = "home_new_movies") {
                                HomePosterShelf(
                                    title = stringResource(R.string.home_new_movies),
                                    items = newReleasesMoviesMix,
                                    onMovieClick = onMovieClick,
                                    onSeriesClick = onSeriesClick,
                                    onLongClick = { item -> onMediaLongClick(item.id, item.title, item.posterUrl, true, ContentType.MOVIE) },
                                    onSeeAll = onNavigateToNewReleases,
                                    maxItems = 20
                                )
                            }
                        }
                        if (homeFeedFilter != HomeFeedFilter.MOVIES && newEpisodesMix.isNotEmpty()) {
                            item(key = "home_new_episodes") {
                                HomePosterShelf(
                                    title = stringResource(R.string.home_new_episodes),
                                    items = newEpisodesMix,
                                    onMovieClick = onMovieClick,
                                    onSeriesClick = onSeriesClick,
                                    onLongClick = { item -> onMediaLongClick(item.id, item.title, item.posterUrl, false, item.contentType) },
                                    onSeeAll = onNavigateToNewReleases,
                                    maxItems = 20
                                )
                            }
                        }

                        // 11–13. Popular Movies, Series, Anime.
                        if (homeFeedFilter != HomeFeedFilter.SERIES && popularMoviesMix.isNotEmpty()) {
                            item(key = "home_popular_movies") {
                                HomePosterShelf(
                                    title = stringResource(R.string.popular_movies),
                                    items = popularMoviesMix,
                                    onMovieClick = onMovieClick,
                                    onSeriesClick = onSeriesClick,
                                    onLongClick = { item -> onMediaLongClick(item.id, item.title, item.posterUrl, true, ContentType.MOVIE) },
                                    onSeeAll = onNavigateToPopular
                                )
                            }
                        }
                        if (homeFeedFilter != HomeFeedFilter.MOVIES && popularSeriesMix.isNotEmpty()) {
                            item(key = "home_popular_series") {
                                HomePosterShelf(
                                    title = stringResource(R.string.popular_series),
                                    items = popularSeriesMix,
                                    onMovieClick = onMovieClick,
                                    onSeriesClick = onSeriesClick,
                                    onLongClick = { item -> onMediaLongClick(item.id, item.title, item.posterUrl, false, item.contentType) },
                                    onSeeAll = onNavigateToPopular
                                )
                            }
                        }
                        if (homeFeedFilter != HomeFeedFilter.MOVIES && popularAnimeMix.isNotEmpty()) {
                            item(key = "home_popular_anime") {
                                HomePosterShelf(
                                    title = stringResource(R.string.popular_anime),
                                    items = popularAnimeMix,
                                    onMovieClick = onMovieClick,
                                    onSeriesClick = onSeriesClick,
                                    onLongClick = { item -> onMediaLongClick(item.id, item.title, item.posterUrl, false, ContentType.ANIME) },
                                    onSeeAll = onNavigateToAnime
                                )
                            }
                        }

                        // 14. Streaming Services, followed by exactly the three requested provider shelves.
                        item(key = "home_streaming_services") {
                            HomeStreamingServicesRow { selectedHomeProvider = it }
                        }
                        val providerShelfSpecs: List<Pair<HomeStreamingService, Boolean>> = when (homeFeedFilter) {
                            HomeFeedFilter.ALL -> listOf(
                                homeStreamingService(8) to false,   // Popular series on Netflix
                                homeStreamingService(337) to true,  // Popular movies on Disney+
                                homeStreamingService(9) to true     // Popular movies on Prime Video
                            )
                            HomeFeedFilter.MOVIES -> listOf(
                                homeStreamingService(337) to true,
                                homeStreamingService(9) to true
                            )
                            HomeFeedFilter.SERIES -> listOf(homeStreamingService(8) to false)
                        }
                        providerShelfSpecs.forEach { (service, isMovieShelf) ->
                            item(key = "home_provider_${service.providerId}_${if (isMovieShelf) "movies" else "series"}") {
                                HomeProviderShelf(
                                    service = service,
                                    region = providerRegion,
                                    isMovie = isMovieShelf,
                                    viewModel = viewModel,
                                    onMovieClick = onMovieClick,
                                    onSeriesClick = onSeriesClick,
                                    onLongClick = { item -> onMediaLongClick(item.id, item.title, item.posterUrl, item.isMovie, item.contentType) }
                                )
                            }
                        }

                        // 18–20. Top Rated Movies, Series, Anime.
                        if (homeFeedFilter != HomeFeedFilter.SERIES && topRatedMoviesMix.isNotEmpty()) {
                            item(key = "home_top_rated_movies") {
                                HomePosterShelf(
                                    title = stringResource(R.string.home_top_rated_movies),
                                    items = topRatedMoviesMix,
                                    onMovieClick = onMovieClick,
                                    onSeriesClick = onSeriesClick,
                                    onLongClick = { item -> onMediaLongClick(item.id, item.title, item.posterUrl, true, ContentType.MOVIE) },
                                    onSeeAll = onNavigateToPopular
                                )
                            }
                        }
                        if (homeFeedFilter != HomeFeedFilter.MOVIES && topRatedSeriesMix.isNotEmpty()) {
                            item(key = "home_top_rated_series") {
                                val seriesItems = topRatedSeriesMix.filter { it.contentType != ContentType.ANIME }
                                HomePosterShelf(
                                    title = stringResource(R.string.home_top_rated_series),
                                    items = seriesItems,
                                    onMovieClick = onMovieClick,
                                    onSeriesClick = onSeriesClick,
                                    onLongClick = { item -> onMediaLongClick(item.id, item.title, item.posterUrl, false, item.contentType) },
                                    onSeeAll = onNavigateToPopular
                                )
                            }
                        }
                        if (homeFeedFilter != HomeFeedFilter.MOVIES && topRatedAnimeMix.isNotEmpty()) {
                            item(key = "home_top_rated_anime") {
                                HomePosterShelf(
                                    title = stringResource(R.string.home_top_rated_anime),
                                    items = topRatedAnimeMix,
                                    onMovieClick = onMovieClick,
                                    onSeriesClick = onSeriesClick,
                                    onLongClick = { item -> onMediaLongClick(item.id, item.title, item.posterUrl, false, ContentType.ANIME) },
                                    onSeeAll = onNavigateToAnime
                                )
                            }
                        }

                        // 21. Upcoming Movies only.
                        if (homeFeedFilter != HomeFeedFilter.SERIES && upcomingMix.isNotEmpty()) {
                            item(key = "home_upcoming_movies") {
                                HomePosterShelf(
                                    title = stringResource(R.string.upcoming_movies),
                                    items = upcomingMix.map { movie -> HomeMixedMediaItem(movie.id, movie.title, movie.posterUrl, movie.year, movie.rating, true, ContentType.MOVIE, movie.backdropUrl) },
                                    onMovieClick = onMovieClick,
                                    onSeriesClick = onSeriesClick,
                                    onLongClick = { item -> onMediaLongClick(item.id, item.title, item.posterUrl, true, ContentType.MOVIE) },
                                    onSeeAll = onNavigateToUpcoming,
                                    maxItems = 10
                                )
                            }
                        }

                        // 22–25. Fixed genre categories and their FlixQuest-specific layouts.
                        val visibleGenreShelves = uiState.homeGenreShelves.filter { shelf ->
                            when (homeFeedFilter) {
                                HomeFeedFilter.ALL -> true
                                HomeFeedFilter.MOVIES -> shelf.kind == HomeGenreKind.MOVIE
                                HomeFeedFilter.SERIES -> shelf.kind == HomeGenreKind.SERIES
                            }
                        }
                        visibleGenreShelves.forEach { shelf ->
                            item(key = "home_genre_${shelf.stableKey}") {
                                val kicker = when (shelf.kind) {
                                    HomeGenreKind.MOVIE -> stringResource(R.string.movies)
                                    HomeGenreKind.SERIES -> stringResource(R.string.series)
                                    HomeGenreKind.ANIME -> stringResource(R.string.anime)
                                }
                                HomeGenreShelfSection(
                                    shelf = shelf,
                                    viewModel = viewModel,
                                    onMovieClick = onMovieClick,
                                    onSeriesClick = onSeriesClick,
                                    onLongClick = { item -> onMediaLongClick(item.id, item.title, item.posterUrl, item.isMovie, item.contentType) },
                                    onSeeAll = { selectedShelf ->
                                        when (selectedShelf.kind) {
                                            HomeGenreKind.MOVIE -> {
                                                selectedMovieGenre = selectedShelf.genre
                                                selectedCategory = HomeNavCategory.MOVIES
                                                viewModel.selectHomeMovieGenre(selectedShelf.genre)
                                            }
                                            HomeGenreKind.SERIES -> {
                                                selectedSeriesGenre = selectedShelf.genre
                                                selectedCategory = HomeNavCategory.SERIES
                                                viewModel.selectHomeSeriesGenre(selectedShelf.genre)
                                            }
                                            HomeGenreKind.ANIME -> {
                                                selectedAnimeGenre = selectedShelf.genre
                                                selectedCategory = HomeNavCategory.ANIME
                                                viewModel.selectHomeAnimeGenre(selectedShelf.genre)
                                            }
                                        }
                                        isGenreDropdownExpanded = false
                                        scope.launch { homeListState.animateScrollToItem(0) }
                                    },
                                    kicker = kicker
                                )
                            }
                        }
                    }
                    HomeNavCategory.MOVIES -> {
                        item(key = "home_movies_genre_dropdown") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                CineStreamDropdownSelector(
                                    label = stringResource(R.string.filter_category),
                                    selectedOption = DropdownOption(
                                        value = selectedMovieGenre,
                                        title = stringResource(R.string.genre_filter_prefix, selectedMovieGenre?.name ?: stringResource(R.string.genre_all)),
                                        icon = Icons.Default.FilterAlt
                                    ),
                                    options = movieGenreOptions,
                                    expanded = isGenreDropdownExpanded,
                                    onExpandedChange = { isGenreDropdownExpanded = it },
                                    onOptionSelected = { option ->
                                        selectedMovieGenre = option.value
                                        viewModel.selectHomeMovieGenre(option.value)
                                    },
                                    leadingIcon = Icons.Default.FilterAlt,
                                    testTag = "home_genre_dropdown"
                                )

                                AnimatedVisibility(
                                    visible = selectedMovieGenre != null,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        ActiveFilterChip(
                                            filterName = selectedMovieGenre?.name ?: "",
                                            onClear = {
                                                selectedMovieGenre = null
                                                viewModel.selectHomeMovieGenre(null)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        item(key = "home_movies_spacer") { Spacer(modifier = Modifier.height(16.dp)) }

                        if (displayCategoryMovies.isEmpty() && (activeHomePageState.isLoadingFirstPage || activeHomePageState.currentPage == 0) && activeHomePageState.loadMoreError == null) {
                            item(key = "home_movies_skeleton") { CatalogGridSkeletonRows(itemCount = 12, columns = 3) }
                        } else if (displayCategoryMovies.isEmpty() && activeHomePageState.loadMoreError != null) {
                            item(key = "home_movies_error") {
                                CatalogGridStatusMessage(
                                    message = activeHomePageState.loadMoreError ?: stringResource(R.string.check_net_retry),
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = { activeHomePaginator.retry(1) },
                                    isError = true
                                )
                            }
                        } else if (displayCategoryMovies.isEmpty()) {
                            item(key = "home_movies_empty") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 40.dp, horizontal = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = stringResource(R.string.no_genre_results),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodyMedium,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        OutlinedButton(
                                            onClick = { selectedMovieGenre = null },
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(stringResource(R.string.filter_clear))
                                        }
                                    }
                                }
                            }
                        } else {
                            verticalGridItems(
                                items = displayCategoryMovies,
                                columns = 3,
                                horizontalPadding = 16.dp,
                                key = { it.id }
                            ) { movie ->
                                val onClick = remember(movie.id) { { onMovieClick(movie.id) } }
                                val onLongClick = remember(movie.id, movie.title, movie.posterUrl) {
                                    { onMediaLongClick(movie.id, movie.title, movie.posterUrl, true, ContentType.MOVIE) }
                                }
                                MediaCard(
                                    title = movie.title,
                                    posterUrl = movie.posterUrl,
                                    rating = movie.rating,
                                    year = movie.year.toString(),
                                    isMovie = true,
                                    contentType = ContentType.MOVIE,
                                    mediaId = movie.id,
                                    onClick = onClick,
                                    onLongClick = onLongClick
                                )
                            }
                        }
                    
                        if (activeHomePageState.isLoadingMore) {
                            item(key = "home_movies_more_loading") {
                                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            }
                        } else if (activeHomePageState.loadMoreError != null && displayCategoryMovies.isNotEmpty()) {
                            item(key = "home_movies_more_error") {
                                CatalogGridStatusMessage(
                                    message = activeHomePageState.loadMoreError ?: stringResource(R.string.check_net_retry),
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = { activeHomePaginator.retry(1) },
                                    isError = true
                                )
                            }
                        }
}
                    HomeNavCategory.SERIES -> {
                        item(key = "home_series_genre_dropdown") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                CineStreamDropdownSelector(
                                    label = stringResource(R.string.filter_category),
                                    selectedOption = DropdownOption(
                                        value = selectedSeriesGenre,
                                        title = stringResource(R.string.genre_filter_prefix, selectedSeriesGenre?.name ?: stringResource(R.string.genre_all)),
                                        icon = Icons.Default.FilterAlt
                                    ),
                                    options = tvGenreOptions,
                                    expanded = isGenreDropdownExpanded,
                                    onExpandedChange = { isGenreDropdownExpanded = it },
                                    onOptionSelected = { option ->
                                        selectedSeriesGenre = option.value
                                        viewModel.selectHomeSeriesGenre(option.value)
                                    },
                                    leadingIcon = Icons.Default.FilterAlt,
                                    testTag = "home_genre_dropdown"
                                )

                                AnimatedVisibility(
                                    visible = selectedSeriesGenre != null,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        ActiveFilterChip(
                                            filterName = selectedSeriesGenre?.name ?: "",
                                            onClear = {
                                                selectedSeriesGenre = null
                                                viewModel.selectHomeSeriesGenre(null)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        item(key = "home_series_spacer") { Spacer(modifier = Modifier.height(16.dp)) }

                        if (displayCategorySeries.isEmpty() && (activeHomePageState.isLoadingFirstPage || activeHomePageState.currentPage == 0) && activeHomePageState.loadMoreError == null) {
                            item(key = "home_series_skeleton") { CatalogGridSkeletonRows(itemCount = 12, columns = 3) }
                        } else if (displayCategorySeries.isEmpty() && activeHomePageState.loadMoreError != null) {
                            item(key = "home_series_error") {
                                CatalogGridStatusMessage(
                                    message = activeHomePageState.loadMoreError ?: stringResource(R.string.check_net_retry),
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = { activeHomePaginator.retry(2) },
                                    isError = true
                                )
                            }
                        } else if (displayCategorySeries.isEmpty()) {
                            item(key = "home_series_empty") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 40.dp, horizontal = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = stringResource(R.string.no_genre_results),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodyMedium,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        OutlinedButton(
                                            onClick = { selectedSeriesGenre = null },
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(stringResource(R.string.filter_clear))
                                        }
                                    }
                                }
                            }
                        } else {
                            verticalGridItems(
                                items = displayCategorySeries,
                                columns = 3,
                                horizontalPadding = 16.dp,
                                key = { it.id }
                            ) { series ->
                                val resolvedType = ContentTypeResolver.resolveSeries(series)
                                val onClick = remember(series.id) { { onSeriesClick(series.id) } }
                                val onLongClick = remember(series.id, resolvedType, series.title, series.posterUrl) {
                                    { onMediaLongClick(series.id, series.title, series.posterUrl, false, resolvedType) }
                                }
                                MediaCard(
                                    title = series.title,
                                    posterUrl = series.posterUrl,
                                    rating = series.rating,
                                    year = com.example.utils.SeasonFormatter.formatSeasonOrFallback(
                                        androidx.compose.ui.platform.LocalContext.current,
                                        series.seasons.size,
                                        if (series.year > 0) series.year.toString() else ""
                                    ),
                                    isMovie = false,
                                    contentType = resolvedType,
                                    mediaId = series.id,
                                    onClick = onClick,
                                    onLongClick = onLongClick
                                )
                            }
                        }
                    
                        if (activeHomePageState.isLoadingMore) {
                            item(key = "home_series_more_loading") {
                                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            }
                        } else if (activeHomePageState.loadMoreError != null && displayCategorySeries.isNotEmpty()) {
                            item(key = "home_series_more_error") {
                                CatalogGridStatusMessage(
                                    message = activeHomePageState.loadMoreError ?: stringResource(R.string.check_net_retry),
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = { activeHomePaginator.retry(2) },
                                    isError = true
                                )
                            }
                        }
}
                    HomeNavCategory.ANIME -> {
                        item(key = "home_anime_genre_dropdown") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                CineStreamDropdownSelector(
                                    label = stringResource(R.string.filter_category),
                                    selectedOption = DropdownOption(
                                        value = selectedAnimeGenre,
                                        title = stringResource(R.string.genre_filter_prefix, selectedAnimeGenre?.name ?: stringResource(R.string.genre_all)),
                                        icon = Icons.Default.FilterAlt
                                    ),
                                    options = animeGenreOptions,
                                    expanded = isGenreDropdownExpanded,
                                    onExpandedChange = { isGenreDropdownExpanded = it },
                                    onOptionSelected = { option ->
                                        selectedAnimeGenre = option.value
                                        viewModel.selectHomeAnimeGenre(option.value)
                                    },
                                    leadingIcon = Icons.Default.FilterAlt,
                                    testTag = "home_genre_dropdown"
                                )

                                AnimatedVisibility(
                                    visible = selectedAnimeGenre != null,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        ActiveFilterChip(
                                            filterName = selectedAnimeGenre?.name ?: "",
                                            onClear = {
                                                selectedAnimeGenre = null
                                                viewModel.selectHomeAnimeGenre(null)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        item(key = "home_anime_spacer") { Spacer(modifier = Modifier.height(16.dp)) }

                        if (displayCategoryAnime.isEmpty() && (activeHomePageState.isLoadingFirstPage || activeHomePageState.currentPage == 0) && activeHomePageState.loadMoreError == null) {
                            item(key = "home_anime_skeleton") { CatalogGridSkeletonRows(itemCount = 12, columns = 3) }
                        } else if (displayCategoryAnime.isEmpty() && activeHomePageState.loadMoreError != null) {
                            item(key = "home_anime_error") {
                                CatalogGridStatusMessage(
                                    message = activeHomePageState.loadMoreError ?: stringResource(R.string.check_net_retry),
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = { activeHomePaginator.retry(3) },
                                    isError = true
                                )
                            }
                        } else if (displayCategoryAnime.isEmpty()) {
                            item(key = "home_anime_empty") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 40.dp, horizontal = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = stringResource(R.string.no_genre_results),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodyMedium,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        OutlinedButton(
                                            onClick = { selectedAnimeGenre = null },
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(stringResource(R.string.filter_clear))
                                        }
                                    }
                                }
                            }
                        } else {
                            verticalGridItems(
                                items = displayCategoryAnime,
                                columns = 3,
                                horizontalPadding = 16.dp,
                                key = { it.id }
                            ) { anime ->
                                val onClick = remember(anime.id) { { onSeriesClick(anime.id) } }
                                val onLongClick = remember(anime.id, anime.title, anime.posterUrl) {
                                    { onMediaLongClick(anime.id, anime.title, anime.posterUrl, false, ContentType.ANIME) }
                                }
                                MediaCard(
                                    title = anime.title,
                                    posterUrl = anime.posterUrl,
                                    rating = anime.rating,
                                    year = com.example.utils.SeasonFormatter.formatSeasonOrFallback(
                                        androidx.compose.ui.platform.LocalContext.current,
                                        anime.seasons.size,
                                        if (anime.year > 0) anime.year.toString() else ""
                                    ),
                                    isMovie = false,
                                    contentType = ContentType.ANIME,
                                    mediaId = anime.id,
                                    onClick = onClick,
                                    onLongClick = onLongClick
                                )
                            }
                        }
                    
                        if (activeHomePageState.isLoadingMore) {
                            item(key = "home_anime_more_loading") {
                                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            }
                        } else if (activeHomePageState.loadMoreError != null && displayCategoryAnime.isNotEmpty()) {
                            item(key = "home_anime_more_error") {
                                CatalogGridStatusMessage(
                                    message = activeHomePageState.loadMoreError ?: stringResource(R.string.check_net_retry),
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = { activeHomePaginator.retry(3) },
                                    isError = true
                                )
                            }
                        }
}
                }
        }
    }
}

    if (showBottomSheet) {
        MediaActionBottomSheet(
            isMovie = bottomSheetIsMovie,
            mediaId = selectedMediaId,
            title = selectedMediaTitle,
            posterUrl = selectedMediaPoster,
            contentType = bottomSheetContentType,
            onDismissRequest = { showBottomSheet = false },
            onDownloadStart = { quality ->
                scope.launch {
                    downloadRepository.addToDownloads(DownloadItem(
                        id = selectedMediaId,
                        mediaId = selectedMediaId,
                        title = selectedMediaTitle,
                        posterUrl = selectedMediaPoster,
                        isMovie = bottomSheetIsMovie,
                        quality = quality
                    ))
                    Toast.makeText(context, context.getString(R.string.download_started), Toast.LENGTH_SHORT).show()
                }
            },
            onAddToLibrary = {
                scope.launch {
                    val libItem = LibraryItem.create(
                        contentType = bottomSheetContentType,
                        tmdbId = selectedMediaId,
                        title = selectedMediaTitle,
                        posterUrl = selectedMediaPoster
                    )
                    libraryRepository.addToLibrary(libItem)
                    Toast.makeText(context, context.getString(R.string.added_to_library), Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    selectedHomeProvider?.let { service ->
        HomeProviderDetailsSheet(
            service = service,
            region = providerRegion,
            viewModel = viewModel,
            onDismiss = { selectedHomeProvider = null },
            onMovieClick = onMovieClick,
            onSeriesClick = onSeriesClick,
            onLongClick = { item -> onMediaLongClick(item.id, item.title, item.posterUrl, item.isMovie, item.contentType) }
        )
    }
}



@Composable
fun SectionTitle(
    title: String,
    onSeeAllClick: (() -> Unit)? = null,
    kicker: String? = null
) {
    val parts = title.split(" ", limit = 2)
    val firstWord = parts.getOrNull(0) ?: ""
    val rest = parts.getOrNull(1) ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            if (!kicker.isNullOrBlank()) {
                Text(
                    text = kicker.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(3.dp))
            }
            Row {
                Text(
                    text = firstWord,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (rest.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = rest,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }

        if (onSeeAllClick != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onSeeAllClick() }
            ) {
                Text(
                    text = stringResource(R.string.see_all),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.see_all),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
