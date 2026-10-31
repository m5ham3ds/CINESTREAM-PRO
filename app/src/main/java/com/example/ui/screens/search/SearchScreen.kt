package com.example.ui.screens.search

import android.content.Context
import org.json.JSONArray
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.ContentType
import com.example.data.model.LibraryItem
import com.example.data.repository.LibraryRepository
import com.example.data.util.ContentTypeResolver
import com.example.data.util.TmdbGenreHelper
import com.example.domain.models.Genre
import com.example.domain.search.MediaDisplayTitleResolver
import com.example.ui.ViewModelFactory
import com.example.ui.components.rememberBriefPullRefreshIndicator
import kotlinx.coroutines.launch

/** A canonical result row shared by the search list and its favourite action. */
data class UnifiedMediaResult(
    val id: String,
    val title: String,
    val canonicalTitle: String,
    val posterUrl: String,
    val isMovie: Boolean,
    val isAnime: Boolean,
    val isDubbed: Boolean,
    val isUnverified: Boolean,
    val variantMode: String,
    val providerId: String?,
    val contentType: String,
    val canonicalPosterUrl: String? = null,
    val providerPosterUrl: String? = null
)

private object SearchHistoryStore {
    private const val PREFS = "cinestream_search_history"
    private const val KEY_QUERIES = "queries"
    private const val LIMIT = 12

