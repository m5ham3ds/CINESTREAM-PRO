package com.example.extension.managed.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDiagnosticsStoreLifecycleTest {
    @Test
    fun activeRunRejectsDuplicateBeginAndFinishedRunIgnoresLateMarks() {
        // Finish any state left by another test before starting this lifecycle scenario.
        PlaybackDiagnosticsStore.finish(false, "reset for test")
        PlaybackDiagnosticsStore.allowExplicitRetry("test-media")
        assertTrue(PlaybackDiagnosticsStore.begin("test-media", "Test title"))
        assertFalse(PlaybackDiagnosticsStore.begin("test-media", "Test title duplicate"))

        PlaybackDiagnosticsStore.registerProviders(listOf("provider-a" to "Provider A"))
        PlaybackDiagnosticsStore.mark(
            "provider-a",
            PlaybackDiagnosticStage.SOURCE_REACHABLE,
            PlaybackDiagnosticStatus.SUCCESS,
            "reachable"
        )
        PlaybackDiagnosticsStore.finish(true, "finished")

        val beforeLateMark = PlaybackDiagnosticsStore.state.value
        PlaybackDiagnosticsStore.mark(
            "provider-a",
            PlaybackDiagnosticStage.WORK_FOUND,
            PlaybackDiagnosticStatus.SUCCESS,
            "late callback must not change completed run"
        )
        val afterLateMark = PlaybackDiagnosticsStore.state.value

        assertFalse(afterLateMark.isRunning)
        assertEquals("finished", afterLateMark.summary)
        assertEquals(beforeLateMark.providers, afterLateMark.providers)
    }
    @Test
    fun completedRunCannotRestartUntilExplicitRetry() {
        PlaybackDiagnosticsStore.finish(false, "test cleanup")
        PlaybackDiagnosticsStore.allowExplicitRetry("sticky-media")
        assertTrue(PlaybackDiagnosticsStore.begin("sticky-media", "Title"))
        PlaybackDiagnosticsStore.finish(false, "failed once")

        assertFalse(PlaybackDiagnosticsStore.state.value.isRunning)
        assertFalse(PlaybackDiagnosticsStore.begin("sticky-media", "automatic duplicate"))
        assertEquals("failed once", PlaybackDiagnosticsStore.state.value.summary)

        PlaybackDiagnosticsStore.allowExplicitRetry("sticky-media")
        assertTrue(PlaybackDiagnosticsStore.begin("sticky-media", "user retry"))
        PlaybackDiagnosticsStore.finish(false, "retry failed")
        assertEquals("retry failed", PlaybackDiagnosticsStore.state.value.summary)
    }

    @Test
    fun laterFallbackFailureCannotDowngradeVerifiedStageSuccess() {
        PlaybackDiagnosticsStore.finish(false, "test cleanup")
        PlaybackDiagnosticsStore.allowExplicitRetry("monotonic-media")
        assertTrue(PlaybackDiagnosticsStore.begin("monotonic-media", "Title"))
        PlaybackDiagnosticsStore.registerProviders(listOf("provider-a" to "Provider A"))
        PlaybackDiagnosticsStore.markSourceReachability("provider-a", PlaybackDiagnosticStatus.SUCCESS, "site responded")
        PlaybackDiagnosticsStore.markSourceReachability("provider-a", PlaybackDiagnosticStatus.FAILED, "fallback query failed")
        PlaybackDiagnosticsStore.mark("provider-a", PlaybackDiagnosticStage.WORK_FOUND, PlaybackDiagnosticStatus.SUCCESS, "work found")
        PlaybackDiagnosticsStore.mark("provider-a", PlaybackDiagnosticStage.WORK_FOUND, PlaybackDiagnosticStatus.FAILED, "later query failed")

        val provider = PlaybackDiagnosticsStore.state.value.providers.single()
        assertEquals(PlaybackDiagnosticStatus.SUCCESS, provider.checks[PlaybackDiagnosticStage.SOURCE_REACHABLE]?.status)
        assertEquals(PlaybackDiagnosticStatus.SUCCESS, provider.checks[PlaybackDiagnosticStage.WORK_FOUND]?.status)
        PlaybackDiagnosticsStore.finish(false, "test complete")
    }

    @Test
    fun completedRunForSameTitleCannotRestartWhenEntryPointUsesDifferentMediaId() {
        PlaybackDiagnosticsStore.finish(false, "test cleanup")
        PlaybackDiagnosticsStore.allowExplicitRetry("cleanup-id")
        assertTrue(PlaybackDiagnosticsStore.begin("database-id", "Black Clover"))
        PlaybackDiagnosticsStore.finish(false, "terminal results")

        assertFalse(PlaybackDiagnosticsStore.begin("episode-id", "Black Clover"))
        assertEquals("terminal results", PlaybackDiagnosticsStore.state.value.summary)

        // An explicit retry remains the only way to clear the terminal snapshot.
        PlaybackDiagnosticsStore.allowExplicitRetry("episode-id")
        assertTrue(PlaybackDiagnosticsStore.begin("episode-id", "Black Clover"))
        PlaybackDiagnosticsStore.finish(false, "retry results")
        assertEquals("retry results", PlaybackDiagnosticsStore.state.value.summary)
    }

}
