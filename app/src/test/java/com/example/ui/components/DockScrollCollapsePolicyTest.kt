package com.example.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DockScrollCollapsePolicyTest {
    @Test
    fun collapseWaitsForDirectionalThreshold() {
        val policy = DockScrollCollapsePolicy(thresholdPx = 60f)
        assertNull(policy.onPostScroll(consumedY = -24f, availableY = 0f, currentlyCollapsed = false))
        assertNull(policy.onPostScroll(consumedY = -30f, availableY = 0f, currentlyCollapsed = false))
        assertEquals(true, policy.onPostScroll(consumedY = -8f, availableY = 0f, currentlyCollapsed = false))
    }

    @Test
    fun reversingDirectionExpandsAfterThreshold() {
        val policy = DockScrollCollapsePolicy(thresholdPx = 60f)
        assertEquals(true, policy.onPostScroll(-61f, 0f, false))
        assertNull(policy.onPostScroll(25f, 0f, true))
        assertEquals(false, policy.onPostScroll(40f, 0f, true))
    }

    @Test
    fun directionChangeRestartsAccumulationInsteadOfAddingOppositeMovement() {
        val policy = DockScrollCollapsePolicy(thresholdPx = 60f)
        assertNull(policy.onPostScroll(-45f, 0f, false))
        assertNull(policy.onPostScroll(40f, 0f, false))
        assertNull(policy.onPostScroll(-20f, 0f, false))
        assertEquals(true, policy.onPostScroll(-45f, 0f, false))
    }

    @Test
    fun scrollingBackTowardTopExpandsAfterReverseThreshold() {
        val policy = DockScrollCollapsePolicy(thresholdPx = 60f)
        assertEquals(true, policy.onPostScroll(-61f, 0f, false))
        assertNull(policy.onPostScroll(28f, 0f, true))
        assertEquals(false, policy.onPostScroll(34f, 0f, true))
    }

    @Test
    fun zeroVerticalDeltaDoesNotReactToHorizontalScrolling() {
        val policy = DockScrollCollapsePolicy(thresholdPx = 60f)
        assertNull(policy.onPostScroll(0f, 0f, false))
    }

    @Test
    fun reachingTopEdgeExpandsImmediatelyLikeZangetsu() {
        val policy = DockScrollCollapsePolicy(thresholdPx = 60f)
        assertEquals(true, policy.onPostScroll(-61f, 0f, false))
        assertEquals(false, policy.onPostScroll(consumedY = 0f, availableY = 12f, currentlyCollapsed = true))
    }
}
