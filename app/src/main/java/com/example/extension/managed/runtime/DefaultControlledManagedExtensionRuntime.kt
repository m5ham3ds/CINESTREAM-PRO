package com.example.extension.managed.runtime

import com.example.extension.managed.contract.ControlledManagedExtensionRuntime
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.EpisodeItem
import com.example.extension.managed.model.ExtensionLifecycleStatus
import com.example.extension.managed.model.ExtractionRequest
import com.example.extension.managed.model.ExtractionResult
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.model.ManagedExtensionValidator
import com.example.extension.managed.model.MediaDetailsResult
import com.example.extension.managed.model.ScraperCapability
import com.example.extension.managed.model.SearchRequest
import com.example.extension.managed.model.SearchResult
import com.example.extension.managed.model.ServerDiscoveryRequest
import com.example.extension.managed.model.ServerDiscoveryResult
import com.example.extension.managed.registry.ManagedExtensionRegistry
import com.example.extension.managed.registry.ManagedExtensionRuntimeRegistry
import com.example.extension.managed.registry.ScraperRegistry
import com.example.extension.managed.runtime.context.ExtensionContext
import com.example.extension.managed.runtime.web.InteractiveChallengeController
import com.example.extension.managed.runtime.web.PerSiteSessionStore
import com.example.extension.managed.runtime.web.ProtectedWebRuntimeError
import com.example.extension.managed.web.TrustedEmbedHostPolicy
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import com.example.extension.managed.runtime.network.ConnectivityMonitor
import com.example.extension.managed.runtime.network.DefaultConnectivityMonitor
import com.example.extension.managed.web.WebExtractionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Standard implementation of the Controlled Managed Extension Runtime.
 * Coordinates validation, compatibility gates, priority fallback, session management,
 * and normalized result output without UI or legacy dependencies.
 */
