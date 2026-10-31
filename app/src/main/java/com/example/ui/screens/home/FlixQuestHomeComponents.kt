@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.ContentType
import com.example.data.util.ContentTypeResolver
import com.example.ui.components.MediaCard

internal enum class HomeFeedFilter { ALL, MOVIES, SERIES }

internal data class HomeStreamingService(val providerId: Int, val name: String, val logoResId: Int)

internal val homeStreamingServices = listOf(
    HomeStreamingService(8, "Netflix", R.drawable.home_provider_netflix),
    HomeStreamingService(9, "Prime Video", R.drawable.home_provider_prime),
    HomeStreamingService(337, "Disney+", R.drawable.home_provider_disney),
    HomeStreamingService(15, "Hulu", R.drawable.home_provider_hulu),
    HomeStreamingService(384, "Max", R.drawable.home_provider_max),
    HomeStreamingService(350, "Apple TV+", R.drawable.home_provider_apple_tv),
    HomeStreamingService(387, "Peacock", R.drawable.home_provider_peacock),
    HomeStreamingService(2, "iTunes", R.drawable.home_provider_itunes),
    HomeStreamingService(188, "YouTube", R.drawable.home_provider_youtube),
    HomeStreamingService(531, "Paramount+", R.drawable.home_provider_paramount),
    HomeStreamingService(175, "Netflix Kids", R.drawable.home_provider_netflix)
)

internal fun homeStreamingService(providerId: Int): HomeStreamingService =
    homeStreamingServices.first { it.providerId == providerId }

@Composable
internal fun FlixQuestHomeHeader(
    selectedFilter: HomeFeedFilter,
    onFilterSelected: (HomeFeedFilter) -> Unit,
    onCategories: () -> Unit,
    onDownloads: () -> Unit,
    onSearch: () -> Unit,
    onNotifications: () -> Unit,
    onMenu: () -> Unit,
    isScrolled: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // A translucent surface at the top reveals the hero art; it becomes
            // solid after scrolling, matching FlixQuest's pinned-header behavior.
            .background(MaterialTheme.colorScheme.surface.copy(alpha = if (isScrolled) 0.97f else 0.82f))
            .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)))
            .padding(bottom = 7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(11.dp))
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(23.dp))
            }
            Text(
                "CINESTREAM",
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.75.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onDownloads) { Icon(Icons.Default.Download, contentDescription = stringResource(R.string.downloads)) }
            IconButton(onClick = onSearch) { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search)) }
            IconButton(onClick = onNotifications) {
                Icon(Icons.Default.Notifications, contentDescription = stringResource(R.string.notifications))
            }
            IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.home_menu)) }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(selected = selectedFilter == HomeFeedFilter.ALL, onClick = { onFilterSelected(HomeFeedFilter.ALL) }, label = { Text(stringResource(R.string.home_filter_all)) })
            FilterChip(selected = selectedFilter == HomeFeedFilter.MOVIES, onClick = { onFilterSelected(HomeFeedFilter.MOVIES) }, label = { Text(stringResource(R.string.home_filter_movies)) })
            FilterChip(selected = selectedFilter == HomeFeedFilter.SERIES, onClick = { onFilterSelected(HomeFeedFilter.SERIES) }, label = { Text(stringResource(R.string.home_filter_series)) })
            FilterChip(selected = false, onClick = onCategories, label = { Text(stringResource(R.string.home_filter_categories)) })
        }
    }
}

