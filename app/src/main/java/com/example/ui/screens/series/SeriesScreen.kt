package com.example.ui.screens.series

import android.widget.Toast
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.LocalMovies
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.example.data.util.ContentTypeResolver
import com.example.data.model.DownloadItem
import com.example.data.model.LibraryItem
import com.example.data.repository.DownloadRepository
import com.example.data.repository.LibraryRepository
import com.example.ui.ViewModelFactory
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
import com.example.ui.components.CategoryNavigationRow
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.domain.models.Series
import kotlinx.coroutines.launch

enum class SeriesNavCategory(val titleRes: Int, val id: String) {
    SERIES(R.string.category_series, "series"),
    GENRES(R.string.category_genres, "genres"),
    NEW_RELEASES(R.string.new_releases, "new_releases"),
    TOP_RATED(R.string.category_top_rated, "top_rated")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(
    onSeriesClick: (String) -> Unit,
    onPlaySeries: ((String) -> Unit)? = null,
    onNavigateToTrending: () -> Unit = {},
    onNavigateToWatching: () -> Unit = {},
    onNavigateToPopular: () -> Unit = {},
    onNavigateToNewReleases: () -> Unit = {},
    onNavigateToUpcoming: () -> Unit = {},
    viewModel: SeriesViewModel = viewModel(factory = ViewModelFactory())
) {
    val uiState by viewModel.uiState.collectAsState()
    val catalogPageState by viewModel.catalogPaginator.getTabStateFlow(2).collectAsState()
    val releasePageState by viewModel.newReleasesPaginator.getTabStateFlow(2).collectAsState()
    val topRatedPageState by viewModel.topRatedPaginator.getTabStateFlow(2).collectAsState()
    val genrePageState by viewModel.genrePaginator.getTabStateFlow(2).collectAsState()
    val catalogItems = remember(catalogPageState.items) {
        catalogPageState.items.mapNotNull { it.first as? Series }.distinctBy { it.id }
    }
    val releaseItems = remember(releasePageState.items) {
        releasePageState.items.mapNotNull { it.first as? Series }.distinctBy { it.id }
    }
    val topRatedItems = remember(topRatedPageState.items) {
        topRatedPageState.items.mapNotNull { it.first as? Series }.distinctBy { it.id }
    }
    val genreItems = remember(genrePageState.items) {
        genrePageState.items.mapNotNull { it.first as? Series }.distinctBy { it.id }
    }
    LaunchedEffect(viewModel) {
        viewModel.ensureLoaded()
    }
    val context = LocalContext.current
    val libraryRepository = remember { LibraryRepository(context) }
    val historyRepository = remember { com.example.data.repository.HistoryRepository(context) }
    val downloadRepository = remember { DownloadRepository(context) }

    val historyItems by historyRepository.getHistoryItems().collectAsState(initial = emptyList())
    val seriesHistoryItems = remember(historyItems, uiState) {
        val knownSeriesIds = (uiState.trendingSeries + uiState.series + uiState.newEpisodes + uiState.upcomingSeries).map { it.id }.toSet()
        val knownSeriesTitles = (uiState.trendingSeries + uiState.series + uiState.newEpisodes + uiState.upcomingSeries).map { it.title.lowercase().trim() }

        historyItems.filter { item ->
            val isAnime = com.example.data.repository.AnimePlaybackStore.isAnime(context, item.id, item.title)
            if (isAnime) return@filter false

            val baseId = if (item.id.contains("_")) item.id.substringBeforeLast("_") else item.id
            val lower = item.title.lowercase()

            val isSeriesItem = !item.isMovie ||
                    item.id.contains("_") ||
                    item.title.contains(" - S") ||
                    item.title.contains(" S") ||
                    item.title.contains("حلقة") ||
                    item.title.contains("الموسم") ||
                    item.title.contains("الحلقة") ||
                    knownSeriesIds.contains(item.id) ||
                    knownSeriesIds.contains(baseId) ||
                    knownSeriesTitles.any { lower.contains(it) || it.contains(lower) }

            isSeriesItem
        }
    }

    val scope = rememberCoroutineScope()

    val hasSeriesContent = uiState.trendingSeries.isNotEmpty() ||
            uiState.series.isNotEmpty() ||
            uiState.newEpisodes.isNotEmpty() ||
            uiState.upcomingSeries.isNotEmpty() ||
            catalogItems.isNotEmpty() || releaseItems.isNotEmpty() || genreItems.isNotEmpty() || topRatedItems.isNotEmpty()
var showBottomSheet by remember { mutableStateOf(false) }
    var selectedMediaContentType by remember { mutableStateOf(ContentType.TV) }
    var selectedMediaId by remember { mutableStateOf("") }
    var selectedMediaTitle by remember { mutableStateOf("") }
    var selectedMediaPoster by remember { mutableStateOf("") }

    var isDropdownExpanded by remember { mutableStateOf(false) }
    var selectedCategory by rememberSaveable { mutableStateOf(SeriesNavCategory.SERIES) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, uiState.selectedGenreId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && (selectedCategory != SeriesNavCategory.SERIES || uiState.selectedGenreId != null)) {
                selectedCategory = SeriesNavCategory.SERIES
                isDropdownExpanded = false
                if (uiState.selectedGenreId != null) viewModel.selectGenre(null)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val seriesCategories = remember {
        listOf(
            SeriesNavCategory.SERIES,
            SeriesNavCategory.GENRES,
            SeriesNavCategory.NEW_RELEASES,
            SeriesNavCategory.TOP_RATED
        )
    }
    val tvGenres = remember(uiState.availableGenres) {
        if (uiState.availableGenres.isNotEmpty()) uiState.availableGenres
        else TmdbGenreHelper.getTvGenres()
    }
    val allGenreStr = stringResource(R.string.genre_all)
    val genreOptions = remember(tvGenres, allGenreStr) {
        listOf(DropdownOption<Genre?>(null, allGenreStr)) + tvGenres.map {
            DropdownOption<Genre?>(it, it.name)
        }
    }
    val listState = rememberLazyListState()
    val activeGridPaginator = when (selectedCategory) {
        SeriesNavCategory.GENRES -> if (uiState.selectedGenreId != null) viewModel.genrePaginator else viewModel.catalogPaginator
        SeriesNavCategory.NEW_RELEASES -> viewModel.newReleasesPaginator
        SeriesNavCategory.TOP_RATED -> viewModel.topRatedPaginator
        else -> viewModel.catalogPaginator
    }
    val activeGridPageState = when (selectedCategory) {
        SeriesNavCategory.GENRES -> if (uiState.selectedGenreId != null) genrePageState else catalogPageState
        SeriesNavCategory.NEW_RELEASES -> releasePageState
        SeriesNavCategory.TOP_RATED -> topRatedPageState
        else -> catalogPageState
    }

    LaunchedEffect(selectedCategory, uiState.selectedGenreId) {
        listState.scrollToItem(0)
    }

    LaunchedEffect(selectedCategory, uiState.selectedGenreId) {
        when (selectedCategory) {
            SeriesNavCategory.GENRES -> {
                if (uiState.selectedGenreId != null) viewModel.genrePaginator.ensureTabLoaded(2)
                else viewModel.catalogPaginator.ensureTabLoaded(2)
            }
            SeriesNavCategory.NEW_RELEASES -> viewModel.newReleasesPaginator.ensureTabLoaded(2)
            SeriesNavCategory.TOP_RATED -> viewModel.topRatedPaginator.ensureTabLoaded(2)
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
        val isPagedGrid = selectedCategory == SeriesNavCategory.GENRES ||
            selectedCategory == SeriesNavCategory.NEW_RELEASES || selectedCategory == SeriesNavCategory.TOP_RATED
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
                    activeGridPaginator.loadMore(2)
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
                SeriesNavCategory.GENRES -> if (uiState.selectedGenreId != null) viewModel.genrePaginator.refresh(2) else viewModel.catalogPaginator.refresh(2)
                SeriesNavCategory.NEW_RELEASES -> viewModel.newReleasesPaginator.refresh(2)
                SeriesNavCategory.TOP_RATED -> viewModel.topRatedPaginator.refresh(2)
                else -> viewModel.loadSeries(forceRefresh = true)
            }
        },
        state = ptrState,
        modifier = Modifier.fillMaxSize()
    ) {
        if (!hasSeriesContent && selectedCategory == SeriesNavCategory.SERIES) {
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
                            onClick = { viewModel.loadSeries(forceRefresh = true) },
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
                item {
                    val heroSeries = if (uiState.trendingSeries.isNotEmpty()) uiState.trendingSeries else uiState.newEpisodes
                    if (heroSeries.isNotEmpty()) {
                        HeroCarousel(items = heroSeries.take(5).map { HeroItem(it.id, it.title, it.backdropUrl, false, contentType = ContentTypeResolver.resolveSeries(it)) }, onClick = onSeriesClick)
                    }
                }

                item(key = "series_category_spacer_top") {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Category Navigation Row (ALWAYS visible at all times)
                item(key = "series_category_navigation_row") {
                    CategoryNavigationRow(
                        categories = seriesCategories,
                        selectedCategory = selectedCategory,
                        onCategorySelected = { category ->
                            // Tapping an active secondary category toggles back to this
                            // screen's primary feed and clears the stale genre selection.
                            if (category == selectedCategory && category != SeriesNavCategory.SERIES) {
                                selectedCategory = SeriesNavCategory.SERIES
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

                item(key = "series_category_spacer_bottom") {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                when (selectedCategory) {
                    SeriesNavCategory.SERIES -> {
                        if (seriesHistoryItems.isNotEmpty()) {
                            item(key = "series_continue_watching") {
                                SectionTitleShared(stringResource(R.string.continue_watching), onSeeAllClick = onNavigateToWatching)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    items(seriesHistoryItems, key = { it.id }) { item ->
                                        val cleanId = if (item.id.contains("_")) item.id.substringBeforeLast("_") else item.id
                                        ContinueWatchingCardShared(
                                            item = item,
                                            onClick = { onSeriesClick(cleanId) },
                                            onPlayClick = {
                                                if (onPlaySeries != null) onPlaySeries(cleanId) else onSeriesClick(cleanId)
                                            }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }

                        // Trending Series
                        if (uiState.trendingSeries.isNotEmpty()) {
                            item(key = "series_trending") {
                                SectionTitleShared(stringResource(R.string.trending_series), onSeeAllClick = onNavigateToTrending)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    itemsIndexed(uiState.trendingSeries, key = { _, series -> series.id }) { index, series ->
                                        val resolvedType = ContentTypeResolver.resolveSeries(series)
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
                                            onClick = { onSeriesClick(series.id) },
                                            onLongClick = { 
                                                selectedMediaId = series.id
                                                selectedMediaTitle = series.title
                                                selectedMediaPoster = series.posterUrl
                                                selectedMediaContentType = resolvedType
                                                showBottomSheet = true
                                            }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }

                        // New Releases
                        if (uiState.newEpisodes.isNotEmpty()) {
                            item(key = "series_new_releases") {
                                SectionTitleShared(stringResource(R.string.new_releases), onSeeAllClick = onNavigateToNewReleases)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    itemsIndexed(uiState.newEpisodes, key = { _, series -> series.id }) { index, series ->
                                        val resolvedType = ContentTypeResolver.resolveSeries(series)
                                        MediaCard(
                                            title = series.title,
                                            posterUrl = series.posterUrl,
                                            rank = index + 1,
                                            rating = series.rating,
                                            year = com.example.utils.SeasonFormatter.formatSeasonOrFallback(
                                                androidx.compose.ui.platform.LocalContext.current,
                                                series.seasons.size,
                                                if (series.year > 0) series.year.toString() else ""
                                            ),
                                            isMovie = false,
                                            contentType = resolvedType,
                                            mediaId = series.id,
                                            onClick = { onSeriesClick(series.id) },
                                            onLongClick = { 
                                                selectedMediaId = series.id
                                                selectedMediaTitle = series.title
                                                selectedMediaPoster = series.posterUrl
                                                selectedMediaContentType = resolvedType
                                                showBottomSheet = true
                                            }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }

                        // Popular Series
                        if (uiState.series.isNotEmpty()) {
                            item(key = "series_popular") {
                                SectionTitleShared(stringResource(R.string.popular_series), onSeeAllClick = onNavigateToPopular)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    itemsIndexed(uiState.series, key = { _, series -> series.id }) { index, series ->
                                        val resolvedType = ContentTypeResolver.resolveSeries(series)
                                        MediaCard(
                                            title = series.title,
                                            posterUrl = series.posterUrl,
                                            rank = index + 1,
                                            rating = series.rating,
                                            year = com.example.utils.SeasonFormatter.formatSeasonOrFallback(
                                                androidx.compose.ui.platform.LocalContext.current,
                                                series.seasons.size,
                                                if (series.year > 0) series.year.toString() else ""
                                            ),
                                            isMovie = false,
                                            contentType = resolvedType,
                                            mediaId = series.id,
                                            onClick = { onSeriesClick(series.id) },
                                            onLongClick = { 
                                                selectedMediaId = series.id
                                                selectedMediaTitle = series.title
                                                selectedMediaPoster = series.posterUrl
                                                selectedMediaContentType = resolvedType
                                                showBottomSheet = true
                                            }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }

                        // Coming Soon Series
                        if (uiState.upcomingSeries.isNotEmpty()) {
                            item(key = "series_coming_soon") {
                                SectionTitleShared(stringResource(R.string.coming_soon), onSeeAllClick = onNavigateToUpcoming)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    itemsIndexed(uiState.upcomingSeries, key = { _, series -> series.id }) { index, series ->
                                        val resolvedType = ContentTypeResolver.resolveSeries(series)
                                        MediaCard(
                                            title = series.title,
                                            posterUrl = series.posterUrl,
                                            rank = index + 1,
                                            rating = series.rating,
                                            showZeroRating = true,
                                            year = com.example.utils.SeasonFormatter.formatSeasonOrFallback(
                                                androidx.compose.ui.platform.LocalContext.current,
                                                series.seasons.size,
                                                if (series.year > 0) series.year.toString() else ""
                                            ),
                                            isMovie = false,
                                            contentType = resolvedType,
                                            mediaId = series.id,
                                            onClick = { onSeriesClick(series.id) },
                                            onLongClick = { 
                                                selectedMediaId = series.id
                                                selectedMediaTitle = series.title
                                                selectedMediaPoster = series.posterUrl
                                                selectedMediaContentType = resolvedType
                                                showBottomSheet = true
                                            },
                                            modifier = Modifier.width(140.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }
                    }
                    SeriesNavCategory.GENRES -> {
                        // Genre Filter Dropdown (immediately below category navigation row)
                        item(key = "series_genre_dropdown_selector") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                CineStreamDropdownSelector(
                                    label = stringResource(R.string.filter_category),
                                    selectedOption = DropdownOption(
                                        value = uiState.selectedGenreId?.let { id -> tvGenres.find { it.id == id } },
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
                                    testTag = "series_genre_dropdown"
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

                        item(key = "series_genre_spacer") {
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        val displaySeries = if (uiState.selectedGenreId == null) catalogItems else genreItems
                        val activeGenreState = if (uiState.selectedGenreId != null) genrePageState else catalogPageState
                        when {
                            (activeGenreState.isLoadingFirstPage || activeGenreState.currentPage == 0) && displaySeries.isEmpty() && activeGenreState.loadMoreError == null -> {
                                item(key = "series_genre_loading") { CatalogGridSkeletonRows(itemCount = 12, columns = 3) }
                            }
                            displaySeries.isEmpty() && activeGenreState.loadMoreError != null -> {
                                item(key = "series_genre_error") {
                                    CatalogGridStatusMessage(
                                        message = activeGenreState.loadMoreError ?: stringResource(R.string.check_net_retry),
                                        actionLabel = stringResource(R.string.retry),
                                        onAction = { activeGridPaginator.retry(2) },
                                        isError = true
                                    )
                                }
                            }
                            displaySeries.isEmpty() -> {
                                item(key = "series_genre_empty") {
                                    CatalogGridStatusMessage(
                                        message = stringResource(R.string.no_genre_results),
                                        actionLabel = stringResource(R.string.filter_clear),
                                        onAction = { viewModel.selectGenre(null) }
                                    )
                                }
                            }
                            else -> {
                                verticalGridItems(
                                    items = displaySeries,
                                    columns = 3,
                                    horizontalPadding = 16.dp,
                                    key = { it.id }
                                ) { series ->
                                    MediaCard(
                                        title = series.title,
                                        posterUrl = series.posterUrl,
                                        rating = series.rating,
                                        year = com.example.utils.SeasonFormatter.formatSeasonOrFallback(context, series.seasons.size, if (series.year > 0) series.year.toString() else ""),
                                        isMovie = false,
                                        contentType = ContentTypeResolver.resolveSeries(series),
                                        mediaId = series.id,
                                        onClick = { onSeriesClick(series.id) },
                                        onLongClick = {
                                            selectedMediaId = series.id
                                            selectedMediaTitle = series.title
                                            selectedMediaPoster = series.posterUrl
                                            selectedMediaContentType = ContentTypeResolver.resolveSeries(series)
                                            showBottomSheet = true
                                        }
                                    )
                                }
                            }
                        }
                    
                        if (activeGenreState.isLoadingMore) {
                            item(key = "series_genre_more_loading") {
                                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        } else if (activeGenreState.loadMoreError != null && displaySeries.isNotEmpty()) {
                            item(key = "series_genre_more_error") {
                                CatalogGridStatusMessage(
                                    message = activeGenreState.loadMoreError ?: stringResource(R.string.check_net_retry),
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = { activeGridPaginator.retry(2) },
                                    isError = true
                                )
                            }
                        }
}
                    SeriesNavCategory.NEW_RELEASES -> {
                        val newReleasesList = releaseItems
                        if ( (releasePageState.isLoadingFirstPage || releasePageState.currentPage == 0) && newReleasesList.isEmpty() && releasePageState.loadMoreError == null) {
                            item(key = "series_release_skeleton") { CatalogGridSkeletonRows() }
                        } else if (newReleasesList.isEmpty() && releasePageState.loadMoreError != null) {
                            item(key = "series_release_error") {
                                CatalogGridStatusMessage(
                                    message = releasePageState.loadMoreError ?: stringResource(R.string.check_net_retry),
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = { viewModel.newReleasesPaginator.retry(2) },
                                    isError = true
                                )
                            }
                        } else if (newReleasesList.isEmpty()) {
                            item(key = "series_release_empty") { CatalogGridStatusMessage(message = stringResource(R.string.no_genre_results)) }
                        } else {
                        verticalGridItems(
                            items = newReleasesList,
                            columns = 3,
                            horizontalPadding = 16.dp,
                            key = { it.id }
                        ) { series ->
                            val resolvedType = ContentTypeResolver.resolveSeries(series)
                            MediaCard(
                                title = series.title,
                                posterUrl = series.posterUrl,
                                rating = series.rating,
                                year = com.example.utils.SeasonFormatter.formatSeasonOrFallback(
                                    context,
                                    series.seasons.size,
                                    if (series.year > 0) series.year.toString() else ""
                                ),
                                isMovie = false,
                                contentType = resolvedType,
                                mediaId = series.id,
                                onClick = { onSeriesClick(series.id) },
                                onLongClick = { 
                                    selectedMediaId = series.id
                                    selectedMediaTitle = series.title
                                    selectedMediaPoster = series.posterUrl
                                    selectedMediaContentType = resolvedType
                                    showBottomSheet = true
                                }
                            )
                        }
                        }
                    
                        if (releasePageState.isLoadingMore) {
                            item(key = "series_release_more_loading") {
                                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        } else if (releasePageState.loadMoreError != null && releaseItems.isNotEmpty()) {
                            item(key = "series_release_more_error") {
                                CatalogGridStatusMessage(
                                    message = releasePageState.loadMoreError ?: stringResource(R.string.check_net_retry),
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = { viewModel.newReleasesPaginator.retry(2) },
                                    isError = true
                                )
                            }
                        }
}
                    SeriesNavCategory.TOP_RATED -> {
                        val topRatedList = topRatedItems
                        if ((topRatedPageState.isLoadingFirstPage || topRatedPageState.currentPage == 0) && topRatedList.isEmpty() && topRatedPageState.loadMoreError == null) {
                            item(key = "series_top_rated_skeleton") { CatalogGridSkeletonRows() }
                        } else if (topRatedList.isEmpty() && topRatedPageState.loadMoreError != null) {
                            item(key = "series_top_rated_error") {
                                CatalogGridStatusMessage(
                                    message = topRatedPageState.loadMoreError ?: stringResource(R.string.check_net_retry),
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = { viewModel.topRatedPaginator.retry(2) },
                                    isError = true
                                )
                            }
                        } else if (topRatedList.isEmpty()) {
                            item(key = "series_top_rated_empty") { CatalogGridStatusMessage(message = stringResource(R.string.no_genre_results)) }
                        } else {
                        verticalGridItems(
                            items = topRatedList,
                            columns = 3,
                            horizontalPadding = 16.dp,
                            key = { it.id }
                        ) { series ->
                            val resolvedType = ContentTypeResolver.resolveSeries(series)
                            MediaCard(
                                title = series.title,
                                posterUrl = series.posterUrl,
                                rating = series.rating,
                                year = com.example.utils.SeasonFormatter.formatSeasonOrFallback(
                                    context,
                                    series.seasons.size,
                                    if (series.year > 0) series.year.toString() else ""
                                ),
                                isMovie = false,
                                contentType = resolvedType,
                                mediaId = series.id,
                                onClick = { onSeriesClick(series.id) },
                                onLongClick = { 
                                    selectedMediaId = series.id
                                    selectedMediaTitle = series.title
                                    selectedMediaPoster = series.posterUrl
                                    selectedMediaContentType = resolvedType
                                    showBottomSheet = true
                                }
                            )
                        }
                        }
                    
                        if (topRatedPageState.isLoadingMore) {
                            item(key = "series_top_rated_more_loading") {
                                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        } else if (topRatedPageState.loadMoreError != null && topRatedItems.isNotEmpty()) {
                            item(key = "series_top_rated_more_error") {
                                CatalogGridStatusMessage(
                                    message = topRatedPageState.loadMoreError ?: stringResource(R.string.check_net_retry),
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = { viewModel.topRatedPaginator.retry(2) },
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
                isMovie = false,
                mediaId = selectedMediaId,
                title = selectedMediaTitle,
                posterUrl = selectedMediaPoster,
                contentType = selectedMediaContentType,
                onDismissRequest = { showBottomSheet = false },
                onDownloadStart = { quality ->
                    scope.launch {
                        downloadRepository.addToDownloads(DownloadItem(
                            id = selectedMediaId,
                            mediaId = selectedMediaId,
                            title = selectedMediaTitle,
                            posterUrl = selectedMediaPoster,
                            isMovie = false,
                            quality = quality
                        ))
                        Toast.makeText(context, context.getString(R.string.download_started), Toast.LENGTH_SHORT).show()
                    }
                },
                onAddToLibrary = {
                    scope.launch {
                        val libItem = LibraryItem.create(
                            contentType = selectedMediaContentType,
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
    
