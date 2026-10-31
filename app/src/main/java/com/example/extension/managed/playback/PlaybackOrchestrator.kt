package com.example.extension.managed.playback

import com.example.extension.managed.contract.ControlledManagedExtensionRuntime
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ExtractionRequest
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.model.PlaybackSource
import com.example.extension.managed.model.QualitySource
import com.example.extension.managed.model.ScraperCapability
import com.example.extension.managed.model.SearchRequest
import com.example.extension.managed.model.EpisodeSearchQueryBuilder
import com.example.extension.managed.model.ServerDiscoveryRequest
import com.example.extension.managed.playback.PlaybackDiagnosticsStore
import com.example.extension.managed.playback.PlaybackDiagnosticStage
import com.example.extension.managed.playback.PlaybackDiagnosticStatus
import com.example.extension.managed.registry.ManagedExtensionRegistry
import com.example.extension.managed.runtime.web.CloudflareForensicLogger
import com.example.extension.managed.runtime.web.InteractiveChallengeController
import com.example.extension.managed.runtime.web.PerSiteSessionStore
import com.example.extension.managed.runtime.web.ProtectedWebRuntime
import com.example.extension.managed.runtime.web.ProtectedWebRuntimeError
import com.example.extension.managed.searchorder.ExtensionEligibilityFilter
import com.example.extension.managed.searchorder.SearchOrderRepository
import com.example.extension.managed.trace.Phase05HLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException

sealed interface PlaybackOrchestratorOutcome {
    data class Started(
        val firstSource: PlaybackSource,
        val session: PlaybackSession
    ) : PlaybackOrchestratorOutcome

    data class Failure(
        val message: String,
        val cause: Throwable? = null
    ) : PlaybackOrchestratorOutcome

    object Cancelled : PlaybackOrchestratorOutcome

    data class ChallengeRequired(
        val extensionId: String,
        val targetUrl: String,
        val candidate: ManagedExtension,
        val session: PlaybackSession
    ) : PlaybackOrchestratorOutcome
}

/**
 * Phase 05S: Playback Orchestrator with Deterministic Fallback and Interactive Cloudflare Recovery.
 *
 * Invariants:
 * 1. Consumes canonical Search Order from SearchOrderRepository without reordering or altering priority.
 * 2. Filters extensions through ExtensionEligibilityFilter enforcing hard ContentType isolation.
 * 3. Preserves Admin Search Order sequence deterministically (no priority sorting, no shuffling).
 * 4. Bounded execution timeouts across network operations, candidates, and challenge recovery.
 * 5. Preserves ExtensionError without swallowing errors via .getOrNull().
 * 6. Cloudflare challenge pauses candidate, displays natural interactive verification, persists session,
 *    and retries the SAME candidate.
 * 7. User cancellation or challenge timeout skips the candidate and falls back cleanly to the next candidate.
 * 8. All candidates failure guarantees a clean terminal error state (zero infinite loading or buffering).
 * 9. QFilm Golden Reference pipeline is strictly preserved.
 * 10. Zero Firestore reads/writes during active playback (consults local snapshot).
 */