@Composable
internal fun HomePosterShelf(
    title: String?,
    items: List<HomeMixedMediaItem>,
    onMovieClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    onLongClick: (HomeMixedMediaItem) -> Unit,
    onSeeAll: (() -> Unit)? = null,
    ranked: Boolean = false,
    maxItems: Int = 20,
    kicker: String? = null
) {
    val visible = remember(items, maxItems) { items.distinctBy { "${it.contentType}:${it.id}" }.take(maxItems) }
    if (visible.isEmpty()) return
    if (title != null) SectionTitle(title, onSeeAllClick = onSeeAll, kicker = kicker)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(if (ranked) 8.dp else 14.dp)
    ) {
        itemsIndexed(visible, key = { _, item -> "${item.contentType}_${item.id}" }) { index, item ->
            Row(verticalAlignment = Alignment.Bottom) {
                if (ranked) {
                    Text(
                        text = (index + 1).toString(),
                        modifier = Modifier.width(42.dp).padding(bottom = 7.dp),
                        fontSize = 51.sp,
                        lineHeight = 54.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1
                    )
                }
                MediaCard(
                    revealIndex = index,
                    title = item.title,
                    posterUrl = item.posterUrl,
                    onClick = { if (item.isMovie) onMovieClick(item.id) else onSeriesClick(item.id) },
                    onLongClick = { onLongClick(item) },
                    rating = item.rating,
                    year = item.year.takeIf { it > 0 }?.toString(),
                    isMovie = item.isMovie,
                    contentType = item.contentType,
                    mediaId = item.id,
                    modifier = Modifier
                )
            }
        }
    }
    Spacer(Modifier.height(23.dp))
}

