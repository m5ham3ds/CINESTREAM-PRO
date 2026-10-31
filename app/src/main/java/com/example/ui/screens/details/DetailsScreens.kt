@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.example.ui.screens.details

import androidx.activity.compose.BackHandler

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import com.example.ui.theme.SuccessGreen
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.ui.res.stringResource
import com.example.R

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.ui.screens.extensions.NoExtensionsDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.data.model.ContentType
import com.example.data.util.ContentTypeResolver
import com.example.data.model.DownloadItem
import com.example.data.model.LibraryItem
import com.example.data.repository.DownloadRepository
import com.example.data.repository.LibraryRepository
import com.example.domain.models.CastMember
import com.example.domain.models.CrewMember
import com.example.domain.models.Episode
import com.example.domain.models.Movie
import com.example.domain.models.Series
import com.example.domain.models.Season
import com.example.domain.models.VideoTrailer
import com.example.data.util.TrailerSelectionHelper
import com.example.ui.components.ExpandableSynopsis
import com.example.ui.ViewModelFactory
import kotlinx.coroutines.launch
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailsScreen(
    onPersonClick: (String) -> Unit = {},
    onRelatedMovieClick: (String, Boolean) -> Unit = { _, _ -> },
    onGenreClick: (String, Int) -> Unit = { _, _ -> },
    onOpenLists: () -> Unit = {},
    movieId: String, 
    autoPlay: Boolean = false,
    initialExperienceMode: String = "STANDARD",
    onBack: () -> Unit,
    onPlay: (String, String, String?, String?, String, String?, String?) -> Unit = { _, _, _, _, _, _, _ -> },
    onNavigateToExtensions: () -> Unit = {},
    viewModel: MovieDetailsViewModel = viewModel(factory = ViewModelFactory())
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val downloadRepository = remember { DownloadRepository(context) }
    val libraryRepository = remember { LibraryRepository(context) }
    val scope = rememberCoroutineScope()
    val libraryItems by libraryRepository.getLibraryItems().collectAsState(initial = emptyList())
    val downloadItems by downloadRepository.getDownloadItems().collectAsState(initial = emptyList())
    val currentMode = if (initialExperienceMode.equals("ARABIC_DUBBED", ignoreCase = true) || initialExperienceMode.equals("DUBBED", ignoreCase = true) || uiState.movie?.mode.equals("dubbed", ignoreCase = true)) "dubbed" else "subtitled"
    val isFavoriteMovie by libraryRepository.isItemInLibrary(movieId, ContentType.MOVIE, currentMode).collectAsState(initial = false)
    val isFavoriteAnime by libraryRepository.isItemInLibrary(movieId, ContentType.ANIME, currentMode).collectAsState(initial = false)
    val restrictions by com.example.data.repository.UserSecurityManager.restrictionsFlow.collectAsState()
    val downloadItem = downloadItems.find { it.id == movieId || it.mediaId == movieId }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showNotReleasedDialog by remember { mutableStateOf(false) }
    var isCoverEnlarged by remember { mutableStateOf(false) }

    val networkObserver = remember { com.example.utils.NetworkConnectivityObserver(context) }
    val isOnline by networkObserver.observe().collectAsState(initial = com.example.utils.NetworkUtils.isInternetAvailable(context))

    LaunchedEffect(isOnline) {
        if (isOnline && uiState.movie == null) {
            viewModel.loadMovie(movieId, forceRefresh = true, experienceMode = initialExperienceMode)
        }
    }

    LaunchedEffect(movieId) {
        viewModel.loadMovie(movieId, experienceMode = initialExperienceMode)
        com.example.ui.screens.player.ServerStateStore.prepareForMedia(movieId, movieId)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (uiState.movie == null) {
            DetailsSkeleton(onBack = onBack)
        } else {
            val movie = uiState.movie!!
            val ctx = LocalContext.current
            val historyRepository = remember { com.example.data.repository.HistoryRepository(ctx) }
            var showSmartDownloadDialog by remember { mutableStateOf(false) }
            var showNoExtensionsDialog by remember { mutableStateOf(false) }
            var selectedTrailerId by remember { mutableStateOf<String?>(null) }
            var selectedDetailsTab by rememberSaveable(movieId) { mutableStateOf("MORE") }
            var selectedArtworkGallery by remember { mutableStateOf<DetailsArtworkGallery?>(null) }

            val ptrState = rememberPullToRefreshState()
            val scrollState = rememberScrollState()
            var activePlayback by remember { mutableStateOf<com.example.ui.components.ActiveInlinePlayback?>(null) }
            val challengeController = remember { com.example.extension.managed.runtime.web.InteractiveChallengeController.getInstance() }
            val challengeUiState by challengeController.stateFlow.collectAsState()
            val isChallengeActive = challengeUiState.isVisible || challengeUiState.phase == com.example.extension.managed.runtime.web.ChallengeStateMachine.Phase.CHALLENGE_TIMEOUT

            val activity = ctx as? android.app.Activity
            val isInPip = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                activity?.isInPictureInPictureMode == true && com.example.ui.screens.player.PlayerStateHolder.isInPipMode
            } else false

            DisposableEffect(Unit) {
                com.example.utils.AdManager.enterDetailsScreen()
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    com.example.ui.screens.player.PlayerStateHolder.isInPipMode = activity?.isInPictureInPictureMode == true
                } else {
                    com.example.ui.screens.player.PlayerStateHolder.isInPipMode = false
                }
                onDispose {
                    com.example.utils.AdManager.exitDetailsScreen()
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        com.example.ui.screens.player.PlayerStateHolder.isInPipMode = activity?.isInPictureInPictureMode == true
                    } else {
                        com.example.ui.screens.player.PlayerStateHolder.isInPipMode = false
                    }
                    val vMode = if (movie.mode.equals("dubbed", ignoreCase = true)) com.example.domain.models.MediaVariantMode.DUBBED else com.example.domain.models.MediaVariantMode.SUBTITLED
                    com.example.extension.managed.runtime.WorkDiscoverySessionManager.INSTANCE.onLeaveWorkPage(
                        workId = movie.id,
                        contentType = com.example.extension.managed.model.ContentType.MOVIE,
                        variantMode = vMode,
                        audioLanguage = com.example.domain.models.VariantLanguage.ARABIC,
                        season = 1,
                        episode = 1
                    )
                }
            }

            LaunchedEffect(movie.id) {
                com.example.extension.managed.runtime.WorkDiscoverySessionManager.INSTANCE.onWorkChanged(movie.id, 1, 1)
                val rawTitle = movie.originalTitle ?: movie.title
                val mediaKey = "$rawTitle-true-1-1"
                com.example.ui.screens.player.ServerStateStore.prepareForMedia(mediaKey, movie.id.toString())
            }

            BackHandler(enabled = (activePlayback != null || selectedTrailerId != null) && !isInPip) {
                activePlayback = null
                selectedTrailerId = null
            }

            val isMovieAnime = (movie.genres.any { it.equals("Anime", ignoreCase = true) }) ||
                (movie.genres.any { it.contains("Animation", ignoreCase = true) } && (movie.language == "ja" || movie.country == "JP")) ||
                com.example.data.repository.AnimePlaybackStore.isAnime(ctx, movie.id, movie.title)
            val isFavorite = if (isMovieAnime) isFavoriteAnime else isFavoriteMovie
            LaunchedEffect(movie.id, movie.genreIds, isMovieAnime) {
                viewModel.loadSimilarMovies(movie, animeOnly = isMovieAnime)
            }

            val currentLang = com.example.data.util.TmdbGenreHelper.getCurrentLanguage(ctx)
            val cachedPrimaryTrailer = remember(movie.trailers, currentLang) {
                com.example.data.util.TrailerSelectionHelper.selectPrimaryTrailer(
                    movie.trailers,
                    preferredLanguage = currentLang
                )
            }
            LaunchedEffect(movie.id, currentLang) {
                viewModel.loadPrimaryTrailer(movie.id, currentLang, movie.trailers)
            }
            val selectedPrimaryTrailer = uiState.primaryTrailer ?: cachedPrimaryTrailer
            val displayMovieGenres = remember(movie.genreIds, movie.genres, currentLang) {
                if (movie.genreIds.isNotEmpty()) {
                    com.example.data.util.TmdbGenreHelper.getMovieGenreNames(movie.genreIds, currentLang)
                } else {
                    movie.genres
                }
            }

            val isDubbedExperience = (initialExperienceMode.equals("ARABIC_DUBBED", ignoreCase = true) || initialExperienceMode.equals("DUBBED", ignoreCase = true) || movie.mode.equals("dubbed", ignoreCase = true)) && !isMovieAnime
            val effectiveMode = if (isDubbedExperience) "dubbed" else "subtitled"
            val movieOrchestrator = remember { com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(ctx) }
            val movieDubbedOrder = remember { movieOrchestrator.registry.getSearchOrder().arabicDubbed }
            val movieDisplayInfo = remember(movie, isDubbedExperience, movieDubbedOrder) {
                com.example.domain.search.MediaDisplayTitleResolver.resolveForMovie(
                    movie = movie,
                    isDubbedIntent = isDubbedExperience,
                    dubbedOrder = movieDubbedOrder,
                    preferredProviderId = movie.providerId
                )
            }
            val displayMovieTitle = movieDisplayInfo.displayTitle
            val displayMoviePoster = movieDisplayInfo.displayPosterUrl?.takeIf { it.isNotBlank() } ?: movie.posterUrl.takeIf { it.isNotBlank() } ?: movie.backdropUrl
            val matchedDubbedCandidate = if (isDubbedExperience) {
                movie.sourceCandidates.firstOrNull {
                    it.providerId == movieDisplayInfo.providerId &&
                    it.mode == com.example.domain.models.MediaVariantMode.DUBBED &&
                    it.status == com.example.domain.models.CoverageStatus.FOUND &&
                    it.sourcePageUrl.isNotBlank()
                } ?: movie.sourceCandidates.firstOrNull {
                    it.mode == com.example.domain.models.MediaVariantMode.DUBBED &&
                    it.status == com.example.domain.models.CoverageStatus.FOUND &&
                    it.sourcePageUrl.isNotBlank()
                }
            } else null

            val isMovieDownloaded = (downloadItem?.isCompleted == true && downloadItem?.variantMode.equals(effectiveMode, ignoreCase = true)) ||
                    com.example.utils.MediaStorageUtils.hasDownloadedMedia(ctx, movie.id, mode = effectiveMode, language = "ar") ||
                    downloadItems.any { (it.id == movie.id || it.mediaId == movie.id) && it.isCompleted && it.variantMode.equals(effectiveMode, ignoreCase = true) }

            val resumeLastPlayback = {
                val vMode = if (effectiveMode.equals("dubbed", ignoreCase = true)) com.example.domain.models.MediaVariantMode.DUBBED else com.example.domain.models.MediaVariantMode.SUBTITLED
                val orchestrator = com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(ctx)
                com.example.extension.managed.runtime.WorkDiscoverySessionManager.INSTANCE.resumeOnPlay(
                    workId = movie.id,
                    variantMode = vMode,
                    audioLanguage = com.example.domain.models.VariantLanguage.ARABIC,
                    season = 1,
                    episode = 1,
                    allEligibleProviders = orchestrator.registry.getAllExtensions()
                )
                if (!restrictions.isWatchAllowed) {
                    Toast.makeText(ctx, ctx.getString(R.string.watching_restricted), Toast.LENGTH_LONG).show()
                } else if (isMovieDownloaded) {
                    selectedTrailerId = null
                    val syncPos = com.example.ui.screens.player.PlaybackSyncStore.getPosition(movie.id)
                    val lastPlayback = com.example.utils.LastPlaybackStore.getLastPlayback(ctx, movie.id)
                    val startPos = if (syncPos > 0L) syncPos else (lastPlayback?.positionMillis ?: 0L)
                    val fileId = downloadItem?.id ?: movie.id
                    activePlayback = com.example.ui.components.ActiveInlinePlayback(
                        mediaId = movie.id,
                        title = movie.title,
                        url = "local_offline_file://$fileId",
                        posterUrl = movie.posterUrl,
                        isMovie = true,
                        initialPosition = startPos,
                        mode = effectiveMode,
                        providerId = movie.providerId
                    )
                    scope.launch { scrollState.animateScrollTo(0) }
                } else {
                    if (!com.example.utils.NetworkUtils.isInternetAvailable(ctx)) {
                        Toast.makeText(ctx, ctx.getString(R.string.no_internet_check_connection), Toast.LENGTH_LONG).show()
                    } else {
                        val lastPlayback = com.example.utils.LastPlaybackStore.getLastPlayback(ctx, movie.id)
                        val rawTitle = movie.originalTitle ?: movie.title
                        val mediaKey = com.example.ui.screens.player.buildMediaKey(
                            title = rawTitle,
                            isMovie = true,
                            season = 1,
                            episode = 1,
                            variantMode = effectiveMode,
                            audioLang = if (effectiveMode == "dubbed") "ar" else "orig"
                        )
                        val altTitleKey = com.example.ui.screens.player.buildMediaKey(
                            title = movie.title,
                            isMovie = true,
                            season = 1,
                            episode = 1,
                            variantMode = effectiveMode,
                            audioLang = if (effectiveMode == "dubbed") "ar" else "orig"
                        )
                        val cachedData = com.example.ui.screens.player.ServerStateStore.getCachedData(movie.id.toString(), mediaKey, altTitleKey)
                        val chosenQuality = lastPlayback?.quality ?: cachedData?.extractedQualities?.firstOrNull()?.name ?: "Auto"
                        val targetCanonical = com.example.ui.screens.player.normalizeCanonicalQualityName(chosenQuality)
                        val matchingQualityUrl = if (targetCanonical != null && targetCanonical != "Auto") {
                            cachedData?.extractedQualities?.find {
                                com.example.ui.screens.player.normalizeCanonicalQualityName(it.name) == targetCanonical && it.url.isNotBlank() && it.url.startsWith("http")
                            }?.url
                        } else null

                        val candidateUrl = if (lastPlayback != null && lastPlayback.url.isNotBlank() && lastPlayback.url.startsWith("http")) {
                            lastPlayback.url
                        } else if (!matchingQualityUrl.isNullOrBlank()) {
                            matchingQualityUrl
                        } else if (cachedData != null && cachedData.extractedQualities.isNotEmpty()) {
                            cachedData.extractedQualities.firstOrNull { it.url.isNotBlank() && it.url.startsWith("http") }?.url
                        } else if (cachedData != null && !cachedData.directStreamUrl.isNullOrBlank()) {
                            cachedData.directStreamUrl
                        } else null

                        val warmResult = com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(ctx).evaluateWarmPlayback(
                            context = ctx,
                            mediaId = movie.id.toString(),
                            candidateUrl = candidateUrl?.takeIf { com.example.ui.components.isValidPlayableMediaUrl(it) },
                            savedQuality = chosenQuality
                        )
                        val cachedUrl = when (warmResult) {
                            is com.example.extension.orchestrator.ManagedMediaOrchestrator.WarmPlaybackResult.Valid -> warmResult.streamUrl
                            is com.example.extension.orchestrator.ManagedMediaOrchestrator.WarmPlaybackResult.StaleOrInvalid -> null
                        }

                        val syncPos = com.example.ui.screens.player.PlaybackSyncStore.getPosition(movie.id)
                        val startPos = if (syncPos > 0L) syncPos else (lastPlayback?.positionMillis ?: 0L)

                        if (cachedUrl != null) {
                            selectedTrailerId = null
                            activePlayback = com.example.ui.components.ActiveInlinePlayback(
                                mediaId = movie.id,
                                title = movie.title,
                                originalTitle = movie.originalTitle ?: movie.title,
                                year = movie.year.toString(),
                                url = cachedUrl,
                                serverName = lastPlayback?.serverName ?: cachedData?.servers?.firstOrNull(),
                                website = lastPlayback?.website ?: cachedData?.website,
                                posterUrl = movie.posterUrl,
                                isMovie = true,
                                initialPosition = startPos,
                                initialQuality = chosenQuality,
                                mode = effectiveMode,
                                providerId = movie.providerId
                            )
                            scope.launch { scrollState.animateScrollTo(0) }
                        } else {
                            val isReleased = (movie.releaseDate ?: "") <= java.time.LocalDate.now().toString()
                            if (!isReleased) {
                                showNotReleasedDialog = true
                            } else {
                                selectedTrailerId = null
                                activePlayback = com.example.ui.components.ActiveInlinePlayback(
                                    mediaId = movie.id,
                                    title = movie.title,
                                    originalTitle = movie.originalTitle ?: movie.title,
                                    year = movie.year.toString(),
                                    url = "auto_extract://",
                                    serverName = null,
                                    website = null,
                                    posterUrl = movie.posterUrl,
                                    isMovie = true,
                                    initialPosition = 0L,
                                    initialQuality = chosenQuality,
                                    mode = effectiveMode,
                                    providerId = movie.providerId
                                )
                                scope.launch { scrollState.animateScrollTo(0) }
                            }
                        }

                        // Background revalidation: strictly trigger ONLY when user clicks play, and ONLY if >= 6 hours have passed!
                        val now = System.currentTimeMillis()
                        val safeKey = cachedData?.scraperKey?.ifBlank { "managed" } ?: "managed"
                        val jobKey = "$safeKey:${movie.id}:"
                        val lastRun = com.example.extension.orchestrator.BackgroundMediaRevalidator.getLastRevalidationTime(jobKey).takeIf { it > 0L }
                            ?: (cachedData?.lastUpdatedTimestamp ?: 0L)
                        val SIX_HOURS_MS = 6 * 3600 * 1000L
                        if (lastRun == 0L || (now - lastRun >= SIX_HOURS_MS)) {
                            val knownUrl = cachedData?.playbackPageUrl ?: lastPlayback?.playbackPageUrl ?: cachedUrl ?: ""
                            val sKey = cachedData?.scraperKey ?: lastPlayback?.scraperKey ?: ""
                            val managedOrchestrator = com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(ctx)
                            managedOrchestrator.revalidateMediaInBackground(
                                mediaId = movie.id,
                                mediaTitle = rawTitle,
                                isMovie = true,
                                knownPlaybackUrl = knownUrl,
                                scraperKey = sKey,
                                currentServerName = lastPlayback?.serverName ?: cachedData?.servers?.firstOrNull(),
                                currentQuality = chosenQuality,
                                altKeys = listOf(movie.id.toString(), mediaKey, altTitleKey)
                            )
                        }
                    }
                }
            }

            val startPlayFlow = {
                resumeLastPlayback()
            }

            var hasAutoPlayed by remember { mutableStateOf(false) }
            LaunchedEffect(movie, autoPlay) {
                if (autoPlay && !hasAutoPlayed) {
                    hasAutoPlayed = true
                    resumeLastPlayback()
                }
            }
            val pullRefreshIndicator = com.example.ui.components.rememberBriefPullRefreshIndicator()
    PullToRefreshBox(
                isRefreshing = pullRefreshIndicator.visible,
                onRefresh = { pullRefreshIndicator.start(); viewModel.loadMovie(movieId) },
                state = ptrState,
                modifier = Modifier.fillMaxSize().padding(bottom = if (isInPip) 0.dp else padding.calculateBottomPadding())
            ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (!isInPip) Modifier.verticalScroll(scrollState) else Modifier)
            ) {
                
                if (showDeleteConfirm && !isInPip) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirm = false },
                        title = { Text(stringResource(R.string.delete_download), color = MaterialTheme.colorScheme.onBackground) },
                        text = { Text(stringResource(R.string.delete_download_confirm), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        confirmButton = {
                            TextButton(onClick = {
                                scope.launch {
                                    if (downloadItem != null) {
                                        downloadRepository.removeFromDownloads(downloadItem)
                                    } else {
                                        downloadRepository.removeFromDownloads(movie.id)
                                    }
                                    Toast.makeText(ctx, ctx.getString(R.string.download_deleted), Toast.LENGTH_SHORT).show()
                                }
                                showDeleteConfirm = false
                            }) { Text(stringResource(R.string.yes_delete), color = Color.Red) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onBackground) }
                        },
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                }
                
                
    if (showNotReleasedDialog && !isInPip) {
        AlertDialog(
            onDismissRequest = { showNotReleasedDialog = false },
            title = { Text(stringResource(R.string.coming_soon), color = MaterialTheme.colorScheme.onBackground) },
            text = { Text(stringResource(R.string.not_released_yet), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = { showNotReleasedDialog = false }) { Text(stringResource(R.string.ok_button)) }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // Hero Image or Video Player
                if (activePlayback != null) {
                    Box(modifier = if (isInPip) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f/9f)) {
                        com.example.ui.components.InlineDetailVideoPlayer(
                            playback = activePlayback!!,
                            onPlaybackUrlExtracted = { realUrl ->
                                activePlayback = activePlayback?.copy(url = realUrl)
                            },
                            onFullscreen = { currentPos ->
                                val p = activePlayback!!
                                val realUrl = if (!p.url.startsWith("auto_extract://") && com.example.ui.components.isValidPlayableMediaUrl(p.url)) {
                                    p.url
                                } else {
                                    val rawTitle = movie.originalTitle ?: movie.title
                                    val cached = com.example.ui.screens.player.ServerStateStore.getCachedData(
                                        movie.id.toString(),
                                        "$rawTitle-true-1-1",
                                        "${movie.title}-true-1-1"
                                    )
                                    val candidate = cached?.directStreamUrl?.takeIf { it.isNotBlank() }
                                        ?: cached?.extractedQualities?.firstOrNull { it.url.isNotBlank() && it.name != "Auto" }?.url
                                    if (com.example.ui.components.isValidPlayableMediaUrl(candidate)) candidate!! else p.url
                                }
                                if (currentPos > 0L) {
                                    com.example.ui.screens.player.PlaybackSyncStore.setPosition(movie.id, currentPos)
                                }
                                activePlayback = null
                                onPlay(p.title, realUrl, p.serverName, p.website, p.posterUrl, p.mode, p.providerId)
                            },
                            onClose = {
                                activePlayback = null
                            },
                            onNavigateToExtensions = onNavigateToExtensions,
                            onChangeServer = {
                                if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                                    Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_SHORT).show()
                                } else {
                                    selectedTrailerId = null
                                    activePlayback = com.example.ui.components.ActiveInlinePlayback(
                                        mediaId = movie.id,
                                        title = movie.title,
                                        url = "auto_extract://",
                                        serverName = null,
                                        website = null,
                                        posterUrl = movie.posterUrl,
                                        isMovie = true,
                                        initialPosition = 0L,
                                        mode = if (isMovieAnime) "subtitled" else effectiveMode,
                                        providerId = movie.providerId
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (!isInPip) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(text = displayMovieTitle, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(modifier = Modifier.height(8.dp))
                        com.example.ui.screens.details.PlaybackDiagnosticsPanel(mediaId = movie.id.toString())
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${movie.year} • ${displayMovieGenres.take(3).joinToString(" • ")}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, contentDescription = stringResource(R.string.rating_r), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(String.format("%.1f", movie.rating), color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                            }
                            Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) { Text("18+", color = MaterialTheme.colorScheme.onBackground) }
                        }
                    }
                    }
                } else if (selectedTrailerId != null) {
                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f/9f)) {
                        com.example.ui.components.InlineYouTubePlayer(
                            videoId = selectedTrailerId!!,
                            modifier = Modifier.fillMaxSize(),
                            onClose = { selectedTrailerId = null }
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(text = displayMovieTitle, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${movie.year} • ${displayMovieGenres.take(3).joinToString(" • ")}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, contentDescription = stringResource(R.string.rating_r), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(String.format("%.1f", movie.rating), color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                            }
                            Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) { Text("18+", color = MaterialTheme.colorScheme.onBackground) }
                        }
                    }
                } else {
                    FlixQuestDetailsHero(
                        backdropUrl = movie.backdropUrl,
                        fallbackPosterUrl = displayMoviePoster ?: movie.posterUrl,
                        title = displayMovieTitle,
                        year = movie.year,
                        rating = movie.rating,
                        extraFact = movie.runtime.takeIf { it > 0 }?.let { minutes -> "${minutes / 60}h ${minutes % 60}m" }.orEmpty(),
                        genres = displayMovieGenres,
                        onBack = onBack,
                        onCoverClick = { isCoverEnlarged = true },
                        onGenreClick = { index -> movie.genreIds.getOrNull(index)?.let { onGenreClick(if (isMovieAnime) "ANIME" else "MOVIES", it) } }
                    )
                }
                
                if (!isInPip) {
                if (isDubbedExperience) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.variant_dubbed),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                if (isDubbedExperience) {
                    val rawTitle = movie.originalTitle ?: movie.title
                    val dubbedMediaKey = com.example.ui.screens.player.buildMediaKey(
                        title = rawTitle,
                        isMovie = true,
                        season = 1,
                        episode = 1,
                        variantMode = "dubbed",
                        audioLang = "ar"
                    )
                    val dubbedCached = com.example.ui.screens.player.ServerStateStore.getCachedData(dubbedMediaKey, movie.id)
                    val isDubbedUnavailable = dubbedCached != null && dubbedCached.servers.isEmpty() && dubbedCached.directStreamUrl.isNullOrBlank()

                    if (isDubbedUnavailable) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.dubbed_unavailable_banner_message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // FlixQuest detail summary and primary actions.
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(54.dp).combinedClickable(
                            onClick = { startPlayFlow() },
                            onLongClick = { if (isMovieDownloaded) showDeleteConfirm = true }
                        ),
                        shape = RoundedCornerShape(13.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.play_now), tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(9.dp))
                            Text(if (isMovieDownloaded) stringResource(R.string.resume_offline) else stringResource(R.string.play_now), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(13.dp)).clickable {
                            if (!restrictions.isDownloadAllowed) {
                                Toast.makeText(context, context.getString(R.string.downloading_restricted_by_admin), Toast.LENGTH_LONG).show()
                            } else if (isMovieDownloaded) {
                                showDeleteConfirm = true
                            } else if (downloadItem != null) {
                                val intent = android.content.Intent(context, com.example.utils.StreamDownloaderService::class.java).apply {
                                    action = if (downloadItem.isPaused) "RESUME" else "PAUSE"
                                    putExtra("id", movie.id.toString())
                                }
                                context.startService(intent)
                                scope.launch { downloadRepository.updateDownload(downloadItem.copy(isPaused = !downloadItem.isPaused)) }
                                Toast.makeText(context, if (downloadItem.isPaused) context.getString(R.string.download_resumed) else context.getString(R.string.download_paused), Toast.LENGTH_SHORT).show()
                            } else {
                                showSmartDownloadDialog = true
                            }
                        },
                        shape = RoundedCornerShape(13.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            if (isMovieDownloaded) Icon(Icons.Default.Check, null, tint = Color(0xFF4CAF50))
                            else if (downloadItem != null) AnimatedDownloadIcon(isPaused = downloadItem.isPaused)
                            else Icon(Icons.Default.Download, contentDescription = stringResource(R.string.downloads), tint = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.width(9.dp))
                            Text(if (isMovieDownloaded) stringResource(R.string.completed) else stringResource(R.string.downloads), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                if (movie.overview.isNotBlank()) {
                    ExpandableSynopsis(
                        overview = movie.overview,
                        modifier = Modifier.padding(horizontal = 18.dp),
                        collapsedMaxLines = 3
                    )
                }
                if (movie.cast.isNotEmpty()) {
                    Text("${stringResource(R.string.details_starring)}: ${movie.cast.take(3).joinToString(", ") { it.name }}", modifier = Modifier.padding(horizontal = 18.dp).padding(top = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (!movie.director.isNullOrBlank()) {
                    Text("${stringResource(R.string.details_director)}: ${movie.director}", modifier = Modifier.padding(horizontal = 18.dp).padding(top = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DetailsQuickActionBar(
                    isInMyList = isFavorite,
                    onMyList = {
                        scope.launch {
                            val item = LibraryItem.create(
                                contentType = if (isMovieAnime) ContentType.ANIME else ContentType.MOVIE,
                                tmdbId = movie.id,
                                title = displayMovieTitle,
                                posterUrl = displayMoviePoster ?: movie.posterUrl,
                                variantMode = effectiveMode,
                                providerId = matchedDubbedCandidate?.providerId ?: movie.providerId ?: ""
                            )
                            if (isFavorite) libraryRepository.removeFromLibrary(item) else libraryRepository.addToLibrary(item)
                        }
                    },
                    onLists = onOpenLists,
                    onTrailer = { selectedDetailsTab = "TRAILERS"; viewModel.showOtherTrailers(movie.id, currentLang) },
                    onShare = { shareDetailsTitle(context, displayMovieTitle, movie.id, true) }
                )
                DetailsSectionTabs(selectedDetailsTab) { tab ->
                    selectedDetailsTab = tab
                    if (tab == "TRAILERS") viewModel.showOtherTrailers(movie.id, currentLang)
                }
                
                if (selectedDetailsTab == "TRAILERS") {
                    Text(stringResource(R.string.trailers), color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(8.dp))
                    DetailsTrailerList(
                        primaryTrailer = selectedPrimaryTrailer,
                        otherTrailers = uiState.otherTrailers,
                        isLoading = uiState.isLoadingOtherTrailers,
                        onSelect = { trailer ->
                            if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                                Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_SHORT).show()
                            } else {
                                activePlayback = null
                                selectedTrailerId = trailer.key
                            }
                        }
                    )
                    Spacer(Modifier.height(24.dp))
                }
                                if (selectedDetailsTab == "TRAILERS") {
                    DetailsArtworkGallerySection(
                        backdropImages = movie.backdropImages.ifEmpty { listOf(movie.backdropUrl).filter { it.isNotBlank() } },
                        posterImages = movie.posterImages.ifEmpty { listOf((displayMoviePoster ?: movie.posterUrl)).filter { it.isNotBlank() } },
                        onOpenGallery = { title, images -> selectedArtworkGallery = DetailsArtworkGallery(title, images) }
                    )
                }
                if (selectedDetailsTab == "DETAILS") {
                    if (movie.cast.isNotEmpty()) {
                        DetailsCastSection(movie.cast, onPersonClick, keyPrefix = "movie")
                    }
                    if (!movie.collectionName.isNullOrBlank() && movie.collectionId != null) {
                        DetailsCollectionCard(
                            title = movie.collectionName,
                            imageUrl = movie.collectionBackdropUrl ?: movie.collectionPosterUrl ?: movie.backdropUrl,
                            onClick = {
                                val collectionId = movie.collectionId
                                if (collectionId != null) openDetailsExternalUrl(context, "https://www.themoviedb.org/collection/$collectionId")
                            }
                        )
                    }
                    val originalTitleLabel = stringResource(R.string.details_original_title)
                    val statusLabel = stringResource(R.string.details_status)
                    val releaseDateLabel = stringResource(R.string.details_release_date)
                    val runtimeLabel = stringResource(R.string.details_runtime)
                    val originalLanguageLabel = stringResource(R.string.details_original_language)
                    val spokenLanguagesLabel = stringResource(R.string.details_spoken_languages)
                    val companiesLabel = stringResource(R.string.details_production_companies)
                    val countriesLabel = stringResource(R.string.details_production_countries)
                    val movieInfoRows = buildList {
                        add(originalTitleLabel to (movie.originalTitle ?: movie.title))
                        movie.status?.takeIf { it.isNotBlank() }?.let { add(statusLabel to it) }
                        movie.releaseDate?.takeIf { it.isNotBlank() }?.let { add(releaseDateLabel to it) }
                        if (movie.runtime > 0) add(runtimeLabel to "${movie.runtime / 60}h ${movie.runtime % 60}m")
                        movie.language.takeIf { it.isNotBlank() }?.let { add(originalLanguageLabel to it.uppercase()) }
                        movie.spokenLanguages.takeIf { it.isNotEmpty() }?.let { add(spokenLanguagesLabel to it.joinToString(", ")) }
                        movie.productionCompanies.takeIf { it.isNotEmpty() }?.let { add(companiesLabel to it.joinToString(", ")) }
                        movie.productionCountries.takeIf { it.isNotEmpty() }?.let { add(countriesLabel to it.joinToString(", ")) }
                    }
                    DetailsInfoTable(
                        title = stringResource(R.string.details_movie_info),
                        rows = movieInfoRows
                    )
                    DetailsExternalLinks(
                        imdbId = movie.imdbId,
                        homepage = movie.homepage,
                        onOpenUrl = { openDetailsExternalUrl(context, it) }
                    )
                }
                if (selectedDetailsTab == "MORE") {
                    DetailsRelatedMovieGrid(
                        relatedItems = uiState.similarMovies,
                        hasMore = uiState.similarMoviesPage < uiState.similarMoviesTotalPages,
                        isLoadingMore = uiState.isLoadingMoreSimilar,
                        onLoadMore = viewModel::loadMoreSimilarMovies,
                        onClick = { related -> onRelatedMovieClick(related.id, isMovieAnime) }
                    )
                }
                } // end if (!isInPip)
            }

            }
            if (showNoExtensionsDialog && !isInPip) {
                NoExtensionsDialog(
                    onDismiss = { showNoExtensionsDialog = false },
                    onGoToExtensions = { 
                        onNavigateToExtensions()
                    },
                    onRetry = {
                        if (com.example.extension.orchestrator.ManagedMediaOrchestrator.hasActiveExtensions()) {
                            showNoExtensionsDialog = false
                        }
                    }
                )
            }
            if (showSmartDownloadDialog && !isInPip) {
                com.example.ui.screens.player.SmartDownloadQualityDialog(
                    title = movie.title,
                    originalTitle = movie.originalTitle ?: movie.title,
                    year = movie.year.toString(),
                    isMovie = true,
                    isAnime = isMovieAnime,
                    posterUrl = movie.posterUrl,
                    mediaId = movie.id,
                    onDismiss = { showSmartDownloadDialog = false },
                    onNavigateToExtensions = onNavigateToExtensions,
                    mode = if (isMovieAnime) "subtitled" else effectiveMode,
                    providerId = movie.providerId
                )
            }

            selectedArtworkGallery?.let { gallery ->
                DetailsArtworkGalleryDialog(gallery) { selectedArtworkGallery = null }
            }

            if (isCoverEnlarged) {
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { isCoverEnlarged = false },
                    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.95f))
                            .clickable { isCoverEnlarged = false },
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = displayMoviePoster,
                            contentDescription = displayMovieTitle,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .clickable { isCoverEnlarged = false }
                        )
                        IconButton(
                            onClick = { isCoverEnlarged = false },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(16.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.cancel),
                                tint = Color.White
                            )
                        }
                    }
                }
            }

    }
}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailsScreen(
    onPersonClick: (String) -> Unit = {},
    onRelatedSeriesClick: (String, Boolean) -> Unit = { _, _ -> },
    onGenreClick: (String, Int) -> Unit = { _, _ -> },
    onOpenLists: () -> Unit = {},
    seriesId: String,
    autoPlay: Boolean = false,
    initialExperienceMode: String = "STANDARD",
    onBack: () -> Unit,
    onPlay: (String, String, String?, String?, String, String?, String?, String?, String?) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
    onNavigateToExtensions: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: SeriesDetailsViewModel = viewModel(factory = ViewModelFactory())
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val libraryRepository = remember { LibraryRepository(context) }
    val resolvedContentType = uiState.series?.let { ContentTypeResolver.resolveSeries(it) }
    val currentMode = if (initialExperienceMode.equals("ARABIC_DUBBED", ignoreCase = true) || initialExperienceMode.equals("DUBBED", ignoreCase = true) || uiState.series?.mode.equals("dubbed", ignoreCase = true)) "dubbed" else "subtitled"
    val isFavoriteTv by libraryRepository.isItemInLibrary(seriesId, ContentType.TV, currentMode).collectAsState(initial = false)
    val isFavoriteAnime by libraryRepository.isItemInLibrary(seriesId, ContentType.ANIME, currentMode).collectAsState(initial = false)
    val downloadRepository = remember { DownloadRepository(context) }
    val downloads by downloadRepository.getDownloadItems().collectAsState(initial = emptyList())
    val downloadedEpisodeIds = downloads.map { it.id }.toSet()
    val historyRepository = remember { com.example.data.repository.HistoryRepository(context) }
    val watchedRepo = remember { com.example.data.repository.WatchedEpisodeRepository(context) }
    val watchedEpisodes by watchedRepo.getAllWatched().collectAsState(initial = emptyList())
    val watchedEpisodeIds = watchedEpisodes.map { it.id }.toSet()
    val restrictions by com.example.data.repository.UserSecurityManager.restrictionsFlow.collectAsState()

    var downloadEpisodeTarget by remember { mutableStateOf<Episode?>(null) }
    var showBatchDownloadSheet by remember { mutableStateOf(false) }
    var showNotReleasedDialog by remember { mutableStateOf(false) }
    var selectedTrailerId by remember { mutableStateOf<String?>(null) }
    var selectedDetailsTab by rememberSaveable(seriesId) { mutableStateOf("MORE") }
    var showSeasonAbout by rememberSaveable(seriesId) { mutableStateOf(false) }
    var showSeasonPicker by rememberSaveable(seriesId) { mutableStateOf(false) }
    var selectedArtworkGallery by remember { mutableStateOf<DetailsArtworkGallery?>(null) }
    var isCoverEnlarged by remember { mutableStateOf(false) }
    var episodeToDelete by remember { mutableStateOf<com.example.data.model.DownloadItem?>(null) }
    var showEpisodeDeleteConfirm by remember { mutableStateOf(false) }
    var activePlayback by remember { mutableStateOf<com.example.ui.components.ActiveInlinePlayback?>(null) }
    val activity = context as? android.app.Activity
    val isInPip = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
        activity?.isInPictureInPictureMode == true && com.example.ui.screens.player.PlayerStateHolder.isInPipMode
    } else false

    DisposableEffect(Unit) {
        com.example.utils.AdManager.enterDetailsScreen()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            com.example.ui.screens.player.PlayerStateHolder.isInPipMode = activity?.isInPictureInPictureMode == true
        } else {
            com.example.ui.screens.player.PlayerStateHolder.isInPipMode = false
        }
        onDispose {
            com.example.utils.AdManager.exitDetailsScreen()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                com.example.ui.screens.player.PlayerStateHolder.isInPipMode = activity?.isInPictureInPictureMode == true
            } else {
                com.example.ui.screens.player.PlayerStateHolder.isInPipMode = false
            }
            val s = uiState.series
            if (s != null) {
                val vMode = if (s.mode.equals("dubbed", ignoreCase = true)) com.example.domain.models.MediaVariantMode.DUBBED else com.example.domain.models.MediaVariantMode.SUBTITLED
                com.example.extension.managed.runtime.WorkDiscoverySessionManager.INSTANCE.onLeaveWorkPage(
                    workId = s.id.toString(),
                    contentType = com.example.extension.managed.model.ContentType.SERIES,
                    variantMode = vMode,
                    audioLanguage = com.example.domain.models.VariantLanguage.ARABIC,
                    season = uiState.selectedSeason?.seasonNumber ?: 1,
                    episode = 1
                )
            }
        }
    }
    BackHandler(enabled = (activePlayback != null || selectedTrailerId != null) && !isInPip) {
        activePlayback = null
        selectedTrailerId = null
    }
    BackHandler(enabled = showSeasonAbout && !isInPip) {
        showSeasonAbout = false
        activePlayback = null
    }

    val networkObserver = remember { com.example.utils.NetworkConnectivityObserver(context) }
    val isOnline by networkObserver.observe().collectAsState(initial = com.example.utils.NetworkUtils.isInternetAvailable(context))

    LaunchedEffect(isOnline, uiState.series, uiState.selectedSeason) {
        if (isOnline) {
            if (uiState.series == null) {
                viewModel.loadSeries(seriesId, forceRefresh = true, experienceMode = initialExperienceMode)
            } else if ((!uiState.isSeasonSynced || uiState.episodes.isEmpty()) && !uiState.isEpisodesLoading) {
                viewModel.triggerInitialEpisodesLoad()
            }
        }
    }

    LaunchedEffect(seriesId) {
        com.example.extension.managed.runtime.WorkDiscoverySessionManager.INSTANCE.onWorkChanged(seriesId, 1, 1)
        viewModel.loadSeries(seriesId, experienceMode = initialExperienceMode)
        com.example.ui.screens.player.ServerStateStore.prepareForMedia(seriesId, seriesId)
    }

    val pullRefreshState = rememberPullToRefreshState()
    val pullRefreshIndicator = com.example.ui.components.rememberBriefPullRefreshIndicator()
    PullToRefreshBox(
        isRefreshing = pullRefreshIndicator.visible,
        onRefresh = { pullRefreshIndicator.start(); viewModel.loadSeries(seriesId, experienceMode = initialExperienceMode) },
        state = pullRefreshState
    ) {
        val series = uiState.series
        if (series != null) {
            LaunchedEffect(series) {
                if (ContentTypeResolver.resolveSeries(series) == ContentType.ANIME ||
                    (series.genres.any { it.contains("Animation", ignoreCase = true) } && (series.originalLanguage == "ja" || series.originCountry.contains("JP"))) ||
                    com.example.data.repository.AnimePlaybackStore.isAnime(context, series.id.toString(), series.title)) {
                    com.example.data.repository.AnimePlaybackStore.markAsAnime(context, series.id.toString())
                }
            }
        }
        if (series == null) {
            DetailsSkeleton(onBack = onBack)
            return@PullToRefreshBox
        }
        if (series != null) {
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            val isSeriesAnime = (resolvedContentType == ContentType.ANIME) ||
                (series.genres.any { it.contains("Animation", ignoreCase = true) } && (series.originalLanguage == "ja" || series.originCountry.contains("JP"))) ||
                com.example.data.repository.AnimePlaybackStore.isAnime(context, series.id.toString(), series.title)
            val isFavorite = if (isSeriesAnime) isFavoriteAnime else isFavoriteTv
            LaunchedEffect(series.id, series.genreIds, isSeriesAnime) {
                viewModel.loadSimilarSeries(series, animeOnly = isSeriesAnime)
            }
            val currentSeriesLang = com.example.data.util.TmdbGenreHelper.getCurrentLanguage(context)
            val cachedPrimaryTrailer = remember(series.trailers, currentSeriesLang) {
                com.example.data.util.TrailerSelectionHelper.selectPrimaryTrailer(
                    series.trailers,
                    preferredLanguage = currentSeriesLang
                )
            }
            LaunchedEffect(series.id, currentSeriesLang) {
                viewModel.loadPrimaryTrailer(series.id, currentSeriesLang, series.trailers)
            }
            val selectedPrimaryTrailer = uiState.primaryTrailer ?: cachedPrimaryTrailer
            val displaySeriesGenres = remember(series.genreIds, series.genres, currentSeriesLang) {
                if (series.genreIds.isNotEmpty()) {
                    com.example.data.util.TmdbGenreHelper.getTvGenreNames(series.genreIds, currentSeriesLang)
                } else {
                    series.genres
                }
            }
            val isDubbedExperience = (initialExperienceMode.equals("ARABIC_DUBBED", ignoreCase = true) || initialExperienceMode.equals("DUBBED", ignoreCase = true) || series.mode.equals("dubbed", ignoreCase = true)) && !isSeriesAnime
            val effectiveMode = if (isDubbedExperience) "dubbed" else "subtitled"
            val seriesOrchestrator = remember { com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(context) }
            val seriesDubbedOrder = remember { seriesOrchestrator.registry.getSearchOrder().arabicDubbed }
            val seriesDisplayInfo = remember(series, isDubbedExperience, seriesDubbedOrder) {
                com.example.domain.search.MediaDisplayTitleResolver.resolveForSeries(
                    series = series,
                    isDubbedIntent = isDubbedExperience,
                    dubbedOrder = seriesDubbedOrder,
                    preferredProviderId = series.providerId,
                    contentType = resolvedContentType ?: ContentType.TV
                )
            }
            val displaySeriesTitle = seriesDisplayInfo.displayTitle
            val displaySeriesPoster = seriesDisplayInfo.displayPosterUrl?.takeIf { it.isNotBlank() } ?: series.posterUrl.takeIf { it.isNotBlank() } ?: series.backdropUrl
            val matchedDubbedCandidate = if (isDubbedExperience) {
                series.sourceCandidates.firstOrNull {
                    it.providerId == seriesDisplayInfo.providerId &&
                    it.mode == com.example.domain.models.MediaVariantMode.DUBBED &&
                    it.status == com.example.domain.models.CoverageStatus.FOUND &&
                    it.sourcePageUrl.isNotBlank()
                } ?: series.sourceCandidates.firstOrNull {
                    it.mode == com.example.domain.models.MediaVariantMode.DUBBED &&
                    it.status == com.example.domain.models.CoverageStatus.FOUND &&
                    it.sourcePageUrl.isNotBlank()
                }
            } else null
            val challengeController = remember { com.example.extension.managed.runtime.web.InteractiveChallengeController.getInstance() }
            val challengeUiState by challengeController.stateFlow.collectAsState()
            val isChallengeActive = challengeUiState.isVisible || challengeUiState.phase == com.example.extension.managed.runtime.web.ChallengeStateMachine.Phase.CHALLENGE_TIMEOUT

            LaunchedEffect(isChallengeActive) {
                if (isChallengeActive && listState.firstVisibleItemIndex > 0) {
                    listState.animateScrollToItem(0)
                }
            }
            val checkIsEpisodeDownloaded: (String) -> Boolean = { epId ->
                val epIdStr1 = "${series.id}_$epId"
                val epIdStr2 = epId
                com.example.utils.MediaStorageUtils.hasDownloadedMedia(context, epIdStr1, mode = effectiveMode, language = "ar") ||
                com.example.utils.MediaStorageUtils.hasDownloadedMedia(context, epIdStr2, mode = effectiveMode, language = "ar") ||
                downloads.any { (it.id == epIdStr1 || it.id == epIdStr2) && it.isCompleted && it.variantMode.equals(effectiveMode, ignoreCase = true) }
            }

            LaunchedEffect(series.id) {
                val rawTitle = series.originalTitle ?: series.title
                val mediaKey = "$rawTitle-false-1-1"
                com.example.ui.screens.player.ServerStateStore.prepareForMedia(mediaKey, series.id.toString())
            }

            val isSeriesLevelDownloaded = com.example.utils.MediaStorageUtils.hasDownloadedMedia(context, series.id.toString(), mode = effectiveMode, language = "ar") ||
                    downloads.any { it.mediaId == series.id.toString() && it.isCompleted && it.variantMode.equals(effectiveMode, ignoreCase = true) }

            val firstDownloadedEpisode = uiState.episodes.firstOrNull { checkIsEpisodeDownloaded(it.id) }

            val playSpecificEpisode: (Episode) -> Unit = { episode ->
                if (!restrictions.isWatchAllowed) {
                    Toast.makeText(context, context.getString(R.string.watching_restricted), Toast.LENGTH_LONG).show()
                } else {
                    val seasonNumber = uiState.selectedSeason?.seasonNumber ?: episode.seasonNumber
                    val isDownloaded = checkIsEpisodeDownloaded(episode.id) || isSeriesLevelDownloaded
                    if (!isDownloaded && !com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                        Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_SHORT).show()
                    } else {
                        val lastPlayback = com.example.utils.LastPlaybackStore.getLastPlayback(context, series.id.toString())
                        val syncPosition = com.example.ui.screens.player.PlaybackSyncStore.getPosition("${series.id}_${episode.id}")
                        val position = if (syncPosition > 0L) syncPosition else if (lastPlayback?.episodeId == episode.id) lastPlayback.positionMillis else 0L
                        val fileId = if (checkIsEpisodeDownloaded(episode.id)) "${series.id}_${episode.id}" else series.id.toString()
                        val episodeTitle = "${series.title} - S${seasonNumber}E${episode.episodeNumber}"
                        activePlayback = com.example.ui.components.ActiveInlinePlayback(
                            mediaId = series.id.toString(),
                            episodeId = episode.id,
                            seasonNumber = seasonNumber,
                            episodeNumber = episode.episodeNumber,
                            originalTitle = series.originalTitle ?: series.title,
                            title = episodeTitle,
                            url = if (isDownloaded) "local_offline_file://$fileId" else "auto_extract://",
                            posterUrl = episode.thumbnailUrl.ifBlank { series.posterUrl },
                            isMovie = false,
                            contentType = resolvedContentType ?: ContentTypeResolver.resolveSeries(series),
                            initialPosition = position,
                            initialQuality = lastPlayback?.quality,
                            mode = effectiveMode,
                            audioLanguage = if (effectiveMode.equals("dubbed", ignoreCase = true)) "ar" else "orig",
                            providerId = series.providerId
                        )
                        selectedTrailerId = null
                    }
                }
            }

            val requestEpisodeDownload: (Episode) -> Unit = { episode ->
                val existing = downloads.firstOrNull { it.id == "${series.id}_${episode.id}" || it.id == episode.id }
                when {
                    existing?.isCompleted == true -> {
                        episodeToDelete = existing
                        showEpisodeDeleteConfirm = true
                    }
                    existing != null -> {
                        val intent = Intent(context, com.example.utils.StreamDownloaderService::class.java).apply {
                            action = if (existing.isPaused) "RESUME" else "PAUSE"
                            putExtra("id", existing.id)
                        }
                        context.startService(intent)
                        scope.launch { downloadRepository.updateDownload(existing.copy(isPaused = !existing.isPaused)) }
                    }
                    !restrictions.isDownloadAllowed -> {
                        Toast.makeText(context, context.getString(R.string.downloading_restricted_by_admin), Toast.LENGTH_LONG).show()
                    }
                    else -> downloadEpisodeTarget = episode
                }
            }

            if (showSeasonAbout && uiState.selectedSeason != null) {
                SeasonAboutScreen(
                    series = series,
                    season = uiState.seasonDetails ?: uiState.selectedSeason!!,
                    allSeasons = series.seasons.filter { it.seasonNumber >= 0 },
                    episodes = uiState.episodes.filter { it.seasonNumber == uiState.selectedSeason?.seasonNumber },
                    isEpisodesLoading = uiState.isEpisodesLoading,
                    isSeasonDetailsLoading = uiState.isSeasonDetailsLoading,
                    visibleEpisodesCount = uiState.visibleEpisodesCount,
                    watchedEpisodeIds = watchedEpisodeIds,
                    downloads = downloads,
                    activePlayback = activePlayback,
                    onBack = { showSeasonAbout = false; activePlayback = null },
                    onSelectSeason = { season ->
                        activePlayback = null
                        viewModel.selectSeason(season)
                        viewModel.loadSelectedSeasonDetails()
                    },
                    onPlayEpisode = playSpecificEpisode,
                    onDownloadEpisode = requestEpisodeDownload,
                    onToggleWatched = { episode, isWatched ->
                        scope.launch {
                            if (isWatched) watchedRepo.markAsUnwatched(episode.id)
                            else watchedRepo.markAsWatched(episode.id)
                        }
                    },
                    onPersonClick = onPersonClick,
                    onLoadMore = { viewModel.loadMoreEpisodes(context) },
                    onPlaybackUrlExtracted = { realUrl -> activePlayback = activePlayback?.copy(url = realUrl) },
                    onFullscreen = { currentPosition ->
                        val playback = activePlayback
                        if (playback != null) {
                            if (currentPosition > 0L) {
                                com.example.ui.screens.player.PlaybackSyncStore.setPosition(
                                    "${playback.mediaId}_${playback.episodeId ?: ""}", currentPosition
                                )
                            }
                            activePlayback = null
                            onPlay(playback.title, playback.url, playback.serverName, playback.website,
                                playback.posterUrl, playback.contentType, playback.episodeId, playback.mode, playback.providerId)
                        }
                    },
                    onClosePlayback = { activePlayback = null },
                    onNavigateToExtensions = onNavigateToExtensions
                )
            } else {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                userScrollEnabled = !isInPip
            ) {
                item {
                val firstUnplayedEpisode = uiState.episodes.firstOrNull { !watchedEpisodeIds.contains(it.id) } ?: uiState.episodes.firstOrNull()

                val resumeLastPlayback = resume@{
                    val vMode = if (series.mode.equals("dubbed", ignoreCase = true)) com.example.domain.models.MediaVariantMode.DUBBED else com.example.domain.models.MediaVariantMode.SUBTITLED
                    val orchestrator = com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(context)
                    com.example.extension.managed.runtime.WorkDiscoverySessionManager.INSTANCE.resumeOnPlay(
                        workId = series.id.toString(),
                        variantMode = vMode,
                        audioLanguage = com.example.domain.models.VariantLanguage.ARABIC,
                        season = uiState.selectedSeason?.seasonNumber ?: 1,
                        episode = 1,
                        allEligibleProviders = orchestrator.registry.getAllExtensions()
                    )
                    if (!restrictions.isWatchAllowed) {
                        Toast.makeText(context, context.getString(R.string.watching_restricted), Toast.LENGTH_LONG).show()
                        return@resume
                    }
                    val lastPlayback = com.example.utils.LastPlaybackStore.getLastPlayback(context, series.id.toString())
                    val targetEpisode = if (lastPlayback?.episodeId != null) {
                        uiState.episodes.find { it.id == lastPlayback.episodeId } 
                            ?: firstDownloadedEpisode 
                            ?: firstUnplayedEpisode
                    } else {
                        firstDownloadedEpisode ?: firstUnplayedEpisode
                    } ?: uiState.episodes.firstOrNull()

                    if (targetEpisode != null) {
                        val ep = targetEpisode
                        val isEpDownloaded = checkIsEpisodeDownloaded(ep.id) || isSeriesLevelDownloaded
                        if (isEpDownloaded) {
                            val fullTitle = "${series.title} - S${uiState.selectedSeason?.seasonNumber ?: 1}E${ep.episodeNumber}"
                            val syncPos = com.example.ui.screens.player.PlaybackSyncStore.getPosition("${series.id}_${ep.id}")
                            val startPos = if (syncPos > 0L) syncPos else (if (lastPlayback?.episodeId == ep.id) lastPlayback.positionMillis else 0L)
                            val fileId = if (checkIsEpisodeDownloaded(ep.id)) "${series.id}_${ep.id}" else series.id.toString()
                            selectedTrailerId = null
                            activePlayback = com.example.ui.components.ActiveInlinePlayback(
                                mediaId = series.id.toString(),
                                episodeId = ep.id,
                                title = fullTitle,
                                url = "local_offline_file://$fileId",
                                posterUrl = ep.thumbnailUrl,
                                isMovie = false,
                                contentType = resolvedContentType ?: ContentTypeResolver.resolveSeries(series),
                                initialPosition = startPos,
                                mode = effectiveMode,
                                audioLanguage = if (effectiveMode.equals("dubbed", ignoreCase = true)) "ar" else "orig",
                                providerId = series.providerId
                            )
                            scope.launch { listState.animateScrollToItem(0) }
                        } else {
                            if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                                Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_LONG).show()
                            } else {
                                val rawTitle = series.originalTitle ?: series.title
                                val epMediaKey = "$rawTitle-false-${uiState.selectedSeason?.seasonNumber ?: 1}-${ep.episodeNumber}"
                                val altEpKey = "${series.title}-false-${uiState.selectedSeason?.seasonNumber ?: 1}-${ep.episodeNumber}"
                                val cachedData = com.example.ui.screens.player.ServerStateStore.getCachedData("${series.id}_${ep.id}", epMediaKey, altEpKey, series.id.toString())
                                val chosenQuality = (if (lastPlayback?.episodeId == ep.id) lastPlayback.quality else null)
                                    ?: cachedData?.extractedQualities?.firstOrNull()?.name ?: "Auto"

                                val targetCanonical = com.example.ui.screens.player.normalizeCanonicalQualityName(chosenQuality)
                                val matchingQualityUrl = if (targetCanonical != null && targetCanonical != "Auto") {
                                    cachedData?.extractedQualities?.find {
                                        com.example.ui.screens.player.normalizeCanonicalQualityName(it.name) == targetCanonical && it.url.isNotBlank() && it.url.startsWith("http")
                                    }?.url
                                } else null

                                val candidateUrl = if (lastPlayback != null && lastPlayback.url.isNotBlank() && lastPlayback.url.startsWith("http") && (lastPlayback.episodeId == null || lastPlayback.episodeId == ep.id)) {
                                    lastPlayback.url
                                } else if (!matchingQualityUrl.isNullOrBlank()) {
                                    matchingQualityUrl
                                } else if (cachedData != null && cachedData.extractedQualities.isNotEmpty()) {
                                    cachedData.extractedQualities.firstOrNull { it.url.isNotBlank() && it.url.startsWith("http") }?.url
                                } else if (cachedData != null && !cachedData.directStreamUrl.isNullOrBlank()) {
                                    cachedData.directStreamUrl
                                } else null

                                val cachedUrl = candidateUrl?.takeIf { com.example.ui.components.isValidPlayableMediaUrl(it) }

                                val fullTitle = "${series.title} - S${uiState.selectedSeason?.seasonNumber ?: 1}E${ep.episodeNumber}"
                                val syncPos = com.example.ui.screens.player.PlaybackSyncStore.getPosition("${series.id}_${ep.id}")
                                val startPos = if (syncPos > 0L) syncPos else (if (lastPlayback?.episodeId == ep.id) lastPlayback.positionMillis else 0L)

                                if (cachedUrl != null) {
                                    selectedTrailerId = null
                                    activePlayback = com.example.ui.components.ActiveInlinePlayback(
                                        mediaId = series.id.toString(),
                                        episodeId = ep.id,
                                        seasonNumber = uiState.selectedSeason?.seasonNumber ?: 1,
                                        episodeNumber = ep.episodeNumber,
                                        originalTitle = series.originalTitle ?: series.title,
                                        title = fullTitle,
                                        url = cachedUrl,
                                        serverName = lastPlayback?.serverName ?: cachedData?.servers?.firstOrNull(),
                                        website = lastPlayback?.website ?: cachedData?.website,
                                        posterUrl = ep.thumbnailUrl,
                                        isMovie = false,
                                        contentType = resolvedContentType ?: ContentTypeResolver.resolveSeries(series),
                                        initialPosition = startPos,
                                        initialQuality = chosenQuality,
                                        mode = effectiveMode,
                                        audioLanguage = if (effectiveMode.equals("dubbed", ignoreCase = true)) "ar" else "orig",
                                        providerId = series.providerId
                                    )
                                    scope.launch { listState.animateScrollToItem(0) }
                                } else {
                                    val isReleased = (series.firstAirDate ?: "") <= java.time.LocalDate.now().toString()
                                    if (!isReleased) {
                                        showNotReleasedDialog = true
                                    } else {
                                        selectedTrailerId = null
                                        activePlayback = com.example.ui.components.ActiveInlinePlayback(
                                            mediaId = series.id.toString(),
                                            episodeId = ep.id.toString(),
                                            seasonNumber = uiState.selectedSeason?.seasonNumber ?: 1,
                                            episodeNumber = ep.episodeNumber,
                                            originalTitle = series.originalTitle ?: series.title,
                                            title = fullTitle,
                                            url = "auto_extract://",
                                            serverName = null,
                                            website = null,
                                            posterUrl = ep.thumbnailUrl.ifBlank { series.posterUrl },
                                            isMovie = false,
                                            contentType = resolvedContentType ?: ContentTypeResolver.resolveSeries(series),
                                            initialPosition = 0L,
                                            initialQuality = chosenQuality,
                                            mode = effectiveMode,
                                            audioLanguage = if (effectiveMode.equals("dubbed", ignoreCase = true)) "ar" else "orig",
                                            providerId = series.providerId
                                        )
                                        scope.launch { listState.animateScrollToItem(0) }
                                    }
                                }

                                // Background revalidation: strictly trigger ONLY when user clicks play, and ONLY if >= 6 hours have passed!
                                val now = System.currentTimeMillis()
                                val safeKey = cachedData?.scraperKey?.ifBlank { "managed" } ?: "managed"
                                val jobKey = "$safeKey:${series.id}:${ep.id}"
                                val lastRun = com.example.extension.orchestrator.BackgroundMediaRevalidator.getLastRevalidationTime(jobKey).takeIf { it > 0L }
                                    ?: (cachedData?.lastUpdatedTimestamp ?: 0L)
                                val SIX_HOURS_MS = 6 * 3600 * 1000L
                                if (lastRun == 0L || (now - lastRun >= SIX_HOURS_MS)) {
                                    val knownUrl = cachedData?.playbackPageUrl ?: lastPlayback?.playbackPageUrl ?: cachedUrl ?: ""
                                    val sKey = cachedData?.scraperKey ?: lastPlayback?.scraperKey ?: ""
                                    val managedOrchestrator = com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(context)
                                    managedOrchestrator.revalidateMediaInBackground(
                                        mediaId = series.id.toString(),
                                        episodeId = ep.id,
                                        mediaTitle = rawTitle,
                                        isMovie = false,
                                        season = uiState.selectedSeason?.seasonNumber ?: 1,
                                        episode = ep.episodeNumber,
                                        knownPlaybackUrl = knownUrl,
                                        scraperKey = sKey,
                                        currentServerName = lastPlayback?.serverName ?: cachedData?.servers?.firstOrNull(),
                                        currentQuality = chosenQuality,
                                        altKeys = listOf("${series.id}_${ep.id}", epMediaKey, altEpKey, series.id.toString())
                                    )
                                }
                            }
                        }
                    } else {
                        val dlForSeries = downloads.find { (it.mediaId == series.id.toString() || it.id.startsWith("${series.id}_")) && it.isCompleted }
                        if (dlForSeries != null) {
                            selectedTrailerId = null
                            val fallbackEpId = if (dlForSeries.id.contains("_")) dlForSeries.id.substringAfter("_") else null
                            activePlayback = com.example.ui.components.ActiveInlinePlayback(
                                mediaId = series.id.toString(),
                                episodeId = fallbackEpId,
                                title = dlForSeries.title,
                                url = "local_offline_file://${dlForSeries.id}",
                                posterUrl = dlForSeries.posterUrl,
                                isMovie = false,
                                contentType = resolvedContentType ?: ContentTypeResolver.resolveSeries(series)
                            )
                            scope.launch { listState.animateScrollToItem(0) }
                        } else {
                            if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                                Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, context.getString(R.string.no_episodes_available), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                val startPlayFlow = {
                    resumeLastPlayback()
                }

                var hasAutoPlayed by remember { mutableStateOf(false) }
                LaunchedEffect(series, uiState.episodes, autoPlay, downloads) {
                    if (autoPlay && !hasAutoPlayed && (uiState.episodes.isNotEmpty() || downloads.isNotEmpty())) {
                        hasAutoPlayed = true
                        resumeLastPlayback()
                    }
                }

                
    if (showNotReleasedDialog && !isInPip) {
        AlertDialog(
            onDismissRequest = { showNotReleasedDialog = false },
            title = { Text(stringResource(R.string.coming_soon), color = MaterialTheme.colorScheme.onBackground) },
            text = { Text(stringResource(R.string.not_released_yet), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = { showNotReleasedDialog = false }) { Text(stringResource(R.string.ok_button)) }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // Hero Image or Video Player
                if (activePlayback != null) {
                    Box(modifier = if (isInPip) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f/9f)) {
                        com.example.ui.components.InlineDetailVideoPlayer(
                            playback = activePlayback!!,
                            onPlaybackUrlExtracted = { realUrl ->
                                activePlayback = activePlayback?.copy(url = realUrl)
                            },
                            onFullscreen = { currentPos ->
                                val p = activePlayback!!
                                val realUrl = if (!p.url.startsWith("auto_extract://") && com.example.ui.components.isValidPlayableMediaUrl(p.url)) {
                                    p.url
                                } else {
                                    val cached = com.example.ui.screens.player.ServerStateStore.getCachedData(
                                        series.id.toString(),
                                        p.mediaId
                                    )
                                    val candidate = cached?.directStreamUrl?.takeIf { it.isNotBlank() }
                                        ?: cached?.extractedQualities?.firstOrNull { it.url.isNotBlank() && it.name != "Auto" }?.url
                                    if (com.example.ui.components.isValidPlayableMediaUrl(candidate)) candidate!! else p.url
                                }
                                val syncKey = "${p.mediaId}_${p.episodeId ?: ""}"
                                if (currentPos > 0L) {
                                    com.example.ui.screens.player.PlaybackSyncStore.setPosition(syncKey, currentPos)
                                }
                                activePlayback = null
                                onPlay(p.title, realUrl, p.serverName, p.website, p.posterUrl, p.contentType, p.episodeId, p.mode, p.providerId)
                            },
                            onClose = {
                                activePlayback = null
                            },
                            onNavigateToExtensions = onNavigateToExtensions,
                            onChangeServer = {
                                if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                                    Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_SHORT).show()
                                } else {
                                    val targetEp = uiState.episodes.find { it.id.toString() == activePlayback?.episodeId }
                                        ?: firstUnplayedEpisode ?: uiState.episodes.firstOrNull()
                                    if (targetEp != null) {
                                        selectedTrailerId = null
                                        val fullTitle = "${series.title} - S${uiState.selectedSeason?.seasonNumber ?: 1}E${targetEp.episodeNumber}"
                                        activePlayback = com.example.ui.components.ActiveInlinePlayback(
                                            mediaId = series.id.toString(),
                                            episodeId = targetEp.id.toString(),
                                            seasonNumber = uiState.selectedSeason?.seasonNumber ?: 1,
                                            episodeNumber = targetEp.episodeNumber,
                                            title = fullTitle,
                                            url = "auto_extract://",
                                            serverName = null,
                                            website = null,
                                            posterUrl = targetEp.thumbnailUrl.ifBlank { series.posterUrl },
                                            isMovie = false,
                                            contentType = resolvedContentType ?: ContentTypeResolver.resolveSeries(series),
                                            initialPosition = 0L
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (!isInPip) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(text = displaySeriesTitle, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(modifier = Modifier.height(8.dp))
                        com.example.ui.screens.details.PlaybackDiagnosticsPanel(mediaId = series.id.toString())
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${series.year} • ${displaySeriesGenres.take(3).joinToString(" • ")}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, contentDescription = stringResource(R.string.rating_r), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(String.format("%.1f", series.rating), color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                            }
                            Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) { Text("18+", color = MaterialTheme.colorScheme.onBackground) }
                        }
                    }
                    }
                } else if (selectedTrailerId != null) {
                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f/9f)) {
                        com.example.ui.components.InlineYouTubePlayer(
                            videoId = selectedTrailerId!!,
                            modifier = Modifier.fillMaxSize(),
                            onClose = { selectedTrailerId = null }
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(text = displaySeriesTitle, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${series.year} • ${displaySeriesGenres.take(3).joinToString(" • ")}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, contentDescription = stringResource(R.string.rating_r), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(String.format("%.1f", series.rating), color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                            }
                            Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) { Text("18+", color = MaterialTheme.colorScheme.onBackground) }
                        }
                    }
                } else {
                    FlixQuestDetailsHero(
                        backdropUrl = series.backdropUrl,
                        fallbackPosterUrl = displaySeriesPoster ?: series.posterUrl,
                        title = displaySeriesTitle,
                        year = series.year,
                        rating = series.rating,
                        extraFact = when {
                            series.seasons.size == 1 -> "1 season"
                            series.seasons.size > 1 -> "${series.seasons.size} seasons"
                            else -> series.status.orEmpty()
                        },
                        genres = displaySeriesGenres,
                        onBack = onBack,
                        onCoverClick = { isCoverEnlarged = true },
                        onGenreClick = { index -> series.genreIds.getOrNull(index)?.let { onGenreClick(if (isSeriesAnime) "ANIME" else "SERIES", it) } }
                    )
                }
                
                if (!isInPip) {
                if (isDubbedExperience) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.variant_dubbed),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                if (isDubbedExperience) {
                    val rawTitle = series.originalTitle ?: series.title
                    val dubbedMediaKey = com.example.ui.screens.player.buildMediaKey(
                        title = rawTitle,
                        isMovie = false,
                        season = uiState.selectedSeason?.seasonNumber ?: 1,
                        episode = 1,
                        variantMode = "dubbed",
                        audioLang = "ar"
                    )
                    val dubbedCached = com.example.ui.screens.player.ServerStateStore.getCachedData(dubbedMediaKey, series.id.toString())
                    val isDubbedUnavailable = dubbedCached != null && dubbedCached.servers.isEmpty() && dubbedCached.directStreamUrl.isNullOrBlank()

                    if (isDubbedUnavailable) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.dubbed_unavailable_banner_message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // FlixQuest detail summary and primary actions. The season/episode stream remains native to CineStream.
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    val isAnyDownloaded = firstDownloadedEpisode != null || isSeriesLevelDownloaded
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(54.dp).combinedClickable(
                            onClick = { startPlayFlow() },
                            onLongClick = {
                                if (isAnyDownloaded) {
                                    val epDl = firstDownloadedEpisode?.let { ep -> downloads.find { it.id == "${series.id}_${ep.id}" || it.id == ep.id } }
                                        ?: downloads.find { it.mediaId == series.id.toString() }
                                    if (epDl != null) { episodeToDelete = epDl; showEpisodeDeleteConfirm = true }
                                }
                            }
                        ),
                        shape = RoundedCornerShape(13.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.play_now), tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(9.dp))
                            Text(if (isAnyDownloaded) stringResource(R.string.resume_offline) else stringResource(R.string.play), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(13.dp)).clickable {
                            if (!restrictions.isDownloadAllowed) {
                                Toast.makeText(context, context.getString(R.string.downloading_restricted_by_admin), Toast.LENGTH_LONG).show()
                            } else if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                                Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_SHORT).show()
                            } else {
                                showBatchDownloadSheet = true
                            }
                        },
                        shape = RoundedCornerShape(13.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Icon(Icons.Default.Download, contentDescription = stringResource(R.string.downloads), tint = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.width(9.dp))
                            Text(stringResource(R.string.downloads), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                if (series.overview.isNotBlank()) {
                    ExpandableSynopsis(
                        overview = series.overview,
                        modifier = Modifier.padding(horizontal = 18.dp),
                        collapsedMaxLines = 3
                    )
                }
                if (series.cast.isNotEmpty()) {
                    Text("${stringResource(R.string.details_starring)}: ${series.cast.take(3).joinToString(", ") { it.name }}", modifier = Modifier.padding(horizontal = 18.dp).padding(top = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (!series.creator.isNullOrBlank()) {
                    Text("${stringResource(R.string.details_created_by)}: ${series.creator}", modifier = Modifier.padding(horizontal = 18.dp).padding(top = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DetailsQuickActionBar(
                    isInMyList = isFavorite,
                    onMyList = {
                        scope.launch {
                            val item = LibraryItem.create(
                                contentType = if (isSeriesAnime) ContentType.ANIME else ContentTypeResolver.resolveSeries(series),
                                tmdbId = series.id,
                                title = displaySeriesTitle,
                                posterUrl = displaySeriesPoster ?: series.posterUrl,
                                variantMode = effectiveMode,
                                providerId = matchedDubbedCandidate?.providerId ?: series.providerId ?: ""
                            )
                            if (isFavorite) libraryRepository.removeFromLibrary(item) else libraryRepository.addToLibrary(item)
                        }
                    },
                    onLists = onOpenLists,
                    onTrailer = { selectedDetailsTab = "TRAILERS"; viewModel.showOtherTrailers(series.id, currentSeriesLang) },
                    onShare = { shareDetailsTitle(context, displaySeriesTitle, series.id, false) }
                )
                if (!isInPip && series.seasons.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        stringResource(R.string.episodes),
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { showSeasonPicker = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    uiState.selectedSeason?.title?.takeIf { it.isNotBlank() }
                                        ?: stringResource(R.string.season_number, uiState.selectedSeason?.seasonNumber ?: 1),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Default.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        TextButton(
                            onClick = {
                                viewModel.loadSelectedSeasonDetails()
                                showSeasonAbout = true
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text(stringResource(R.string.about_this_season), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (uiState.isEpisodesLoading && uiState.episodes.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (uiState.episodes.isEmpty()) {
                        Text(
                            stringResource(R.string.no_episodes_available),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)
                        )
                    } else {
                        uiState.episodes.take(uiState.visibleEpisodesCount).forEach { episode ->
                            val isWatched = watchedEpisodeIds.contains(episode.id)
                            val downloadItem = downloads.firstOrNull { it.id == "${series.id}_${episode.id}" || it.id == episode.id }
                            val isDownloaded = downloadItem?.isCompleted == true || checkIsEpisodeDownloaded(episode.id)
                            EpisodeCard(
                                episode = episode,
                                isWatched = isWatched,
                                downloadItem = downloadItem,
                                onClick = { playSpecificEpisode(episode) },
                                onLongClick = {
                                    if (isDownloaded) {
                                        episodeToDelete = downloadItem ?: com.example.data.model.DownloadItem(
                                            id = "${series.id}_${episode.id}", mediaId = series.id.toString(),
                                            title = "${series.title} - S${uiState.selectedSeason?.seasonNumber ?: 1}E${episode.episodeNumber}",
                                            posterUrl = episode.thumbnailUrl, isMovie = false, quality = "", progress = 1f,
                                            isPaused = false, isCompleted = true, fileSizeBytes = 0L
                                        )
                                        showEpisodeDeleteConfirm = true
                                    } else {
                                        scope.launch {
                                            if (isWatched) watchedRepo.markAsUnwatched(episode.id)
                                            else watchedRepo.markAsWatched(episode.id)
                                        }
                                    }
                                },
                                onDownloadClick = { requestEpisodeDownload(episode) },
                                onLongDownloadClick = { requestEpisodeDownload(episode) }
                            )
                        }
                        if (uiState.episodes.size > uiState.visibleEpisodesCount || uiState.isLoadingMore) {
                            TextButton(
                                onClick = { viewModel.loadMoreEpisodes(context) },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            ) {
                                if (uiState.isLoadingMore) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                else Text(stringResource(R.string.load_more))
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                DetailsSectionTabs(selectedDetailsTab) { tab ->
                    selectedDetailsTab = tab
                    if (tab == "TRAILERS") viewModel.showOtherTrailers(series.id, currentSeriesLang)
                }
                
                if (selectedDetailsTab == "TRAILERS") {
                    Text(stringResource(R.string.trailers), color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(8.dp))
                    DetailsTrailerList(
                        primaryTrailer = selectedPrimaryTrailer,
                        otherTrailers = uiState.otherTrailers,
                        isLoading = uiState.isLoadingOtherTrailers,
                        onSelect = { trailer ->
                            if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                                Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_SHORT).show()
                            } else {
                                activePlayback = null
                                selectedTrailerId = trailer.key
                            }
                        }
                    )
                    Spacer(Modifier.height(24.dp))
                }
                                if (selectedDetailsTab == "TRAILERS") {
                    DetailsArtworkGallerySection(
                        backdropImages = series.backdropImages.ifEmpty { listOf(series.backdropUrl).filter { it.isNotBlank() } },
                        posterImages = series.posterImages.ifEmpty { listOf((displaySeriesPoster ?: series.posterUrl)).filter { it.isNotBlank() } },
                        onOpenGallery = { title, images -> selectedArtworkGallery = DetailsArtworkGallery(title, images) }
                    )
                }
                if (selectedDetailsTab == "DETAILS") {
                    if (series.cast.isNotEmpty()) {
                        DetailsCastSection(series.cast, onPersonClick, keyPrefix = "series")
                    }
                    val originalTitleLabel = stringResource(R.string.details_original_title)
                    val statusLabel = stringResource(R.string.details_status)
                    val releaseDateLabel = stringResource(R.string.details_release_date)
                    val lastAirDateLabel = stringResource(R.string.details_last_air_date)
                    val seasonsLabel = stringResource(R.string.details_seasons)
                    val episodesLabel = stringResource(R.string.details_episodes)
                    val runtimeLabel = stringResource(R.string.details_runtime)
                    val originalLanguageLabel = stringResource(R.string.details_original_language)
                    val spokenLanguagesLabel = stringResource(R.string.details_spoken_languages)
                    val companiesLabel = stringResource(R.string.details_production_companies)
                    val countriesLabel = stringResource(R.string.details_production_countries)
                    val networksLabel = stringResource(R.string.details_networks)
                    val seriesInfoRows = buildList {
                        add(originalTitleLabel to (series.originalTitle ?: series.title))
                        series.status?.takeIf { it.isNotBlank() }?.let { add(statusLabel to it) }
                        series.firstAirDate?.takeIf { it.isNotBlank() }?.let { add(releaseDateLabel to it) }
                        series.lastAirDate?.takeIf { it.isNotBlank() }?.let { add(lastAirDateLabel to it) }
                        val seasonCount = series.numberOfSeasons ?: series.seasons.size
                        if (seasonCount > 0) add(seasonsLabel to seasonCount.toString())
                        series.numberOfEpisodes?.takeIf { it > 0 }?.let { add(episodesLabel to it.toString()) }
                        series.episodeRuntime.firstOrNull()?.takeIf { it > 0 }?.let { add(runtimeLabel to "$it min") }
                        series.originalLanguage?.takeIf { it.isNotBlank() }?.let { add(originalLanguageLabel to it.uppercase()) }
                        series.spokenLanguages.takeIf { it.isNotEmpty() }?.let { add(spokenLanguagesLabel to it.joinToString(", ")) }
                        series.productionCompanies.takeIf { it.isNotEmpty() }?.let { add(companiesLabel to it.joinToString(", ")) }
                        (series.productionCountries.ifEmpty { series.originCountry }).takeIf { it.isNotEmpty() }?.let { add(countriesLabel to it.joinToString(", ")) }
                        series.networkNames.takeIf { it.isNotEmpty() }?.let { add(networksLabel to it.joinToString(", ")) }
                    }
                    DetailsInfoTable(
                        title = stringResource(R.string.details_series_info),
                        rows = seriesInfoRows
                    )
                    DetailsExternalLinks(
                        imdbId = series.imdbId,
                        homepage = series.homepage,
                        onOpenUrl = { openDetailsExternalUrl(context, it) }
                    )

                }
                if (selectedDetailsTab == "MORE") {
                    DetailsRelatedSeriesGrid(
                        relatedItems = uiState.similarSeries,
                        hasMore = uiState.similarSeriesPage < uiState.similarSeriesTotalPages,
                        isLoadingMore = uiState.isLoadingMoreSimilar,
                        onLoadMore = viewModel::loadMoreSimilarSeries,
                        onClick = { related -> onRelatedSeriesClick(related.id, isSeriesAnime) }
                    )
                }
                } // Close if (!isInPip) block
                } // Close item block
                
            }
            } // close season-about content branch

            if (showSeasonPicker && !isInPip) {
                SeasonPickerBottomSheet(
                    seasons = series.seasons.filter { it.seasonNumber >= 0 },
                    selectedSeasonNumber = uiState.selectedSeason?.seasonNumber,
                    onDismiss = { showSeasonPicker = false },
                    onSelect = { selected ->
                        activePlayback = null
                        viewModel.selectSeason(selected)
                        showSeasonPicker = false
                    }
                )
            }

            if (downloadEpisodeTarget != null && !isInPip) {
                val ep = downloadEpisodeTarget!!
                com.example.ui.screens.player.SmartDownloadQualityDialog(
                    title = series.title,
                    originalTitle = series.originalTitle ?: series.title,
                    year = series.year.toString(),
                    isMovie = false,
                    season = uiState.selectedSeason?.seasonNumber ?: 1,
                    episode = ep.episodeNumber,
                    isAnime = series.genres.any { it.contains("Animation", ignoreCase = true) || it.contains("Anime", ignoreCase = true) },
                    posterUrl = ep.thumbnailUrl.ifBlank { series.posterUrl },
                    mediaId = series.id.toString(),
                    episodeId = ep.id,
                    onDismiss = { downloadEpisodeTarget = null },
                    onNavigateToExtensions = onNavigateToExtensions,
                    mode = effectiveMode,
                    providerId = series.providerId
                )
            }

            if (showBatchDownloadSheet && !isInPip) {
                com.example.ui.components.BatchDownloadSheet(
                    series = series,
                    currentSeason = uiState.selectedSeason,
                    episodes = uiState.episodes,
                    onDismiss = { showBatchDownloadSheet = false }
                )
            }

            selectedArtworkGallery?.let { gallery ->
                DetailsArtworkGalleryDialog(gallery) { selectedArtworkGallery = null }
            }

            if (isCoverEnlarged) {
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { isCoverEnlarged = false },
                    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.95f))
                            .clickable { isCoverEnlarged = false },
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = displaySeriesPoster,
                            contentDescription = displaySeriesTitle,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .clickable { isCoverEnlarged = false }
                        )
                        IconButton(
                            onClick = { isCoverEnlarged = false },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(16.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.cancel),
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            if (showEpisodeDeleteConfirm && !isInPip && episodeToDelete != null) {
                AlertDialog(
                    onDismissRequest = {
                        showEpisodeDeleteConfirm = false
                        episodeToDelete = null
                    },
                    title = { Text(stringResource(R.string.delete_download), color = MaterialTheme.colorScheme.onBackground) },
                    text = { Text(stringResource(R.string.delete_download_confirm), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    confirmButton = {
                        TextButton(onClick = {
                            episodeToDelete?.let { epDl ->
                                scope.launch {
                                    downloadRepository.removeFromDownloads(epDl)
                                    Toast.makeText(context, context.getString(R.string.download_deleted), Toast.LENGTH_SHORT).show()
                                }
                            }
                            showEpisodeDeleteConfirm = false
                            episodeToDelete = null
                        }) { Text(stringResource(R.string.yes_delete), color = Color.Red) }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showEpisodeDeleteConfirm = false
                            episodeToDelete = null
                        }) { Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onBackground) }
                    },
                    containerColor = MaterialTheme.colorScheme.surface
                )
            }
        }
    }
}

@Composable
fun TrailerCard(
    trailer: VideoTrailer,
    onClick: () -> Unit
) {
    val fallbackTitle = stringResource(R.string.trailers)
    val displayTitle = trailer.name.ifBlank { trailer.type.ifBlank { fallbackTitle } }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = "https://img.youtube.com/vi/${trailer.key}/hqdefault.jpg",
                contentDescription = displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.62f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.play_now),
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            displayTitle,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DetailsTrailerList(
    primaryTrailer: VideoTrailer?,
    otherTrailers: List<VideoTrailer>,
    isLoading: Boolean,
    onSelect: (VideoTrailer) -> Unit
) {
    val videos = remember(primaryTrailer, otherTrailers) {
        (listOfNotNull(primaryTrailer) + otherTrailers).filter { it.key.isNotBlank() }.distinctBy { it.key }
    }
    when {
        videos.isEmpty() && isLoading -> Box(
            Modifier.fillMaxWidth().padding(vertical = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        videos.isEmpty() -> Text(
            stringResource(R.string.details_no_trailers),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 22.dp)
        )
        else -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            videos.forEachIndexed { index, trailer ->
                if (index > 0) Spacer(Modifier.height(18.dp))
                TrailerCard(trailer = trailer) { onSelect(trailer) }
            }
            if (isLoading) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
fun CastMemberCard(cast: CastMember, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 560f),
        label = "cast-card-press-scale"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(80.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
    ) {
        val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
        AsyncImage(
            model = cast.profileUrl,
            placeholder = androidx.compose.ui.graphics.painter.ColorPainter(placeholderColor),
            error = androidx.compose.ui.graphics.painter.ColorPainter(placeholderColor),
            contentDescription = cast.name.ifEmpty { "Cast member" },
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(72.dp).clip(CircleShape).background(placeholderColor)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = cast.name.ifEmpty { "—" },
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (cast.character.isNotBlank()) {
            Text(
                text = cast.character,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun EpisodeCard(
    episode: Episode,
    isWatched: Boolean,
    downloadItem: com.example.data.model.DownloadItem? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onLongDownloadClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(120.dp).aspectRatio(16f/9f).clip(RoundedCornerShape(8.dp))
        ) {
            AsyncImage(
                model = episode.thumbnailUrl,
                contentDescription = episode.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.play_now), tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.align(Alignment.Center))
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${episode.episodeNumber}. ${episode.title}",
                color = if (isWatched) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Star, contentDescription = stringResource(R.string.rating_r), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(String.format("%.1f", episode.rating), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.width(8.dp))
                Text("${episode.duration}m", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(episode.overview, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val isEpisodeDownloaded = downloadItem?.isCompleted == true
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = onDownloadClick,
                    onLongClick = onLongDownloadClick
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isEpisodeDownloaded) {
                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.completed), tint = Color(0xFF4CAF50))
            } else if (downloadItem != null) {
                AnimatedDownloadIcon(isPaused = downloadItem.isPaused)
            } else {
                Icon(Icons.Default.Download, contentDescription = stringResource(R.string.downloads), tint = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
    }
@Composable
fun AnimatedDownloadIcon(isPaused: Boolean) {
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition()
    val offset by infiniteTransition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(800, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        )
    )
    
    androidx.compose.foundation.layout.Box(
        modifier = androidx.compose.ui.Modifier.size(24.dp).clipToBounds(), 
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        val tint = if (isPaused) androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.5f) else androidx.compose.material3.MaterialTheme.colorScheme.primary
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Default.ArrowDownward, 
            contentDescription = stringResource(R.string.downloading), 
            tint = tint, 
            modifier = androidx.compose.ui.Modifier.graphicsLayer {
                translationY = if (isPaused) 0f else offset * density
            }
        )
    


}




}


/** FlixQuest-style 16:9 artwork header with a back affordance and title facts beneath it. */
@Composable
private fun FlixQuestDetailsHero(
    backdropUrl: String,
    fallbackPosterUrl: String,
    title: String,
    year: Int,
    rating: Double,
    extraFact: String,
    genres: List<String>,
    onBack: () -> Unit,
    onCoverClick: () -> Unit,
    onGenreClick: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            AsyncImage(
                model = backdropUrl.takeIf { it.isNotBlank() } ?: fallbackPosterUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clickable(onClick = onCoverClick)
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = .35f), Color.Transparent, Color.Black.copy(alpha = .58f)),
                        startY = 0f
                    )
                )
            )
            Surface(
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp).size(42.dp),
                shape = CircleShape,
                color = Color.Black.copy(alpha = .52f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .18f))
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(year.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Default.Star, contentDescription = stringResource(R.string.rating_r), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Text(if (rating > 0) String.format("%.1f", rating) else "—", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (extraFact.isNotBlank()) {
                    Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(extraFact, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                if (year >= java.time.LocalDate.now().year - 1) {
                    Surface(shape = RoundedCornerShape(5.dp), color = Color.Black.copy(alpha = .72f)) {
                        Text(stringResource(R.string.details_new_badge), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
                    }
                }
            }
            if (genres.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(genres.distinct().take(8)) { index, genre ->
                        Surface(
                            onClick = { onGenreClick(index) },
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ) {
                            Text(genre, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsQuickActionBar(
    isInMyList: Boolean,
    onMyList: () -> Unit,
    onLists: () -> Unit,
    onTrailer: () -> Unit,
    onShare: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        DetailsQuickAction(Icons.Default.Bookmark, stringResource(R.string.my_list), isInMyList, onMyList)
        DetailsQuickAction(Icons.Default.PlaylistAdd, stringResource(R.string.details_lists), false, onLists)
        DetailsQuickAction(Icons.Default.VideoLibrary, stringResource(R.string.trailers), false, onTrailer)
        DetailsQuickAction(Icons.Default.Share, stringResource(R.string.share), false, onShare)
    }
}

@Composable
private fun DetailsQuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.widthIn(min = 62.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(25.dp))
        Text(label, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DetailsSectionTabs(selected: String, onSelect: (String) -> Unit) {
    val tabs = listOf(
        "MORE" to stringResource(R.string.details_more_like_this),
        "TRAILERS" to stringResource(R.string.details_trailers_more),
        "DETAILS" to stringResource(R.string.details_about)
    )
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(tabs, key = { it.first }) { (key, label) ->
            val isSelected = selected == key
            Surface(
                modifier = Modifier
                    .height(46.dp)
                    .clip(CircleShape)
                    .clickable { onSelect(key) },
                shape = CircleShape,
                color = if (isSelected) Color(0xFFF2F0F3) else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isSelected) Color(0xFF17131A) else MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 19.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = if (isSelected) Color(0xFF17131A) else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailsCastSection(
    cast: List<CastMember>,
    onPersonClick: (String) -> Unit,
    keyPrefix: String,
    onSeeAll: (() -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.cast),
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (onSeeAll != null) {
                TextButton(onClick = onSeeAll) {
                    Text(stringResource(R.string.see_all))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(cast.distinctBy { it.id }, key = { "${keyPrefix}_cast_${it.id}" }) { member ->
                CastMemberCard(member) { onPersonClick(member.id) }
            }
        }
    }
}

@Composable
private fun DetailsRelatedMovieGrid(
    relatedItems: List<Movie>,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    onClick: (Movie) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        if (relatedItems.isEmpty()) {
            Text(stringResource(R.string.details_no_related), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 18.dp))
        } else {
            relatedItems.chunked(3).forEachIndexed { rowIndex, rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(11.dp), modifier = Modifier.fillMaxWidth()) {
                    rowItems.forEach { movie ->
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onClick(movie) }
                        ) {
                            AsyncImage(
                                model = movie.posterUrl,
                                contentDescription = movie.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(.68f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                            Spacer(Modifier.height(7.dp))
                            Text(movie.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (movie.rating > 0) "★ ${String.format("%.1f", movie.rating)}" else "—", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
                if (rowIndex < (relatedItems.size + 2) / 3 - 1) Spacer(Modifier.height(16.dp))
            }
        }
        if (hasMore) {
            TextButton(
                onClick = onLoadMore,
                enabled = !isLoadingMore,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                if (isLoadingMore) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(9.dp))
                }
                Text(stringResource(R.string.show_more))
            }
        }
    }
}

@Composable
private fun DetailsRelatedSeriesGrid(
    relatedItems: List<Series>,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    onClick: (Series) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        if (relatedItems.isEmpty()) {
            Text(stringResource(R.string.details_no_related), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 18.dp))
        } else {
            relatedItems.chunked(3).forEachIndexed { rowIndex, rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(11.dp), modifier = Modifier.fillMaxWidth()) {
                    rowItems.forEach { series ->
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onClick(series) }
                        ) {
                            AsyncImage(
                                model = series.posterUrl,
                                contentDescription = series.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(.68f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                            Spacer(Modifier.height(7.dp))
                            Text(series.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (series.rating > 0) "★ ${String.format("%.1f", series.rating)}" else "—", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
                if (rowIndex < (relatedItems.size + 2) / 3 - 1) Spacer(Modifier.height(16.dp))
            }
        }
        if (hasMore) {
            TextButton(
                onClick = onLoadMore,
                enabled = !isLoadingMore,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                if (isLoadingMore) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(9.dp))
                }
                Text(stringResource(R.string.show_more))
            }
        }
    }
}

private data class DetailsArtworkGallery(val title: String, val images: List<String>)

@Composable
private fun DetailsArtworkGallerySection(
    backdropImages: List<String>,
    posterImages: List<String>,
    onOpenGallery: (String, List<String>) -> Unit
) {
    val backdrops = backdropImages.filter { it.isNotBlank() }.distinct()
    val posters = posterImages.filter { it.isNotBlank() }.distinct()
    val backdropsLabel = stringResource(R.string.details_backdrops)
    val postersLabel = stringResource(R.string.details_posters)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.details_images), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (backdrops.isNotEmpty()) {
            DetailsArtworkPreviewCard(
                imageUrl = backdrops.first(),
                label = backdropsLabel,
                count = backdrops.size,
                onClick = { onOpenGallery(backdropsLabel, backdrops) }
            )
            Spacer(Modifier.height(14.dp))
        }
        if (posters.isNotEmpty()) {
            DetailsArtworkPreviewCard(
                imageUrl = posters.first(),
                label = postersLabel,
                count = posters.size,
                onClick = { onOpenGallery(postersLabel, posters) }
            )
        }
        if (backdrops.isEmpty() && posters.isEmpty()) {
            Text(stringResource(R.string.details_no_images), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 18.dp))
        }
    }
}

@Composable
private fun DetailsArtworkPreviewCard(
    imageUrl: String,
    label: String,
    count: Int,
    onClick: () -> Unit
) {
    Box(
        Modifier.fillMaxWidth().height(176.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = label,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .82f))))
        )
        Row(
            Modifier.align(Alignment.BottomStart).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Icon(Icons.Default.Collections, contentDescription = null, tint = Color.White)
            Text("$count $label", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DetailsArtworkGalleryDialog(gallery: DetailsArtworkGallery, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(gallery.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close)) }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    itemsIndexed(gallery.images, key = { index, url -> "${gallery.title}_${index}_$url" }) { _, url ->
                        AsyncImage(
                            model = url,
                            contentDescription = gallery.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(280.dp).height(190.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                }
                Text("${gallery.images.size} ${gallery.title}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun DetailsCollectionCard(title: String, imageUrl: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)) {
            AsyncImage(model = imageUrl, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .82f)))))
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.details_view_collection), color = Color.White.copy(alpha = .88f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DetailsInfoTable(title: String, rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(12.dp))
        rows.forEachIndexed { index, (label, value) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.Top) {
                Text(label, modifier = Modifier.weight(.9f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Text(value, modifier = Modifier.weight(1.1f), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            }
            if (index < rows.lastIndex) Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
        }
    }
}

@Composable
private fun DetailsExternalLinks(
    imdbId: String?,
    homepage: String?,
    onOpenUrl: (String) -> Unit
) {
    val websiteLabel = stringResource(R.string.details_website)
    val links = buildList {
        imdbId?.takeIf { it.startsWith("tt") }?.let { add("IMDb" to "https://www.imdb.com/title/$it/") }
        homepage?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { add(websiteLabel to it) }
    }
    if (links.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.details_social_links), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            links.forEach { (label, url) ->
                Surface(
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onOpenUrl(url) },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null)
                        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

private fun openDetailsExternalUrl(context: android.content.Context, rawUrl: String) {
    val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return
    if (uri.scheme != "https" && uri.scheme != "http") return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.cannot_open_link), Toast.LENGTH_SHORT).show()
    }
}

private fun shareDetailsTitle(context: android.content.Context, title: String, id: String, isMovie: Boolean) {
    val mediaRoute = if (isMovie) "movie" else "tv"
    val url = "https://www.themoviedb.org/$mediaRoute/${android.net.Uri.encode(id)}"
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        this.type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, title)
        putExtra(android.content.Intent.EXTRA_TEXT, "$title\n$url")
    }
    context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.share)))
}

/** FlixQuest-style season chooser: every season remains selectable and shows its episode count. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeasonPickerBottomSheet(
    seasons: List<Season>,
    selectedSeasonNumber: Int?,
    onDismiss: () -> Unit,
    onSelect: (Season) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Text(
            stringResource(R.string.seasons_and_episodes),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(seasons, key = { "season_picker_${it.seasonNumber}" }) { season ->
                val selected = season.seasonNumber == selectedSeasonNumber
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(season) }
                        .padding(horizontal = 22.dp, vertical = 17.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            season.title.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.season_number, season.seasonNumber),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(R.string.episodes_count, season.episodeCount),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            }
        }
    }
}

/** Dedicated season-only detail surface. It intentionally contains no series recommendations. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeasonAboutScreen(
    series: Series,
    season: Season,
    allSeasons: List<Season>,
    episodes: List<Episode>,
    isEpisodesLoading: Boolean,
    isSeasonDetailsLoading: Boolean,
    visibleEpisodesCount: Int,
    watchedEpisodeIds: Set<String>,
    downloads: List<com.example.data.model.DownloadItem>,
    activePlayback: com.example.ui.components.ActiveInlinePlayback?,
    onBack: () -> Unit,
    onSelectSeason: (Season) -> Unit,
    onPlayEpisode: (Episode) -> Unit,
    onDownloadEpisode: (Episode) -> Unit,
    onToggleWatched: (Episode, Boolean) -> Unit,
    onPersonClick: (String) -> Unit,
    onLoadMore: () -> Unit,
    onPlaybackUrlExtracted: (String) -> Unit,
    onFullscreen: (Long) -> Unit,
    onClosePlayback: () -> Unit,
    onNavigateToExtensions: () -> Unit
) {
    var showSeasonPicker by rememberSaveable(series.id) { mutableStateOf(false) }
    var showAllCredits by rememberSaveable(series.id, season.seasonNumber) { mutableStateOf(false) }
    var selectedSeasonTrailerId by rememberSaveable(series.id, season.seasonNumber) { mutableStateOf<String?>(null) }
    BackHandler(enabled = showAllCredits) { showAllCredits = false }
    val posterImages = remember(season.posterImages, season.posterUrl, series.posterImages, series.posterUrl) {
        (season.posterImages.ifEmpty { series.posterImages } + listOf(season.posterUrl.ifBlank { series.posterUrl }))
            .filter { it.isNotBlank() }.distinct()
    }
    val backdropImages = remember(season.backdropImages, series.backdropImages) {
        (season.backdropImages.ifEmpty { series.backdropImages }).filter { it.isNotBlank() }.distinct()
    }
    val seasonYear = season.airDate?.take(4)?.toIntOrNull()
    val displaySeasonTitle = season.title.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.season_number, season.seasonNumber)

    if (showAllCredits) {
        SeasonCastAndCrewScreen(
            seriesTitle = series.title,
            seasonTitle = displaySeasonTitle,
            cast = season.cast,
            crew = season.crew,
            onBack = { showAllCredits = false },
            onPersonClick = onPersonClick
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(270.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (activePlayback != null) {
                com.example.ui.components.InlineDetailVideoPlayer(
                    playback = activePlayback,
                    onPlaybackUrlExtracted = onPlaybackUrlExtracted,
                    onFullscreen = onFullscreen,
                    onClose = onClosePlayback,
                    onNavigateToExtensions = onNavigateToExtensions,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                AsyncImage(
                    model = series.backdropUrl.ifBlank { season.posterUrl.ifBlank { series.posterUrl } },
                    contentDescription = series.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.18f),
                            0.55f to Color.Black.copy(alpha = 0.12f),
                            1f to MaterialTheme.colorScheme.background
                        )
                    )
                )
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(start = 14.dp, top = 10.dp)
                        .size(44.dp)
                        .background(Color.Black.copy(alpha = 0.48f), CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(4.dp))
            Text(
                series.title.uppercase(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = allSeasons.size > 1) { showSeasonPicker = true }
                    .padding(top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    displaySeasonTitle,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold
                )
                if (allSeasons.size > 1) {
                    Icon(Icons.Default.ExpandMore, contentDescription = stringResource(R.string.seasons_and_episodes), tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(30.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                seasonYear?.let {
                    Text(it.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    stringResource(R.string.episodes_count, season.episodeCount.takeIf { it > 0 } ?: episodes.size),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (season.rating > 0.0) {
                    Text("·  ★ ${String.format("%.1f", season.rating)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                }
                if (isSeasonDetailsLoading) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                }
            }

            Spacer(Modifier.height(22.dp))
            val firstEpisode = episodes.firstOrNull()
            Button(
                onClick = { firstEpisode?.let(onPlayEpisode) },
                enabled = firstEpisode != null && !isEpisodesLoading,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(9.dp))
                Text(
                    if (firstEpisode != null) "${stringResource(R.string.play)} S${season.seasonNumber}:E${firstEpisode.episodeNumber}"
                    else stringResource(R.string.play),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (season.overview.isNotBlank()) {
                Spacer(Modifier.height(22.dp))
                ExpandableSynopsis(overview = season.overview, modifier = Modifier.fillMaxWidth(), collapsedMaxLines = 3)
            }

            Spacer(Modifier.height(26.dp))
            Text(
                stringResource(R.string.episodes),
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            if (isEpisodesLoading && episodes.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (episodes.isEmpty()) {
                Text(stringResource(R.string.no_episodes_available), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 18.dp))
            } else {
                episodes.take(visibleEpisodesCount).forEach { episode ->
                    val downloadItem = downloads.firstOrNull { it.id == "${series.id}_${episode.id}" || it.id == episode.id }
                    EpisodeCard(
                        episode = episode,
                        isWatched = watchedEpisodeIds.contains(episode.id),
                        downloadItem = downloadItem,
                        onClick = { onPlayEpisode(episode) },
                        onLongClick = { onToggleWatched(episode, watchedEpisodeIds.contains(episode.id)) },
                        onDownloadClick = { onDownloadEpisode(episode) },
                        onLongDownloadClick = { onDownloadEpisode(episode) }
                    )
                }
                if (episodes.size > visibleEpisodesCount) {
                    TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.load_more))
                    }
                }
            }

            if (season.cast.isNotEmpty() || season.crew.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                if (season.cast.isNotEmpty()) {
                    DetailsCastSection(
                        season.cast,
                        onPersonClick,
                        keyPrefix = "season_${season.seasonNumber}",
                        onSeeAll = { showAllCredits = true }
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.crew), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { showAllCredits = true }) { Text(stringResource(R.string.see_all)) }
                    }
                }
            }

            if (season.trailers.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.details_videos),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                selectedSeasonTrailerId?.let { videoId ->
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).aspectRatio(16f / 9f)) {
                        com.example.ui.components.InlineYouTubePlayer(
                            videoId = videoId,
                            modifier = Modifier.fillMaxSize(),
                            onClose = { selectedSeasonTrailerId = null }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(season.trailers.distinctBy { it.key }, key = { "season_video_${season.seasonNumber}_${it.key}" }) { trailer ->
                        SeasonVideoCard(trailer = trailer, onClick = { selectedSeasonTrailerId = trailer.key })
                    }
                }
            }

            if (posterImages.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.details_posters),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(posterImages, key = { it }) { imageUrl ->
                        AsyncImage(
                            model = imageUrl,
                            contentDescription = displaySeasonTitle,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(112.dp).aspectRatio(0.68f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                }
            }
            if (backdropImages.isNotEmpty()) {
                Text(
                    stringResource(R.string.details_backdrops),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 26.dp)) {
                    items(backdropImages, key = { it }) { imageUrl ->
                        AsyncImage(
                            model = imageUrl,
                            contentDescription = displaySeasonTitle,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(220.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    if (showSeasonPicker) {
        SeasonPickerBottomSheet(
            seasons = allSeasons,
            selectedSeasonNumber = season.seasonNumber,
            onDismiss = { showSeasonPicker = false },
            onSelect = { selected ->
                showSeasonPicker = false
                onSelectSeason(selected)
            }
        )
    }
}

@Composable
private fun SeasonVideoCard(
    trailer: VideoTrailer,
    onClick: () -> Unit
) {
    val fallbackTitle = stringResource(R.string.details_videos)
    val displayTitle = trailer.name.ifBlank { trailer.type.ifBlank { fallbackTitle } }
    Column(
        modifier = Modifier
            .width(286.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = "https://img.youtube.com/vi/${trailer.key}/hqdefault.jpg",
                contentDescription = displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.62f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.play_now), tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            displayTitle,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SeasonCastAndCrewScreen(
    seriesTitle: String,
    seasonTitle: String,
    cast: List<CastMember>,
    crew: List<CrewMember>,
    onBack: () -> Unit,
    onPersonClick: (String) -> Unit
) {
    val hasCast = cast.isNotEmpty()
    val hasCrew = crew.isNotEmpty()
    var selectedTab by rememberSaveable(seriesTitle, seasonTitle) {
        mutableStateOf(if (hasCast) "CAST" else "CREW")
    }
    val departments = remember(crew) {
        crew.filter { it.name.isNotBlank() }
            .groupBy { it.department.ifBlank { "Crew" } }
            .toList()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "$seriesTitle · $seasonTitle".uppercase(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    letterSpacing = 1.2.sp
                )
                Text(
                    stringResource(R.string.cast_and_crew),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (hasCast && hasCrew) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("CAST" to R.string.cast, "CREW" to R.string.crew).forEach { (tab, label) ->
                    val selected = selectedTab == tab
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .clickable { selectedTab = tab },
                        shape = RoundedCornerShape(24.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                    ) {
                        Box(Modifier.padding(vertical = 15.dp), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(label),
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        when {
            selectedTab == "CAST" && hasCast -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 6.dp, bottom = 32.dp)
            ) {
                items(cast.sortedBy { it.order }, key = { "season_cast_${it.id}" }) { person ->
                    val episodeLabel = if (person.episodeCount > 0) stringResource(R.string.episodes_count, person.episodeCount) else null
                    SeasonCreditPersonRow(
                        name = person.name,
                        detail = listOfNotNull(person.character.takeIf { it.isNotBlank() }, episodeLabel).joinToString(" · "),
                        profileUrl = person.profileUrl,
                        onClick = { onPersonClick(person.id) }
                    )
                }
            }
            selectedTab == "CREW" && hasCrew -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 6.dp, bottom = 32.dp)
            ) {
                departments.forEach { (department, people) ->
                    item(key = "season_crew_department_$department") {
                        Text(
                            department.uppercase(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.6.sp,
                            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 8.dp)
                        )
                    }
                    items(people, key = { "season_crew_${department}_${it.id}" }) { person ->
                        val episodeLabel = if (person.episodeCount > 0) stringResource(R.string.episodes_count, person.episodeCount) else null
                        SeasonCreditPersonRow(
                            name = person.name,
                            detail = listOfNotNull(person.jobs.filter { it.isNotBlank() }.distinct().takeIf { it.isNotEmpty() }?.joinToString(", "), episodeLabel).joinToString(" · "),
                            profileUrl = person.profileUrl,
                            onClick = { onPersonClick(person.id) }
                        )
                    }
                }
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.details_no_related), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SeasonCreditPersonRow(
    name: String,
    detail: String,
    profileUrl: String?,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (!profileUrl.isNullOrBlank()) {
                AsyncImage(
                    model = profileUrl,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