class DefaultControlledManagedExtensionRuntime(
    private val scraperRegistry: ScraperRegistry = ScraperRegistry.INSTANCE,
    private val fallbackManager: FallbackManager = FallbackManager(scraperRegistry),
    private val webEngineProvider: (() -> WebExtractionEngine)? = null,
    private val managedExtensionRegistry: ManagedExtensionRegistry = ManagedExtensionRegistry.INSTANCE,
    private val connectivityMonitor: ConnectivityMonitor = DefaultConnectivityMonitor(),
    override val currentAppVersionCode: Long = 1L,
    override val supportedRuntimeApiVersion: Int = 1
) : ControlledManagedExtensionRuntime {

    /**
     * Reconcile candidates against the latest Admin-managed snapshot immediately before execution.
     * The caller's search-order sequence is preserved; globally disabled providers are removed.
     */
    private fun reconcileWithLatestAdminState(candidates: List<ManagedExtension>): List<ManagedExtension> {
        val latest = ManagedExtensionRuntimeRegistry.INSTANCE.getAllExtensions()
        if (latest.isEmpty()) return candidates
        val latestById = latest.associateBy { it.id.trim().lowercase() }
        return candidates.mapNotNull { supplied ->
            val current = latestById[supplied.id.trim().lowercase()] ?: return@mapNotNull null
            if (current.status != ExtensionLifecycleStatus.ACTIVE ||
                !current.enabled || !current.userEnabled
            ) return@mapNotNull null
            current
        }
    }

    override suspend fun search(
        request: SearchRequest
    ): Result<SearchResult> = search(managedExtensionRegistry.getAllExtensions(), request)

    override suspend fun discoverServers(
        request: ServerDiscoveryRequest
    ): Result<ServerDiscoveryResult> = discoverServers(managedExtensionRegistry.getAllExtensions(), request)

    override suspend fun extractStream(
        request: ExtractionRequest
    ): Result<ExtractionResult> = extractStream(managedExtensionRegistry.getAllExtensions(), request)

    override suspend fun search(
        extensions: List<ManagedExtension>,
        request: SearchRequest
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        if (!connectivityMonitor.isConnected()) {
            return@withContext Result.failure(
                ExtensionError.NoInternet().let { Exception(it.message, it) }
            )
        }

        val targetContentType = request.contentType ?: ContentType.MOVIE
        val candidates = fallbackManager.filterAndSortCandidates(
            candidates = reconcileWithLatestAdminState(extensions),
            contentType = targetContentType,
            capability = ScraperCapability.SEARCH,
            appVersionCode = currentAppVersionCode,
            supportedRuntimeApi = supportedRuntimeApiVersion
        )

        if (candidates.isEmpty()) {
            return@withContext Result.failure(
                Exception(ExtensionError.ExtractionFailed("No compatible candidate extensions available for search").message)
            )
        }

        var lastError: Throwable? = null

        for (candidate in candidates) {
            val scraper = scraperRegistry.getScraper(candidate.scraperKey) ?: continue
            val result = executeWithInteractiveRecovery(
                extension = candidate,
                fallbackUrl = candidate.searchUrl?.takeIf { it.isNotBlank() } ?: candidate.baseUrl,
                contentType = targetContentType,
                mediaTitle = request.query,
                operationName = "search"
            ) { scraper.search(candidate, request) }
            if (result.isSuccess) {
                return@withContext result
            }

            val error = result.exceptionOrNull()
            lastError = error
            if (isChallengeInteractionTerminal(error) || isVerifiedChallengeStillOpen(error)) {
                return@withContext Result.failure(error ?: Exception("Challenge interaction stopped"))
            }
            val isRecoverable = when (error) {
                is ExtensionError -> fallbackManager.isRecoverable(error)
                else -> !((error?.message?.contains("NO_INTERNET", ignoreCase = true) == true) ||
                        (error?.message?.contains("No internet", ignoreCase = true) == true))
            }
            if (!isRecoverable) {
                return@withContext Result.failure(error ?: Exception("Unrecoverable error"))
            }
        }

        Result.failure(lastError ?: Exception(ExtensionError.ExtractionFailed("All candidate extensions failed for search").message))
    }

    override suspend fun getDetails(
        extension: ManagedExtension,
        url: String
    ): Result<MediaDetailsResult> = withContext(Dispatchers.IO) {
        val validation = validateAndCheckCompatibility(extension, ScraperCapability.DETAILS, null)
        if (validation.isFailure) {
            return@withContext Result.failure(validation.exceptionOrNull()!!)
        }

        val scraper = scraperRegistry.getScraper(extension.scraperKey)
            ?: return@withContext Result.failure(Exception(ExtensionError.MissingBundledScraper(extension.scraperKey).message))

        executeWithInteractiveRecovery(
            extension = extension,
            fallbackUrl = url,
            contentType = extension.contentTypes.firstOrNull() ?: ContentType.ANIME,
            operationName = "details"
        ) { scraper.getDetails(extension, url) }
    }

    override suspend fun getEpisodes(
        extension: ManagedExtension,
        seriesUrl: String,
        season: Int,
        contentType: ContentType
    ): Result<List<EpisodeItem>> = withContext(Dispatchers.IO) {
        val targetContentType = contentType.takeIf { extension.contentTypes.contains(it) }
            ?: extension.contentTypes.firstOrNull { it == ContentType.ANIME || it == ContentType.SERIES }
            ?: contentType
        val validation = validateAndCheckCompatibility(extension, ScraperCapability.EPISODES, targetContentType)
        if (validation.isFailure) {
            return@withContext Result.failure(validation.exceptionOrNull()!!)
        }

        val scraper = scraperRegistry.getScraper(extension.scraperKey)
            ?: return@withContext Result.failure(Exception(ExtensionError.MissingBundledScraper(extension.scraperKey).message))

        executeWithInteractiveRecovery(
            extension = extension,
            fallbackUrl = seriesUrl,
            contentType = targetContentType,
            operationName = "episodes"
        ) { scraper.getEpisodes(extension, seriesUrl, season) }
    }

    override suspend fun discoverServers(
        extensions: List<ManagedExtension>,
        request: ServerDiscoveryRequest
    ): Result<ServerDiscoveryResult> = withContext(Dispatchers.IO) {
        if (!connectivityMonitor.isConnected()) {
            return@withContext Result.failure(
                ExtensionError.NoInternet().let { Exception(it.message, it) }
            )
        }

        val targetContentType = request.contentType ?: if (request.isMovie) ContentType.MOVIE else ContentType.SERIES
        val candidates = fallbackManager.filterAndSortCandidates(
            candidates = reconcileWithLatestAdminState(extensions),
            contentType = targetContentType,
            capability = ScraperCapability.SERVER_DISCOVERY,
            appVersionCode = currentAppVersionCode,
            supportedRuntimeApi = supportedRuntimeApiVersion
        )

        if (candidates.isEmpty()) {
            return@withContext Result.failure(
                Exception(ExtensionError.ExtractionFailed("No compatible candidate extensions available for server discovery").message)
            )
        }

        var lastError: Throwable? = null

        for (candidate in candidates) {
            val scraper = scraperRegistry.getScraper(candidate.scraperKey) ?: continue
            val session = ExtractionSession(
                extensionId = candidate.id,
                scraperKey = candidate.scraperKey,
                targetPageUrl = request.targetUrl
            )

            val webEngine = webEngineProvider?.invoke()
            val context = ExtensionContext.from(
                extension = candidate,
                session = session,
                connectivity = connectivityMonitor,
                webEngine = webEngine
            )
            val result = executeWithInteractiveRecovery(
                extension = candidate,
                fallbackUrl = request.targetUrl,
                contentType = targetContentType,
                mediaTitle = request.mediaTitle,
                operationName = "server_discovery"
            ) { scraper.discoverServers(context, request) }
            if (result.isSuccess) {
                return@withContext result
            }

            val error = result.exceptionOrNull()
            lastError = error
            if (isChallengeInteractionTerminal(error) || isVerifiedChallengeStillOpen(error)) {
                return@withContext Result.failure(error ?: Exception("Challenge interaction stopped"))
            }
            val isRecoverable = when (error) {
                is ExtensionError -> fallbackManager.isRecoverable(error)
                else -> !((error?.message?.contains("NO_INTERNET", ignoreCase = true) == true) ||
                        (error?.message?.contains("No internet", ignoreCase = true) == true))
            }
            if (!isRecoverable) {
                return@withContext Result.failure(error ?: Exception("Unrecoverable error"))
            }
        }

        Result.failure(lastError ?: Exception(ExtensionError.ExtractionFailed("All candidate extensions failed for server discovery").message))
    }

    override suspend fun extractStream(
        extensions: List<ManagedExtension>,
        request: ExtractionRequest
    ): Result<ExtractionResult> = withContext(Dispatchers.IO) {
        if (!connectivityMonitor.isConnected()) {
            return@withContext Result.failure(
                ExtensionError.NoInternet().let { Exception(it.message, it) }
            )
        }

        val candidates = reconcileWithLatestAdminState(extensions).filter { ext ->
            val scraper = scraperRegistry.getScraper(ext.scraperKey) ?: return@filter false
            ManagedExtensionValidator.validate(ext) is ManagedExtensionValidator.ValidationResult.Valid &&
                    ext.status == ExtensionLifecycleStatus.ACTIVE &&
                    ext.enabled &&
                    ext.userEnabled &&
                    currentAppVersionCode >= ext.minAppVersionCode &&
                    ext.runtimeApiVersion <= supportedRuntimeApiVersion &&
                    ext.contentTypes.any { it in scraper.supportedContentTypes } &&
                    scraper.supportedCapabilities.contains(ScraperCapability.VIDEO_EXTRACTION) &&
                    ext.allowsCapability(ScraperCapability.VIDEO_EXTRACTION, scraper.supportedCapabilities)
        }.sortedByDescending { it.priority }

        if (candidates.isEmpty()) {
            return@withContext Result.failure(
                Exception(ExtensionError.ExtractionFailed("No candidate extensions available for stream extraction").message)
            )
        }

        var lastError: Throwable? = null

        for (candidate in candidates) {
            val scraper = scraperRegistry.getScraper(candidate.scraperKey) ?: continue
            val session = ExtractionSession(
                extensionId = candidate.id,
                scraperKey = candidate.scraperKey,
                targetPageUrl = request.serverItem.link
            )

            val webEngine = webEngineProvider?.invoke()
            val context = ExtensionContext.from(
                extension = candidate,
                session = session,
                connectivity = connectivityMonitor,
                webEngine = webEngine
            )
            val result = executeWithInteractiveRecovery(
                extension = candidate,
                fallbackUrl = request.serverItem.link,
                contentType = candidate.contentTypes.firstOrNull() ?: ContentType.ANIME,
                mediaTitle = request.mediaTitle,
                operationName = "stream_extraction"
            ) { scraper.extractStream(context, request) }
            if (result.isSuccess) {
                return@withContext result
            }

            val error = result.exceptionOrNull()
            lastError = error
            if (isChallengeInteractionTerminal(error) || isVerifiedChallengeStillOpen(error)) {
                return@withContext Result.failure(error ?: Exception("Challenge interaction stopped"))
            }
            val isRecoverable = when (error) {
                is ExtensionError -> fallbackManager.isRecoverable(error)
                else -> !((error?.message?.contains("NO_INTERNET", ignoreCase = true) == true) ||
                        (error?.message?.contains("No internet", ignoreCase = true) == true))
            }
            if (!isRecoverable) {
                return@withContext Result.failure(error ?: Exception("Unrecoverable error"))
            }
        }

        Result.failure(lastError ?: Exception(ExtensionError.ExtractionFailed("All candidate extensions failed for stream extraction").message))
    }

    /**
     * Retry the exact operation on the same extension after the user finishes the official
     * Cloudflare/Turnstile page. This wrapper is intentionally used by every runtime operation
     * so search/details/episodes/discovery/extraction share the same lifecycle.
     */
    private suspend fun <T> executeWithInteractiveRecovery(
        extension: ManagedExtension,
        fallbackUrl: String,
        contentType: ContentType,
        mediaTitle: String = "",
        operationName: String,
        operation: suspend () -> Result<T>
    ): Result<T> {
        suspend fun runSafely(): Result<T> = try {
            operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        }

        val first = runSafely()
        val firstError = first.exceptionOrNull()
        val challenge = findCloudflareChallenge(firstError) ?: return first
        val challengeUrl = resolveSafeChallengeUrl(challenge.challengeUrl, fallbackUrl, extension.baseUrl)
            ?: return first
        val host = try { URI(challengeUrl).host.orEmpty() } catch (_: Throwable) { return first }
        if (host.isBlank()) return first

        val controller = InteractiveChallengeController.getInstance()
        val context = InteractiveChallengeController.ExtractionContext(
            title = mediaTitle,
            contentType = contentType,
            extensionId = extension.id,
            scraperKey = extension.scraperKey,
            targetUrl = fallbackUrl,
            expectedOrigin = extension.baseUrl
        )
        val verified = controller.launchInteractiveChallenge(
            siteKey = extension.scraperKey,
            canonicalHost = PerSiteSessionStore.canonicalizeHost(host),
            challengeUrl = challengeUrl,
            context = context,
            timeoutMs = 180_000L
        )
        if (verified.isFailure) {
            val state = controller.stateFlow.value
            val sameChallengeSurface = state.isVisible &&
                state.siteKey.equals(extension.scraperKey, ignoreCase = true) &&
                PerSiteSessionStore.canonicalizeHost(state.canonicalHost) == PerSiteSessionStore.canonicalizeHost(host)
            // Timeouts and detectable-but-incomplete pages remain retryable. The dialog's Retry
            // button can reopen the official challenge and then resume this exact operation.
            if (sameChallengeSurface && state.phase in setOf(
                    com.example.extension.managed.runtime.web.ChallengeStateMachine.Phase.CHALLENGE_TIMEOUT,
                    com.example.extension.managed.runtime.web.ChallengeStateMachine.Phase.CHALLENGE_FAILED,
                    com.example.extension.managed.runtime.web.ChallengeStateMachine.Phase.WAITING_FOR_USER
                )) {
                return awaitManualChallengeRetry(controller, extension, operationName, ::runSafely)
            }
            return Result.failure(verified.exceptionOrNull() ?: firstError ?: challenge)
        }

        val retried = runSafely()
        if (retried.isSuccess) {
            controller.onRecoveryRetryCompleted(true, false)
            return retried
        }
        val retryError = retried.exceptionOrNull()
        val challengeStillPresent = findCloudflareChallenge(retryError) != null
        if (!challengeStillPresent) {
            controller.onRecoveryRetryCompleted(false, false, localizedOperationFailure(operationName))
            return retried
        }

        val currentUi = controller.stateFlow.value
        if (!currentUi.isVisible || !currentUi.verificationCompleted ||
            currentUi.phase != com.example.extension.managed.runtime.web.ChallengeStateMachine.Phase.EXTRACTION_RETRY) {
            return Result.failure(ProtectedWebRuntimeError.CloudflareChallengeCancelled(extension.scraperKey))
        }
        controller.onRecoveryRetryCompleted(false, true, localizedOperationFailure(operationName))
        return awaitManualChallengeRetry(controller, extension, operationName, ::runSafely)
    }

    private suspend fun <T> awaitManualChallengeRetry(
        controller: InteractiveChallengeController,
        extension: ManagedExtension,
        operationName: String,
        runOperation: suspend () -> Result<T>
    ): Result<T> {
        val outcome = CompletableDeferred<Result<T>>()
        controller.setPostVerificationRetryHandler(
            onRetry = {
                val next = try {
                    runOperation()
                } catch (cancelled: CancellationException) {
                    controller.clearPostVerificationRetryHandler()
                    outcome.complete(Result.failure(cancelled))
                    return@setPostVerificationRetryHandler
                } catch (failure: Throwable) {
                    Result.failure(failure)
                }
                if (next.isSuccess) {
                    controller.onRecoveryRetryCompleted(true, false)
                    controller.clearPostVerificationRetryHandler()
                    outcome.complete(next)
                } else {
                    val stillChallenge = findCloudflareChallenge(next.exceptionOrNull()) != null
                    if (stillChallenge) {
                        controller.onRecoveryRetryCompleted(false, true, localizedOperationFailure(operationName))
                    } else {
                        controller.onRecoveryRetryCompleted(false, false, localizedOperationFailure(operationName))
                        controller.clearPostVerificationRetryHandler()
                        outcome.complete(next)
                    }
                }
            },
            onExit = {
                outcome.complete(Result.failure(ProtectedWebRuntimeError.CloudflareChallengeCancelled(extension.scraperKey)))
            }
        )
        return outcome.await()
    }

    private fun findCloudflareChallenge(error: Throwable?): ExtensionError.CloudflareChallenge? {
        var current = error
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        repeat(12) {
            val item = current ?: return null
            if (!visited.add(item)) return null
            if (item is ExtensionError.CloudflareChallenge) return item
            current = item.cause
        }
        return null
    }

    private fun resolveSafeChallengeUrl(raw: String?, fallbackUrl: String, providerBaseUrl: String): String? {
        val candidates = listOfNotNull(raw?.takeIf { it.isNotBlank() }, fallbackUrl, providerBaseUrl)
        for (candidate in candidates) {
            val uri = try { URI(candidate.trim()) } catch (_: Throwable) { continue }
            val host = uri.host?.lowercase()?.trim().orEmpty()
            if (!uri.scheme.equals("https", ignoreCase = true) || host.isBlank() || uri.userInfo != null) continue
            if (TrustedEmbedHostPolicy.isForbiddenHost(host)) continue
            if (com.example.extension.managed.runtime.web.ProtectedSiteHttpClient.isProviderHttpsUrl(candidate, providerBaseUrl) ||
                TrustedEmbedHostPolicy.isKnownEmbedHost(host) || host == "challenges.cloudflare.com") {
                return uri.toASCIIString()
            }
        }
        return null
    }

    private fun isChallengeInteractionTerminal(error: Throwable?): Boolean {
        var current = error
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        repeat(12) {
            val item = current ?: return false
            if (!visited.add(item)) return false
            if (item is ProtectedWebRuntimeError.CloudflareChallengeCancelled ||
                item is ProtectedWebRuntimeError.CloudflareChallengeUnverifiedExit ||
                item is ProtectedWebRuntimeError.CloudflareChallengeTimeout) return true
            // A challenge surface is app-global, not owned by the current screen.
            // Do not silently fall through to another provider while a verified
            // surface is still being reused or is waiting for the user to retry.
            if (item is ProtectedWebRuntimeError.CloudflareChallengeFailed &&
                InteractiveChallengeController.getInstance().stateFlow.value.isVisible) return true
            current = item.cause
        }
        return false
    }

    private fun isVerifiedChallengeStillOpen(error: Throwable?): Boolean {
        if (findCloudflareChallenge(error) == null) return false
        val state = InteractiveChallengeController.getInstance().stateFlow.value
        return state.isVisible && state.verificationCompleted &&
            state.phase == com.example.extension.managed.runtime.web.ChallengeStateMachine.Phase.EXTRACTION_RETRY
    }

    private fun localizedOperationFailure(operationName: String): String = when (operationName) {
        "search" -> "The source still requires verification to finish search."
        "details" -> "The source still requires verification to load details."
        "episodes" -> "The source still requires verification to load episodes."
        "server_discovery" -> "The source still requires verification to find servers."
        else -> "The source still requires verification to extract the media stream."
    }

    private fun validateAndCheckCompatibility(
        extension: ManagedExtension,
        requiredCapability: ScraperCapability,
        requiredContentType: ContentType?
    ): Result<Unit> {
        val validation = ManagedExtensionValidator.validate(extension)
        if (validation is ManagedExtensionValidator.ValidationResult.Invalid) {
            return Result.failure(Exception(validation.error.message))
        }

        if (extension.status == ExtensionLifecycleStatus.DISABLED || !extension.enabled) {
            return Result.failure(Exception(ExtensionError.SourceDisabled(extension.id).message))
        }

        if (extension.status == ExtensionLifecycleStatus.MAINTENANCE) {
            return Result.failure(Exception(ExtensionError.MaintenanceHold(extension.id).message))
        }

        if (!extension.userEnabled) {
            return Result.failure(Exception(ExtensionError.UserDisabled(extension.id).message))
        }

        if (currentAppVersionCode < extension.minAppVersionCode) {
            return Result.failure(
                Exception(ExtensionError.IncompatibleAppVersion(currentAppVersionCode, extension.minAppVersionCode).message)
            )
        }

        if (extension.runtimeApiVersion > supportedRuntimeApiVersion) {
            return Result.failure(
                Exception(ExtensionError.IncompatibleRuntime(supportedRuntimeApiVersion, extension.runtimeApiVersion).message)
            )
        }

        val scraper = scraperRegistry.getScraper(extension.scraperKey)
            ?: return Result.failure(Exception(ExtensionError.UnknownScraperKey(extension.scraperKey).message))

        if (!scraper.supportedCapabilities.contains(requiredCapability) ||
            !extension.allowsCapability(requiredCapability, scraper.supportedCapabilities)) {
            return Result.failure(Exception(ExtensionError.CapabilityUnsupported(requiredCapability).message))
        }

        if (requiredContentType != null) {
            if (!extension.contentTypes.contains(requiredContentType)) {
                return Result.failure(Exception(ExtensionError.ContentTypeUnsupported(requiredContentType).message))
            }
            if (!scraper.supportedContentTypes.contains(requiredContentType)) {
                return Result.failure(Exception(ExtensionError.ContentTypeUnsupported(requiredContentType).message))
            }
        }

        return Result.success(Unit)
    }
}