@Composable
private fun HomeWideMediaCard(item: HomeMixedMediaItem, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.toFloat()
    val screenHeight = configuration.screenHeightDp.toFloat()
    val isTablet = screenWidth >= 600f
    val landscapeTablet = isTablet && screenWidth > screenHeight
    val baseCount = when {
        screenWidth >= 1300f -> 7
        screenWidth >= 840f -> 6
        screenWidth >= 600f -> 5
        else -> 3
    }
    val count = baseCount + if (landscapeTablet) 1 else 0
    val gutter = if (isTablet) 24f else 16f
    val posterWidth = ((screenWidth - gutter * 2 - 10f * count) / (count + .15f))
        .coerceIn(if (isTablet) 112f else 104f, if (isTablet) 190f else 140f)
    val stillWidth = (posterWidth * 2).dp
    Box(
        modifier = modifier
            .width(stillWidth)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = item.backdropUrl.ifBlank { item.posterUrl },
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(item.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                item.year.takeIf { it > 0 }?.let { Text(it.toString(), color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.labelSmall) }
                if (item.rating > 0) {
                    Text(if (item.year > 0) "  ·  ★ ${"%.1f".format(item.rating)}" else "★ ${"%.1f".format(item.rating)}", color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun HomeGenreFeatureCard(item: HomeMixedMediaItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = item.backdropUrl.ifBlank { item.posterUrl },
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    .48f to Color.Transparent,
                    1f to Color.Black.copy(alpha = .86f)
                )
            )
        )
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth(.9f).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                item.title,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val facts = listOfNotNull(
                item.year.takeIf { it > 0 }?.toString(),
                if (item.isMovie) stringResource(R.string.movies) else stringResource(R.string.series),
                item.rating.takeIf { it > 0 }?.let { "★ ${"%.1f".format(it)}" }
            ).joinToString("  ·  ")
            if (facts.isNotBlank()) {
                Text(facts, color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun HomeWideMediaRow(items: List<HomeMixedMediaItem>, onClick: (HomeMixedMediaItem) -> Unit, onLongClick: (HomeMixedMediaItem) -> Unit) {
    if (items.isEmpty()) return
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        lazyItems(items, key = { "wide_${it.contentType}_${it.id}" }) { item ->
            HomeWideMediaCard(item, onClick = { onClick(item) }, onLongClick = { onLongClick(item) })
        }
    }
    Spacer(Modifier.height(18.dp))
}

@Composable
internal fun HomeGenreShelfSection(
    shelf: HomeGenreShelf,
    viewModel: HomeViewModel,
    onMovieClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    onLongClick: (HomeMixedMediaItem) -> Unit,
    onSeeAll: (HomeGenreShelf) -> Unit,
    kicker: String? = null
) {
    when (shelf.kind) {
        HomeGenreKind.MOVIE -> {
            val moviesFlow = remember(viewModel, shelf.genre.id) { viewModel.moviesByGenre(shelf.genre.id) }
            val movies by moviesFlow.collectAsState(initial = emptyList())
            val items = remember(movies) { movies.map { HomeMixedMediaItem(it.id, it.title, it.posterUrl, it.year, it.rating, true, ContentType.MOVIE, it.backdropUrl) } }
            RenderGenreLayout(shelf, items, onMovieClick, onSeriesClick, onLongClick, { onSeeAll(shelf) }, kicker)
        }
        HomeGenreKind.SERIES -> {
            val seriesFlow = remember(viewModel, shelf.genre.id) { viewModel.seriesByGenre(shelf.genre.id) }
            val series by seriesFlow.collectAsState(initial = emptyList())
            val items = remember(series) { series.map { HomeMixedMediaItem(it.id, it.title, it.posterUrl, it.year, it.rating, false, ContentType.TV, it.backdropUrl) } }
            RenderGenreLayout(shelf, items, onMovieClick, onSeriesClick, onLongClick, { onSeeAll(shelf) }, kicker)
        }
        HomeGenreKind.ANIME -> {
            val animeFlow = remember(viewModel, shelf.genre.id) { viewModel.animeByGenre(shelf.genre.id) }
            val anime by animeFlow.collectAsState(initial = emptyList())
            val items = remember(anime) { anime.map { HomeMixedMediaItem(it.id, it.title, it.posterUrl, it.year, it.rating, false, ContentType.ANIME, it.backdropUrl) } }
            RenderGenreLayout(shelf, items, onMovieClick, onSeriesClick, onLongClick, { onSeeAll(shelf) }, kicker)
        }
    }
}

@Composable
private fun RenderGenreLayout(
    shelf: HomeGenreShelf,
    items: List<HomeMixedMediaItem>,
    onMovieClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    onLongClick: (HomeMixedMediaItem) -> Unit,
    onSeeAll: () -> Unit,
    kicker: String? = null
) {
    if (items.isEmpty()) return
    when (shelf.layout) {
        HomeGenreLayout.FEATURE -> {
            SectionTitle(shelf.genre.name, onSeeAllClick = onSeeAll, kicker = kicker)
            items.firstOrNull()?.let { featured ->
                HomeGenreFeatureCard(
                    featured,
                    onClick = { if (featured.isMovie) onMovieClick(featured.id) else onSeriesClick(featured.id) },
                    onLongClick = { onLongClick(featured) }
                )
            }
            HomePosterShelf(null, items.drop(1), onMovieClick, onSeriesClick, onLongClick, maxItems = 12)
        }
        HomeGenreLayout.STILLS_AND_POSTERS -> {
            SectionTitle(shelf.genre.name, onSeeAllClick = onSeeAll, kicker = kicker)
            HomeWideMediaRow(items.take(6), { if (it.isMovie) onMovieClick(it.id) else onSeriesClick(it.id) }, onLongClick)
            HomePosterShelf(null, items.drop(6), onMovieClick, onSeriesClick, onLongClick, maxItems = 12)
        }
        HomeGenreLayout.TWO_POSTER_ROWS -> {
            HomePosterShelf(shelf.genre.name, items.filterIndexed { index, _ -> index % 2 == 0 }, onMovieClick, onSeriesClick, onLongClick, onSeeAll, maxItems = 10, kicker = kicker)
            HomePosterShelf(null, items.filterIndexed { index, _ -> index % 2 == 1 }, onMovieClick, onSeriesClick, onLongClick, maxItems = 10)
        }
        HomeGenreLayout.STILLS -> {
            SectionTitle(shelf.genre.name, onSeeAllClick = onSeeAll, kicker = kicker)
            HomeWideMediaRow(items.take(12), { if (it.isMovie) onMovieClick(it.id) else onSeriesClick(it.id) }, onLongClick)
        }
        HomeGenreLayout.POSTERS -> HomePosterShelf(shelf.genre.name, items, onMovieClick, onSeriesClick, onLongClick, onSeeAll, maxItems = 16, kicker = kicker)
    }
}

@Composable
internal fun HomeStreamingServicesRow(onServiceSelected: (HomeStreamingService) -> Unit) {
    SectionTitle(stringResource(R.string.home_streaming_services))
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        lazyItems(homeStreamingServices, key = { it.providerId }) { service ->
            Surface(
                shape = RoundedCornerShape(13.dp),
                color = Color(0xFFF5F5F5),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f)),
                modifier = Modifier.width(130.dp).height(74.dp).clip(RoundedCornerShape(13.dp))
                    .clickable { onServiceSelected(service) }
            ) {
                Image(
                    painter = painterResource(service.logoResId),
                    contentDescription = service.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)
                )
            }
        }
    }
    Spacer(Modifier.height(22.dp))
}

