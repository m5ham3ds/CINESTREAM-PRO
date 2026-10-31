package com.example.extension.managed.runtime

import com.example.extension.managed.contract.BaseSiteScraper
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.*
import com.example.extension.managed.registry.ManagedExtensionRegistry
import com.example.extension.managed.registry.ManagedExtensionRuntimeRegistry
import com.example.extension.managed.registry.ScraperRegistry
import com.example.extension.managed.runtime.network.DefaultConnectivityMonitor
import com.example.extension.managed.runtime.web.ChallengeStateMachine
import com.example.extension.managed.runtime.web.InteractiveChallengeController
import com.example.extension.managed.runtime.web.PerSiteSessionStore
import com.example.extension.managed.runtime.web.ProtectedSiteSession
import com.example.extension.managed.runtime.web.ProtectedSessionState
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.runtime.context.ExtensionContext
import com.example.extension.managed.web.WebExtractionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression test: an HTTP challenge must open the global UI and retry the same operation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DefaultControlledManagedExtensionRuntimeChallengeRecoveryTest {

    @Test
    fun searchResumesOnTheSameScraperAfterInteractiveVerification() = runBlocking {
        val extension = ManagedExtension(
            id = "challenge-test",
            name = "Challenge Test",
            baseUrl = "https://challenge-test.example",
            scraperKey = "challenge-test",
            contentTypes = setOf(ContentType.ANIME),
            capabilities = setOf(ScraperCapability.SEARCH),
            capabilitiesConfigured = false
        )
        val scraper = ChallengeOnceScraper()
        val scraperRegistry = ScraperRegistry(mapOf("challenge-test" to scraper))
        val managedRegistry = ManagedExtensionRegistry(listOf(extension))
        val oldRegistry = ManagedExtensionRuntimeRegistry.INSTANCE
        val controller = InteractiveChallengeController.getInstance()
        val store = PerSiteSessionStore.getInstance()
        store.clearAllSessions()
        controller.reset()
        ManagedExtensionRuntimeRegistry.setTestInstance(managedRegistry)

        try {
            val runtime = DefaultControlledManagedExtensionRuntime(
                scraperRegistry = scraperRegistry,
                fallbackManager = FallbackManager(scraperRegistry),
                managedExtensionRegistry = managedRegistry,
                connectivityMonitor = DefaultConnectivityMonitor { true }
            )

            val operation = async(Dispatchers.Unconfined) {
                runtime.search(listOf(extension), SearchRequest("test title", ContentType.ANIME))
            }
            val challengeUi = withTimeout(5_000L) {
                controller.stateFlow.first { it.isVisible && it.phase == ChallengeStateMachine.Phase.WAITING_FOR_USER }
            }
            assertEquals("challenge-test", challengeUi.siteKey)
            assertEquals("https://challenge-test.example/search?q=test%20title", challengeUi.challengeUrl)

            controller.onVerificationCompleted(
                attemptId = challengeUi.attemptId,
                challengeKey = challengeUi.challengeKey,
                cookies = mapOf("cf_clearance" to "test-clearance"),
                userAgent = "CineStream-Test-UA"
            )

            val result = withTimeout(5_000L) { operation.await() }
            assertTrue("search should succeed after the exact operation is retried", result.isSuccess)
            assertEquals("the first attempt and the retry must use the same scraper", 2, scraper.searchCalls)
            assertFalse("the dialog should close only after the retry succeeds", controller.stateFlow.value.isVisible)
            assertEquals(ProtectedSessionState.ACTIVE, store.getSession("challenge-test", "challenge-test.example")?.state)
        } finally {
            controller.reset()
            store.clearAllSessions()
            ManagedExtensionRuntimeRegistry.setTestInstance(oldRegistry)
        }
    }

    @Test
    fun repeatedChallengeKeepsOriginalOperationAttachedAcrossManualRetry() = runBlocking {
        val extension = ManagedExtension(
            id = "challenge-twice",
            name = "Challenge Twice",
            baseUrl = "https://challenge-twice.example",
            scraperKey = "challenge-twice",
            contentTypes = setOf(ContentType.ANIME),
            capabilities = setOf(ScraperCapability.SEARCH),
            capabilitiesConfigured = false
        )
        val scraper = ChallengeTwiceScraper()
        val scraperRegistry = ScraperRegistry(mapOf("challenge-twice" to scraper))
        val managedRegistry = ManagedExtensionRegistry(listOf(extension))
        val oldRegistry = ManagedExtensionRuntimeRegistry.INSTANCE
        val controller = InteractiveChallengeController.getInstance()
        val store = PerSiteSessionStore.getInstance()
        store.clearAllSessions()
        controller.reset()
        ManagedExtensionRuntimeRegistry.setTestInstance(managedRegistry)

        try {
            val runtime = DefaultControlledManagedExtensionRuntime(
                scraperRegistry = scraperRegistry,
                fallbackManager = FallbackManager(scraperRegistry),
                managedExtensionRegistry = managedRegistry,
                connectivityMonitor = DefaultConnectivityMonitor { true }
            )
            val operation = async(Dispatchers.Unconfined) {
                runtime.search(listOf(extension), SearchRequest("repeat challenge", ContentType.ANIME))
            }
            val firstUi = withTimeout(5_000L) {
                controller.stateFlow.first { it.isVisible && it.phase == ChallengeStateMachine.Phase.WAITING_FOR_USER }
            }
            controller.onVerificationCompleted(firstUi.attemptId, firstUi.challengeKey,
                mapOf("cf_clearance" to "first-clearance"), "CineStream-Test-UA")

            val failedRetryUi = withTimeout(5_000L) {
                controller.stateFlow.first {
                    it.isVisible && it.verificationCompleted &&
                        it.phase == ChallengeStateMachine.Phase.EXTRACTION_RETRY &&
                        !it.errorMessage.isNullOrBlank()
                }
            }
            controller.retryChallenge()
            val secondUi = withTimeout(5_000L) {
                controller.stateFlow.first {
                    it.isVisible && it.attemptId > failedRetryUi.attemptId &&
                        it.phase == ChallengeStateMachine.Phase.WAITING_FOR_USER
                }
            }
            controller.onVerificationCompleted(secondUi.attemptId, secondUi.challengeKey,
                mapOf("cf_clearance" to "second-clearance"), "CineStream-Test-UA")

            val result = withTimeout(5_000L) { operation.await() }
            assertTrue("manual verification retry must resume the same search", result.isSuccess)
            assertEquals("first failure + challenged retry + successful manual retry", 3, scraper.searchCalls)
            assertFalse("dialog closes only after the original search succeeds", controller.stateFlow.value.isVisible)
        } finally {
            controller.reset()
            store.clearAllSessions()
            ManagedExtensionRuntimeRegistry.setTestInstance(oldRegistry)
        }
    }

    private class ChallengeOnceScraper : BaseSiteScraper {
        override val scraperKey = "challenge-test"
        override val implementationVersion = 1
        override val supportedCapabilities = setOf(ScraperCapability.SEARCH)
        override val supportedContentTypes = setOf(ContentType.ANIME)
        var searchCalls = 0

        override suspend fun search(extension: ManagedExtension, request: SearchRequest): Result<SearchResult> {
            searchCalls++
            if (searchCalls == 1) {
                return Result.failure(ExtensionError.CloudflareChallenge(
                    detail = "synthetic protected search response",
                    challengeUrl = "https://challenge-test.example/search?q=test%20title"
                ))
            }
            return Result.success(SearchResult(
                items = listOf(SearchMediaItem("test-1", "Test title", null, "https://challenge-test.example/anime/test-1", ContentType.ANIME)),
                page = 1,
                hasNextPage = false
            ))
        }

        override suspend fun getDetails(extension: ManagedExtension, url: String) =
            Result.failure<MediaDetailsResult>(UnsupportedOperationException("not used in this test"))

        override suspend fun getEpisodes(extension: ManagedExtension, seriesUrl: String, season: Int) =
            Result.failure<List<EpisodeItem>>(UnsupportedOperationException("not used in this test"))

        override suspend fun discoverServers(extension: ManagedExtension, session: ExtractionSession, request: ServerDiscoveryRequest, webEngine: WebExtractionEngine?) =
            Result.failure<ServerDiscoveryResult>(UnsupportedOperationException("not used in this test"))

        override suspend fun extractStream(extension: ManagedExtension, session: ExtractionSession, request: ExtractionRequest, webEngine: WebExtractionEngine?) =
            Result.failure<ExtractionResult>(UnsupportedOperationException("not used in this test"))
    }

    private class ChallengeTwiceScraper : BaseSiteScraper {
        override val scraperKey = "challenge-twice"
        override val implementationVersion = 1
        override val supportedCapabilities = setOf(ScraperCapability.SEARCH)
        override val supportedContentTypes = setOf(ContentType.ANIME)
        var searchCalls = 0

        override suspend fun search(extension: ManagedExtension, request: SearchRequest): Result<SearchResult> {
            searchCalls++
            if (searchCalls <= 2) {
                return Result.failure(ExtensionError.CloudflareChallenge(
                    detail = "synthetic repeat challenge $searchCalls",
                    challengeUrl = "https://challenge-twice.example/search?q=repeat"
                ))
            }
            return Result.success(SearchResult(
                items = listOf(SearchMediaItem("repeat-1", "Repeat test", null,
                    "https://challenge-twice.example/anime/repeat-1", ContentType.ANIME)),
                page = 1,
                hasNextPage = false
            ))
        }

        override suspend fun getDetails(extension: ManagedExtension, url: String) =
            Result.failure<MediaDetailsResult>(UnsupportedOperationException("not used in this test"))
        override suspend fun getEpisodes(extension: ManagedExtension, seriesUrl: String, season: Int) =
            Result.failure<List<EpisodeItem>>(UnsupportedOperationException("not used in this test"))
        override suspend fun discoverServers(extension: ManagedExtension, session: ExtractionSession, request: ServerDiscoveryRequest, webEngine: WebExtractionEngine?) =
            Result.failure<ServerDiscoveryResult>(UnsupportedOperationException("not used in this test"))
        override suspend fun extractStream(extension: ManagedExtension, session: ExtractionSession, request: ExtractionRequest, webEngine: WebExtractionEngine?) =
            Result.failure<ExtractionResult>(UnsupportedOperationException("not used in this test"))
    }
}
