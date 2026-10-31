package com.example.ui.components

import kotlin.math.abs

/**
 * Small, screen-independent version of Zangetsu's DockScrollCollapse policy.
 *
 * A negative Compose scroll delta (finger moving upward / content advancing)
 * collapses the dock; a deliberate reverse movement expands it. Direction
 * changes restart the accumulated threshold so touch jitter cannot toggle the
 * dock. The sign is intentionally opposite to Flutter ScrollNotification's
 * scrollDelta used in Zangetsu.
 */
internal class DockScrollCollapsePolicy(
    private val thresholdPx: Float = 60f
) {
    private var accumulatedPx: Float = 0f

    /** Returns the new collapsed state, or null when no state change is due. */
    fun onPostScroll(
        consumedY: Float,
        availableY: Float,
        currentlyCollapsed: Boolean
    ): Boolean? {
        // At the top edge, a downward gesture is left unconsumed by the page.
        // Match Zangetsu's immediate reset when the content reaches its start.
        // A negative remainder is usually the opposite edge (list bottom), so
        // it must not expand the dock.
        if (currentlyCollapsed && availableY > 0.5f) {
            accumulatedPx = 0f
            return false
        }

        // Horizontal rails and scroll events without vertical movement do not
        // participate in dock collapse. Reverse movement inside the content
        // expands only after the same deliberate directional threshold.
        if (abs(consumedY) < 0.5f) return null

        if ((consumedY > 0f && accumulatedPx < 0f) ||
            (consumedY < 0f && accumulatedPx > 0f)
        ) {
            accumulatedPx = 0f
        }
        accumulatedPx += consumedY

        return when {
            accumulatedPx < -thresholdPx && !currentlyCollapsed -> {
                accumulatedPx = 0f
                true
            }
            accumulatedPx > thresholdPx && currentlyCollapsed -> {
                accumulatedPx = 0f
                false
            }
            else -> null
        }
    }

    fun reset() {
        accumulatedPx = 0f
    }
}
