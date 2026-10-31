package com.example.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import coil.size.Precision
import com.example.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import android.os.SystemClock

private val MediaCardRevealAlphaEasing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

internal enum class PosterState {
    LOADING,
    SUCCESS,
    ERROR
}

@Composable
internal fun MediaCardPoster(
    posterUrl: String,
    mediaId: String?,
    title: String,
    modifier: Modifier = Modifier
) {
    var posterState by remember(posterUrl) {
        mutableStateOf(if (posterUrl.isBlank()) PosterState.ERROR else PosterState.LOADING)
    }
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        // Match FlixQuest MediaArt: decode at the actual rendered size (in
        // device pixels), not at the source image's full resolution.
        val decodeSize = remember(maxWidth, maxHeight, density) {
            with(density) {
                maxWidth.roundToPx().coerceAtLeast(1) to maxHeight.roundToPx().coerceAtLeast(1)
            }
        }
        if (posterState == PosterState.LOADING) {
            // FlixQuest uses a stable surface placeholder. Avoid running one
            // infinite shimmer animation per loading card during fast flings.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            )
        } else if (posterState == PosterState.ERROR) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        if (posterUrl.isNotBlank()) {
            val context = LocalContext.current
            val imageRequest = remember(posterUrl, mediaId, decodeSize) {
                val model = if (!mediaId.isNullOrBlank()) {
                    com.example.utils.DownloadedPostersManager.getPosterModel(context, mediaId, posterUrl)
                } else {
                    posterUrl
                }
                ImageRequest.Builder(context)
                    .data(model)
                    .size(decodeSize.first, decodeSize.second)
                    .precision(Precision.INEXACT)
                    // Include decode dimensions in memory keys so a poster
                    // cached at thumbnail size cannot be reused as a hero.
                    .memoryCacheKey("$posterUrl:${decodeSize.first}x${decodeSize.second}")
                    .diskCacheKey(posterUrl)
                    .transitionFactory(com.example.utils.SelectiveCrossfadeTransitionFactory(180))
                    .scale(Scale.FILL)
                    .build()
            }
            AsyncImage(
                model = imageRequest,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onLoading = {
                    posterState = PosterState.LOADING
                },
                onSuccess = {
                    posterState = PosterState.SUCCESS
                },
                onError = {
                    posterState = PosterState.ERROR
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCard(
    title: String,
    posterUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    rank: Int? = null,
    rating: Double? = null,
    year: String? = null,
    isMovie: Boolean = true,
    contentType: String = if (isMovie) com.example.data.model.ContentType.MOVIE else com.example.data.model.ContentType.TV,
    mediaId: String? = null,
    isBookmarked: Boolean = false,
    showZeroRating: Boolean = false,
    revealIndex: Int? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.965f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 520f),
        label = "media-card-press-scale"
    )

    // Stable, saveable identity prevents a card from replaying its entrance
    // every time a LazyRow/LazyColumn disposes and later recreates it.
    val revealKey = remember(contentType, mediaId, title, posterUrl) {
        "${contentType}:${mediaId?.takeIf { it.isNotBlank() } ?: "$title|$posterUrl"}"
    }
    val shouldAnimateReveal = !mediaId.isNullOrBlank() || posterUrl.isNotBlank()
    var hasRevealed by rememberSaveable(revealKey) {
        mutableStateOf(!shouldAnimateReveal)
    }
    val revealQueued = remember(revealKey) { AtomicBoolean(false) }
    val lastVisibilityCheck = remember(revealKey) { AtomicLong(0L) }
    val revealScope = rememberCoroutineScope()
    val inheritedRevealIndex = LocalMediaCardRevealIndex.current
    val revealIndexValue = revealIndex ?: inheritedRevealIndex ?: revealKey.hashCode()
    val revealMotion by animateFloatAsState(
        targetValue = if (hasRevealed) 1f else 0f,
        animationSpec = tween(
            durationMillis = MediaCardRevealPolicy.MOTION_DURATION_MILLIS,
            easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
        ),
        label = "media-card-rise-motion"
    )
    val density = LocalDensity.current
    val revealOffsetPx = with(density) {
        (MediaCardRevealPolicy.TRAVEL_DP * (1f - revealMotion)).dp.toPx()
    }
    val revealScale = 0.94f + (0.06f * revealMotion)
    // Like Zangetsu's 80ms VisibilityDetector cadence, do not traverse the
    // layout/clip tree on every scroll frame for every un-revealed card.
    val shouldCheckVisibility = shouldAnimateReveal &&
        !hasRevealed && !revealQueued.get()

    Box(
        modifier = modifier
            .width(140.dp)
            .aspectRatio(3f / 4f)
            // Root-local clipped bounds account for nested LazyRow/LazyColumn viewports,
            // not merely whether an item was precomposed just outside the screen.
            .onGloballyPositioned { coordinates ->
                if (shouldCheckVisibility && coordinates.isAttached) {
                    val now = SystemClock.uptimeMillis()
                    val previous = lastVisibilityCheck.get()
                    if (now - previous >= MediaCardRevealPolicy.VISIBILITY_CHECK_INTERVAL_MILLIS &&
                        lastVisibilityCheck.compareAndSet(previous, now)
                    ) {
                        val rootCoordinates = coordinates.findRootCoordinates()
                        val fullBounds = rootCoordinates.localBoundingBoxOf(coordinates, clipBounds = false)
                        val visibleBounds = rootCoordinates.localBoundingBoxOf(coordinates, clipBounds = true)
                        val visibleFraction = MediaCardRevealPolicy.visibleFraction(
                            totalWidth = fullBounds.width,
                            totalHeight = fullBounds.height,
                            clippedWidth = visibleBounds.width,
                            clippedHeight = visibleBounds.height
                        )
                        if (MediaCardRevealPolicy.shouldReveal(visibleFraction) &&
                            revealQueued.compareAndSet(false, true)
                        ) {
                            revealScope.launch {
                                delay(MediaCardRevealPolicy.staggerDelayMillis(revealIndexValue))
                                hasRevealed = true
                            }
                        }
                    }
                }
            }
            .then(
                if (pressScale != 1f || revealMotion < 1f) {
                    Modifier.graphicsLayer {
                        scaleX = pressScale * revealScale
                        scaleY = pressScale * revealScale
                        translationY = revealOffsetPx
                        // Match Zangetsu: the fade reaches full opacity before
                        // the 380ms rise/scale controller finishes settling.
                        alpha = MediaCardRevealAlphaEasing.transform(
                            (revealMotion * 1.4f).coerceIn(0f, 1f)
                        )
                    }
                } else {
                    Modifier
                }
            )
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        MediaCardPoster(
            posterUrl = posterUrl,
            mediaId = mediaId,
            title = title,
            modifier = Modifier.fillMaxSize()
        )

        // Gradient overlay for text readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha = 0.9f)),
                        startY = 150f
                    )
                )
        )

        // Top Badges
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            if (rank != null) {
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(text = rank.toString(), color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Spacer(modifier = Modifier.width(4.dp))
            }

            if (title.isNotBlank()) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder, 
                    contentDescription = stringResource(R.string.cd_bookmark), 
                    tint = if (isBookmarked) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Bottom Text (Title, Rating, Year)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
        ) {
            if (title.isNotBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = androidx.compose.ui.graphics.Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val displayRating = rating?.takeIf {
                    it.isFinite() && (it > 0.0 || (showZeroRating && it == 0.0))
                }
                val displayYear = year?.trim()?.takeIf { value ->
                    value.isNotEmpty() && value != "0" && value != "0000"
                }
                if (displayRating != null || displayYear != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (displayRating != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = stringResource(R.string.cd_rating),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = displayRating.toString(),
                                    color = Color.LightGray,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        if (displayYear != null) {
                            Text(
                                text = displayYear,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.75f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.45f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                )
            }
        }
    }
}
