package com.example.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.ViewModelFactory
import com.example.ui.components.MediaCard
import com.example.ui.components.CustomTopBar
import com.example.ui.components.CineStreamFilterModalDropdown
import com.example.ui.components.DropdownOption
import com.example.ui.components.ActiveFilterChip
import com.example.data.util.TmdbGenreHelper
import com.example.domain.models.Genre
import com.example.domain.models.Movie
import com.example.domain.models.Series

import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PopularScreen(
    onItemClick: (String, Boolean) -> Unit,
    onBack: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = ViewModelFactory()),
    initialTabIndex: Int = 0
) {
    val paginator = viewModel.popularPaginator
    val tabStates by paginator.tabStates.collectAsState()
    val allStr = stringResource(R.string.all)
    val moviesStr = stringResource(R.string.movies)
    val seriesStr = stringResource(R.string.series)
    val animeStr = stringResource(R.string.anime)
    val tabsList = listOf(allStr, moviesStr, seriesStr, animeStr)
    val pagerState = rememberPagerState(
        initialPage = initialTabIndex.coerceIn(0, tabsList.size - 1),
        pageCount = { tabsList.size }
    )
    val coroutineScope = rememberCoroutineScope()
    val selectedTab = tabsList[pagerState.currentPage]

    var isFilterModalOpen by remember { mutableStateOf(false) }
    var selectedGenre by remember { mutableStateOf<Genre?>(null) }
    var hasLeftThisScreen by remember { mutableStateOf(false) }
    val pageLifecycleOwner = LocalLifecycleOwner.current
    val latestPagerPage by rememberUpdatedState(pagerState.currentPage)
    val latestSelectedGenre by rememberUpdatedState(selectedGenre)
    DisposableEffect(pageLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                hasLeftThisScreen = true
            } else if (event == Lifecycle.Event.ON_RESUME && hasLeftThisScreen) {
                hasLeftThisScreen = false
                if (latestPagerPage != 0 || latestSelectedGenre != null) {
                    selectedGenre = null
                    coroutineScope.launch { pagerState.scrollToPage(0) }
                }
            }
        }
        pageLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { pageLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val currentGenres = remember(pagerState.currentPage) {
        when (pagerState.currentPage) {
            1 -> TmdbGenreHelper.getMovieGenres()
            2 -> TmdbGenreHelper.getTvGenres()
            3 -> TmdbGenreHelper.getAnimeGenres()
            else -> {
                val movieG = TmdbGenreHelper.getMovieGenres()
                val tvG = TmdbGenreHelper.getTvGenres()
                (movieG + tvG).distinctBy { it.name }
            }
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        paginator.onTabSwitched(pagerState.currentPage)
    }

    if (isFilterModalOpen) {
        CineStreamFilterModalDropdown(
            title = stringResource(R.string.filter_by_genre),
            selectedOption = selectedGenre,
            options = currentGenres.map { DropdownOption(it, it.name) },
            onDismiss = { isFilterModalOpen = false },
            onOptionSelected = { genre ->
                selectedGenre = genre
                isFilterModalOpen = false
            },
            onClearFilter = {
                selectedGenre = null
                isFilterModalOpen = false
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CustomTopBar(
            titleFirst = stringResource(R.string.popular_title_first),
            titleSecond = stringResource(R.string.popular_title_second),
            subtitle = stringResource(R.string.popular_subtitle),
            onBack = onBack,
            showFilter = true,
            onFilterClick = { isFilterModalOpen = true },
            isFilterActive = selectedGenre != null
        )
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp)),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            tabsList.forEach { tab ->
                val isSelected = selectedTab == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { coroutineScope.launch { pagerState.animateScrollToPage(tabsList.indexOf(tab)) } }
                        .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .border(if (isSelected) 1.dp else 0.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        tab,
                        color = if (isSelected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))

        AnimatedVisibility(
            visible = selectedGenre != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                ActiveFilterChip(
                    filterName = selectedGenre?.name ?: "",
                    onClear = { selectedGenre = null }
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val tabState = tabStates[page] ?: com.example.data.repository.TabPaginationState()
            val ptrState = rememberPullToRefreshState()
            val rawItems = tabState.items
            val items = remember(rawItems, selectedGenre) {
                if (selectedGenre == null) rawItems
                else {
                    val genreId = selectedGenre!!.id
                    val genreName = selectedGenre!!.name
                    rawItems.filter { (media, isMovie) ->
                        if (isMovie) {
                            val m = media as? Movie
                            m != null && (m.genreIds.contains(genreId) || m.genres.any { it.contains(genreName, ignoreCase = true) })
                        } else {
                            val s = media as? Series
                            s != null && (s.genreIds.contains(genreId) || s.genres.any { it.contains(genreName, ignoreCase = true) })
                        }
                    }
                }
            }
            val gridState = rememberLazyGridState()
            // Limit automatic genre scans: uncommon genres must not trigger requests
            // through many upstream pages without user interaction.
            var genreAutoFillPages by remember(page, selectedGenre?.id) { mutableIntStateOf(0) }

            LaunchedEffect(selectedGenre?.id) {
                gridState.scrollToItem(0)
            }
            LaunchedEffect(
                page, selectedGenre?.id, items.size, tabState.currentPage, tabState.hasNextPage,
                tabState.isLoadingFirstPage, tabState.isLoadingMore, tabState.loadMoreError
            ) {
                if (selectedGenre != null && items.size < 9 && tabState.hasNextPage &&
                    !tabState.isLoadingFirstPage && !tabState.isLoadingMore && tabState.currentPage > 0 &&
                    tabState.loadMoreError == null && genreAutoFillPages < 3
                ) {
                    genreAutoFillPages += 1
                    paginator.loadMore(page)
                }
            }

            // A page may change `items.size` while the viewport remains at the end.
            // Require a new user-scroll approach after each request to avoid rapid
            // auto-draining the catalog and triggering HTTP 429 responses.
            var paginationArmed by remember(page, selectedGenre?.id) { mutableStateOf(true) }
            LaunchedEffect(
                gridState, page, selectedGenre?.id, items.size, tabState.currentPage,
                tabState.hasNextPage, tabState.isLoadingFirstPage, tabState.isLoadingMore,
                tabState.loadMoreError
            ) {
                if (selectedGenre == null || items.size >= 9) {
                    snapshotFlow {
                        val layout = gridState.layoutInfo
                        val total = layout.totalItemsCount
                        val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
                        val atEnd = total > 0 && lastVisible >= total - 6
                        atEnd to gridState.isScrollInProgress
                    }.collect { (atEnd, userScrolling) ->
                        if (!atEnd) {
                            paginationArmed = true
                        } else if (
                            userScrolling && paginationArmed && tabState.hasNextPage &&
                            !tabState.isLoadingFirstPage && !tabState.isLoadingMore &&
                            tabState.loadMoreError == null
                        ) {
                            paginationArmed = false
                            paginator.loadMore(page)
                        }
                    }
                }
            }

            val pullRefreshIndicator = com.example.ui.components.rememberBriefPullRefreshIndicator()
    PullToRefreshBox(
                isRefreshing = pullRefreshIndicator.visible,
                onRefresh = { pullRefreshIndicator.start(); paginator.refresh(page) },
                state = ptrState,
                modifier = Modifier.fillMaxSize()
            ) {
                if ((tabState.isLoadingFirstPage || tabState.currentPage == 0) && rawItems.isEmpty() && tabState.loadMoreError == null) {
                    com.example.ui.components.GridScreenSkeleton()
                } else if (rawItems.isEmpty() && tabState.loadMoreError != null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = tabState.loadMoreError ?: stringResource(R.string.net_error),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { paginator.retry(page) },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                } else if (items.isEmpty() && rawItems.isNotEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.no_genre_results),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            if (selectedGenre != null && tabState.hasNextPage && !tabState.isLoadingMore && tabState.loadMoreError == null) {
                                OutlinedButton(onClick = { paginator.loadMore(page) }, shape = RoundedCornerShape(8.dp)) {
                                    Text(stringResource(R.string.load_more_matches))
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { selectedGenre = null },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(stringResource(R.string.filter_clear))
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(items, key = { index, (media, isMovie) ->
                            val id = if (isMovie) (media as com.example.domain.models.Movie).id else (media as com.example.domain.models.Series).id
                            "${if(isMovie) "m" else "s"}_${id}_$index"
                        }) { index, (media, isMovie) ->
                            val title = if (isMovie) (media as com.example.domain.models.Movie).title else (media as com.example.domain.models.Series).title
                            val poster = if (isMovie) (media as com.example.domain.models.Movie).posterUrl else (media as com.example.domain.models.Series).posterUrl
                            val cardRating = if (isMovie) {
                                (media as com.example.domain.models.Movie).rating
                            } else {
                                (media as com.example.domain.models.Series).rating
                            }
                            val cardYear = if (isMovie) {
                                val movie = media as com.example.domain.models.Movie
                                movie.releaseDate?.take(4)?.takeIf { it.toIntOrNull() != null }
                                    ?: movie.year.takeIf { it > 0 }?.toString()
                            } else {
                                val series = media as com.example.domain.models.Series
                                series.firstAirDate?.take(4)?.takeIf { it.toIntOrNull() != null }
                                    ?: series.year.takeIf { it > 0 }?.toString()
                            }
                            val id = if (isMovie) (media as com.example.domain.models.Movie).id else (media as com.example.domain.models.Series).id
                            val onClick = remember(id, isMovie) {
                                { onItemClick(id, isMovie) }
                            }
                            MediaCard(
                                revealIndex = index,
                                title = title,
                                posterUrl = poster,
                                isMovie = isMovie,
                                rank = index + 1,
                                rating = cardRating,
                                year = cardYear,
                                mediaId = id,
                                onClick = onClick
                            )
                        }

                        if (selectedGenre != null && items.size < 9 && tabState.hasNextPage &&
                            !tabState.isLoadingMore && tabState.loadMoreError == null
                        ) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                    OutlinedButton(onClick = { paginator.loadMore(page) }, shape = RoundedCornerShape(8.dp)) {
                                        Text(stringResource(R.string.load_more_matches))
                                    }
                                }
                            }
                        }
                        if (tabState.isLoadingMore) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        strokeWidth = 2.5.dp
                                    )
                                }
                            }
                        } else if (tabState.loadMoreError != null) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = tabState.loadMoreError ?: stringResource(R.string.net_error),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = { paginator.retry(page) },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                                    ) {
                                        Text(text = stringResource(R.string.retry), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