class PlaybackOrchestrator(
    private val searchOrderRepository: SearchOrderRepository,
    private val eligibilityFilter: ExtensionEligibilityFilter,
    private val extensionRegistry: ManagedExtensionRegistry,
    private val runtime: ControlledManagedExtensionRuntime,
    private val sufficiencyPolicy: QualitySufficiencyPolicy = QualitySufficiencyPolicy(),
    private val isOnlineChecker: () -> Boolean = { true },
    private val fallbackManager: com.example.extension.managed.runtime.FallbackManager = com.example.extension.managed.runtime.FallbackManager(com.example.extension.managed.registry.ScraperRegistry.INSTANCE),
    private val protectedWebRuntime: ProtectedWebRuntime = ProtectedWebRuntime.getInstance(),
    private val interactiveChallengeController: InteractiveChallengeController = InteractiveChallengeController.getInstance()
) {

    companion object {
        const val TIMEOUT_NETWORK_MS = 10_000L
        const val TIMEOUT_EXTRACTION_MS = 15_000L
        const val TIMEOUT_CANDIDATE_MS = 45_000L
        const val TIMEOUT_CHALLENGE_MS = 60_000L
        const val TIMEOUT_SESSION_MS = 180_000L
    }

    private fun isCloudflareChallenge(error: Throwable?): Boolean {
        if (error == null) return false
        if (error is ExtensionError.CloudflareChallenge) return true
        if (error.cause is ExtensionError.CloudflareChallenge) return true
        val msg = error.message?.lowercase() ?: ""
        return msg.contains("cloudflare") || msg.contains("turnstile") || msg.contains("just a moment")
    }

    private fun safeFailureSummary(error: Throwable?): String {
        if (error == null) return "Unknown error"
        if (isCloudflareChallenge(error)) return "Cloudflare challenge"
        return CloudflareForensicLogger.safeDiagnosticText(
            error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        )
    }

    /** A challenge may open only the HTTPS origin belonging to this configured provider. */
    private fun safeChallengeUrl(candidate: ManagedExtension, proposedUrl: String?): String {
        val base = try { java.net.URI(candidate.baseUrl) } catch (_: Exception) { null }
        val baseHost = PerSiteSessionStore.canonicalizeHost(base?.host.orEmpty())
        val proposed = try { java.net.URI(proposedUrl.orEmpty()) } catch (_: Exception) { null }
        if (proposed != null && proposed.scheme.equals("https", ignoreCase = true) &&
            !proposed.host.isNullOrBlank() && proposed.userInfo == null && baseHost.isNotBlank() &&
            PerSiteSessionStore.canonicalizeHost(proposed.host) == baseHost) {
            return proposed.toASCIIString()
        }
        return if (baseHost.isNotBlank()) "https://$baseHost/" else "https://invalid.invalid/"
    }

    /** A playback URL is accepted only when it is an absolute HTTP(S) URL with a host. */
    private fun isValidPlaybackUrl(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        return try {
            val uri = java.net.URI(rawUrl.trim())
            (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
                !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun searchWithDiagnostics(
        candidate: ManagedExtension,
        request: SearchRequest
    ): Result<com.example.extension.managed.model.SearchResult> {
        val result = runtime.search(listOf(candidate), request)
        if (result.isSuccess) {
            PlaybackDiagnosticsStore.markSourceReachability(candidate.id, PlaybackDiagnosticStatus.SUCCESS, "استجاب مصدر البحث: ${candidate.name}")
        } else {
            PlaybackDiagnosticsStore.markSourceReachability(candidate.id, PlaybackDiagnosticStatus.FAILED, "فشل الاتصال/البحث: ${safeFailureSummary(result.exceptionOrNull())}")
        }
        return result
    }

    suspend fun orchestratePlayback(
        session: PlaybackSession,
        onFirstPlayableSource: (PlaybackSource) -> Unit,
        onQualitiesDiscovered: (List<QualitySource>) -> Unit = {},
        onError: (String) -> Unit = {}
    ): PlaybackOrchestratorOutcome = withContext(Dispatchers.IO + session.sessionJob) {
        if (!session.isActive) {
            return@withContext PlaybackOrchestratorOutcome.Cancelled
        }

        // Ignore duplicate entry points instead of resetting or overlapping an active run.
        if (!PlaybackDiagnosticsStore.begin(session.mediaId, session.title)) {
            Phase05HLogger.log("PLAYBACK", session.mediaId, "DUPLICATE_ORCHESTRATION_IGNORED: diagnostics run already active")
            return@withContext PlaybackOrchestratorOutcome.Cancelled
        }

        if (!isOnlineChecker()) {
            val userMsg = "لا يوجد اتصال بالإنترنت"
            onError(userMsg)
            PlaybackDiagnosticsStore.finish(false, userMsg)
            return@withContext PlaybackOrchestratorOutcome.Failure(userMsg)
        }

        val targetContentType = session.contentType
        val cachedStore = com.example.ui.screens.player.ServerStateStore.getCachedData(session.mediaId)
        val targetMode = com.example.domain.models.MediaVariantMode.fromString(cachedStore?.variantMode)
        val isDubbedSession = session.variantMode.equals("dubbed", ignoreCase = true) ||
            targetMode == com.example.domain.models.MediaVariantMode.DUBBED
        val requestedLanguage = com.example.domain.models.VariantLanguage.fromString(
            cachedStore?.audioLanguage ?: session.audioLanguage
        )
        // A dubbed session defaults to Arabic unless English dubbing was explicitly selected.
        val dubbedLanguage = if (requestedLanguage == com.example.domain.models.VariantLanguage.ENGLISH) {
            com.example.domain.models.VariantLanguage.ENGLISH
        } else {
            com.example.domain.models.VariantLanguage.ARABIC
        }
        val requiredAudio = if (isDubbedSession) {
            if (dubbedLanguage == com.example.domain.models.VariantLanguage.ENGLISH) {
                com.example.domain.models.AudioCapability.ENGLISH_DUB
            } else {
                com.example.domain.models.AudioCapability.ARABIC_DUB
            }
        } else null

        Phase05HLogger.log("PLAYBACK", session.mediaId, "PLAYBACK_SESSION_STARTED: mediaId=${session.mediaId}, title='${session.title}', isDubbed=$isDubbedSession")
        Phase05HLogger.log("PLAYBACK", session.mediaId, "CONTENT_TYPE_RESOLVED: type=$targetContentType")

        // 1. Fetch canonical Search Order for requested content type and variant mode.
        // If the Admin document exists, an empty group is authoritative and must not fall
        // through to local-priority ordering.
        val adminSearchOrder = searchOrderRepository.getSearchOrder()
        val searchOrderIds = if (isDubbedSession) {
            adminSearchOrder?.takeIf { it.isDubbedAllowed(dubbedLanguage) }
                ?.getDubbedOrder(dubbedLanguage).orEmpty()
        } else {
            adminSearchOrder?.getOrderForContentType(targetContentType).orEmpty()
        }
        Phase05HLogger.log("PLAYBACK", session.mediaId, "SEARCH_ORDER_RESOLVED: order=$searchOrderIds, isDubbed=$isDubbedSession")

        // 2. Fetch all registered managed extensions
        val allExtensions = extensionRegistry.getAllExtensions()

        // 3. Filter candidates through eligibility pipeline & hard content-type isolation
        val initialCandidates = if (searchOrderIds.isNotEmpty()) {
            eligibilityFilter.filterEligibleExtensions(
                orderedExtensionIds = searchOrderIds,
                availableExtensions = allExtensions,
                targetContentType = targetContentType,
                capability = ScraperCapability.SERVER_DISCOVERY,
                requiredAudioCapability = requiredAudio,
                currentAppVersionCode = runtime.currentAppVersionCode,
                supportedRuntimeApiVersion = runtime.supportedRuntimeApiVersion
            )
        } else emptyList()

        val eligibleCandidates = if (searchOrderIds.isNotEmpty()) {
            // The local Extensions snapshot is authoritative, preserving the Admin's exact order.
            initialCandidates
        } else {
            // Never bypass the Extensions page's canonical order with local-priority fallback.
            emptyList()
        }
        PlaybackDiagnosticsStore.registerProviders(
            searchOrderIds.mapNotNull { id -> allExtensions.firstOrNull { it.id == id }?.let { it.id to it.name } }
        )
        val eligibleIds = eligibleCandidates.map { it.id }.toSet()
        searchOrderIds.filterNot { it in eligibleIds }.forEach { skippedId ->
            PlaybackDiagnosticsStore.skipProvider(skippedId, "تم تجاوز الإضافة لأنها غير مؤهلة أو معطلة في لقطة الإعدادات المحلية")
        }

        if (eligibleCandidates.isEmpty()) {
            Phase05HLogger.log("PLAYBACK", session.mediaId, "ALL_CANDIDATES_FAILED: zero eligible extensions for $targetContentType (isDubbed=$isDubbedSession)")
            Phase05HLogger.log("PLAYBACK", session.mediaId, "PLAYBACK_FAILED: No eligible extensions")
            val userMsg = if (isDubbedSession) "النسخة المدبلجة غير متوفرة لهذا العمل" else "تعذر تشغيل هذا المحتوى"
            onError(userMsg)
            PlaybackDiagnosticsStore.finish(false, "لا توجد إضافات مؤهلة في ترتيب البحث المحلي؛ افحص حالة التفعيل وترتيب البحث")
            return@withContext PlaybackOrchestratorOutcome.Failure(
                if (isDubbedSession) "النسخة المدبلجة غير متوفرة لهذا العمل" else "No eligible managed extensions found for $targetContentType with search order: $searchOrderIds"
            )
        }

        Phase05HLogger.log(
            "PLAYBACK",
            session.mediaId,
            "PLAYBACK_CANDIDATES_RESOLVED: ${eligibleCandidates.map { it.id }}"
        )

        session.orderedCandidates.addAll(eligibleCandidates)

        // Keep the canonical configured order authoritative. A prior discovery session may
        // provide hints, but it must never reorder or silently remove enabled providers.
        val candidatesToRun = eligibleCandidates

        val primaryTitle = EpisodeSearchQueryBuilder.baseTitle(session.title)
        val titleQueries = EpisodeSearchQueryBuilder.buildQueries(
            title = session.title,
            season = session.season,
            episode = session.episode,
            isMovie = session.isMovie
        )
        val cleanTitle = titleQueries.firstOrNull() ?: primaryTitle
        val cleanTitleWithYear = if (session.year.isNotBlank() && session.year != "0") "$cleanTitle ${session.year}" else cleanTitle

        val primaryOriginalTitle = session.originalTitle?.let(EpisodeSearchQueryBuilder::baseTitle)
        val originalQueries = session.originalTitle?.let {
            EpisodeSearchQueryBuilder.buildQueries(it, session.season, session.episode, session.isMovie)
        }.orEmpty()
        val cleanOriginalTitle = originalQueries.firstOrNull()
        val cleanOriginalTitleWithYear = if (!cleanOriginalTitle.isNullOrBlank() && session.year.isNotBlank() && session.year != "0") "$cleanOriginalTitle ${session.year}" else cleanOriginalTitle

        // Stage 1: all eligible extensions independently test source reachability and run
        // one primary work search concurrently. The result is cached for the ordered phase,
        // so a successful primary search is not repeated when that provider's turn arrives.
        val preflightResults = coroutineScope {
            candidatesToRun.map { candidate ->
                async(Dispatchers.IO) {
                    candidate.id to try {
                        withTimeout(TIMEOUT_NETWORK_MS) {
                            searchWithDiagnostics(
                                candidate,
                                SearchRequest(query = cleanTitle, contentType = targetContentType)
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                }
            }.awaitAll().toMap()
        }
        val preflightCompletedIds = preflightResults.filterValues { it.isSuccess }.keys
        val preflightWorkById = preflightResults.mapValues { (_, result) ->
            result.getOrNull()?.items?.firstOrNull()
        }
        Phase05HLogger.log(
            "PLAYBACK", session.mediaId,
            "PARALLEL_PREFLIGHT_COMPLETED: providers=${candidatesToRun.size}, found=${preflightWorkById.values.count { it != null }}"
        )

        var firstPlayableEmitted = false

        // 4. Iterate over eligible candidates in exact configured order
        for (candidate in candidatesToRun) {
            if (!session.isActive) break

            // Phase 05Q.5-C: Strict Candidate Filter against PlaybackAttempt cancelled set
            if (session.attempt.isCandidateCancelled(candidate.id) || session.attempt.isCandidateCancelled(candidate.scraperKey)) {
                Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_SKIPPED_ALREADY_CANCELLED: extensionId=${candidate.id}")
                Phase05HLogger.log("PLAYBACK", session.mediaId, "NEXT_CANDIDATE")
                continue
            }

            // Diagnostics contract: every eligible, enabled provider must complete its
            // ordered stages. Quality sufficiency may affect playback selection elsewhere,
            // but must not silently skip providers in this diagnostic pass.

            Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_SELECTED: extensionId=${candidate.id}")
            // SOURCE_REACHABLE is recorded by the concurrent preflight. Do not reset its
            // final status to RUNNING when this provider reaches its ordered continuation.

            var candidateAttempt = 0
            val maxCandidateAttempts = 2 // 1 initial attempt + 1 retry if Cloudflare challenge was successfully solved
            var candidateChallengeRecovered = false

            while (candidateAttempt < maxCandidateAttempts && session.isActive &&
                !session.attempt.isCandidateCancelled(candidate.id) &&
                !session.attempt.isCandidateCancelled(candidate.scraperKey)) {
                candidateAttempt++

                try {
                    val candidatePrimarySource: PlaybackSource? = withTimeoutOrNull(TIMEOUT_CANDIDATE_MS) {
                        // Step A: Search title on candidate extension. EgyDead's index is Arabic and
                        // returns the exact episode page more reliably than English season phrases.
                        var searchResult: com.example.extension.managed.model.SearchMediaItem? = preflightWorkById[candidate.id]
                        val isEgyDead = candidate.scraperKey.equals("egydead", true) ||
                            candidate.baseUrl.contains("egydead", ignoreCase = true) || candidate.id.equals("egydead", true)
                        if (isEgyDead && !session.isMovie && searchResult == null) {
                            val localizedEpisodeQuery = EpisodeSearchQueryBuilder.buildEgyDeadEpisodeQuery(
                                session.title, session.season, session.episode
                            )
                            if (localizedEpisodeQuery.isNotBlank()) {
                                Phase05HLogger.log("PLAYBACK", session.mediaId, "EGYDEAD_EPISODE_SEARCH: query='$localizedEpisodeQuery'")
                                val localizedResult = searchWithDiagnostics(candidate, SearchRequest(query = localizedEpisodeQuery, contentType = targetContentType))
                                if (localizedResult.isFailure) {
                                    val err = localizedResult.exceptionOrNull()
                                    if (err is ExtensionError.SecurityViolation) throw err
                                    if (isCloudflareChallenge(err)) {
                                        throw (err as? ExtensionError.CloudflareChallenge ?: ExtensionError.CloudflareChallenge(err?.message ?: "Cloudflare on EgyDead episode search"))
                                    }
                                }
                                searchResult = localizedResult.getOrNull()?.items?.firstOrNull { item ->
                                    item.url.contains("/episodes/", ignoreCase = true) &&
                                        (item.title.contains("الحلقة ${session.episode}") || item.url.contains(Regex("(?i)s${session.season.toString().padStart(2, '0')}e${session.episode.toString().padStart(2, '0')}")))
                                } ?: localizedResult.getOrNull()?.items?.firstOrNull()
                            }
                        }
                        if (searchResult == null) searchResult = if (isDubbedSession) {
                            var dubbedRes: com.example.extension.managed.model.SearchMediaItem? = null
                            if (!cleanTitle.contains("مدبلج")) {
                                Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_DUBBED_SEARCH_STARTED: extensionId=${candidate.id}, query='$cleanTitle مدبلج'")
                                val dubbedReq = SearchRequest(query = "$cleanTitle مدبلج", contentType = targetContentType)
                                dubbedRes = searchWithDiagnostics(candidate, dubbedReq).getOrNull()?.items?.firstOrNull()
                            }
                            if (dubbedRes == null && !cleanOriginalTitle.isNullOrBlank() && cleanOriginalTitle != cleanTitle && !cleanOriginalTitle.contains("مدبلج")) {
                                Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_DUBBED_ORIGINAL_SEARCH_STARTED: extensionId=${candidate.id}, query='$cleanOriginalTitle مدبلج'")
                                val dubbedReqOrig = SearchRequest(query = "$cleanOriginalTitle مدبلج", contentType = targetContentType)
                                dubbedRes = searchWithDiagnostics(candidate, dubbedReqOrig).getOrNull()?.items?.firstOrNull()
                            }
                            dubbedRes
                        } else null

                        if (searchResult == null && candidate.id !in preflightCompletedIds) {
                            Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_SEARCH_STARTED: extensionId=${candidate.id}, query='$cleanTitle'")
                            val searchReq1 = SearchRequest(query = cleanTitle, contentType = targetContentType)
                            val searchRes1 = searchWithDiagnostics(candidate, searchReq1)

                            if (searchRes1.isFailure) {
                                val err = searchRes1.exceptionOrNull()
                                if (err is ExtensionError.SecurityViolation) throw err
                                if (isCloudflareChallenge(err)) {
                                    throw (err as? ExtensionError.CloudflareChallenge ?: ExtensionError.CloudflareChallenge(err?.message ?: "Cloudflare on search"))
                                }
                            }

                            searchResult = searchRes1.getOrNull()?.items?.firstOrNull()
                        }

                        if (searchResult == null && cleanTitleWithYear != cleanTitle) {
                            val searchReq2 = SearchRequest(query = cleanTitleWithYear, contentType = targetContentType)
                            val searchRes2 = searchWithDiagnostics(candidate, searchReq2)
                            if (searchRes2.isFailure) {
                                val err = searchRes2.exceptionOrNull()
                                if (err is ExtensionError.SecurityViolation) throw err
                                if (isCloudflareChallenge(err)) {
                                    throw (err as? ExtensionError.CloudflareChallenge ?: ExtensionError.CloudflareChallenge(err?.message ?: "Cloudflare on search"))
                                }
                            }
                            searchResult = searchRes2.getOrNull()?.items?.firstOrNull()
                        }

                        // Fallback to originalTitle if not found
                        if (searchResult == null && !cleanOriginalTitle.isNullOrBlank() && cleanOriginalTitle != cleanTitle) {
                            val searchReqOrig1 = SearchRequest(query = cleanOriginalTitle, contentType = targetContentType)
                            val searchResOrig1 = searchWithDiagnostics(candidate, searchReqOrig1)
                            if (searchResOrig1.isFailure) {
                                val err = searchResOrig1.exceptionOrNull()
                                if (err is ExtensionError.SecurityViolation) throw err
                                if (isCloudflareChallenge(err)) {
                                    throw (err as? ExtensionError.CloudflareChallenge ?: ExtensionError.CloudflareChallenge(err?.message ?: "Cloudflare on search"))
                                }
                            }
                            searchResult = searchResOrig1.getOrNull()?.items?.firstOrNull()

                            if (searchResult == null && !cleanOriginalTitleWithYear.isNullOrBlank()) {
                                val searchReqOrig2 = SearchRequest(query = cleanOriginalTitleWithYear, contentType = targetContentType)
                                val searchResOrig2 = searchWithDiagnostics(candidate, searchReqOrig2)
                                if (searchResOrig2.isFailure) {
                                    val err = searchResOrig2.exceptionOrNull()
                                    if (err is ExtensionError.SecurityViolation) throw err
                                    if (isCloudflareChallenge(err)) {
                                        throw (err as? ExtensionError.CloudflareChallenge ?: ExtensionError.CloudflareChallenge(err?.message ?: "Cloudflare on search"))
                                    }
                                }
                                searchResult = searchResOrig2.getOrNull()?.items?.firstOrNull()
                            }
                        }

                        // Fallback to primary title (before season/subtitle)
                        if (searchResult == null && !primaryTitle.isNullOrBlank() && primaryTitle != cleanTitle && primaryTitle.length >= 3) {
                            val searchReqPrim = SearchRequest(query = primaryTitle, contentType = targetContentType)
                            val searchResPrim = searchWithDiagnostics(candidate, searchReqPrim)
                            if (searchResPrim.isFailure) {
                                val err = searchResPrim.exceptionOrNull()
                                if (err is ExtensionError.SecurityViolation) throw err
                                if (isCloudflareChallenge(err)) {
                                    throw (err as? ExtensionError.CloudflareChallenge ?: ExtensionError.CloudflareChallenge(err?.message ?: "Cloudflare on search"))
                                }
                            }
                            searchResult = searchResPrim.getOrNull()?.items?.firstOrNull()
                        }

                        if (searchResult == null && !primaryOriginalTitle.isNullOrBlank() && primaryOriginalTitle != cleanOriginalTitle && primaryOriginalTitle.length >= 3) {
                            val searchReqOrigPrim = SearchRequest(query = primaryOriginalTitle, contentType = targetContentType)
                            val searchResOrigPrim = searchWithDiagnostics(candidate, searchReqOrigPrim)
                            if (searchResOrigPrim.isFailure) {
                                val err = searchResOrigPrim.exceptionOrNull()
                                if (err is ExtensionError.SecurityViolation) throw err
                                if (isCloudflareChallenge(err)) {
                                    throw (err as? ExtensionError.CloudflareChallenge ?: ExtensionError.CloudflareChallenge(err?.message ?: "Cloudflare on search"))
                                }
                            }
                            searchResult = searchResOrigPrim.getOrNull()?.items?.firstOrNull()
                        }

                        if (searchResult == null) {
                            PlaybackDiagnosticsStore.mark(candidate.id, PlaybackDiagnosticStage.WORK_FOUND, PlaybackDiagnosticStatus.FAILED, "لم يعثر البحث على صفحة العمل")
                            PlaybackDiagnosticsStore.markRunningChecksFailed(candidate.id, "توقفت المحاولة قبل العثور على العمل")
                            Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_SEARCH_FAILED: extensionId=${candidate.id}")
                            return@withTimeoutOrNull null
                        }
                        PlaybackDiagnosticsStore.mark(candidate.id, PlaybackDiagnosticStage.WORK_FOUND, PlaybackDiagnosticStatus.SUCCESS, "تم العثور على نتيجة: ${searchResult.title}")

                        if (!session.isActive) return@withTimeoutOrNull null

                        // Step B: Resolve episode target URL if series/anime
                        val targetUrl = if (session.isMovie) {
                            PlaybackDiagnosticsStore.mark(candidate.id, PlaybackDiagnosticStage.EPISODE_FOUND, PlaybackDiagnosticStatus.SKIPPED, "فيلم؛ لا يتطلب اختيار حلقة")
                            searchResult.url
                        } else if (searchResult.url.contains("/episodes/", ignoreCase = true)) {
                            val titleMatchesEpisode = Regex("(?i)(?:الحلقة|episode)\\s*0*${session.episode}(?!\\d)").containsMatchIn(searchResult.title)
                            val urlMatchesEpisode = Regex("(?i)(?:s0*${session.season}e0*${session.episode}(?!\\d)|episode[-_/ ]0*${session.episode}(?!\\d)|ep[-_/ ]0*${session.episode}(?!\\d))").containsMatchIn(searchResult.url)
                            if (titleMatchesEpisode || urlMatchesEpisode) {
                                PlaybackDiagnosticsStore.mark(candidate.id, PlaybackDiagnosticStage.EPISODE_FOUND, PlaybackDiagnosticStatus.SUCCESS, "تم العثور على صفحة الحلقة ${session.episode}")
                            } else {
                                PlaybackDiagnosticsStore.mark(candidate.id, PlaybackDiagnosticStage.EPISODE_FOUND, PlaybackDiagnosticStatus.FAILED, "عُثر على صفحة حلقة لكن لم يمكن تأكيد رقم الحلقة ${session.episode}")
                                Phase05HLogger.log("PLAYBACK", session.mediaId, "EPISODE_VALIDATION_FAILED: extensionId=${candidate.id}; refusing server discovery on an unverified episode URL")
                                return@withTimeoutOrNull null
                            }
                            searchResult.url
                        } else {
                            PlaybackDiagnosticsStore.mark(candidate.id, PlaybackDiagnosticStage.EPISODE_FOUND, PlaybackDiagnosticStatus.RUNNING, "جارٍ البحث عن الموسم ${session.season} الحلقة ${session.episode}")
                            val epResult = runtime.getEpisodes(candidate, searchResult.url, session.season, targetContentType)
                            if (epResult.isFailure) {
                                val err = epResult.exceptionOrNull()
                                if (err is ExtensionError.SecurityViolation) throw err
                                if (isCloudflareChallenge(err)) {
                                    throw (err as? ExtensionError.CloudflareChallenge ?: ExtensionError.CloudflareChallenge(err?.message ?: "Cloudflare on episodes"))
                                }
                            }
                            val epList = epResult.getOrNull() ?: emptyList()
                            val requestedEpisode = epList.firstOrNull { it.episodeNumber == session.episode }
                            if (requestedEpisode != null) {
                                PlaybackDiagnosticsStore.mark(candidate.id, PlaybackDiagnosticStage.EPISODE_FOUND, PlaybackDiagnosticStatus.SUCCESS, "تم العثور على الحلقة ${session.episode} ضمن الموسم ${session.season}")
                                requestedEpisode.url
                            } else {
                                PlaybackDiagnosticsStore.mark(candidate.id, PlaybackDiagnosticStage.EPISODE_FOUND, PlaybackDiagnosticStatus.FAILED, "لم يُعثر على الحلقة ${session.episode} في الموسم ${session.season}؛ أعاد الموقع ${epList.size} حلقة")
                                Phase05HLogger.log("PLAYBACK", session.mediaId, "EPISODE_NOT_FOUND: extensionId=${candidate.id}; refusing to continue to server discovery")
                                return@withTimeoutOrNull null
                            }
                        }

                        if (!session.isActive) return@withTimeoutOrNull null

                        // Step C: Discover servers for the target page
                        PlaybackDiagnosticsStore.mark(candidate.id, PlaybackDiagnosticStage.SERVERS_FOUND, PlaybackDiagnosticStatus.RUNNING, "جارٍ فتح صفحة الحلقة واستخراج السيرفرات")
                        Phase05HLogger.log("PLAYBACK", session.mediaId, "SERVER_DISCOVERY_STARTED: extensionId=${candidate.id}")
                        val discoveryRequest = ServerDiscoveryRequest(
                            targetUrl = targetUrl,
                            mediaTitle = session.title,
                            isMovie = session.isMovie,
                            season = session.season,
                            episode = session.episode,
                            contentType = targetContentType
                        )

                        val serverDiscoveryResult = runtime.discoverServers(listOf(candidate), discoveryRequest)
                        if (serverDiscoveryResult.isFailure) {
                            val err = serverDiscoveryResult.exceptionOrNull()
                            if (err is ExtensionError.SecurityViolation) throw err
                            if (isCloudflareChallenge(err)) {
                                throw (err as? ExtensionError.CloudflareChallenge ?: ExtensionError.CloudflareChallenge(err?.message ?: "Cloudflare on server discovery"))
                            }
                        }

                        val servers = serverDiscoveryResult.getOrNull()?.servers ?: emptyList()
                        if (servers.isEmpty()) {
                            PlaybackDiagnosticsStore.mark(candidate.id, PlaybackDiagnosticStage.SERVERS_FOUND, PlaybackDiagnosticStatus.FAILED, "لم يعثر الموقع على سيرفرات تشغيل")
                            Phase05HLogger.log("PLAYBACK", session.mediaId, "SERVER_DISCOVERY_FAILED: extensionId=${candidate.id}")
                            return@withTimeoutOrNull null
                        }
                        PlaybackDiagnosticsStore.mark(candidate.id, PlaybackDiagnosticStage.SERVERS_FOUND, PlaybackDiagnosticStatus.SUCCESS, "تم العثور على ${servers.size} سيرفر تشغيل")

                        if (!session.isActive) return@withTimeoutOrNull null

                        // Step D: inspect every discovered server, not just the first three.
                        // Keep the first valid source for immediate playback, while aggregating
                        // distinct quality variants from all servers that return them.
                        PlaybackDiagnosticsStore.mark(candidate.id, PlaybackDiagnosticStage.QUALITIES_EXTRACTED, PlaybackDiagnosticStatus.RUNNING, "بدأ فحص ${servers.size} سيرفر واستخراج المصادر والجودات")
                        var extractedSource: PlaybackSource? = null
                        val extractedFromServers = mutableListOf<PlaybackSource>()
                        val orderedServers = servers.sortedByDescending { it.isDirectStream && isValidPlaybackUrl(it.link) }
                        // Quality-targeted diagnostics apply to every discovered PLAYBACK server,
                        // regardless of provider. Download links are never part of this list: they
                        // are excluded by each provider's discoverServers implementation.
                        // Stop as soon as the four core resolutions have been confirmed from actual
                        // extracted playback metadata; page-advertised download labels do not count.
                        val corePlaybackHeights = setOf(360, 480, 720, 1080)
                        fun collectedCoreHeights(): Set<Int> = extractedFromServers.flatMap { src ->
                            val labels = src.qualities.map { it.label } + src.variants.map { it.displayQuality }
                            labels.mapNotNull { label ->
                                Regex("(?i)(?:^|\\D)(360|480|720|1080)\\s*p?(?:$|\\D)")
                                    .find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            }
                        }.toSet()
                        for (srv in orderedServers) {
                            if (!session.isActive) break
                            if (collectedCoreHeights().containsAll(corePlaybackHeights)) {
                                Phase05HLogger.log("PLAYBACK", session.mediaId, "CORE_PLAYBACK_QUALITIES_COMPLETE: stopping before serverId=${srv.id}; extensionId=${candidate.id}; heights=${collectedCoreHeights().sorted()}")
                                break
                            }
                            val direct = srv.isDirectStream && isValidPlaybackUrl(srv.link)
                            val extractResult = if (direct) {
                                // Do not wrap a direct HLS URL in a bare PlaybackSource: that drops
                                // master-playlist variants and was a primary reason direct .m3u8
                                // servers reported zero extracted qualities. Use the same parser path
                                // as runtime-extracted media and preserve the provider Referer.
                                val sourceHeaders = if (candidate.baseUrl.isNotBlank()) {
                                    mapOf("Referer" to candidate.baseUrl)
                                } else emptyMap()
                                val parsedSource = try {
                                    com.example.extension.managed.web.MediaStreamDetector.createPlaybackSource(
                                        srv.link,
                                        sourceHeaders
                                    )
                                } catch (qualityError: Throwable) {
                                    Phase05HLogger.log(
                                        "PLAYBACK",
                                        session.mediaId,
                                        "DIRECT_SOURCE_QUALITY_PARSE_FAILED: extensionId=${candidate.id}; serverId=${srv.id}; reason=${qualityError.message ?: qualityError.javaClass.simpleName}"
                                    )
                                    PlaybackSource(
                                        streamUrl = srv.link,
                                        headers = sourceHeaders,
                                        mimeType = if (srv.link.contains(".m3u8", ignoreCase = true)) "application/x-mpegURL" else if (srv.link.contains(".mpd", ignoreCase = true)) "application/dash+xml" else "video/mp4"
                                    )
                                }
                                Result.success(com.example.extension.managed.model.ExtractionResult(playbackSource = parsedSource))
                            } else if (candidate.scraperKey != "qfilm" && srv.link.startsWith("http", ignoreCase = true) && !srv.link.contains(".m3u8", ignoreCase = true) && !srv.link.contains(".mp4", ignoreCase = true)) {
                                val webExtract = protectedWebRuntime.extractMedia(
                                    siteKey = candidate.scraperKey,
                                    targetUrl = srv.link,
                                    timeoutMs = 15_000L,
                                    expectedOrigin = candidate.baseUrl,
                                    enableInteractiveRecovery = true,
                                    extractionContext = InteractiveChallengeController.ExtractionContext(
                                        mediaId = session.mediaId,
                                        title = session.title,
                                        originalTitle = session.originalTitle,
                                        year = session.year,
                                        contentType = targetContentType,
                                        season = session.season,
                                        episode = session.episode,
                                        extensionId = candidate.id,
                                        scraperKey = candidate.scraperKey,
                                        serverId = srv.id,
                                        serverName = srv.name,
                                        targetUrl = srv.link
                                    )
                                )
                                // ProtectedWebRuntime already owns the complete challenge lifecycle:
                                // interactive verification, cookie persistence, and one extraction retry.
                                // Falling back to runtime.extractStream here starts a second independent
                                // extraction attempt and can reopen the same Cloudflare challenge after
                                // the user has just completed it. Preserve cancellation/exit semantics and
                                // let the outer server loop move on when this single managed attempt fails.
                                if (webExtract.isFailure) {
                                    val err = webExtract.exceptionOrNull()
                                    Phase05HLogger.log(
                                        "PLAYBACK",
                                        session.mediaId,
                                        "PROTECTED_WEB_EXTRACTION_FAILED_NO_DUPLICATE_RETRY: extensionId=${candidate.id}; serverId=${srv.id}; reason=${safeFailureSummary(err)}"
                                    )
                                    if (err is ProtectedWebRuntimeError.CloudflareChallengeCancelled ||
                                        err is ProtectedWebRuntimeError.CloudflareChallengeUnverifiedExit) throw err
                                }
                                webExtract
                            } else {
                                runtime.extractStream(listOf(candidate), ExtractionRequest(serverItem = srv, mediaTitle = session.title, timeoutMs = 15_000L))
                            }

                            if (extractResult.isSuccess) {
                                val src = extractResult.getOrThrow().playbackSource
                                if (src != null && isValidPlaybackUrl(src.streamUrl)) {
                                    extractedFromServers.add(src)
                                    Phase05HLogger.log("PLAYBACK", session.mediaId, "PLAYBACK_SERVER_SOURCE_CHECKED: extensionId=${candidate.id}; serverId=${srv.id}; qualities=${src.qualities.map { it.label }}; variants=${src.variants.map { it.displayQuality }}; coreHeights=${collectedCoreHeights().sorted()}")
                                    if (extractedSource == null) extractedSource = src
                                }
                            } else {
                                val err = extractResult.exceptionOrNull()
                                if (err is ExtensionError.SecurityViolation) throw err
                                if (err is ProtectedWebRuntimeError.CloudflareChallengeCancelled || err is ProtectedWebRuntimeError.CloudflareChallengeUnverifiedExit) throw err
                                // A blocked/broken embed must not prevent checking later servers.
                                Phase05HLogger.log("PLAYBACK", session.mediaId, "SERVER_EXTRACTION_FAILED: extensionId=${candidate.id}; serverId=${srv.id}; reason=${safeFailureSummary(err)}")
                            }
                        }
                        val primary = extractedSource
                        if (primary != null) {
                            val allQualities = extractedFromServers.flatMap { src ->
                                when {
                                    src.qualities.isNotEmpty() -> src.qualities
                                    src.variants.isNotEmpty() -> src.variants.map { it.toQualitySource() }
                                    else -> emptyList()
                                }
                            }.distinctBy { it.label.lowercase() + "|" + it.url }
                            val allVariants = extractedFromServers.flatMap { it.variants }.distinctBy { it.url }
                            extractedSource = primary.copy(
                                qualities = allQualities,
                                variants = allVariants
                            )
                            Phase05HLogger.log("PLAYBACK", session.mediaId, "SERVER_EXTRACTION_COMPLETED: extensionId=${candidate.id}; discovered=${servers.size}; validSources=${extractedFromServers.size}; qualities=${allQualities.size}")
                        }
                        extractedSource
                    }

                    if (candidatePrimarySource != null && isValidPlaybackUrl(candidatePrimarySource.streamUrl)) {
                        // A previously verified challenge may still be visible while the ordered
                        // fallback loop tries later providers. Any valid playable source is a
                        // terminal success for that pending verification surface.
                        interactiveChallengeController.onExtractionRetryCompleted(success = true)
                        val hasDistinctQualities = candidatePrimarySource.qualities.isNotEmpty() || candidatePrimarySource.variants.isNotEmpty()
                        PlaybackDiagnosticsStore.mark(
                            candidate.id,
                            PlaybackDiagnosticStage.QUALITIES_EXTRACTED,
                            if (hasDistinctQualities) PlaybackDiagnosticStatus.SUCCESS else PlaybackDiagnosticStatus.FAILED,
                            if (hasDistinctQualities) {
                                "تم استخراج مصدر صالح وأُعيدت ${candidatePrimarySource.qualities.size + candidatePrimarySource.variants.size} جودة"
                            } else {
                                "عُثر على رابط مصدر صالح، لكن لم تُستخرج جودات منفصلة؛ لا يُسجل هذا نجاحًا لاختبار الجودات"
                            }
                        )
                        Phase05HLogger.log("PLAYBACK", session.mediaId, "PLAYBACK_SOURCE_RESOLVED: extensionId=${candidate.id}; player-confirmation-required=true")
                        // Keep every extracted source in configured provider order. Do not
                        // hand a candidate to the player while diagnostics are still testing
                        // later providers: a failed early source must not show a terminal
                        // player error while the orchestrator is still working.
                        if (session.discoveredSources.none { it.streamUrl == candidatePrimarySource.streamUrl }) {
                            session.discoveredSources.add(candidatePrimarySource)
                        }
                        if (!firstPlayableEmitted) {
                            firstPlayableEmitted = true
                        }

                        // Extract and record qualities from primary source
                        val newQualities = mutableListOf<QualitySource>()
                        if (candidatePrimarySource.qualities.isNotEmpty()) {
                            newQualities.addAll(candidatePrimarySource.qualities)
                        } else if (candidatePrimarySource.variants.isNotEmpty()) {
                            newQualities.addAll(candidatePrimarySource.variants.map { it.toQualitySource() })
                        } else {
                            newQualities.add(QualitySource(label = "Auto", url = candidatePrimarySource.streamUrl))
                        }

                        for (q in newQualities) {
                            if (session.discoveredQualities.none { it.label.equals(q.label, ignoreCase = true) }) {
                                session.discoveredQualities.add(q)
                            }
                        }
                        onQualitiesDiscovered(session.discoveredQualities.toList())

                        // Playback source established! Break out of candidate attempt loop
                        break
                    } else {
                        PlaybackDiagnosticsStore.markRunningChecksFailed(candidate.id, "لم تكتمل هذه المرحلة ضمن مهلة المصدر")
                        if (candidateChallengeRecovered && candidateAttempt >= maxCandidateAttempts) {
                            interactiveChallengeController.onExtractionRetryCompleted(
                                success = false,
                                reason = "اكتمل التحقق، لكن لم يُستخرج مصدر صالح من الإضافة ${candidate.name}. يجري فحص بقية المصادر."
                            )
                        }
                        // Candidate produced no playable source within bounded timeout
                        if (candidateAttempt >= maxCandidateAttempts) {
                            Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_SKIPPED: extensionId=${candidate.id}")
                            Phase05HLogger.log("PLAYBACK", session.mediaId, "NEXT_CANDIDATE")
                        }
                    }
                } catch (secEx: ExtensionError.SecurityViolation) {
                    if (candidateChallengeRecovered) {
                        interactiveChallengeController.onExtractionRetryCompleted(
                            success = false,
                            reason = "توقفت إعادة المحاولة بسبب قيد أمان."
                        )
                    }
                    PlaybackDiagnosticsStore.markRunningChecksFailed(candidate.id, "توقفت المحاولة بسبب قيد أمان: ${safeFailureSummary(secEx)}")
                    Phase05HLogger.log("PLAYBACK", session.mediaId, "FATAL_SECURITY_ERROR: extensionId=${candidate.id}, msg=${safeFailureSummary(secEx)}")
                    val userMsg = "تعذر تشغيل هذا المحتوى بسبب قيود أمان"
                    onError(userMsg)
                    return@withContext PlaybackOrchestratorOutcome.Failure("Security violation: ${secEx.message}", secEx)
                } catch (cfEx: ExtensionError.CloudflareChallenge) {
                    PlaybackDiagnosticsStore.markRunningChecksFailed(candidate.id, "توقفت المحاولة بانتظار تحقق Cloudflare")
                    Phase05HLogger.log("PLAYBACK", session.mediaId, "CLOUDFLARE_DETECTED: extensionId=${candidate.id}")
                    Phase05HLogger.log("PLAYBACK", session.mediaId, "CHALLENGE_REQUIRED: extensionId=${candidate.id}")

                    // CIRCUIT BREAKER: Bounded to 1 interactive recovery attempt per candidate to strictly prevent verification loops
                    if (candidateChallengeRecovered) {
                        interactiveChallengeController.onExtractionRetryCompleted(
                            success = false,
                            reason = "استمر تحدي Cloudflare بعد إعادة المحاولة. يمكنك إكمال التحقق أو تجربة مصدر آخر."
                        )
                        Phase05HLogger.log("PLAYBACK", session.mediaId, "CHALLENGE_CIRCUIT_BREAKER: candidate=${candidate.id} already recovered challenge once, skipping to avoid loop")
                        Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_SKIPPED: extensionId=${candidate.id}")
                        Phase05HLogger.log("PLAYBACK", session.mediaId, "NEXT_CANDIDATE")
                        break
                    }
                    candidateChallengeRecovered = true

                    Phase05HLogger.log("PLAYBACK", session.mediaId, "CHALLENGE_UI_OPENED: extensionId=${candidate.id}")

                    val canonicalHost = PerSiteSessionStore.canonicalizeHost(candidate.baseUrl)
                    val recoveryResult = interactiveChallengeController.launchInteractiveChallenge(
                        siteKey = candidate.scraperKey,
                        canonicalHost = canonicalHost,
                        challengeUrl = safeChallengeUrl(candidate, cfEx.challengeUrl),
                        context = InteractiveChallengeController.ExtractionContext(
                            mediaId = session.mediaId,
                            title = session.title,
                            originalTitle = session.originalTitle,
                            year = session.year,
                            contentType = targetContentType,
                            season = session.season,
                            episode = session.episode,
                            extensionId = candidate.id,
                            scraperKey = candidate.scraperKey,
                            targetUrl = safeChallengeUrl(candidate, cfEx.challengeUrl)
                        ),
                        timeoutMs = TIMEOUT_CHALLENGE_MS
                    )

                    if (recoveryResult.isSuccess) {
                        Phase05HLogger.log("PLAYBACK", session.mediaId, "CHALLENGE_COMPLETED: extensionId=${candidate.id}")
                        Phase05HLogger.log("PLAYBACK", session.mediaId, "SESSION_PERSISTED: extensionId=${candidate.id}")
                        Phase05HLogger.log("PLAYBACK", session.mediaId, "EXTRACTION_RETRY: extensionId=${candidate.id}")
                        Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_RETRY: extensionId=${candidate.id}")
                        // Loop continues to attemptCount 2: Retries SAME candidate!
                    } else {
                        val failEx = recoveryResult.exceptionOrNull()
                        if (failEx is ProtectedWebRuntimeError.CloudflareChallengeUnverifiedExit) {
                            Phase05HLogger.log("PLAYBACK", session.mediaId, "UNVERIFIED_EXIT: extensionId=${candidate.id}")
                            Phase05HLogger.log("PLAYBACK", session.mediaId, "PLAYBACK_TERMINATED_ON_EXIT")
                            return@withContext PlaybackOrchestratorOutcome.Failure("Playback terminated due to unverified exit", failEx)
                        } else if (failEx is ProtectedWebRuntimeError.CloudflareChallengeCancelled) {
                            Phase05HLogger.log("PLAYBACK", session.mediaId, "CHALLENGE_CANCELLED: extensionId=${candidate.id}")
                            Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_CANCELLED: extensionId=${candidate.id}")
                            session.attempt.cancelCandidate(candidate.id)
                            session.attempt.cancelCandidate(candidate.scraperKey)
                            CloudflareForensicLogger.cfUi17_candidateCancelled(candidate.id)
                        } else if (failEx is ProtectedWebRuntimeError.CloudflareChallengeTimeout) {
                            Phase05HLogger.log("PLAYBACK", session.mediaId, "CHALLENGE_TIMEOUT: extensionId=${candidate.id}")
                        }
                        Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_SKIPPED: extensionId=${candidate.id}")
                        Phase05HLogger.log("PLAYBACK", session.mediaId, "NEXT_CANDIDATE")
                        // Break out of while loop to proceed to next candidate
                        break
                    }
                } catch (exitEx: ProtectedWebRuntimeError.CloudflareChallengeUnverifiedExit) {
                    PlaybackDiagnosticsStore.markRunningChecksFailed(candidate.id, "خرجت عملية التحقق قبل اكتمالها")
                    Phase05HLogger.log("PLAYBACK", session.mediaId, "UNVERIFIED_EXIT: extensionId=${candidate.id}")
                    Phase05HLogger.log("PLAYBACK", session.mediaId, "PLAYBACK_TERMINATED_ON_EXIT")
                    return@withContext PlaybackOrchestratorOutcome.Failure("Playback terminated due to unverified exit", exitEx)
                } catch (cancelEx: ProtectedWebRuntimeError.CloudflareChallengeCancelled) {
                    PlaybackDiagnosticsStore.markRunningChecksFailed(candidate.id, "ألغى المستخدم التحقق")
                    Phase05HLogger.log("PLAYBACK", session.mediaId, "CHALLENGE_CANCELLED: extensionId=${candidate.id}")
                    Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_CANCELLED: extensionId=${candidate.id}")
                    session.attempt.cancelCandidate(candidate.id)
                    session.attempt.cancelCandidate(candidate.scraperKey)
                    CloudflareForensicLogger.cfUi17_candidateCancelled(candidate.id)
                    Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_SKIPPED: extensionId=${candidate.id}")
                    Phase05HLogger.log("PLAYBACK", session.mediaId, "NEXT_CANDIDATE")
                    break
                } catch (e: Throwable) {
                    if (candidateChallengeRecovered && e !is CancellationException) {
                        interactiveChallengeController.onExtractionRetryCompleted(
                            success = false,
                            reason = "فشلت إعادة المحاولة بعد التحقق: ${safeFailureSummary(e)}"
                        )
                    }
                    if (e is CancellationException) throw e
                    PlaybackDiagnosticsStore.markRunningChecksFailed(candidate.id, "خطأ في هذه الإضافة: ${safeFailureSummary(e)}")
                    Phase05HLogger.log("PLAYBACK", session.mediaId, "CANDIDATE_EXCEPTION: extensionId=${candidate.id}, msg=${safeFailureSummary(e)}")
                    Phase05HLogger.log("PLAYBACK", session.mediaId, "NEXT_CANDIDATE")
                    break
                }
            }
        }

        return@withContext if (firstPlayableEmitted && session.discoveredSources.isNotEmpty()) {
            // Only hand off to the player after the ordered diagnostic pass is terminal.
            // This prevents the player from displaying a failed first source while the
            // remaining enabled extensions are still being tested.
            val selectedSource = session.discoveredSources.first()
            if (!session.firstPlayableDeferred.isCompleted) session.firstPlayableDeferred.complete(selectedSource)
            if (session.isActive) onFirstPlayableSource(selectedSource)
            PlaybackDiagnosticsStore.finish(true, "اكتمل فحص الإضافات وعُثر على مصدر مستخرج؛ تأكيد تشغيل الفيديو مسؤولية المشغّل")
            PlaybackOrchestratorOutcome.Started(selectedSource, session)
        } else {
            Phase05HLogger.log("PLAYBACK", session.mediaId, "ALL_CANDIDATES_FAILED: isDubbed=$isDubbedSession")
            Phase05HLogger.log("PLAYBACK", session.mediaId, "PLAYBACK_FAILED")
            val userMsg = if (isDubbedSession) "النسخة المدبلجة غير متوفرة لهذا العمل" else "تعذر تشغيل هذا المحتوى"
            onError(userMsg)
            PlaybackDiagnosticsStore.finish(false, "انتهت المحاولة دون العثور على مصدر صالح؛ راجع أول مرحلة حمراء")
            PlaybackOrchestratorOutcome.Failure(if (isDubbedSession) "النسخة المدبلجة غير متوفرة لهذا العمل" else "No playback sources are currently available.")
        }
    }
}
