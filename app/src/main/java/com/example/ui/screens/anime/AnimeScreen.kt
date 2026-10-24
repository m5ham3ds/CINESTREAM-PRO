package com.example.ui.screens.anime

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

enum class AnimeNavCategory(val titleRes: Int, val id: String) {
    ANIME(R.string.category_anime, "anime"),
    GENRES(R.string.category_genres, "genres"),
    NEW_RELEASES(R.string.new_releases, "new_releases"),
    TOP_RATED(R.string.category_top_rated, "top_rated")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeScreen(
    onAnimeClick: (String) -> Unit,
    onPlayAnime: ((String) -> Unit)? = null,
    onNavigateToTrending: () -> Unit = {},
    onNavigateToWatching: () -> Unit = {},
    onNavigateToPopular: () -> Unit = {},
    onNavigateToNewReleases: () -> Unit = {},
    onNavigateToUpcoming: () -> Unit = {},
    viewModel: AnimeViewModel = viewModel(factory = ViewModelFactory())
) {
    val uiState by viewModel.uiState.collectAsState()
    val catalogPageState by viewModel.catalogPaginator.getTabStateFlow(3).collectAsState()
    val releasePageState by viewModel.newReleasesPaginator.getTabStateFlow(3).collectAsState()
    val topRatedPageState by viewModel.topRatedPaginator.getTabStateFlow(3).collectAsState()
    val genrePageState by viewModel.genrePaginator.getTabStateFlow(3).collectAsState()
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
    val animeHistoryItems = remember(historyItems, uiState) {
        val knownAnimeTitles = (uiState.trendingAnime + uiState.series + uiState.newEpisodes + uiState.upcomingAnime)
            .map { it.title.lowercase().trim() }
            .filter { it.isNotBlank() }
        val knownAnimeIds = (uiState.trendingAnime + uiState.series + uiState.newEpisodes + uiState.upcomingAnime)
            .map { it.id }
            .toSet()

        knownAnimeIds.forEach { com.example.data.repository.AnimePlaybackStore.markAsAnime(context, it) }

        historyItems.filter { item ->
            val lowerTitle = item.title.lowercase()
            val baseId = if (item.id.contains("_")) item.id.substringBeforeLast("_") else item.id

            com.example.data.repository.AnimePlaybackStore.isAnime(context, item.id, item.title) ||
            com.example.data.repository.AnimePlaybackStore.isAnime(context, baseId, item.title) ||
            knownAnimeIds.contains(item.id) ||
            knownAnimeIds.contains(baseId) ||
            knownAnimeTitles.any { lowerTitle.contains(it) || it.contains(lowerTitle) } ||
            lowerTitle.contains("anime") || lowerTitle.contains("أنمي") || lowerTitle.contains("انمي") ||
            lowerTitle.contains("أوتاكو") || lowerTitle.contains("otaku") ||
            item.title.any { ch -> ch in '\u3040'..'\u30FF' || ch in '\u4E00'..'\u9FFF' }
        }
    }

    val scope = rememberCoroutineScope()

    val hasAnimeContent = uiState.trendingAnime.isNotEmpty() ||
            uiState.series.isNotEmpty() ||
            uiState.newEpisodes.isNotEmpty() ||
            uiState.upcomingAnime.isNotEmpty() ||
            catalogItems.isNotEmpty() || releaseItems.isNotEmpty() || genreItems.isNotEmpty() || topRatedItems.isNotEmpty()

    var showBottomSheet by remember { mutableStateOf(false) }
    var selectedMediaId by remember { mutableStateOf("") }
    var selectedMediaTitle by remember { mutableStateOf("") }
    var selectedMediaPoster by remember { mutableStateOf("") }

    var isDropdownExpanded by remember { mutableStateOf(false) }
    var selectedCategory by rememberSaveable { mutableStateOf(AnimeNavCategory.ANIME) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, uiState.selectedGenreId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && (selectedCategory != AnimeNavCategory.ANIME || uiState.selectedGenreId != null)) {
                selectedCategory = AnimeNavCategory.ANIME
                isDropdownExpanded = false
                if (uiState.selectedGenreId != null) viewModel.selectGenre(null)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val animeCategories = remember {
        listOf(
            AnimeNavCategory.ANIME,
            AnimeNavCategory.GENRES,
            AnimeNavCategory.NEW_RELEASES,
            AnimeNavCategory.TOP_RATED
        )
    }
    val animeGenres = remember(uiState.availableGenres) {
        if (uiState.availableGenres.isNotEmpty()) uiState.availableGenres
        else TmdbGenreHelper.getAnimeGenres()
    }
    val allGenreStr = stringResource(R.string.genre_all)
    val genreOptions = remember(animeGenres, allGenreStr) {
        listOf(DropdownOption<Genre?>(null, allGenreStr)) + animeGenres.map {
            DropdownOption<Genre?>(it, it.name)
        }
    }
    val listState = rememberLazyListState()
    val activeGridPaginator = when (selectedCategory) {
        AnimeNavCategory.GENRES -> if (uiState.selectedGenreId != null) viewModel.genrePaginator else viewModel.catalogPaginator
        AnimeNavCategory.NEW_RELEASES -> viewModel.newReleasesPaginator
        AnimeNavCategory.TOP_RATED -> viewModel.topRatedPaginator
        else -> viewModel.catalogPaginator
    }
    val activeGridPageState = when (selectedCategory) {
        AnimeNavCategory.GENRES -> if (uiState.selectedGenreId != null) genrePageState else catalogPageState
        AnimeNavCategory.NEW_RELEASES -> releasePageState
        AnimeNavCategory.TOP_RATED -> topRatedPageState
        else -> catalogPageState
    }

    LaunchedEffect(selectedCategory, uiState.selectedGenreId) {
        listState.scrollToItem(0)
    }

    LaunchedEffect(selectedCategory, uiState.selectedGenreId) {
        when (selectedCategory) {
            AnimeNavCategory.GENRES -> {
                if (uiState.selectedGenreId != null) viewModel.genrePaginator.ensureTabLoaded(3)
                else viewModel.catalogPaginator.ensureTabLoaded(3)
            }
            AnimeNavCategory.NEW_RELEASES -> viewModel.newReleasesPaginator.ensureTabLoaded(3)
            AnimeNavCategory.TOP_RATED -> viewModel.topRatedPaginator.ensureTabLoaded(3)
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
        val isPagedGrid = selectedCategory == AnimeNavCategory.GENRES ||
            selectedCategory == AnimeNavCategory.NEW_RELEASES || selectedCategory == AnimeNavCategory.TOP_RATED
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
                    activeGridPaginator.loadMore(3)
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
                AnimeNavCategory.GENRES -> if (uiState.selectedGenreId != null) viewModel.genrePaginator.refresh(3) else viewModel.catalogPaginator.refresh(3)
                AnimeNavCategory.NEW_RELEASES -> viewModel.newReleasesPaginator.refresh(3)
                AnimeNavCategory.TOP_RATED -> viewModel.topRatedPaginator.refresh(3)
                else -> viewModel.loadData(forceRefresh = true)
            }
        },
        state = ptrState,
        modifier = Modifier.fillMaxSize()
    ) {
        if (!hasAnimeContent && selectedCategory == AnimeNavCategory.ANIME) {
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
                            onClick = { viewModel.loadData(forceRefresh = true) },
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
                    val heroAnime = if (uiState.trendingAnime.isNotEmpty()) uiState.trendingAnime else uiState.newEpisodes
                    if (heroAnime.isNotEmpty()) {
                        HeroCarousel(items = heroAnime.take(5).map { HeroItem(it.id, it.title, it.backdropUrl, false, contentType = ContentType.ANIME) }, onClick = onAnimeClick)
                    }
                }

                item(key = "anime_category_spacer_top") {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Category Navigation Row (ALWAYS visible at all times)
                item(key = "anime_category_navigation_row") {
                    CategoryNavigationRow(
                        categories = animeCategories,
                        selectedCategory = selectedCategory,
                        onCategorySelected = { category ->
                            // Tapping an active secondary category toggles back to this
                            // screen's primary feed and clears the stale genre selection.
                            if (category == selectedCategory && category != AnimeNavCategory.ANIME) {
                                selectedCategory = AnimeNavCategory.ANIME
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

                item(key = "anime_category_spacer_bottom") {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                when (selectedCategory) {
                    AnimeNavCategory.ANIME -> {
                        if (animeHistoryItems.isNotEmpty()) {
                            item(key = "anime_continue_watching") {
                                SectionTitleShared(stringResource(R.string.continue_watching), onSeeAllClick = onNavigateToWatching)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    items(animeHistoryItems, key = { it.id }) { item ->
                                        val cleanId = if (item.id.contains("_")) item.id.substringBeforeLast("_") else item.id
                                        ContinueWatchingCardShared(
                                            item = item,
                                            onClick = { onAnimeClick(cleanId) },
                                            onPlayClick = {
                                                if (onPlayAnime != null) onPlayAnime(cleanId) else onAnimeClick(cleanId)
                                            }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }

                        // Trending Anime
                        if (uiState.trendingAnime.isNotEmpty()) {
                            item(key = "anime_trending") {
                                SectionTitleShared(stringResource(R.string.trending_anime), onSeeAllClick = onNavigateToTrending)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    itemsIndexed(uiState.trendingAnime, key = { _, series -> series.id }) { index, series ->
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
                                            contentType = ContentType.ANIME,
                                            mediaId = series.id,
                                            onClick = { onAnimeClick(series.id) },
                                            onLongClick = { 
                                                selectedMediaId = series.id
                                                selectedMediaTitle = series.title
                                                selectedMediaPoster = series.posterUrl
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
                            item(key = "anime_new_releases") {
                                SectionTitleShared(stringResource(R.string.new_releases), onSeeAllClick = onNavigateToNewReleases)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    itemsIndexed(uiState.newEpisodes, key = { _, series -> series.id }) { index, series ->
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
                                            contentType = ContentType.ANIME,
                                            mediaId = series.id,
                                            onClick = { onAnimeClick(series.id) },
                                            onLongClick = { 
                                                selectedMediaId = series.id
                                                selectedMediaTitle = series.title
                                                selectedMediaPoster = series.posterUrl
                                                showBottomSheet = true
                                            }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }

                        // Popular Anime
                        if (uiState.series.isNotEmpty()) {
                            item(key = "anime_popular") {
                                SectionTitleShared(stringResource(R.string.popular_anime), onSeeAllClick = onNavigateToPopular)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    itemsIndexed(uiState.series, key = { _, series -> series.id }) { index, series ->
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
                                            contentType = ContentType.ANIME,
                                            mediaId = series.id,
                                            onClick = { onAnimeClick(series.id) },
                                            onLongClick = { 
                                                selectedMediaId = series.id
                                                selectedMediaTitle = series.title
                                                selectedMediaPoster = series.posterUrl
                                                showBottomSheet = true
                                            }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }

                        // Coming Soon Anime
                        if (uiState.upcomingAnime.isNotEmpty()) {
                            item(key = "anime_coming_soon") {
                                SectionTitleShared(stringResource(R.string.coming_soon), onSeeAllClick = onNavigateToUpcoming)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    itemsIndexed(uiState.upcomingAnime, key = { _, series -> series.id }) { index, series ->
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
                                            contentType = ContentType.ANIME,
                                            mediaId = series.id,
                                            onClick = { onAnimeClick(series.id) },
                                            onLongClick = { 
                                                selectedMediaId = series.id
                                                selectedMediaTitle = series.title
                                                selectedMediaPoster = series.posterUrl
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
                    AnimeNavCategory.GENRES -> {
                        // Genre Filter Dropdown (immediately below category navigation row)
                        item(key = "anime_genre_dropdown_selector") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                CineStreamDropdownSelector(
                                    label = stringResource(R.string.filter_category),
                                    selectedOption = DropdownOption(
                                        value = uiState.selectedGenreId?.let { id -> animeGenres.find { it.id == id } },
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
                                    testTag = "anime_genre_dropdown"
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

                        item(key = "anime_genre_spacer") {
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        val displayAnime = if (uiState.selectedGenreId == null) catalogItems else genreItems
                        val activeGenreState = if (uiState.selectedGenreId != null) genrePageState else catalogPageState
                        when {
                            (activeGenreState.isLoadingFirstPage || activeGenreState.currentPage == 0) && displayAnime.isEmpty() && activeGenreState.loadMoreError == null -> {
                                item(key = "anime_genre_loading") { CatalogGridSkeletonRows(itemCount = 12, columns = 3) }
                            }
                            displayAnime.isEmpty() && activeGenreState.loadMoreError != null -> {
                                item(key = "anime_genre_error") {
                                    CatalogGridStatusMessage(
                                        message = activeGenreState.loadMoreError ?: stringResource(R.string.check_net_retry),
                                        actionLabel = stringResource(R.string.retry),
                                        onAction = { activeGridPaginator.retry(3) },
                                        isError = true
                                    )
                                }
                            }
                            displayAnime.isEmpty() -> {
                                item(key = "anime_genre_empty") {
                                    CatalogGridStatusMessage(
                                        message = stringResource(R.string.no_genre_results),
                                        actionLabel = stringResource(R.string.filter_clear),
                                        onAction = { viewModel.selectGenre(null) }
                                    )
                                }
                            }
                            else -> {
                                verticalGridItems(
                                    items = displayAnime,
                                    columns = 3,
                                    horizontalPadding = 16.dp,
                                    key = { it.id }
                                ) { anime ->
                                    MediaCard(
                                        title = anime.title,
                                        posterUrl = anime.posterUrl,
                                        rating = anime.rating,
                                        year = com.example.utils.SeasonFormatter.formatSeasonOrFallback(context, anime.seasons.size, if (anime.year > 0) anime.year.toString() else ""),
                                        isMovie = false,
                                        contentType = ContentType.ANIME,
                                        mediaId = anime.id,
                                        onClick = { onAnimeClick(anime.id) },
                                        onLongClick = {
                                            selectedMediaId = anime.id
                                            selectedMediaTitle = anime.title
                                            selectedMediaPoster = anime.posterUrl
                                            
                                            showBottomSheet = true
                                        }
                                    )
                                }
                            }
                        }
                    
                        if (activeGenreState.isLoadingMore) {
                            item(key = "anime_genre_more_loading") {
                                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        } else if (activeGenreState.loadMoreError != null && displayAnime.isNotEmpty()) {
                            item(key = "anime_genre_more_error") {
                                CatalogGridStatusMessage(
                                    message = activeGenreState.loadMoreError ?: stringResource(R.string.check_net_retry),
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = { activeGridPaginator.retry(3) },
                                    isError = true
                                )
                            }
                        }
}
                    AnimeNavCategory.NEW_RELEASES -> {
                        val newReleasesList = releaseItems
                        if ( (releasePageState.isLoadingFirstPage || releasePageState.currentPage == 0) && newReleasesList.isEmpty() && releasePageState.loadMoreError == null) {
                            item(key = "anime_release_skeleton") { CatalogGridSkeletonRows() }
                        } else if (newReleasesList.isEmpty() && releasePageState.loadMoreError != null) {
                            item(key = "anime_release_error") {
                                CatalogGridStatusMessage(
                                    message = releasePageState.loadMoreError ?: stringResource(R.string.check_net_retry),
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = { viewModel.newReleasesPaginator.retry(3) },
                                    isError = true
                                )
                            }
                        } else if (newReleasesList.isEmpty()) {
                            item(key = "anime_release_empty") { CatalogGridStatusMessage(message = stringResource(R.string.no_genre_results)) }
                        } else {
                        verticalGridItems(
                            items = newReleasesList,
                            columns = 3,
                            horizontalPadding = 16.dp,
                            key = { it.id }
                        ) { series ->
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
                                contentType = ContentType.ANIME,
                                mediaId = series.id,
                                onClick = { onAnimeClick(series.id) },
                                onLongClick = { 
                                    selectedMediaId = series.id
                                    selectedMediaTitle = series.title
                                    selectedMediaPoster = series.posterUrl
                                    showBottomSheet = true
                                }
                            )
                        }
                        }
                    
                        if (releasePageState.isLoadingMore) {
                            item(key = "anime_release_more_loading") {
                                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        } else if (releasePageState.loadMoreError != null && releaseItems.isNotEmpty()) {
                            item(key = "anime_release_more_error") {
                                CatalogGridStatusMessage(
                                    message = releasePageState.loadMoreError ?: stringResource(R.string.check_net_retry),
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = { viewModel.newReleasesPaginator.retry(3) },
                                    isError = true
                                )
                            }
                        }
}
                    AnimeNavCategory.TOP_RATED -> {
                        val topRatedList = topRatedItems
                        if ((topRatedPageState.isLoadingFirstPage || topRatedPageState.currentPage == 0) && topRatedList.isEmpty() && topRatedPageState.loadMoreError == null) {
                            item(key = "anime_top_rated_skeleton") { CatalogGridSkeletonRows() }
                        } else if (topRatedList.isEmpty() && topRatedPageState.loadMoreError != null) {
                            item(key = "anime_top_rated_error") {
                                CatalogGridStatusMessage(
                                    message = topRatedPageState.loadMoreError ?: stringResource(R.string.check_net_retry),
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = { viewModel.topRatedPaginator.retry(3) },
                                    isError = true
                                )
                            }
                        } else if (topRatedList.isEmpty()) {
                            item(key = "anime_top_rated_empty") { CatalogGridStatusMessage(message = stringResource(R.string.no_genre_results)) }
                        } else {
                        verticalGridItems(
                            items = topRatedList,
                            columns = 3,
                            horizontalPadding = 16.dp,
                            key = { it.id }
                        ) { series ->
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
                                contentType = ContentType.ANIME,
                                mediaId = series.id,
                                onClick = { onAnimeClick(series.id) },
                                onLongClick = { 
                                    selectedMediaId = series.id
                                    selectedMediaTitle = series.title
                                    selectedMediaPoster = series.posterUrl
                                    showBottomSheet = true
                                }
                            )
                        }
                        }
                    
                        if (topRatedPageState.isLoadingMore) {
                            item(key = "anime_top_rated_more_loading") {
                                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        } else if (topRatedPageState.loadMoreError != null && topRatedItems.isNotEmpty()) {
                            item(key = "anime_top_rated_more_error") {
                                CatalogGridStatusMessage(
                                    message = topRatedPageState.loadMoreError ?: stringResource(R.string.check_net_retry),
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = { viewModel.topRatedPaginator.retry(3) },
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
                contentType = ContentType.ANIME,
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
                            contentType = ContentType.ANIME,
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
