package com.example.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarHalf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.ContentType
import com.example.data.util.ContentTypeResolver
import com.example.data.util.TmdbGenreHelper
import com.example.domain.models.Genre
import com.example.domain.models.MediaDiscoverRequest
import com.example.domain.models.Movie
import com.example.domain.models.Series
import com.example.ui.ViewModelFactory
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class DiscoverKind { MOVIES, SERIES, ANIME }
private enum class DiscoverSort(val apiValue: String) {
    POPULAR("popularity.desc"),
    LEAST_POPULAR("popularity.asc"),
    TOP_RATED("vote_average.desc"),
    LOWEST_RATED("vote_average.asc")
}

private class DiscoverFilterState {
    var sort by mutableStateOf(DiscoverSort.POPULAR)
    var genres by mutableStateOf(emptySet<Int>())
    var providers by mutableStateOf(emptySet<Int>())
    var year by mutableStateOf<Int?>(null)
    var minimumVotes by mutableStateOf(0)
    var status by mutableStateOf("any")
}

private data class DiscoverLoadedPage(
    val items: List<HomeMixedMediaItem>,
    val totalPages: Int,
    val totalResults: Int
)

/** FlixQuest's filter-first Discover page, backed by CineStream's TMDB repository. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoverScreen(
    onMovieClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    homeViewModel: HomeViewModel = viewModel(factory = ViewModelFactory()),
    moviesViewModel: com.example.ui.screens.movies.MoviesViewModel = viewModel(factory = ViewModelFactory()),
    seriesViewModel: com.example.ui.screens.series.SeriesViewModel = viewModel(factory = ViewModelFactory()),
    animeViewModel: com.example.ui.screens.anime.AnimeViewModel = viewModel(factory = ViewModelFactory()),
    initialKindName: String? = null,
    initialGenreId: Int? = null,
    secondaryDockOpen: Boolean = false
) {
    val movies by moviesViewModel.uiState.collectAsState()
    val series by seriesViewModel.uiState.collectAsState()
    val anime by animeViewModel.uiState.collectAsState()

    var kindName by rememberSaveable { mutableStateOf(initialKindName ?: DiscoverKind.MOVIES.name) }
    val kind = DiscoverKind.valueOf(kindName)
    // FlixQuest keeps an independent filter state for each content kind.
    val movieFilters = remember { DiscoverFilterState() }
    val seriesFilters = remember { DiscoverFilterState() }
    val animeFilters = remember { DiscoverFilterState() }
    val filters = when (kind) {
        DiscoverKind.MOVIES -> movieFilters
        DiscoverKind.SERIES -> seriesFilters
        DiscoverKind.ANIME -> animeFilters
    }
    val context = LocalContext.current
    val yearOptions = remember {
        (Calendar.getInstance().get(Calendar.YEAR) downTo 1950).toList()
    }
    // FlixQuest's Discover uses the US provider catalogue consistently. Keep the
    // same region so the provider IDs and logo grid return deterministic results
    // regardless of the device's SIM / locale (for example, Sudan).
    val region = "US"
    val genres = when (kind) {
        DiscoverKind.MOVIES -> movies.availableGenres.ifEmpty { TmdbGenreHelper.getMovieGenres() }
        DiscoverKind.SERIES -> series.availableGenres.ifEmpty { TmdbGenreHelper.getTvGenres() }
        DiscoverKind.ANIME -> anime.availableGenres.ifEmpty { TmdbGenreHelper.getAnimeGenres() }
    }

    LaunchedEffect(initialKindName, initialGenreId) {
        val requestedKind = initialKindName?.let { requested ->
            runCatching { DiscoverKind.valueOf(requested) }.getOrNull()
        }
        if (requestedKind != null) {
            kindName = requestedKind.name
            val targetFilters = when (requestedKind) {
                DiscoverKind.MOVIES -> movieFilters
                DiscoverKind.SERIES -> seriesFilters
                DiscoverKind.ANIME -> animeFilters
            }
            targetFilters.genres = initialGenreId?.let(::setOf) ?: emptySet()
            targetFilters.providers = emptySet()
            targetFilters.year = null
            targetFilters.minimumVotes = 0
            targetFilters.status = "any"
            targetFilters.sort = DiscoverSort.POPULAR
        }
    }

    LaunchedEffect(homeViewModel) { homeViewModel.ensureLoaded() }
    LaunchedEffect(kind, moviesViewModel, seriesViewModel, animeViewModel) {
        when (kind) {
            DiscoverKind.MOVIES -> moviesViewModel.ensureLoaded()
            DiscoverKind.SERIES -> seriesViewModel.ensureLoaded()
            DiscoverKind.ANIME -> animeViewModel.ensureLoaded()
        }
    }

    val request = remember(
        kind, filters.sort, filters.genres, filters.providers,
        filters.year, filters.minimumVotes, filters.status, region
    ) {
        MediaDiscoverRequest(
            sortBy = filters.sort.apiValue,
            genreIds = filters.genres.sorted(),
            year = filters.year,
            minimumVoteCount = filters.minimumVotes,
            providerIds = filters.providers.sorted(),
            watchRegion = region,
            status = filters.status.takeIf { it != "any" },
            animeOnly = kind == DiscoverKind.ANIME
        )
    }
    val queryKey = remember(kind, request) { "$kind|$request" }
    val firstPageCache = remember { mutableStateMapOf<String, DiscoverLoadedPage>() }
    var counting by remember { mutableStateOf(false) }
    var matchCount by remember { mutableStateOf<Int?>(null) }
    var countError by remember { mutableStateOf<String?>(null) }

    var showingResults by rememberSaveable { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyList<HomeMixedMediaItem>()) }
    var resultsTotal by remember { mutableStateOf(0) }
    var resultsPage by remember { mutableStateOf(1) }
    var resultsPages by remember { mutableStateOf(1) }
    var loadingResults by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var resultsError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(queryKey) {
        delay(450)
        counting = true
        countError = null
        matchCount = null
        try {
            val loaded = fetchDiscoverPage(homeViewModel, kind, request.copy(page = 1))
            firstPageCache[queryKey] = loaded
            matchCount = loaded.totalResults
        } catch (error: Exception) {
            countError = error.message
        } finally {
            counting = false
        }
    }

    fun openResults() {
        val cached = firstPageCache[queryKey]
        if (cached != null) {
            results = cached.items
            resultsTotal = cached.totalResults
            resultsPages = cached.totalPages
            resultsPage = 1
            resultsError = null
            showingResults = true
            return
        }
        scope.launch {
            loadingResults = true
            resultsError = null
            showingResults = true
            try {
                val loaded = fetchDiscoverPage(homeViewModel, kind, request.copy(page = 1))
                firstPageCache[queryKey] = loaded
                results = loaded.items
                resultsTotal = loaded.totalResults
                resultsPages = loaded.totalPages
                resultsPage = 1
            } catch (error: Exception) {
                resultsError = error.message ?: context.getString(R.string.discover_load_failed)
            } finally {
                loadingResults = false
            }
        }
    }

    fun loadMoreResults() {
        if (loadingMore || resultsPage >= resultsPages) return
        scope.launch {
            loadingMore = true
            try {
                val next = fetchDiscoverPage(homeViewModel, kind, request.copy(page = resultsPage + 1))
                results = (results + next.items).distinctBy { "${it.contentType}:${it.id}" }
                resultsPage = next.items.let { if (it.isNotEmpty()) resultsPage + 1 else resultsPages }
                resultsPages = next.totalPages
            } catch (error: Exception) {
                resultsError = error.message ?: context.getString(R.string.discover_load_failed)
            } finally {
                loadingMore = false
            }
        }
    }

    BackHandler(enabled = showingResults) { showingResults = false }

    if (showingResults) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showingResults = false }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.discover_results), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.discover_result_count, NumberFormat.getIntegerInstance().format(resultsTotal)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            when {
                loadingResults -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                resultsError != null && results.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(resultsError.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
                results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 110.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(results, key = { "${it.contentType}:${it.id}" }) { item ->
                        DiscoverResultCard(
                            item = item,
                            onClick = { if (item.isMovie) onMovieClick(item.id) else onSeriesClick(item.id) }
                        )
                    }
                    if (resultsPage < resultsPages) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Button(
                                onClick = ::loadMoreResults,
                                enabled = !loadingMore,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                            ) {
                                if (loadingMore) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                                else Text(stringResource(R.string.discover_load_more))
                            }
                        }
                    }
                    if (resultsError != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(resultsError.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
            }
        }
        return
    }

    val navigationBarInset = with(LocalDensity.current) {
        WindowInsets.navigationBars.getBottom(this).toDp()
    }
    // The results action is a first-class layer above the secondary and primary docks.
    // Reserve the exact dock stack height so it can never be hidden behind either capsule.
    val primaryDockFootprint = 54.dp + 5.dp
    val secondaryDockHeight = with(LocalDensity.current) {
        (16.dp + 1.dp + (9.sp.toDp() + 4.dp).coerceAtLeast(14.dp) + 7.dp).coerceAtLeast(46.dp)
    }
    val secondaryDockFootprint by animateDpAsState(
        targetValue = if (secondaryDockOpen) secondaryDockHeight + 5.dp else 0.dp,
        animationSpec = tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "discover_action_secondary_dock_offset"
    )
    val resultsActionBottom = navigationBarInset + 12.dp + primaryDockFootprint + secondaryDockFootprint

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = resultsActionBottom + 72.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.browse_with_filters),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 10.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        DiscoverKind.MOVIES to stringResource(R.string.movies),
                        DiscoverKind.SERIES to stringResource(R.string.series),
                        DiscoverKind.ANIME to stringResource(R.string.anime)
                    ).forEach { (value, label) ->
                        val selected = kind == value
                        Surface(
                            modifier = Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(17.dp))
                                .clickable {
                                    if (kind != value) {
                                        kindName = value.name
                                        showingResults = false
                                    }
                                },
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(17.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    label,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            item {
                DiscoverSection(stringResource(R.string.sort_by)) {
                    val options = listOf(
                        Triple(DiscoverSort.POPULAR, stringResource(R.string.sort_popular), Icons.Default.Whatshot),
                        Triple(DiscoverSort.LEAST_POPULAR, stringResource(R.string.sort_least_popular), Icons.Default.TrendingDown),
                        Triple(DiscoverSort.TOP_RATED, stringResource(R.string.sort_top_rated), Icons.Outlined.Star),
                        Triple(DiscoverSort.LOWEST_RATED, stringResource(R.string.sort_lowest_rated), Icons.Outlined.StarHalf)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        options.chunked(2).forEach { rowOptions ->
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                rowOptions.forEach { (value, label, icon) ->
                                    DiscoverSortTile(
                                        modifier = Modifier.weight(1f),
                                        label = label,
                                        icon = icon,
                                        selected = filters.sort == value,
                                        onClick = { filters.sort = value }
                                    )
                                }
                                if (rowOptions.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            item {
                DiscoverSection(stringResource(R.string.genres)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        genres.forEach { genre ->
                            DiscoverPill(
                                label = genre.name,
                                selected = genre.id in filters.genres,
                                onClick = {
                                    filters.genres = if (genre.id in filters.genres) filters.genres - genre.id else filters.genres + genre.id
                                }
                            )
                        }
                    }
                }
            }

            item {
                DiscoverSection(stringResource(R.string.streaming_on)) {
                    BoxWithConstraints {
                        val columns = if (maxWidth >= 700.dp) 5 else 3
                        val spacing = 10.dp
                        val tileWidth = (maxWidth - spacing * (columns - 1)) / columns
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(spacing),
                            verticalArrangement = Arrangement.spacedBy(spacing)
                        ) {
                            // Keep the exact provider order and TMDB IDs from
                            // FlixQuest's DiscoverQuery. Only the tile geometry
                            // changes here; filter semantics remain untouched.
                            for (provider in homeStreamingServices.distinctBy { it.providerId }) {
                                val selected = provider.providerId in filters.providers
                                Box(
                                    modifier = Modifier
                                        .width(tileWidth)
                                        .height(tileWidth * 0.58f)
                                        .border(
                                            BorderStroke(
                                                2.dp,
                                                if (selected) MaterialTheme.colorScheme.onBackground
                                                else Color.Transparent
                                            ),
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            filters.providers = if (selected) {
                                                filters.providers - provider.providerId
                                            } else {
                                                filters.providers + provider.providerId
                                            }
                                        }
                                        .padding(3.dp)
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(Color(0xFFF2F2F3))
                                ) {
                                    Image(
                                        painter = painterResource(id = provider.logoResId),
                                        contentDescription = provider.name,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                    if (provider.name.endsWith("Kids")) {
                                        Text(
                                            text = "KIDS",
                                            color = Color(0xFF141516),
                                            fontSize = 9.sp,
                                            lineHeight = 10.sp,
                                            letterSpacing = 1.2.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .padding(start = 6.dp, bottom = 4.dp)
                                        )
                                    }
                                    if (selected) {
                                        Surface(
                                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp),
                                            shape = RoundedCornerShape(50),
                                            color = Color(0xFF141516)
                                        ) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.padding(3.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                DiscoverSection(stringResource(R.string.year), horizontalPadding = false) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item(key = "any") {
                            DiscoverPill(stringResource(R.string.any_year), filters.year == null) { filters.year = null }
                        }
                        items(items = yearOptions, key = { year -> year }) { year ->
                            DiscoverPill(year.toString(), filters.year == year) {
                                filters.year = if (filters.year == year) null else year
                            }
                        }
                    }
                }
            }

            item {
                DiscoverSection(stringResource(R.string.minimum_ratings)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        listOf(0, 100, 1_000, 5_000, 10_000, 20_000).forEach { threshold ->
                            val label = when (threshold) {
                                0 -> stringResource(R.string.any_filter)
                                1_000 -> "1K+ ratings"
                                5_000 -> "5K+ ratings"
                                10_000 -> "10K+ ratings"
                                20_000 -> "20K+ ratings"
                                else -> "$threshold+ ratings"
                            }
                            DiscoverPill(label, filters.minimumVotes == threshold) { filters.minimumVotes = threshold }
                        }
                    }
                }
            }

            if (kind != DiscoverKind.MOVIES) {
                item {
                    DiscoverSection(
                        if (kind == DiscoverKind.ANIME) stringResource(R.string.anime_status) else stringResource(R.string.tv_series_status)
                    ) {
                        val statuses = listOf(
                            "any" to stringResource(R.string.any_status),
                            "0" to stringResource(R.string.returning_series),
                            "1" to stringResource(R.string.planned),
                            "2" to stringResource(R.string.in_production),
                            "3" to stringResource(R.string.ended),
                            "4" to stringResource(R.string.canceled),
                            "5" to stringResource(R.string.pilot)
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            statuses.forEach { (value, label) ->
                                DiscoverPill(label, filters.status == value) { filters.status = value }
                            }
                        }
                    }
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .zIndex(2f)
                .padding(start = 16.dp, end = 16.dp, bottom = resultsActionBottom, top = 8.dp)
                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(14.dp))
                .border(BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f)), RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            val knownMatchCount = matchCount
            Button(
                onClick = ::openResults,
                enabled = !counting && knownMatchCount != 0,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (counting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            knownMatchCount == 0 -> stringResource(R.string.no_titles_match)
                            knownMatchCount != null -> stringResource(R.string.discover_show_titles, NumberFormat.getIntegerInstance().format(knownMatchCount))
                            else -> stringResource(R.string.discover_show_results)
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
            if (countError != null) {
                Text(stringResource(R.string.discover_count_unavailable), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private suspend fun fetchDiscoverPage(
    viewModel: HomeViewModel,
    kind: DiscoverKind,
    request: MediaDiscoverRequest
): DiscoverLoadedPage = when (kind) {
    DiscoverKind.MOVIES -> {
        val result = viewModel.discoverMovies(request)
        DiscoverLoadedPage(
            result.items.map { movie ->
                HomeMixedMediaItem(movie.id, movie.title, movie.posterUrl, movie.year, movie.rating, true, ContentType.MOVIE, movie.backdropUrl, movie.genreIds)
            }, result.totalPages, result.totalResults
        )
    }
    DiscoverKind.SERIES, DiscoverKind.ANIME -> {
        val result = viewModel.discoverSeries(request)
        DiscoverLoadedPage(
            result.items.map { series ->
                val contentType = if (kind == DiscoverKind.ANIME) ContentType.ANIME else ContentTypeResolver.resolveSeries(series)
                HomeMixedMediaItem(series.id, series.title, series.posterUrl, series.year, series.rating, false, contentType, series.backdropUrl, series.genreIds)
            }, result.totalPages, result.totalResults
        )
    }
}

@Composable
private fun DiscoverSection(
    title: String,
    horizontalPadding: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 18.dp)
        )
        Spacer(Modifier.height(13.dp))
        val childModifier = if (horizontalPadding) Modifier.padding(horizontal = 18.dp) else Modifier
        Column(childModifier, content = content)
    }
}

@Composable
private fun DiscoverPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            label,
            maxLines = 1,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
        )
    }
}

@Composable
private fun DiscoverSortTile(
    modifier: Modifier,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.height(64.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall
            )
        }
    }
}

@Composable
private fun DiscoverResultCard(item: HomeMixedMediaItem, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        AsyncImage(
            model = item.posterUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))
        )
        Spacer(Modifier.height(7.dp))
        Text(item.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            buildList {
                if (item.year > 0) add(item.year.toString())
                if (item.rating > 0) add("★ ${"%.1f".format(Locale.getDefault(), item.rating)}")
            }.joinToString(" · "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall
        )
    }
}