@Composable
internal fun HomeProviderShelf(
    service: HomeStreamingService,
    region: String,
    isMovie: Boolean,
    viewModel: HomeViewModel,
    onMovieClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    onLongClick: (HomeMixedMediaItem) -> Unit
) {
    if (isMovie) {
        val moviesFlow = remember(viewModel, service.providerId, region) { viewModel.moviesByProvider(service.providerId, region) }
        val movies by moviesFlow.collectAsState(initial = emptyList())
        val items = remember(movies) { movies.map { HomeMixedMediaItem(it.id, it.title, it.posterUrl, it.year, it.rating, true, ContentType.MOVIE, it.backdropUrl) } }
        if (items.isNotEmpty()) HomePosterShelf(stringResource(R.string.home_popular_movies_on_provider, service.name), items, onMovieClick, onSeriesClick, onLongClick, maxItems = 15)
    } else {
        val seriesFlow = remember(viewModel, service.providerId, region) { viewModel.seriesByProvider(service.providerId, region) }
        val series by seriesFlow.collectAsState(initial = emptyList())
        val items = remember(series) { series.map { HomeMixedMediaItem(it.id, it.title, it.posterUrl, it.year, it.rating, false, ContentTypeResolver.resolveSeries(it), it.backdropUrl) } }
        if (items.isNotEmpty()) HomePosterShelf(stringResource(R.string.home_popular_series_on_provider, service.name), items, onMovieClick, onSeriesClick, onLongClick, maxItems = 15)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeProviderDetailsSheet(
    service: HomeStreamingService,
    region: String,
    viewModel: HomeViewModel,
    onDismiss: () -> Unit,
    onMovieClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    onLongClick: (HomeMixedMediaItem) -> Unit
) {
    val moviesFlow = remember(viewModel, service.providerId, region) { viewModel.moviesByProvider(service.providerId, region) }
    val seriesFlow = remember(viewModel, service.providerId, region) { viewModel.seriesByProvider(service.providerId, region) }
    val movies by moviesFlow.collectAsState(initial = emptyList())
    val series by seriesFlow.collectAsState(initial = emptyList())
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SectionTitle(service.name)
            val movieItems = remember(movies) { movies.map { HomeMixedMediaItem(it.id, it.title, it.posterUrl, it.year, it.rating, true, ContentType.MOVIE, it.backdropUrl) } }
            val seriesItems = remember(series) { series.map { HomeMixedMediaItem(it.id, it.title, it.posterUrl, it.year, it.rating, false, ContentType.TV, it.backdropUrl) } }
            if (movieItems.isNotEmpty()) HomePosterShelf(stringResource(R.string.category_movies), movieItems, { id -> onDismiss(); onMovieClick(id) }, { id -> onDismiss(); onSeriesClick(id) }, onLongClick)
            if (seriesItems.isNotEmpty()) HomePosterShelf(stringResource(R.string.category_series), seriesItems, { id -> onDismiss(); onMovieClick(id) }, { id -> onDismiss(); onSeriesClick(id) }, onLongClick)
            if (movieItems.isEmpty() && seriesItems.isEmpty()) {
                Text(stringResource(R.string.home_empty_provider), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp))
            }
        }
    }
}