    fun read(context: Context): List<String> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_QUERIES, "[]") ?: "[]"
        val json = JSONArray(raw)
        buildList {
            for (index in 0 until json.length()) {
                json.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }.distinct().take(LIMIT)
    }.getOrDefault(emptyList())

    fun add(context: Context, query: String): List<String> {
        val normalized = query.trim().replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return read(context)
        val updated = (listOf(normalized) + read(context).filterNot { it.equals(normalized, true) })
            .take(LIMIT)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_QUERIES, JSONArray(updated).toString())
            .apply()
        return updated
    }

    fun remove(context: Context, query: String): List<String> {
        val updated = read(context).filterNot { it == query }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_QUERIES, JSONArray(updated).toString())
            .apply()
        return updated
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_QUERIES)
            .apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    onMediaClick: (String, Boolean) -> Unit = { _, _ -> },
    onMediaClickWithMode: ((String, Boolean, String) -> Unit)? = null,
    onAnimeClick: ((String) -> Unit)? = null,
    onPlayMovie: ((String) -> Unit)? = null,
    onPlaySeries: ((String) -> Unit)? = null,
    onPlayAnime: ((String) -> Unit)? = null,
    onNavigateToDiscover: (String?, Int?) -> Unit = { _, _ -> },
    viewModel: SearchViewModel = viewModel(factory = ViewModelFactory())
) {
    val uiState by viewModel.uiState.collectAsState()
    var searchQuery by rememberSaveable { mutableStateOf(uiState.query) }
    var selectedItemForFavorite by remember { mutableStateOf<UnifiedMediaResult?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val libraryRepository = remember { LibraryRepository(context) }
    var recentQueries by remember(context) { mutableStateOf(SearchHistoryStore.read(context)) }
    val ptrState = rememberPullToRefreshState()
    val pullRefreshIndicator = rememberBriefPullRefreshIndicator()

    LaunchedEffect(uiState.query) {
        if (searchQuery != uiState.query) searchQuery = uiState.query
    }
    LaunchedEffect(viewModel) { viewModel.initializeIfNeeded() }

    val movieGenres = remember(uiState.movieGenres) {
        uiState.movieGenres.ifEmpty { TmdbGenreHelper.getMovieGenres() }
    }
    val seriesGenres = remember(uiState.tvGenres) {
        uiState.tvGenres.ifEmpty { TmdbGenreHelper.getTvGenres() }
    }
    val animeGenres = remember(uiState.animeGenres) {
        uiState.animeGenres.ifEmpty { TmdbGenreHelper.getAnimeGenres() }
    }

    val searchResults = remember(
        uiState.filteredMovieResults,
        uiState.filteredSeriesResults,
        uiState.isDubbedIntent
    ) {
        val movieItems = uiState.filteredMovieResults.map { movie ->
            val info = MediaDisplayTitleResolver.resolveForMovie(movie, isDubbedIntent = uiState.isDubbedIntent)
            val dubbed = info.isConfirmedDubbed
            val unverified = !dubbed && uiState.isDubbedIntent && SearchViewModel.hasUnverifiedCandidate(movie)
            UnifiedMediaResult(
                id = movie.id,
                title = info.displayTitle,
                canonicalTitle = movie.title,
                posterUrl = info.displayPosterUrl ?: movie.posterUrl,
                isMovie = true,
                isAnime = false,
                isDubbed = dubbed,
                isUnverified = unverified,
                variantMode = if (dubbed) "dubbed" else "subtitled",
                providerId = info.providerId ?: movie.providerId,
                contentType = ContentType.MOVIE,
                canonicalPosterUrl = movie.posterUrl,
                providerPosterUrl = info.providerPosterUrl
            )
        }
        val seriesItems = uiState.filteredSeriesResults.map { series ->
            val info = MediaDisplayTitleResolver.resolveForSeries(series, isDubbedIntent = uiState.isDubbedIntent)
            val dubbed = info.isConfirmedDubbed
            val unverified = !dubbed && uiState.isDubbedIntent && SearchViewModel.hasUnverifiedCandidate(series)
            val isAnime = ContentTypeResolver.resolveSeries(series) == ContentType.ANIME
            UnifiedMediaResult(
                id = series.id,
                title = info.displayTitle,
                canonicalTitle = series.title,
                posterUrl = info.displayPosterUrl ?: series.posterUrl,
                isMovie = false,
                isAnime = isAnime,
                isDubbed = dubbed,
                isUnverified = unverified,
                variantMode = if (dubbed) "dubbed" else "subtitled",
                providerId = info.providerId ?: series.providerId,
                contentType = if (isAnime) ContentType.ANIME else ContentType.TV,
                canonicalPosterUrl = series.posterUrl,
                providerPosterUrl = info.providerPosterUrl
            )
        }
        val combined = movieItems + seriesItems
        if (uiState.isDubbedIntent) {
            val trigger = com.example.domain.search.SearchTriggerParser.parse(uiState.query)
            com.example.domain.search.DubbedSearchRankingEngine.rankUnifiedMedia(
                items = combined,
                cleanQuery = trigger.cleanQuery,
                isDubbedIntent = true,
                titleExtractor = { it.title },
                canonicalTitleExtractor = { it.canonicalTitle },
                isDubbedExtractor = { it.isDubbed }
            )
        } else combined
    }

    fun hideKeyboard() {
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    fun rememberQuery(query: String) {
        recentQueries = SearchHistoryStore.add(context, query)
    }

    fun submit(query: String) {
        val value = query.trim()
        if (value.isBlank()) return
        rememberQuery(value)
        hideKeyboard()
        searchQuery = value
        viewModel.submitSearch(value)
    }

    fun openTopSuggestion(item: SearchSuggestionItem, play: Boolean) {
        if (play) {
            when (item.mediaKind) {
                "MOVIE" -> (onPlayMovie ?: { id -> onMediaClick(id, true) })(item.id)
                "SERIES" -> (onPlaySeries ?: { id -> onMediaClick(id, false) })(item.id)
                "ANIME" -> (onPlayAnime ?: { id -> (onAnimeClick ?: { animeId -> onMediaClick(animeId, false) })(id) })(item.id)
            }
        } else {
            when (item.mediaKind) {
                "MOVIE" -> onMediaClick(item.id, true)
                "SERIES" -> onMediaClick(item.id, false)
                "ANIME" -> (onAnimeClick ?: { id -> onMediaClick(id, false) })(item.id)
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = pullRefreshIndicator.visible,
        onRefresh = { pullRefreshIndicator.start(); viewModel.refresh() },
        state = ptrState,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            stickyHeader(key = "search_input_header") {
                SearchInputHeader(
                    value = searchQuery,
                    onValueChange = { value ->
                        searchQuery = value
                        viewModel.onQueryChange(value)
                    },
                    onSubmit = { submit(searchQuery) },
                    onClear = {
                        searchQuery = ""
                        viewModel.onQueryChange("")
                        viewModel.clearFilters()
                    }
                )
            }

            if (searchQuery.isBlank()) {
                if (recentQueries.isNotEmpty()) {
                    item(key = "recent_searches") {
                        RecentSearchesSection(
                            queries = recentQueries,
                            onSearch = ::submit,
                            onRemove = { query -> recentQueries = SearchHistoryStore.remove(context, query) },
                            onClear = {
                                SearchHistoryStore.clear(context)
                                recentQueries = emptyList()
                            }
                        )
                    }
                }

                item(key = "browse_filters_cta") {
                    BrowseFiltersCard(onClick = { onNavigateToDiscover(null, null) })
                }

                if (uiState.topSearches.isNotEmpty()) {
                    item(key = "top_searches_title") {
                        SearchSectionTitle(title = stringResource(R.string.top_searches))
                    }
                    itemsIndexed(
                        items = uiState.topSearches,
                        key = { _, item -> "${item.mediaKind}:${item.id}" }
                    ) { _, item ->
                        TopSearchRow(
                            item = item,
                            onOpen = { openTopSuggestion(item, play = false) },
                            onPlay = { openTopSuggestion(item, play = true) }
                        )
                    }
                } else if (uiState.isLoadingTopSearches) {
                    item(key = "top_searches_loading") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }

                item(key = "movie_genres_section") {
                    GenreTilesSection(
                        title = stringResource(R.string.movie_genres),
                        genres = movieGenres,
                        onGenreClick = { genre -> onNavigateToDiscover("MOVIES", genre.id) }
                    )
                }
                item(key = "series_genres_section") {
                    GenreTilesSection(
                        title = stringResource(R.string.series_genres),
                        genres = seriesGenres,
                        onGenreClick = { genre -> onNavigateToDiscover("SERIES", genre.id) }
                    )
                }
                item(key = "anime_genres_section") {
                    GenreTilesSection(
                        title = stringResource(R.string.anime_genres),
                        genres = animeGenres,
                        onGenreClick = { genre -> onNavigateToDiscover("ANIME", genre.id) }
                    )
                }
            } else {
                item(key = "search_result_filters") {
                    SearchResultFilters(
                        selected = uiState.selectedContentType,
                        onSelected = viewModel::setContentTypeFilter
                    )
                }

                if (uiState.isSearching && searchResults.isEmpty()) {
                    item(key = "search_results_loading") {
                        com.example.ui.components.SearchScreenSkeleton()
                    }
                } else if (searchResults.isEmpty()) {
                    item(key = "search_no_results") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 64.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = stringResource(R.string.no_results),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                } else {
                    item(key = "search_results_summary") {
                        Text(
                            text = stringResource(R.string.results_for, searchQuery),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 8.dp)
                        )
                    }
                    items(
                        items = searchResults,
                        key = { "${it.contentType}:${it.id}:${it.variantMode}" }
                    ) { media ->
                        SearchResultRow(
                            media = media,
                            onClick = {
                                rememberQuery(searchQuery)
                                hideKeyboard()
                                if (media.isAnime) {
                                    com.example.data.repository.AnimePlaybackStore.markAsAnime(context, media.id)
                                    (onAnimeClick ?: { id -> onMediaClick(id, false) })(media.id)
                                } else {
                                    if (media.isMovie) {
                                        val movie = uiState.movieResults.firstOrNull { it.id == media.id }
                                            ?: uiState.filteredMovieResults.firstOrNull { it.id == media.id }
                                        if (movie != null) {
                                            val targetMode = if (media.isDubbed) "ARABIC_DUBBED" else "STANDARD"
                                            com.example.ui.screens.details.MovieDetailsViewModel.saveState(
                                                movie.id,
                                                com.example.ui.screens.details.MovieDetailsUiState(movie = movie, isLoading = false),
                                                experienceMode = targetMode
                                            )
                                            com.example.utils.MediaDetailsCacheManager.saveMovieDetails(
                                                context, movie, variantMode = media.variantMode
                                            )
                                        }
                                    } else {
                                        val series = uiState.seriesResults.firstOrNull { it.id == media.id }
                                            ?: uiState.filteredSeriesResults.firstOrNull { it.id == media.id }
                                        if (series != null) {
                                            val targetMode = if (media.isDubbed) "ARABIC_DUBBED" else "STANDARD"
                                            com.example.ui.screens.details.SeriesDetailsViewModel.saveState(
                                                series.id,
                                                com.example.ui.screens.details.SeriesDetailsUiState(series = series, isLoading = false),
                                                experienceMode = targetMode
                                            )
                                            com.example.utils.MediaDetailsCacheManager.saveSeriesDetails(
                                                context, series, variantMode = media.variantMode
                                            )
                                        }
                                    }
                                    val targetMode = if (media.isDubbed) "ARABIC_DUBBED" else "STANDARD"
                                    if (onMediaClickWithMode != null) {
                                        onMediaClickWithMode(media.id, media.isMovie, targetMode)
                                    } else onMediaClick(media.id, media.isMovie)
                                }
                            },
                            onLongClick = { selectedItemForFavorite = media }
                        )
                    }
                }
            }

            item(key = "search_bottom_inset") {
                Spacer(Modifier.height(112.dp))
            }
        }
    }

    if (selectedItemForFavorite != null) {
        val target = selectedItemForFavorite!!
        val inLibrary by libraryRepository.isItemInLibrary(
            target.id,
            target.contentType,
            target.variantMode
        ).collectAsState(initial = false)
        val actionText = stringResource(
            if (inLibrary) R.string.remove_from_favorites else R.string.add_to_library_favorites
        )
        AlertDialog(
            onDismissRequest = { selectedItemForFavorite = null },
            title = { Text(actionText, fontWeight = FontWeight.Bold) },
            text = { Text(target.title, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val item = LibraryItem.create(
                            contentType = target.contentType,
                            tmdbId = target.id,
                            title = target.title,
                            posterUrl = target.posterUrl,
                            variantMode = target.variantMode,
                            providerId = target.providerId ?: ""
                        )
                        scope.launch {
                            if (inLibrary) {
                                libraryRepository.removeFromLibrary(item)
                                android.widget.Toast.makeText(context, context.getString(R.string.removed_from_favorites), android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                libraryRepository.addToLibrary(item)
                                android.widget.Toast.makeText(context, context.getString(R.string.added_to_library), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                        selectedItemForFavorite = null
                    }
                ) { Text(actionText, color = if (inLibrary) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { selectedItemForFavorite = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

@Composable
private fun SearchInputHeader(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 1.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .height(58.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(25.dp))
            Spacer(Modifier.width(12.dp))
            TextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = {
                    Text(
                        text = stringResource(R.string.search_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp,
                        maxLines = 1
                    )
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() })
            )
            if (value.isNotEmpty()) {
                IconButton(onClick = onClear, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentSearchesSection(
    queries: List<String>,
    onSearch: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.recent_searches), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                Text(stringResource(R.string.clear_all), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            queries.forEach { query ->
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onSearch(query) }
                        .padding(start = 13.dp, end = 5.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(query, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(5.dp))
                    IconButton(onClick = { onRemove(query) }, modifier = Modifier.size(22.dp)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove), modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseFiltersCard(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.FilterAlt, contentDescription = null, modifier = Modifier.size(25.dp), tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.browse_with_filters), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(stringResource(R.string.browse_with_filters_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SearchSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp)
    )
}

@Composable
private fun TopSearchRow(
    item: SearchSuggestionItem,
    onOpen: () -> Unit,
    onPlay: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(128.dp)
                .height(72.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (item.imageUrl.isNotBlank()) {
                AsyncImage(
                    model = item.imageUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(4.dp))
            val kindLabel = when (item.mediaKind) {
                "MOVIE" -> stringResource(R.string.movie_singular)
                "ANIME" -> stringResource(R.string.anime)
                else -> stringResource(R.string.series_singular)
            }
            Text(
                text = buildString {
                    if (item.year > 0) append(item.year)
                    if (isNotEmpty()) append("  ·  ")
                    append(kindLabel)
                    if (item.rating > 0.0) append("  ·  ★ ${String.format(java.util.Locale.US, "%.1f", item.rating)}")
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onPlay, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Default.PlayCircleOutline, contentDescription = stringResource(R.string.play), modifier = Modifier.size(31.dp), tint = MaterialTheme.colorScheme.onBackground)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreTilesSection(
    title: String,
    genres: List<Genre>,
    onGenreClick: (Genre) -> Unit
) {
    if (genres.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        SearchSectionTitle(title)
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            // FlixQuest uses two compact, equal-width tiles per row on phones
            // (four on tablets), rather than oversized filter chips.
            val columns = if (maxWidth >= 700.dp) 4 else 2
            val gap = 10.dp
            val tileWidth = (maxWidth - gap * (columns - 1)) / columns
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = columns,
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                genres.forEach { genre ->
                    Row(
                        modifier = Modifier
                            .width(tileWidth)
                            .height(52.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable { onGenreClick(genre) }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            genre.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 15.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultFilters(
    selected: String,
    onSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            "ALL" to stringResource(R.string.filter_all),
            "MOVIE" to stringResource(R.string.filter_movies),
            "TV" to stringResource(R.string.filter_series),
            "ANIME" to stringResource(R.string.filter_anime)
        ).forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelected(value) },
                label = { Text(label, maxLines = 1) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchResultRow(
    media: UnifiedMediaResult,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 18.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = media.posterUrl,
            contentDescription = media.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(76.dp).height(108.dp).clip(RoundedCornerShape(10.dp))
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(media.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        media.isAnime -> stringResource(R.string.anime)
                        media.isMovie -> stringResource(R.string.movie_singular)
                        else -> stringResource(R.string.series_singular)
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (media.isDubbed) {
                    Spacer(Modifier.width(7.dp))
                    Text(stringResource(R.string.variant_dubbed), fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                } else if (media.isUnverified) {
                    Spacer(Modifier.width(7.dp))
                    Text(stringResource(R.string.dubbed_unverified), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}
