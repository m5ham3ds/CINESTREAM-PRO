package com.example.ui.components

import androidx.compose.runtime.compositionLocalOf

/** Visual index supplied by lazy grid/list containers for a stable stagger order. */
internal val LocalMediaCardRevealIndex = compositionLocalOf<Int?> { null }

/**
 * Geometry and timing rules for the one-shot media-card reveal animation.
 * Values mirror Zangetsu's RevealItem rise preset.
 */
internal object MediaCardRevealPolicy {
    const val TRAVEL_DP = 28f
    const val MOTION_DURATION_MILLIS = 380
    const val FADE_DURATION_MILLIS = 270
    const val STAGGER_STEP_MILLIS = 45
    const val STAGGER_BUCKETS = 8
    const val VISIBILITY_CHECK_INTERVAL_MILLIS = 80L
    const val MIN_VISIBLE_FRACTION = 0.01f

    fun visibleFraction(
        totalWidth: Float,
        totalHeight: Float,
        clippedWidth: Float,
        clippedHeight: Float
    ): Float {
        val totalArea = totalWidth.coerceAtLeast(0f) * totalHeight.coerceAtLeast(0f)
        if (totalArea <= 0f) return 0f
        val visibleArea = clippedWidth.coerceAtLeast(0f) * clippedHeight.coerceAtLeast(0f)
        return (visibleArea / totalArea).coerceIn(0f, 1f)
    }

    fun shouldReveal(visibleFraction: Float): Boolean =
        visibleFraction > MIN_VISIBLE_FRACTION

    fun staggerDelayMillis(index: Int): Long {
        val bucket = ((index % STAGGER_BUCKETS) + STAGGER_BUCKETS) % STAGGER_BUCKETS
        return (STAGGER_STEP_MILLIS * bucket).toLong()
    }
}
