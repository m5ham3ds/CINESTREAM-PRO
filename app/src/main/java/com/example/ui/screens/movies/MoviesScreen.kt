package com.example.ui.screens.movies

import android.widget.Toast
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.LocalMovies
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.ContentType
import com.example.data.model.DownloadItem
import com.example.data.model.LibraryItem
import com.example.data.repository.DownloadRepository
import com.example.data.repository.LibraryRepository
import com.example.ui.ViewModelFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import com.example.ui.components.CineStreamDropdownSelector
import com.example.ui.components.DropdownOption
import com.example.ui.components.ActiveFilterChip
import com.example.data.util.TmdbGenreHelper
import com.example.domain.models.Genre
import com.example.domain.models.Movie
import com.example.ui.components.ContinueWatchingCardShared
import com.example.ui.components.MediaActionBottomSheet
import com.example.ui.components.MediaCard
import com.example.ui.components.verticalGridItems
import com.example.ui.components.MediaScreenSkeleton
import com.example.ui.components.CatalogGridSkeletonRows
import com.example.ui.components.CatalogGridStatusMessage
import com.example.ui.components.HeroCarousel
import com.example.ui.components.HeroItem
import com.example.ui.components.SectionTitleShared
import kotlinx.coroutines.launch

import com.example.ui.components.CategoryNavigationRow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState

