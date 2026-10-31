package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.example.R
import com.example.navigation.Screen

/**
 * CineStream's two-level floating dock.
 *
 * Primary order: Home, New & Hot, centre menu, Discover, Profile.
 * Secondary order: Movies, Series, Search, Anime, Settings.
 * The secondary menu remains an action surface even while its centre glyph
 * reflects the currently selected secondary destination. Scroll collapse uses
 * the same interaction model as Zangetsu: labels fold away, icons scale down,
 * and the floating pill contracts while remaining interactive.
 */
@Composable
fun BottomNavBar(
    navController: NavController,
    modifier: Modifier = Modifier,
    onReselectItem: ((Screen) -> Unit)? = null,
    onNavigateToItem: ((Screen) -> Unit)? = null,
    collapsed: Boolean = false,
    onSecondaryMenuOpenChanged: (Boolean) -> Unit = {}
) {
    val entry by navController.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route
    var secondaryOpen by remember { mutableStateOf(false) }

    val primary = listOf(
        DockItem(Screen.Home, stringResource(R.string.home), Icons.Default.Home),
        DockItem(Screen.NewAndHot, stringResource(R.string.new_and_hot), Icons.Default.Whatshot),
        DockItem(Screen.Discover, stringResource(R.string.discover), Icons.Default.Explore),
        DockItem(Screen.Profile, stringResource(R.string.profile), Icons.Default.Person)
    )
    val secondary = listOf(
        DockItem(Screen.Movies, stringResource(R.string.movies), Icons.Default.Movie),
        DockItem(Screen.Series, stringResource(R.string.series), Icons.Default.Tv),
        DockItem(Screen.Search, stringResource(R.string.search), Icons.Default.Search),
        DockItem(Screen.Anime, stringResource(R.string.anime), Icons.Default.Face),
        DockItem(Screen.Settings, stringResource(R.string.settings), Icons.Default.Settings)
    )
    // Make the second dock label slot respect the user's system font scale.
    // A fixed 24dp label box can still clip glyphs on devices with enlarged text.
    val compactLabelHeight = with(LocalDensity.current) {
        (9.sp.toDp() + 4.dp).coerceAtLeast(14.dp)
    }
    // A genuinely compact five-item capsule: icon + one short label line + small insets.
    val secondaryDockHeight = (16.dp + 1.dp + compactLabelHeight + 7.dp)
        .coerceAtLeast(46.dp)
    val activeSecondaryItem = secondary.firstOrNull { it.screen.route == currentRoute }
    val secondarySelected = activeSecondaryItem != null
    val collapseProgress by animateFloatAsState(
        targetValue = if (collapsed) 1f else 0f,
        animationSpec = tween(
            durationMillis = 340,
            easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
        ),
        label = "bottom_dock_scroll_collapse"
    )
    val dockMaxWidth = 360.dp - (92.dp * collapseProgress)
    // Compact the primary capsule itself while preserving readable label line boxes.
    val dockHeight = 54.dp - (7.dp * collapseProgress)
    LaunchedEffect(currentRoute) {
        secondaryOpen = false
        onSecondaryMenuOpenChanged(false)
    }
    LaunchedEffect(secondaryOpen) { onSecondaryMenuOpenChanged(secondaryOpen) }

    fun navigateTo(screen: Screen) {
        secondaryOpen = false
        if (currentRoute == screen.route) {
            onReselectItem?.invoke(screen)
        } else {
            if (currentRoute == Screen.Search.route && screen != Screen.Search) {
                onNavigateToItem?.invoke(screen)
            } else {
                onNavigateToItem?.invoke(screen)
            }
            navController.navigate(screen.route) {
                popUpTo(Screen.Home.route) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            AnimatedVisibility(
                visible = secondaryOpen,
                enter = fadeIn(tween(180)) + slideInVertically(
                    animationSpec = tween(240),
                    initialOffsetY = { it / 3 }
                ) + expandVertically(animationSpec = tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing)),
                exit = fadeOut(tween(130)) + slideOutVertically(
                    animationSpec = tween(160),
                    targetOffsetY = { it / 4 }
                ) + shrinkVertically(animationSpec = tween(160))
            ) {
                Surface(
                    modifier = Modifier
                        // Keep the secondary dock visibly smaller than the primary dock.
                        // widthIn must wrap fillMaxWidth so the cap is applied before filling.
                        .widthIn(max = 270.dp)
                        .fillMaxWidth()
                        .height(secondaryDockHeight)
                        .shadow(6.dp, RoundedCornerShape(14.dp)),
                    shape = RoundedCornerShape(14.dp),
                    // Preserve the translucent surface; the page remains intentionally visible behind it.
                    color = Color(0xF016181C),
                    border = BorderStroke(1.dp, Color(0xFF343943))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(secondaryDockHeight)
                            .padding(horizontal = 2.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        secondary.forEach { item ->
                            DockItemView(
                                item = item,
                                selected = currentRoute == item.screen.route,
                                modifier = Modifier.weight(1f),
                                compact = true,
                                onClick = { navigateTo(item.screen) }
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .widthIn(max = dockMaxWidth)
                    .fillMaxWidth()
                    .shadow(8.dp, RoundedCornerShape(26.dp)),
                shape = RoundedCornerShape(26.dp),
                color = Color(0xF016181C),
                border = BorderStroke(1.dp, Color(0xFF2A2E37))
            ) {
                Row(
                    modifier = Modifier
                        .height(dockHeight)
                        .padding(
                            horizontal = 3.dp,
                            vertical = 2.dp
                        ),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DockItemView(
                        item = primary[0],
                        selected = currentRoute == Screen.Home.route,
                        modifier = Modifier.weight(1f),
                        collapseProgress = collapseProgress,
                        onClick = { navigateTo(Screen.Home) }
                    )
                    DockItemView(
                        item = primary[1],
                        selected = currentRoute == Screen.NewAndHot.route,
                        modifier = Modifier.weight(1f),
                        collapseProgress = collapseProgress,
                        onClick = { navigateTo(Screen.NewAndHot) }
                    )
                    val rotation by animateFloatAsState(
                        targetValue = if (secondaryOpen && !secondarySelected) 45f else 0f,
                        animationSpec = tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        label = "secondary_menu_rotation"
                    )
                    val menuSelected = secondarySelected || secondaryOpen
                    val menuIcon = activeSecondaryItem?.icon
                        ?: if (secondaryOpen) Icons.Default.Add else Icons.Default.Apps
                    val menuDescription = if (secondaryOpen) stringResource(R.string.close_menu)
                        else stringResource(R.string.home_menu)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = androidx.compose.material3.ripple(bounded = true, radius = 28.dp)
                            ) { secondaryOpen = !secondaryOpen },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        val menuButtonSize = 34.dp - (5.dp * collapseProgress)
                        Surface(
                            modifier = Modifier.size(menuButtonSize),
                            shape = CircleShape,
                            color = if (menuSelected) MaterialTheme.colorScheme.primary else Color(0xFF252A33),
                            border = BorderStroke(1.dp, if (menuSelected) MaterialTheme.colorScheme.primary.copy(alpha = .65f) else Color(0xFF3A404B))
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                AnimatedContent(
                                    targetState = menuIcon,
                                    transitionSpec = {
                                        fadeIn(tween(150)) + androidx.compose.animation.scaleIn(
                                            initialScale = .70f,
                                            animationSpec = tween(
                                                380,
                                                easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
                                            )
                                        ) togetherWith fadeOut(tween(100))
                                    },
                                    label = "secondary_menu_destination_glyph"
                                ) { icon ->
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = menuDescription,
                                        tint = if (menuSelected) MaterialTheme.colorScheme.onPrimary else Color.White,
                                        modifier = Modifier
                                            .size(22.dp - (3.dp * collapseProgress))
                                            .rotate(rotation)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(1.dp - (1.dp * collapseProgress)))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(14.dp * (1f - collapseProgress))
                                .clipToBounds(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (secondaryOpen) stringResource(R.string.close_menu)
                                    else stringResource(R.string.menu),
                                modifier = Modifier.fillMaxWidth(),
                                color = if (menuSelected) MaterialTheme.colorScheme.primary else Color(0xFF9AA2B1),
                                fontSize = (10f - (0.5f * collapseProgress)).sp,
                                lineHeight = 12.sp,
                                textAlign = TextAlign.Center,
                                softWrap = false,
                                maxLines = 1,
                                overflow = TextOverflow.Clip
                            )
                        }
                    }
                    DockItemView(
                        item = primary[2],
                        selected = currentRoute == Screen.Discover.route,
                        modifier = Modifier.weight(1f),
                        collapseProgress = collapseProgress,
                        onClick = { navigateTo(Screen.Discover) }
                    )
                    DockItemView(
                        item = primary[3],
                        selected = currentRoute == Screen.Profile.route,
                        modifier = Modifier.weight(1f),
                        collapseProgress = collapseProgress,
                        onClick = { navigateTo(Screen.Profile) }
                    )
                }
            }
        }
    }
}

private data class DockItem(
    val screen: Screen,
    val label: String,
    val icon: ImageVector
)

@Composable
private fun DockItemView(
    item: DockItem,
    selected: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    collapseProgress: Float = 0f,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val tint by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF8A92A0),
        animationSpec = tween(200),
        label = "dock_item_tint"
    )
    val iconSize = if (compact) 16.dp else 19.dp - (2.dp * collapseProgress)
    val labelAlpha = if (compact) 1f else 1f - collapseProgress
    // Content-aware label slots keep the capsules tight without clipping descenders.
    val labelHeight = if (compact) {
        with(LocalDensity.current) { (9.sp.toDp() + 4.dp).coerceAtLeast(14.dp) }
    } else {
        13.dp * labelAlpha
    }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(22.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.material3.ripple(bounded = false, color = tint)
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                val selectedEnter = if (targetState) {
                    fadeIn(tween(120)) + androidx.compose.animation.scaleIn(
                        initialScale = .70f,
                        animationSpec = tween(
                            380,
                            easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
                        )
                    )
                } else {
                    fadeIn(tween(90))
                }
                selectedEnter togetherWith fadeOut(tween(100))
            },
            label = "dock_selected_icon"
        ) { active ->
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = if (active) MaterialTheme.colorScheme.primary else Color(0xFF8A92A0),
                modifier = Modifier.size(iconSize)
            )
        }
        Spacer(Modifier.height(if (compact) 1.dp else 2.dp * labelAlpha))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(labelHeight)
                .clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = item.label,
                modifier = Modifier.fillMaxWidth(),
                color = tint.copy(alpha = labelAlpha),
                fontSize = if (compact) 8.5.sp else (9.5f - (0.5f * collapseProgress)).sp,
                lineHeight = if (compact) 10.sp else 10.5.sp,
                style = MaterialTheme.typography.labelSmall.copy(
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                ),
                textAlign = TextAlign.Center,
                softWrap = false,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
