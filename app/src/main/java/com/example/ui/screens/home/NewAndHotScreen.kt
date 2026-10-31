package com.example.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import com.example.data.remote.RetrofitClient
import com.example.ui.ViewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.LinkedHashMap
import java.util.Locale

private enum class HotSection {
    COMING_SOON, EVERYONE_WATCHING, TOP_MOVIES, TOP_SERIES, TOP_ANIME
}

private data class HotEntry(
    val media: HomeMixedMediaItem,
    val overview: String,
    val releaseDate: String? = null
)

private data class HotDateGroup(val date: LocalDate, val entries: List<HotEntry>)

/**
 * FlixQuest-style New & Hot, backed only by CineStream's existing TMDB feeds.
 * Anime remains a separate media type in every segment and navigation action.
 */
@Composable
fun NewAndHotScreen(
    onMovieClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    onPlayMovie: (String) -> Unit = onMovieClick,
    onPlaySeries: (String) -> Unit = onSeriesClick,
    viewModel: HomeViewModel = viewModel(factory = ViewModelFactory())
) {
    val state by viewModel.uiState.collectAsState()
    var sectionName by rememberSaveable { mutableStateOf(HotSection.COMING_SOON.name) }
    val section = HotSection.valueOf(sectionName)

    LaunchedEffect(viewModel) {
        viewModel.ensureLoaded()
        viewModel.ensureCuratedFeedsLoaded()
    }

    val upcomingEntries = remember(state.upcomingMovies, state.upcomingSeries, state.upcomingAnime) {
        buildList {
            state.upcomingMovies.forEach { movie ->
                add(
                    HotEntry(
                        HomeMixedMediaItem(
                            movie.id, movie.title, movie.posterUrl, movie.year, movie.rating,
                            true, ContentType.MOVIE, movie.backdropUrl, movie.genreIds
                        ),
                        movie.overview,
                        movie.releaseDate
                    )
                )
            }
            state.upcomingSeries.forEach { series ->
                if (ContentTypeResolver.resolveSeries(series) != ContentType.ANIME) {
                    add(
                        HotEntry(
                            HomeMixedMediaItem(
                                series.id, series.title, series.posterUrl, series.year, series.rating,
                                false, ContentTypeResolver.resolveSeries(series), series.backdropUrl, series.genreIds
                            ),
                            series.overview,
                            series.firstAirDate
                        )
                    )
                }
            }
            state.upcomingAnime.forEach { anime ->
                add(
                    HotEntry(
                        HomeMixedMediaItem(
                            anime.id, anime.title, anime.posterUrl, anime.year, anime.rating,
                            false, ContentType.ANIME, anime.backdropUrl, anime.genreIds
                        ),
                        anime.overview,
                        anime.firstAirDate
                    )
                )
            }
        }.distinctBy { "${it.media.contentType}:${it.media.id}" }
            .mapNotNull { entry ->
                val date = runCatching { LocalDate.parse(entry.releaseDate?.take(10)) }.getOrNull()
                if (date == null || date.isBefore(LocalDate.now())) null else date to entry
            }
            .sortedWith(compareBy<Pair<LocalDate, HotEntry>> { it.first }.thenByDescending { it.second.media.rating })
            .groupBy({ it.first }, { it.second })
            .map { (date, entries) -> HotDateGroup(date, entries) }
    }

    val everyoneWatching = remember(state.trendingMovies, state.trendingSeries, state.trendingAnime) {
        val lists = listOf(
            state.trendingMovies.map { movie ->
                HotEntry(
                    HomeMixedMediaItem(movie.id, movie.title, movie.posterUrl, movie.year, movie.rating, true, ContentType.MOVIE, movie.backdropUrl, movie.genreIds),
                    movie.overview
                )
            },
            state.trendingSeries.filter { ContentTypeResolver.resolveSeries(it) != ContentType.ANIME }.map { series ->
                HotEntry(
                    HomeMixedMediaItem(series.id, series.title, series.posterUrl, series.year, series.rating, false, ContentTypeResolver.resolveSeries(series), series.backdropUrl, series.genreIds),
                    series.overview
                )
            },
            state.trendingAnime.map { anime ->
                HotEntry(
                    HomeMixedMediaItem(anime.id, anime.title, anime.posterUrl, anime.year, anime.rating, false, ContentType.ANIME, anime.backdropUrl, anime.genreIds),
                    anime.overview
                )
            }
        )
        buildList {
            val maxSize = lists.maxOfOrNull { it.size } ?: 0
            for (index in 0 until maxSize) {
                lists.forEach { list -> if (index < list.size) add(list[index]) }
            }
        }.distinctBy { "${it.media.contentType}:${it.media.id}" }.take(20)
    }

    val topMovies = remember(state.trendingMovies, state.topRatedMovies) {
        (state.trendingMovies.ifEmpty { state.topRatedMovies })
            .distinctBy { it.id }.take(10).map { movie ->
                HotEntry(
                    HomeMixedMediaItem(movie.id, movie.title, movie.posterUrl, movie.year, movie.rating, true, ContentType.MOVIE, movie.backdropUrl, movie.genreIds),
                    movie.overview
                )
            }
    }
    val topSeries = remember(state.trendingSeries, state.topRatedSeries) {
        (state.trendingSeries.filter { ContentTypeResolver.resolveSeries(it) != ContentType.ANIME }
            .ifEmpty { state.topRatedSeries.filter { ContentTypeResolver.resolveSeries(it) != ContentType.ANIME } })
            .distinctBy { it.id }.take(10).map { series ->
                HotEntry(
                    HomeMixedMediaItem(series.id, series.title, series.posterUrl, series.year, series.rating, false, ContentTypeResolver.resolveSeries(series), series.backdropUrl, series.genreIds),
                    series.overview
                )
            }
    }
    val sections = listOf(
        HotSection.COMING_SOON to stringResource(R.string.coming_soon),
        HotSection.EVERYONE_WATCHING to stringResource(R.string.everyone_watching),
        HotSection.TOP_MOVIES to stringResource(R.string.top_10_movies),
        HotSection.TOP_SERIES to stringResource(R.string.top_10_series),
        HotSection.TOP_ANIME to stringResource(R.string.top_10_anime)
    )

    val topAnime = remember(state.trendingAnime, state.topRatedAnime) {
        (state.trendingAnime.ifEmpty { state.topRatedAnime })
            .distinctBy { it.id }.take(10).map { anime ->
                HotEntry(
                    HomeMixedMediaItem(anime.id, anime.title, anime.posterUrl, anime.year, anime.rating, false, ContentType.ANIME, anime.backdropUrl, anime.genreIds),
                    anime.overview
                )
            }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.new_and_hot),
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 18.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            items(sections, key = { it.first.name }) { (value, label) ->
                val selected = section == value
                Surface(
                    onClick = { sectionName = value.name },
                    shape = RoundedCornerShape(50),
                    color = if (selected) Color(0xFFF2F2F3) else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (selected) Color(0xFF141516) else MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Text(
                        text = label,
                        maxLines = 1,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        androidx.compose.animation.AnimatedContent(
            targetState = section,
            transitionSpec = {
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
            },
            label = "new_hot_content",
            modifier = Modifier.weight(1f)
        ) { selected ->
            when (selected) {
                HotSection.COMING_SOON -> {
                    if (upcomingEntries.isEmpty()) {
                        HotEmptyState(isLoading = state.isLoading, error = state.error)
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 110.dp),
                            verticalArrangement = Arrangement.spacedBy(28.dp)
                        ) {
                            items(upcomingEntries, key = { it.date.toString() }) { group ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Column(
                                        modifier = Modifier.width(68.dp).padding(top = 2.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = group.date.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault()))
                                                .uppercase(Locale.getDefault()),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                letterSpacing = 2.1.sp,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            maxLines = 1
                                        )
                                        Text(
                                            text = group.date.dayOfMonth.toString(),
                                            color = MaterialTheme.colorScheme.onBackground,
                                            fontSize = 42.sp,
                                            lineHeight = 44.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            maxLines = 1
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(24.dp)
                                    ) {
                                        group.entries.forEach { entry ->
                                            ComingSoonFeatureCard(
                                                entry = entry,
                                                date = group.date,
                                                onOpen = { openEntry(entry, onMovieClick, onSeriesClick) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                HotSection.EVERYONE_WATCHING -> {
                    if (everyoneWatching.isEmpty()) {
                        HotEmptyState(isLoading = state.isLoading, error = state.error)
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 110.dp),
                            verticalArrangement = Arrangement.spacedBy(28.dp)
                        ) {
                            items(everyoneWatching, key = { "${it.media.contentType}:${it.media.id}" }) { entry ->
                                HotFeatureCard(
                                    entry = entry,
                                    showPlay = true,
                                    onOpen = { openEntry(entry, onMovieClick, onSeriesClick) },
                                    onPlay = { playEntry(entry, onPlayMovie, onPlaySeries) }
                                )
                            }
                        }
                    }
                }
                HotSection.TOP_MOVIES -> RankedHotList(topMovies, isLoading = state.isLoading, onMovieClick, onSeriesClick)
                HotSection.TOP_SERIES -> RankedHotList(topSeries, isLoading = state.isLoading, onMovieClick, onSeriesClick)
                HotSection.TOP_ANIME -> RankedHotList(topAnime, isLoading = state.isLoading, onMovieClick, onSeriesClick)
            }
        }
    }
}

/**
 * Small session cache for the optional logo artwork used on Coming Soon.
 * The lookup follows FlixQuest's /movie|tv/{id} images.logo selection rule,
 * but uses CineStream's existing authenticated Cloudflare TMDB gateway.
 */
private object HotTitleLogoCache {
    private const val MAX_ENTRIES = 240
    private val cache = LinkedHashMap<String, String?>(32, 0.75f, true)

    suspend fun resolve(media: HomeMixedMediaItem, language: String): String? {
        val numericId = media.id.toIntOrNull() ?: return null
        val languageCode = language.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
        val key = "${media.contentType}:$numericId:$languageCode"
        synchronized(cache) {
            if (cache.containsKey(key)) return cache[key]
        }

        val logo = try {
            withContext(Dispatchers.IO) {
                val tmdbLanguage = if (languageCode == "ar") "ar-SA" else "en-US"
                val images = if (media.contentType == ContentType.MOVIE) {
                    RetrofitClient.tmdbApi.getMovieDetails(
                        numericId,
                        language = tmdbLanguage,
                        appendToResponse = "images"
                    ).data?.images
                } else {
                    RetrofitClient.tmdbApi.getSeriesDetails(
                        numericId,
                        language = tmdbLanguage,
                        appendToResponse = "images"
                    ).data?.images
                }
                val candidates = images?.logos.orEmpty().filter { image ->
                    val path = image.filePath.orEmpty()
                    path.isNotBlank() && !path.lowercase(Locale.ROOT).endsWith(".svg") &&
                        (image.language == languageCode || image.language == "en" || image.language == null)
                }
                fun languageTier(code: String?): Int = when (code) {
                    languageCode -> 0
                    "en" -> 1
                    null -> 2
                    else -> 3
                }
                candidates.sortedWith(
                    compareBy<com.example.data.remote.TmdbImage> { languageTier(it.language) }
                        .thenBy { image ->
                            val width = image.width?.toDouble() ?: 0.0
                            val height = image.height?.toDouble() ?: 0.0
                            if (height > 0.0 && width / height < 1.0) 1 else 0
                        }
                        .thenByDescending { it.voteAverage ?: 0.0 }
                ).firstOrNull()?.fullUrl("w500")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Do not cache a network failure; a later visit may retry it.
            return null
        }

        // Cache successful empty lookups as well, so titles with no TMDB logo
        // do not generate a new request on every recomposition.
        synchronized(cache) {
            cache[key] = logo
            while (cache.size > MAX_ENTRIES) {
                val firstKey = cache.entries.iterator().let { if (it.hasNext()) it.next().key else null }
                if (firstKey == null) break
                cache.remove(firstKey)
            }
        }
        return logo
    }
}

@Composable
private fun ComingSoonFeatureCard(
    entry: HotEntry,
    date: LocalDate,
    onOpen: () -> Unit
) {
    val item = entry.media
    val context = LocalContext.current
    val language = remember(context) { TmdbGenreHelper.getCurrentLanguage(context) }
    val cacheKey = remember(item.id, item.contentType, language) {
        "${item.contentType}:${item.id}:$language"
    }
    var logoUrl by remember(cacheKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(cacheKey) {
        logoUrl = HotTitleLogoCache.resolve(item, language)
    }

    val genreNames = remember(item.genreIds, item.contentType, language) {
        when (item.contentType) {
            ContentType.MOVIE -> TmdbGenreHelper.getMovieGenreNames(item.genreIds, language)
            ContentType.ANIME -> TmdbGenreHelper.getAnimeGenres(language)
                .filter { it.id in item.genreIds }.map { it.name }
            else -> TmdbGenreHelper.getTvGenreNames(item.genreIds, language)
        }
    }
    val typeLabel = when (item.contentType) {
        ContentType.MOVIE -> stringResource(R.string.movie_singular)
        ContentType.ANIME -> stringResource(R.string.anime)
        else -> stringResource(R.string.series_singular)
    }
    val dateLabel = date.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
    val premiereText = stringResource(
        if (item.contentType == ContentType.MOVIE) R.string.coming_date else R.string.premieres_date,
        dateLabel
    )

    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        AsyncImage(
            model = item.backdropUrl.ifBlank { item.posterUrl },
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
        )
        Spacer(Modifier.height(12.dp))
        if (!logoUrl.isNullOrBlank()) {
            AsyncImage(
                model = logoUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                modifier = Modifier.fillMaxWidth(0.88f).height(48.dp)
            )
        } else {
            Text(
                text = item.title,
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = premiereText.uppercase(Locale.getDefault()),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 12.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Bold
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = (listOf(typeLabel) + genreNames).joinToString(" · "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (entry.overview.isNotBlank()) {
            Spacer(Modifier.height(9.dp))
            Text(
                text = entry.overview,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600) 4 else 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(14.dp))
        HotMyListButton(item)
    }
}

@Composable
private fun HotFeatureCard(
    entry: HotEntry,
    showPlay: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit
) {
    val item = entry.media
    Column(Modifier.fillMaxWidth()) {
        AsyncImage(
            model = item.backdropUrl.ifBlank { item.posterUrl },
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen)
        )
        Spacer(Modifier.height(13.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = onOpen)
        )
        Spacer(Modifier.height(6.dp))
        val typeLabel = when (item.contentType) {
            ContentType.MOVIE -> stringResource(R.string.movie_singular)
            ContentType.ANIME -> stringResource(R.string.anime)
            else -> stringResource(R.string.series_singular)
        }
        Text(
            text = buildList {
                add(typeLabel)
                if (item.year > 0) add(item.year.toString())
                if (item.rating > 0.0) add("★ ${"%.1f".format(Locale.getDefault(), item.rating)}")
            }.joinToString(" · "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        if (entry.overview.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = entry.overview,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .82f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showPlay) {
                Button(
                    onClick = onPlay,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    androidx.compose.material3.Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(R.string.play), fontWeight = FontWeight.Bold)
                }
            }
            HotMyListButton(item)
        }
    }
}

@Composable
private fun HotMyListButton(item: HomeMixedMediaItem) {
    val context = LocalContext.current
    val repository = remember(context) { LibraryRepository(context) }
    val scope = rememberCoroutineScope()
    val libraryType = when (item.contentType) {
        ContentType.MOVIE -> ContentType.MOVIE
        ContentType.ANIME -> ContentType.ANIME
        else -> ContentType.TV
    }
    val saved by repository.isItemInLibrary(item.id, libraryType, "subtitled").collectAsState(initial = false)
    var busy by remember(item.id) { mutableStateOf(false) }
    Button(
        onClick = {
            if (!busy) scope.launch {
                busy = true
                try {
                    val libraryItem = LibraryItem.create(
                        contentType = libraryType,
                        tmdbId = item.id,
                        title = item.title,
                        posterUrl = item.posterUrl,
                        variantMode = "subtitled"
                    )
                    if (saved) repository.removeFromLibrary(libraryItem)
                    else repository.addToLibrary(libraryItem)
                } finally {
                    busy = false
                }
            }
        },
        enabled = !busy,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            androidx.compose.material3.Icon(
                if (saved) Icons.Default.Check else Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(stringResource(if (saved) R.string.in_my_list else R.string.my_list))
    }
}

@Composable
private fun RankedHotList(
    entries: List<HotEntry>,
    isLoading: Boolean,
    onMovieClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit
) {
    if (entries.isEmpty()) {
        HotEmptyState(isLoading = isLoading, error = null)
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 110.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        items(entries.size, key = { index -> "${entries[index].media.contentType}:${entries[index].media.id}" }) { index ->
            val entry = entries[index]
            val item = entry.media
            Row(
                modifier = Modifier.fillMaxWidth().clickable {
                    openEntry(entry, onMovieClick, onSeriesClick)
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = (index + 1).toString(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .78f),
                    fontSize = 82.sp,
                    lineHeight = 88.sp,
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.displayLarge.copy(drawStyle = Stroke(width = 1.25f)),
                    modifier = Modifier.width(66.dp),
                    maxLines = 1
                )
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(84.dp).aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(9.dp))
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        buildList {
                            if (item.year > 0) add(item.year.toString())
                            if (item.rating > 0.0) add("★ ${"%.1f".format(Locale.getDefault(), item.rating)}")
                        }.joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (entry.overview.isNotBlank()) {
                        Text(entry.overview, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun HotEmptyState(isLoading: Boolean, error: String?) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        when {
            isLoading -> CircularProgressIndicator()
            !error.isNullOrBlank() -> Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("▱", fontSize = 38.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.new_and_hot_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun openEntry(
    entry: HotEntry,
    onMovieClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit
) {
    if (entry.media.isMovie) onMovieClick(entry.media.id) else onSeriesClick(entry.media.id)
}

private fun playEntry(
    entry: HotEntry,
    onPlayMovie: (String) -> Unit,
    onPlaySeries: (String) -> Unit
) {
    if (entry.media.isMovie) onPlayMovie(entry.media.id) else onPlaySeries(entry.media.id)
}
