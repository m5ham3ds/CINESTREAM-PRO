package com.example.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCardRevealPolicyTest {
    @Test
    fun visibleFractionUsesClippedAreaAndClampsToOne() {
        assertEquals(
            0.5f,
            MediaCardRevealPolicy.visibleFraction(100f, 100f, 50f, 100f),
            0.0001f
        )
        assertEquals(
            1f,
            MediaCardRevealPolicy.visibleFraction(100f, 100f, 120f, 100f),
            0.0001f
        )
    }

    @Test
    fun emptyOrInvalidBoundsDoNotTriggerReveal() {
        assertEquals(0f, MediaCardRevealPolicy.visibleFraction(0f, 100f, 40f, 100f), 0f)
        assertFalse(MediaCardRevealPolicy.shouldReveal(0f))
        assertFalse(MediaCardRevealPolicy.shouldReveal(0.005f))
        assertTrue(MediaCardRevealPolicy.shouldReveal(0.02f))
    }

    @Test
    fun visibilityChecksUseZangetsuCadence() {
        assertEquals(80L, MediaCardRevealPolicy.VISIBILITY_CHECK_INTERVAL_MILLIS)
    }

    @Test
    fun staggerRunsAcrossEightItemsWithoutNegativeDelays() {
        assertEquals(0L, MediaCardRevealPolicy.staggerDelayMillis(0))
        assertEquals(45L, MediaCardRevealPolicy.staggerDelayMillis(1))
        assertEquals(315L, MediaCardRevealPolicy.staggerDelayMillis(7))
        assertEquals(0L, MediaCardRevealPolicy.staggerDelayMillis(8))
        assertEquals(315L, MediaCardRevealPolicy.staggerDelayMillis(-1))
    }
}