enum class MoviesNavCategory(val titleRes: Int, val id: String) {
    MOVIES(R.string.category_movies, "movies"),
    GENRES(R.string.category_genres, "genres"),
    NEW_RELEASES(R.string.new_releases, "new_releases"),
    TOP_RATED(R.string.category_top_rated, "top_rated")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoviesScreen(
    onMovieClick: (String) -> Unit,
    onPlayMovie: ((String) -> Unit)? = null,
    onNavigateToTrending: () -> Unit = {},
    onNavigateToWatching: () -> Unit = {},
    onNavigateToPopular: () -> Unit = {},
    onNavigateToNewReleases: () -> Unit = {},
    onNavigateToUpcoming: () -> Unit = {},
    viewModel: MoviesViewModel = viewModel(factory = ViewModelFactory())
) {
    val uiState by viewModel.uiState.collectAsState()
    val catalogPageState by viewModel.catalogPaginator.getTabStateFlow(1).collectAsState()
    val releasePageState by viewModel.newReleasesPaginator.getTabStateFlow(1).collectAsState()
    val topRatedPageState by viewModel.topRatedPaginator.getTabStateFlow(1).collectAsState()
    val genrePageState by viewModel.genrePaginator.getTabStateFlow(1).collectAsState()
    val catalogItems = remember(catalogPageState.items) {
        catalogPageState.items.mapNotNull { it.first as? Movie }.distinctBy { it.id }
    }
    val releaseItems = remember(releasePageState.items) {
        releasePageState.items.mapNotNull { it.first as? Movie }.distinctBy { it.id }
    }
    val topRatedItems = remember(topRatedPageState.items) {
        topRatedPageState.items.mapNotNull { it.first as? Movie }.distinctBy { it.id }
    }
    val genreItems = remember(genrePageState.items) {
        genrePageState.items.mapNotNull { it.first as? Movie }.distinctBy { it.id }
    }
    LaunchedEffect(viewModel) {
        viewModel.ensureLoaded()
    }
    val context = LocalContext.current
    val libraryRepository = remember { LibraryRepository(context) }
    val historyRepository = remember { com.example.data.repository.HistoryRepository(context) }
    val downloadRepository = remember { DownloadRepository(context) }

    val historyItems by historyRepository.getHistoryItems().collectAsState(initial = emptyList())
    val movieHistoryItems = historyItems.filter { it.isMovie && !com.example.data.repository.AnimePlaybackStore.isAnime(context, it.id, it.title) }

    val scope = rememberCoroutineScope()

    val hasMovieContent = uiState.trendingMovies.isNotEmpty() ||
            uiState.movies.isNotEmpty() ||
            uiState.newReleasesMovies.isNotEmpty() ||
            uiState.upcomingMovies.isNotEmpty() ||
            catalogItems.isNotEmpty() || releaseItems.isNotEmpty() || genreItems.isNotEmpty() || topRatedItems.isNotEmpty()
var showBottomSheet by remember { mutableStateOf(false) }
    var selectedMediaId by remember { mutableStateOf("") }
    var selectedMediaTitle by remember { mutableStateOf("") }
    var selectedMediaPoster by remember { mutableStateOf("") }

    var isDropdownExpanded by remember { mutableStateOf(false) }
    var selectedCategory by rememberSaveable { mutableStateOf(MoviesNavCategory.MOVIES) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestSelectedCategory = rememberUpdatedState(selectedCategory)
    val latestSelectedGenreId = rememberUpdatedState(uiState.selectedGenreId)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && (latestSelectedCategory.value != MoviesNavCategory.MOVIES || latestSelectedGenreId.value != null)) {
                selectedCategory = MoviesNavCategory.MOVIES
                isDropdownExpanded = false
                if (latestSelectedGenreId.value != null) viewModel.selectGenre(null)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val movieCategories = remember {
        listOf(
            MoviesNavCategory.MOVIES,
            MoviesNavCategory.GENRES,
            MoviesNavCategory.NEW_RELEASES,
            MoviesNavCategory.TOP_RATED
        )
    }
    val movieGenres = remember(uiState.availableGenres) {
        if (uiState.availableGenres.isNotEmpty()) uiState.availableGenres
        else TmdbGenreHelper.getMovieGenres()
    }
    val allGenreStr = stringResource(R.string.genre_all)
    val genreOptions = remember(movieGenres, allGenreStr) {
        listOf(DropdownOption<Genre?>(null, allGenreStr)) + movieGenres.map {
            DropdownOption<Genre?>(it, it.name)
        }
    }
    val listState = rememberLazyListState()
    val activeGridPaginator = when (selectedCategory) {
        MoviesNavCategory.GENRES -> if (uiState.selectedGenreId != null) viewModel.genrePaginator else viewModel.catalogPaginator
        MoviesNavCategory.NEW_RELEASES -> viewModel.newReleasesPaginator
        MoviesNavCategory.TOP_RATED -> viewModel.topRatedPaginator
        else -> viewModel.catalogPaginator
    }
    val activeGridPageState = when (selectedCategory) {
        MoviesNavCategory.GENRES -> if (uiState.selectedGenreId != null) genrePageState else catalogPageState
        MoviesNavCategory.NEW_RELEASES -> releasePageState
        MoviesNavCategory.TOP_RATED -> topRatedPageState
        else -> catalogPageState
    }

    LaunchedEffect(selectedCategory, uiState.selectedGenreId) {
        listState.scrollToItem(0)
    }

    LaunchedEffect(selectedCategory, uiState.selectedGenreId) {
        when (selectedCategory) {
            MoviesNavCategory.GENRES -> {
                if (uiState.selectedGenreId != null) viewModel.genrePaginator.ensureTabLoaded(1)
                else viewModel.catalogPaginator.ensureTabLoaded(1)
            }
            MoviesNavCategory.NEW_RELEASES -> viewModel.newReleasesPaginator.ensureTabLoaded(1)
            MoviesNavCategory.TOP_RATED -> viewModel.topRatedPaginator.ensureTabLoaded(1)
            else -> Unit
        }
    }

    // One load-more request per approach to the end. Keep the trigger disarmed while
    // the viewport remains pinned at the bottom, otherwise a short/sparse page can
    // start a rapid request loop without another user scroll gesture.
    var gridPaginationArmed by remember(selectedCategory, uiState.selectedGenreId) { mutableStateOf(true) }
    LaunchedEffect(
        selectedCategory, uiState.selectedGenreId,
        activeGridPageState.currentPage, activeGridPageState.items.size,
        activeGridPageState.isLoadingFirstPage, activeGridPageState.isLoadingMore,
        activeGridPageState.hasNextPage, activeGridPageState.loadMoreError
    ) {
        val isPagedGrid = selectedCategory == MoviesNavCategory.GENRES ||
            selectedCategory == MoviesNavCategory.NEW_RELEASES || selectedCategory == MoviesNavCategory.TOP_RATED
        if (isPagedGrid && activeGridPageState.hasNextPage && activeGridPageState.loadMoreError == null) {
            snapshotFlow {
                val layout = listState.layoutInfo
                val total = layout.totalItemsCount
                val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
                val atEnd = total > 0 && lastVisible >= total - 5
                atEnd to listState.isScrollInProgress
            }.collect { (atEnd, userScrolling) ->
                if (!atEnd) {
                    gridPaginationArmed = true
                } else if (
                    userScrolling && gridPaginationArmed &&
                    activeGridPageState.hasNextPage &&
                    !activeGridPageState.isLoadingFirstPage &&
                    !activeGridPageState.isLoadingMore &&
                    activeGridPageState.loadMoreError == null
                ) {
                    gridPaginationArmed = false
                    activeGridPaginator.loadMore(1)
                }
            }
        }
    }

    val ptrState = rememberPullToRefreshState()
    
    val pullRefreshIndicator = com.example.ui.components.rememberBriefPullRefreshIndicator()
    PullToRefreshBox(
        isRefreshing = pullRefreshIndicator.visible,
        onRefresh = {
            pullRefreshIndicator.start()
            when (selectedCategory) {
                MoviesNavCategory.GENRES -> if (uiState.selectedGenreId != null) viewModel.genrePaginator.refresh(1) else viewModel.catalogPaginator.refresh(1)
                MoviesNavCategory.NEW_RELEASES -> viewModel.newReleasesPaginator.refresh(1)
                MoviesNavCategory.TOP_RATED -> viewModel.topRatedPaginator.refresh(1)
                else -> viewModel.loadMovies(forceRefresh = true)
            }
        },
        state = ptrState,
        modifier = Modifier.fillMaxSize()
    ) {
        if (!hasMovieContent && selectedCategory == MoviesNavCategory.MOVIES) {
            val isOffline = !com.example.utils.NetworkUtils.isInternetAvailable(context)
            if (uiState.isLoading || isOffline) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    MediaScreenSkeleton()
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
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
                            onClick = { viewModel.loadMovies(forceRefresh = true) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text(stringResource(R.string.retry), color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                // Hero Section
                item(key = "movies_hero_section") {
                    val heroMovies = if (uiState.trendingMovies.isNotEmpty()) uiState.trendingMovies else uiState.movies
                    if (heroMovies.isNotEmpty()) {
                        HeroCarousel(items = heroMovies.take(5).map { HeroItem(it.id, it.title, it.backdropUrl, true, contentType = ContentType.MOVIE) }, onClick = onMovieClick)
                    }
                }

                item(key = "movies_category_spacer_top") {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Category Navigation Row (ALWAYS visible at all times)
                item(key = "movies_category_navigation_row") {
                    CategoryNavigationRow(
                        categories = movieCategories,
                        selectedCategory = selectedCategory,
                        onCategorySelected = { category ->
                            // Tapping an active secondary category toggles back to this
                            // screen's primary feed and clears the stale genre selection.
                            if (category == selectedCategory && category != MoviesNavCategory.MOVIES) {
                                selectedCategory = MoviesNavCategory.MOVIES
                                viewModel.selectGenre(null)
                            } else {
                                selectedCategory = category
                            }
                            isDropdownExpanded = false
                        },
                        categoryTitle = { stringResource(it.titleRes) },
                        categoryTag = { it.id }
                    )
                }

                item(key = "movies_category_spacer_bottom") {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                when (selectedCategory) {
                    MoviesNavCategory.MOVIES -> {
                        // Section: Continue Watching
                        if (movieHistoryItems.isNotEmpty()) {
                            item(key = "movies_continue_watching") {
                                SectionTitleShared(stringResource(R.string.continue_watching), onSeeAllClick = onNavigateToWatching)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    items(movieHistoryItems, key = { it.id }) { item ->
                                        ContinueWatchingCardShared(
                                            item = item,
                                            onClick = { onMovieClick(item.id) },
                                            onPlayClick = {
                                                if (onPlayMovie != null) onPlayMovie(item.id) else onMovieClick(item.id)
                                            }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }

                        // Section: Trending Movies
                        if (uiState.trendingMovies.isNotEmpty()) {
                            item(key = "movies_trending") {
                                SectionTitleShared(stringResource(R.string.trending_movies), onSeeAllClick = onNavigateToTrending)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    itemsIndexed(uiState.trendingMovies, key = { _, movie -> movie.id }) { index, movie ->
                                        MediaCard(
                                            revealIndex = index,
                                            title = movie.title,
                                            posterUrl = movie.posterUrl,
                                            rating = movie.rating,
                                            year = movie.releaseDate?.take(4)?.takeIf { it.toIntOrNull() != null }
                                                ?: movie.year.takeIf { it > 0 }?.toString(),
                                            mediaId = movie.id,
                                            onClick = { onMovieClick(movie.id) },
                                            onLongClick = { 
                                                selectedMediaId = movie.id
                                                selectedMediaTitle = movie.title
                                                selectedMediaPoster = movie.posterUrl
                                                showBottomSheet = true
                                            }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }

                        // Section: New Releases
                        if (uiState.newReleasesMovies.isNotEmpty()) {
                            item(key = "movies_new_releases") {
                                SectionTitleShared(stringResource(R.string.new_releases), onSeeAllClick = onNavigateToNewReleases)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    itemsIndexed(uiState.newReleasesMovies, key = { _, movie -> movie.id }) { index, movie ->
                                        MediaCard(
                                            revealIndex = index,
                                            title = movie.title,
                                            posterUrl = movie.posterUrl,
                                            rank = index + 1,
                                            rating = movie.rating,
                                            year = movie.releaseDate?.take(4)?.takeIf { it.toIntOrNull() != null }
                                                ?: movie.year.takeIf { it > 0 }?.toString(),
                                            mediaId = movie.id,
                                            onClick = { onMovieClick(movie.id) },
                                            onLongClick = { 
                                                selectedMediaId = movie.id
                                                selectedMediaTitle = movie.title
                                                selectedMediaPoster = movie.posterUrl
                                                showBottomSheet = true
                                            }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }

                        // Section: Popular Movies
                        if (uiState.movies.isNotEmpty()) {
                            item(key = "movies_popular") {
                                SectionTitleShared(stringResource(R.string.popular_movies), onSeeAllClick = onNavigateToPopular)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    itemsIndexed(uiState.movies, key = { _, movie -> movie.id }) { index, movie ->
                                        MediaCard(
                                            revealIndex = index,
                                            title = movie.title,
                                            posterUrl = movie.posterUrl,
                                            rank = index + 1,
                                            rating = movie.rating,
                                            year = movie.releaseDate?.take(4)?.takeIf { it.toIntOrNull() != null }
                                                ?: movie.year.takeIf { it > 0 }?.toString(),
                                            mediaId = movie.id,
                                            onClick = { onMovieClick(movie.id) },
                                            onLongClick = { 
                                                selectedMediaId = movie.id
                                                selectedMediaTitle = movie.title
                                                selectedMediaPoster = movie.posterUrl
                                                showBottomSheet = true
                                            }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }

                        // Section: Coming Soon Movies
                        if (uiState.upcomingMovies.isNotEmpty()) {
                            item(key = "movies_coming_soon") {
                                SectionTitleShared(stringResource(R.string.coming_soon), onSeeAllClick = onNavigateToUpcoming)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    itemsIndexed(uiState.upcomingMovies, key = { _, movie -> movie.id }) { index, movie ->
                                        MediaCard(
                                            revealIndex = index,
                                            title = movie.title,
                                            posterUrl = movie.posterUrl,
                                            rank = index + 1,
                                            rating = movie.rating,
                                            showZeroRating = true,
                                            year = movie.releaseDate?.take(4)?.takeIf { it.toIntOrNull() != null }
                                                ?: movie.year.takeIf { it > 0 }?.toString(),
                                            mediaId = movie.id,
                                            onClick = { onMovieClick(movie.id) },
                                            onLongClick = { 
                                                selectedMediaId = movie.id
                                                selectedMediaTitle = movie.title
                                                selectedMediaPoster = movie.posterUrl
                                                showBottomSheet = true
                                            }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }
                    }
                    MoviesNavCategory.GENRES -> {
                        // Genre Filter Selector (immediately below Category row)
                        item(key = "movies_genre_dropdown_selector") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                CineStreamDropdownSelector(
                                    label = stringResource(R.string.filter_category),
                                    selectedOption = DropdownOption(
                                        value = uiState.selectedGenreId?.let { id -> movieGenres.find { it.id == id } },
                                        title = stringResource(R.string.genre_filter_prefix, uiState.selectedGenreName ?: stringResource(R.string.genre_all)),
                                        icon = Icons.Default.Category
                                    ),
                                    options = genreOptions,
                                    expanded = isDropdownExpanded,
                                    onExpandedChange = { isDropdownExpanded = it },
                                    onOptionSelected = { option ->
                                        viewModel.selectGenre(option.value)
                                    },
                                    leadingIcon = Icons.Default.Category,
                                    testTag = "movies_genre_dropdown"
                                )

                                AnimatedVisibility(
                                    visible = uiState.selectedGenreId != null,
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
                                            filterName = uiState.selectedGenreName ?: "",
                                            onClear = { viewModel.selectGenre(null) }
                                        )
                                    }
                                }
                            }
                        }

                        item(key = "movies_genre_spacer") {
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        val displayMovies = if (uiState.selectedGenreId == null) catalogItems else genreItems
                        val activeGenreState = if (uiState.selectedGenreId != null) genrePageState else catalogPageState
                        when {
                            (activeGenreState.isLoadingFirstPage || activeGenreState.currentPage == 0) && displayMovies.isEmpty() && activeGenreState.loadMoreError == null -> {
                                item(key = "movies_genre_loading") { CatalogGridSkeletonRows(itemCount = 12, columns = 3) }
                            }
                            displayMovies.isEmpty() && activeGenreState.loadMoreError != null -> {
                                item(key = "movies_genre_error") {
                                    CatalogGridStatusMessage(
                                        message = activeGenreState.loadMoreError ?: stringResource(R.string.check_net_retry),
                                        actionLabel = stringResource(R.string.retry),
                                        onAction = { activeGridPaginator.retry(1) },
                                        isError = true
                                    )
                                }
                            }
                            displayMovies.isEmpty() -> {
                                item(key = "movies_genre_empty") {
                                    CatalogGridStatusMessage(
                                        message = stringResource(R.string.no_genre_results),
                                        actionLabel = stringResource(R.string.filter_clear),
                                        onAction = { viewModel.selectGenre(null) }
                                    )
                                }
                            }
                            else -> {
                                verticalGridItems(
                                    items = displayMovies,
                                    columns = 3,
                                    horizontalPadding = 16.dp,
                                    key = { it.id }
                                ) { movie ->
                                    MediaCard(
                                        title = movie.title,
                                        posterUrl = movie.posterUrl,
                                        rating = movie.rating,
                                        year = movie.releaseDate?.take(4) ?: movie.year.toString(),
                                        isMovie = true,
                                        contentType = ContentType.MOVIE,
                                        mediaId = movie.id,
                                        onClick = { onMovieClick(movie.id) },
                                        onLongClick = {
                                            selectedMediaId = movie.id
                                            selectedMediaTitle = movie.title
                                            selectedMediaPoster = movie.posterUrl
                                            
                                            showBottomSheet = true
                                        }
                                    )
                                }
                            }
                        }
                    
                        if (activeGenreState.isLoadingMore) {
                            item(key = "movies_genre_more_loading") {
                                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        } else if (activeGenreState.loadMoreError != null && displayMovies.isNotEmpty()) {
                            item(key = "movies_genre_more_error") {
                                CatalogGridStatusMessage(
                                    message = activeGenreState.loadMoreError ?: stringResource(R.string.check_net_retry),
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = { activeGridPaginator.retry(1) },
                                    isError = true
                                )
                            }
                        }
}
                    MoviesNavCategory.NEW_RELEASES -> {
                        val newReleasesList = releaseItems
                        if ( (releasePageState.isLoadingFirstPage || releasePageState.currentPage == 0) && newReleasesList.isEmpty() && releasePageState.loadMoreError == null) {
                            item(key = "movie_release_skeleton") { CatalogGridSkeletonRows() }
                        } else if (newReleasesList.isEmpty() && releasePageState.loadMoreError != null) {
                            item(key = "movie_release_error") {
                                CatalogGridStatusMessage(
                                    message = releasePageState.loadMoreError ?: stringResource(R.string.check_net_retry),
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = { viewModel.newReleasesPaginator.retry(1) },
                                    isError = true
                                )
                            }
                        } else if (newReleasesList.isEmpty()) {
                            item(key = "movie_release_empty") { CatalogGridStatusMessage(message = stringResource(R.string.no_genre_results)) }
                        } else {
                        verticalGridItems(
                            items = newReleasesList,
                            columns = 3,
                            horizontalPadding = 16.dp,
                            key = { it.id }
                        ) { movie ->
                            MediaCard(
                                title = movie.title,
                                posterUrl = movie.posterUrl,
                                rating = movie.rating,
                                year = movie.releaseDate?.take(4) ?: movie.year.toString(),
                                isMovie = true,
                                mediaId = movie.id,
                                onClick = { onMovieClick(movie.id) },
                                onLongClick = { 
                                    selectedMediaId = movie.id
                                    selectedMediaTitle = movie.title
                                    selectedMediaPoster = movie.posterUrl
                                    showBottomSheet = true
                                }
                            )
                        }
                        }
                    
                        if (releasePageState.isLoadingMore) {
                            item(key = "movies_release_more_loading") {
                                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        } else if (releasePageState.loadMoreError != null && releaseItems.isNotEmpty()) {
                            item(key = "movies_release_more_error") {
                                CatalogGridStatusMessage(
                                    message = releasePageState.loadMoreError ?: stringResource(R.string.check_net_retry),
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = { viewModel.newReleasesPaginator.retry(1) },
                                    isError = true
                                )
                            }
                        }
}
                    MoviesNavCategory.TOP_RATED -> {
                        val topRatedList = topRatedItems
                        if ((topRatedPageState.isLoadingFirstPage || topRatedPageState.currentPage == 0) && topRatedList.isEmpty() && topRatedPageState.loadMoreError == null) {
                            item(key = "movie_top_rated_skeleton") { CatalogGridSkeletonRows() }
                        } else if (topRatedList.isEmpty() && topRatedPageState.loadMoreError != null) {
                            item(key = "movie_top_rated_error") {
                                CatalogGridStatusMessage(
                                    message = topRatedPageState.loadMoreError ?: stringResource(R.string.check_net_retry),
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = { viewModel.topRatedPaginator.retry(1) },
                                    isError = true
                                )
                            }
                        } else if (topRatedList.isEmpty()) {
                            item(key = "movie_top_rated_empty") { CatalogGridStatusMessage(message = stringResource(R.string.no_genre_results)) }
                        } else {
                        verticalGridItems(
                            items = topRatedList,
                            columns = 3,
                            horizontalPadding = 16.dp,
                            key = { it.id }
                        ) { movie ->
                            MediaCard(
                                title = movie.title,
                                posterUrl = movie.posterUrl,
                                rating = movie.rating,
                                year = movie.releaseDate?.take(4) ?: movie.year.toString(),
                                isMovie = true,
                                mediaId = movie.id,
                                onClick = { onMovieClick(movie.id) },
                                onLongClick = { 
                                    selectedMediaId = movie.id
                                    selectedMediaTitle = movie.title
                                    selectedMediaPoster = movie.posterUrl
                                    showBottomSheet = true
                                }
                            )
                        }
                        }
                    
                        if (topRatedPageState.isLoadingMore) {
                            item(key = "movies_top_rated_more_loading") {
                                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        } else if (topRatedPageState.loadMoreError != null && topRatedItems.isNotEmpty()) {
                            item(key = "movies_top_rated_more_error") {
                                CatalogGridStatusMessage(
                                    message = topRatedPageState.loadMoreError ?: stringResource(R.string.check_net_retry),
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = { viewModel.topRatedPaginator.retry(1) },
                                    isError = true
                                )
                            }
                        }
}
                }
    }
if (showBottomSheet) {
            MediaActionBottomSheet(
                isMovie = true,
                mediaId = selectedMediaId,
                title = selectedMediaTitle,
                posterUrl = selectedMediaPoster,
                contentType = ContentType.MOVIE,
                onDismissRequest = { showBottomSheet = false },
                onDownloadStart = { quality ->
                    scope.launch {
                        downloadRepository.addToDownloads(DownloadItem(
                            id = selectedMediaId,
                            mediaId = selectedMediaId,
                            title = selectedMediaTitle,
                            posterUrl = selectedMediaPoster,
                            isMovie = true,
                            quality = quality
                        ))
                        Toast.makeText(context, context.getString(R.string.download_started), Toast.LENGTH_SHORT).show()
                    }
                },
                onAddToLibrary = {
                    scope.launch {
                        val libItem = LibraryItem.create(
                            contentType = ContentType.MOVIE,
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
    }
}
}
