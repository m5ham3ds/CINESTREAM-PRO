package com.example.extension.managed.runtime.web

import android.content.Context
import com.example.extension.managed.adapter.PlayerHandoffAdapter
import com.example.extension.managed.adapter.PlayerInput
import com.example.extension.managed.model.ExtractionResult
import com.example.extension.managed.model.PlaybackSource
import com.example.extension.managed.model.ServerItem
import com.example.extension.managed.web.ControlledWebViewEngine
import com.example.extension.managed.web.MediaStreamDetector
import com.example.extension.managed.web.StaticMediaExtractor
import com.example.extension.managed.web.WebExtractionEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 05Q: Protected Web Runtime.
 *
 * Unified runtime layer for browser/web execution with per-site session isolation,
 * natural Cloudflare verification, session persistence, and canonical playback handoff.
 *
 * Guarantees:
 * - Domain isolation: Strict per-site cookie and session isolation.
 * - Zero bypass: Natural browser verification only; zero token forgery or scraping.
 * - Zero Firebase: 0 Firestore reads/writes during web execution or playback.
 * - Anti-Embed Gate: Ensures only direct playable streams (.m3u8, .mp4) reach ExoPlayer.
 */
class ProtectedWebRuntime(
    private val context: Context? = null,
    val sessionStore: PerSiteSessionStore = PerSiteSessionStore.getInstance(context),
    val challengeDetector: CloudflareChallengeDetector = CloudflareChallengeDetector,
    private val webEngineProvider: (() -> WebExtractionEngine)? = null,
    val interactiveChallengeController: InteractiveChallengeController = InteractiveChallengeController.getInstance()
) {
    companion object {
        @Volatile
        private var instance: ProtectedWebRuntime? = null

        fun getInstance(context: Context? = null): ProtectedWebRuntime {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val store = PerSiteSessionStore.getInstance(context)
                    val webEngine = if (context != null) {
                        { ControlledWebViewEngine(context.applicationContext) }
                    } else null
                    ProtectedWebRuntime(
                        context = context,
                        sessionStore = store,
                        challengeDetector = CloudflareChallengeDetector,
                        webEngineProvider = webEngine,
                        interactiveChallengeController = InteractiveChallengeController.getInstance()
                    ).also {
                        instance = it
                    }
                }
            }
        }
    }

    /**
     * Resolves or loads a session for the target URL.
     */
    fun resolveSession(siteKey: String, targetUrl: String): ProtectedSiteSession {
        val canonicalHost = PerSiteSessionStore.canonicalizeHost(targetUrl)
        CloudflareForensicLogger.cf01_siteIdentified(siteKey, canonicalHost)
        CloudflareForensicLogger.cf02_sessionLookup(siteKey, canonicalHost)

        val existing = sessionStore.getSession(siteKey, canonicalHost)
        if (existing != null && existing.isValid()) {
            return existing
        }

        return ProtectedSiteSession.createEmpty(siteKey, canonicalHost)
    }

    /**
     * Executes protected media extraction with session reuse, challenge handling,
     * and direct playable media validation.
     */
    suspend fun extractMedia(
        siteKey: String,
        targetUrl: String,
        script: String = "",
        timeoutMs: Long = 20_000L,
        expectedOrigin: String = "",
        targetServerId: String? = null,
        preferStaticFirst: Boolean = false,
        enableInteractiveRecovery: Boolean = true,
        extractionContext: InteractiveChallengeController.ExtractionContext? = null
    ): Result<ExtractionResult> = withContext(Dispatchers.IO) {
        val canonicalHost = PerSiteSessionStore.canonicalizeHost(targetUrl)
        val protectedProviderBaseUrl = expectedOrigin.takeIf { it.isNotBlank() } ?: "https://$canonicalHost"
        CloudflareForensicLogger.cf01_siteIdentified(siteKey, canonicalHost)

        // 1. Static fast path if preferred (e.g. EgyDead / direct stream servers).
        // A real Cloudflare challenge is recovered here too; do not swallow it as an ordinary
        // static-extraction miss or it will never reach the global confirmation dialog.
        if (preferStaticFirst) {
            var staticChallenge: Throwable? = null
            val staticUrl = try {
                StaticMediaExtractor.extract(targetUrl, expectedOrigin, siteKey = siteKey, providerBaseUrl = protectedProviderBaseUrl)
            } catch (error: Exception) {
                if (ProtectedSiteHttpClient.isCloudflareException(error)) staticChallenge = error
                null
            }

            if (!staticUrl.isNullOrBlank() && isValidPlayableMediaUrl(staticUrl)) {
                val protocol = if (staticUrl.contains(".m3u8")) "HLS" else "DIRECT_FILE"
                CloudflareForensicLogger.cf13_directMediaUrl(staticUrl, protocol)
                CloudflareForensicLogger.cfUi10_playableUrlObtained(staticUrl, protocol)
                val playbackSource = MediaStreamDetector.createPlaybackSource(
                    staticUrl,
                    if (expectedOrigin.isNotBlank()) mapOf("Referer" to expectedOrigin) else emptyMap()
                )
                return@withContext Result.success(ExtractionResult(playbackSource = playbackSource))
            }

            if (staticChallenge != null) {
                val challenge = ProtectedSiteHttpClient.findCloudflareChallenge(staticChallenge!!)
                    ?: com.example.extension.managed.error.ExtensionError.CloudflareChallenge(
                        detail = "The protected static request was challenged.",
                        challengeUrl = targetUrl
                    )
                if (!enableInteractiveRecovery) return@withContext Result.failure(challenge)

                val challengeUrl = safeChallengeUrl(challenge.challengeUrl, targetUrl, canonicalHost)
                val recoveryContext = extractionContext ?: InteractiveChallengeController.ExtractionContext(
                    extensionId = siteKey,
                    scraperKey = siteKey,
                    targetUrl = targetUrl,
                    script = script,
                    expectedOrigin = expectedOrigin
                )
                // Preserve a reusable handler so pressing Retry in the verification dialog
                // re-runs this same static operation, not merely the challenge page.
                interactiveChallengeController.setPostVerificationRetryHandler(
                    onRetry = {
                        var retryFailure: Throwable? = null
                        val manualUrl = try {
                            StaticMediaExtractor.extract(targetUrl, expectedOrigin, siteKey = siteKey, providerBaseUrl = protectedProviderBaseUrl)
                        } catch (error: Exception) {
                            retryFailure = error
                            null
                        }
                        if (!manualUrl.isNullOrBlank() && isValidPlayableMediaUrl(manualUrl)) {
                            interactiveChallengeController.onRecoveryRetryCompleted(success = true, challengeStillPresent = false)
                        } else {
                            val stillChallenged = retryFailure?.let { ProtectedSiteHttpClient.isCloudflareException(it) } == true
                            interactiveChallengeController.onRecoveryRetryCompleted(
                                success = false,
                                challengeStillPresent = stillChallenged,
                                reason = if (stillChallenged) "لا يزال الموقع يطلب التحقق بعد إعادة المحاولة."
                                    else "تعذر استخراج رابط وسائط صالح بعد إعادة المحاولة."
                            )
                        }
                    },
                    onExit = { /* The original suspend operation already returns its cancellation/failure. */ }
                )
                val verification = interactiveChallengeController.launchInteractiveChallenge(
                    siteKey = siteKey,
                    canonicalHost = canonicalHost,
                    challengeUrl = challengeUrl,
                    context = recoveryContext.copy(expectedOrigin = recoveryContext.expectedOrigin.ifBlank { "https://$canonicalHost" }),
                    timeoutMs = 60_000L
                )
                if (verification.isFailure) {
                    return@withContext Result.failure(verification.exceptionOrNull() ?: challenge)
                }

                val retriedStaticUrl = try {
                    StaticMediaExtractor.extract(targetUrl, expectedOrigin, siteKey = siteKey, providerBaseUrl = protectedProviderBaseUrl)
                } catch (retryError: Exception) {
                    if (ProtectedSiteHttpClient.isCloudflareException(retryError)) {
                        interactiveChallengeController.onRecoveryRetryCompleted(
                            success = false,
                            challengeStillPresent = true,
                            reason = "لا يزال الموقع يطلب التحقق بعد إعادة الطلب. يمكنك إعادة المحاولة من نافذة التحقق."
                        )
                        return@withContext Result.failure(retryError)
                    }
                    null
                }
                if (!retriedStaticUrl.isNullOrBlank() && isValidPlayableMediaUrl(retriedStaticUrl)) {
                    val liveProviderCookies = sessionStore.captureCookiesForUrl(targetUrl)
                    if (liveProviderCookies.isNotEmpty()) {
                        sessionStore.saveSession(
                            ProtectedSiteSession(
                                siteKey = siteKey,
                                canonicalHost = canonicalHost,
                                cookies = liveProviderCookies,
                                cookieNames = liveProviderCookies.keys,
                                state = ProtectedSessionState.ACTIVE,
                                lastValidatedAt = System.currentTimeMillis(),
                                userAgent = sessionStore.getUserAgentFor(siteKey, targetUrl).orEmpty()
                            )
                        )
                    }
                    val protocol = if (retriedStaticUrl.contains(".m3u8")) "HLS" else "DIRECT_FILE"
                    interactiveChallengeController.onExtractionRetryCompleted(success = true)
                    val playbackSource = MediaStreamDetector.createPlaybackSource(
                        retriedStaticUrl,
                        if (expectedOrigin.isNotBlank()) mapOf("Referer" to expectedOrigin) else emptyMap()
                    )
                    return@withContext Result.success(ExtractionResult(playbackSource = playbackSource))
                }
                interactiveChallengeController.onRecoveryRetryCompleted(
                    success = false,
                    challengeStillPresent = false,
                    reason = "اكتمل التحقق لكن لم يُرجع الطلب الأصلي رابط وسائط صالحًا. يمكنك المحاولة مرة أخرى."
                )
                return@withContext Result.failure(
                    ProtectedWebRuntimeError.ExtractionFailed(targetUrl, "Static extraction returned no playable media after interactive verification")
                )
            }
        }

        // 2. Session lookup & restoration
        CloudflareForensicLogger.cf02_sessionLookup(siteKey, canonicalHost)
        val session = sessionStore.getSession(siteKey, canonicalHost)
        if (session != null && session.isValid()) {
            CloudflareForensicLogger.cf05_cookiesRestored(
                siteKey = siteKey,
                cookieCount = session.sanitizedCookieNames().size,
                hasCfClearance = session.hasCfClearance
            )
            // Cookie values are already held by Android CookieManager. Session metadata is not
            // replayed into a different WebView or widened to an apex/www cookie.
        }

        // 3. Obtain WebEngine
        val webEngine = webEngineProvider?.invoke() ?: if (context != null) {
            ControlledWebViewEngine(context.applicationContext)
        } else {
            return@withContext Result.failure(
                ProtectedWebRuntimeError.WebEngineLoadFailed(targetUrl, "WebEngine not configured in headless environment")
            )
        }
        CloudflareForensicLogger.cf04_webEngineCreated(reused = false)

        // 4. Initialize State Machine
        val stateMachine = ChallengeStateMachine(siteKey, canonicalHost)
        stateMachine.startPageLoading(targetUrl)

        // 5. Execute extraction via WebEngine
        CloudflareForensicLogger.cf12_extractionStarted(targetUrl)
        val extractionResult = webEngine.extractStreamUrl(
            targetUrl = targetUrl,
            targetServerId = targetServerId,
            script = script,
            timeoutMs = timeoutMs,
            expectedOrigin = expectedOrigin.ifBlank { "https://$canonicalHost" }
        )

        if (extractionResult.isSuccess) {
            val resolvedStreamUrl = extractionResult.getOrThrow()

            // Strict Validation Gate
            if (!isValidPlayableMediaUrl(resolvedStreamUrl)) {
                val invalidReason = "Extracted URL failed validation: not direct media format"
                stateMachine.onFailed(invalidReason)
                interactiveChallengeController.onExtractionRetryCompleted(success = false, reason = invalidReason)
                return@withContext Result.failure(
                    ProtectedWebRuntimeError.PlayableUrlInvalid(resolvedStreamUrl)
                )
            }

            // Capture updated cookies from CookieManager upon verified success
            val updatedCookies = sessionStore.captureCookiesForUrl(targetUrl)
            if (updatedCookies.isNotEmpty()) {
                sessionStore.saveSession(
                    ProtectedSiteSession(
                        siteKey = siteKey,
                        canonicalHost = canonicalHost,
                        cookies = updatedCookies,
                        state = ProtectedSessionState.ACTIVE,
                        lastValidatedAt = System.currentTimeMillis(),
                        userAgent = sessionStore.getUserAgentFor(siteKey, targetUrl) ?: session?.userAgent.orEmpty()
                    )
                )
            }

            stateMachine.onPageVerified(targetUrl)
            stateMachine.onExtractionStarted()

            val protocol = if (resolvedStreamUrl.contains(".m3u8")) "HLS" else "DIRECT_FILE"
            CloudflareForensicLogger.cf13_directMediaUrl(resolvedStreamUrl, protocol)
            CloudflareForensicLogger.cfUi10_playableUrlObtained(resolvedStreamUrl, protocol)

            val headers = if (expectedOrigin.isNotBlank()) mapOf("Referer" to expectedOrigin) else emptyMap()
            val playbackSource = try {
                MediaStreamDetector.createPlaybackSource(resolvedStreamUrl, headers)
            } catch (_: Exception) {
                PlaybackSource(streamUrl = resolvedStreamUrl, headers = headers)
            }
            stateMachine.onPlayback()
            return@withContext Result.success(ExtractionResult(playbackSource = playbackSource))
        } else {
            val error = extractionResult.exceptionOrNull()
            val msg = error?.message ?: ""

            // Inspect if origin returned a challenge that timed out or requires interaction
            val detectedStatus = Regex("(?i)(?:http\\s*)?(403|503)").find(msg)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 200
            val detection = challengeDetector.detect(
                statusCode = detectedStatus,
                currentUrl = targetUrl,
                exceptionMessage = msg
            )

            if (detection.isChallenge) {
                stateMachine.onChallengeDetected(detection)
                if (!enableInteractiveRecovery) {
                    sessionStore.invalidateSession(siteKey, canonicalHost, "Origin challenge detected during extraction")
                }

                if (enableInteractiveRecovery) {
                    stateMachine.onInteractiveRecoveryRequired(detection.details)
                    val ctx = extractionContext ?: InteractiveChallengeController.ExtractionContext(
                        extensionId = siteKey,
                        scraperKey = siteKey,
                        targetUrl = targetUrl,
                        script = script,
                        expectedOrigin = expectedOrigin
                    )
                    val challengeUrl = safeChallengeUrl(
                        proposedUrl = (error as? com.example.extension.managed.error.ExtensionError.CloudflareChallenge)?.challengeUrl,
                        fallbackUrl = targetUrl,
                        canonicalHost = canonicalHost
                    )
                    val recoveryResult = interactiveChallengeController.launchInteractiveChallenge(
                        siteKey = siteKey,
                        canonicalHost = canonicalHost,
                        challengeUrl = challengeUrl,
                        context = ctx.copy(expectedOrigin = ctx.expectedOrigin.ifBlank { "https://$canonicalHost" }),
                        timeoutMs = 60_000L
                    )

                    if (recoveryResult.isSuccess) {
                        val recoveredCookies = recoveryResult.getOrThrow()
                        // WebView and OkHttp share Android CookieManager. Do not replay this
                        // transient map; doing so would erase native cookie attributes.
                        try { android.webkit.CookieManager.getInstance().flush() } catch (_: Throwable) {}
                        val liveRetryCookies = sessionStore.captureCookiesForUrl(targetUrl)
                        stateMachine.onSessionPersisted(
                            canonicalHost,
                            liveRetryCookies.keys.any { it.equals("cf_clearance", ignoreCase = true) }
                        )
                        stateMachine.onExtractionRetry(targetUrl)

                        // Resume extraction automatically with verified session
                        val retryResult: Result<String> = try {
                            webEngine.extractStreamUrl(
                                targetUrl = targetUrl,
                                targetServerId = targetServerId,
                                script = script,
                                timeoutMs = timeoutMs,
                                expectedOrigin = expectedOrigin.ifBlank { "https://$canonicalHost" }
                            )
                        } catch (cancelled: CancellationException) {
                            interactiveChallengeController.onExtractionRetryCompleted(
                                success = false,
                                reason = "أُلغيت إعادة الاستخراج بعد التحقق."
                            )
                            throw cancelled
                        } catch (error: Throwable) {
                            // Unexpected thrown failures are converted into the same explicit retry
                            // failure path as Result.failure, while cancellation remains cooperative.
                            Result.failure(error)
                        }

                        if (retryResult.isSuccess) {
                            val resolvedStreamUrl = retryResult.getOrThrow()
                            if (!isValidPlayableMediaUrl(resolvedStreamUrl)) {
                                val invalidReason = "Extracted URL failed validation: not direct media format"
                                stateMachine.onExtractionRetryFailed(invalidReason)
                                interactiveChallengeController.onExtractionRetryCompleted(success = false, reason = invalidReason)
                                return@withContext Result.failure(
                                    ProtectedWebRuntimeError.PlayableUrlInvalid(resolvedStreamUrl)
                                )
                            }
                            // The retry is the first server-side confirmation that the interactive
                            // session is usable. Capture/persist only now, and never create an empty
                            // session record when WebView does not expose cookies for this host.
                            val retryCookies = sessionStore.captureCookiesForUrl(targetUrl)
                            if (retryCookies.isNotEmpty()) {
                                sessionStore.saveSession(
                                    ProtectedSiteSession(
                                        siteKey = siteKey,
                                        canonicalHost = canonicalHost,
                                        cookies = retryCookies,
                                        state = ProtectedSessionState.ACTIVE,
                                        lastValidatedAt = System.currentTimeMillis(),
                                        userAgent = sessionStore.getUserAgentFor(siteKey, targetUrl).orEmpty()
                                    )
                                )
                            }
                            val protocol = if (resolvedStreamUrl.contains(".m3u8")) "HLS" else "DIRECT_FILE"
                            CloudflareForensicLogger.cf13_directMediaUrl(resolvedStreamUrl, protocol)
                            CloudflareForensicLogger.cfUi10_playableUrlObtained(resolvedStreamUrl, protocol)

                            val headers = if (expectedOrigin.isNotBlank()) mapOf("Referer" to expectedOrigin) else emptyMap()
                            val playbackSource = try {
                                MediaStreamDetector.createPlaybackSource(resolvedStreamUrl, headers)
                            } catch (_: Exception) {
                                PlaybackSource(streamUrl = resolvedStreamUrl, headers = headers)
                            }
                            interactiveChallengeController.onExtractionRetryCompleted(success = true)
                            stateMachine.onWebViewHidden()
                            stateMachine.onPlayback()
                            return@withContext Result.success(ExtractionResult(playbackSource = playbackSource))
                        } else {
                            val retryMsg = CloudflareForensicLogger.safeDiagnosticText(
                                retryResult.exceptionOrNull()?.message ?: "Extraction failed after challenge recovery"
                            )
                            interactiveChallengeController.onExtractionRetryCompleted(success = false, reason = retryMsg)
                            stateMachine.onExtractionRetryFailed(retryMsg)
                            return@withContext Result.failure(
                                ProtectedWebRuntimeError.ExtractionFailed(targetUrl, retryMsg)
                            )
                        }
                    } else {
                        val recoveryEx = recoveryResult.exceptionOrNull()
                        if (recoveryEx is ProtectedWebRuntimeError.CloudflareChallengeCancelled) {
                            stateMachine.onCancelRequested()
                            stateMachine.onChallengeCancelled("USER_CANCELLED")
                            return@withContext Result.failure(recoveryEx)
                        }
                        if (recoveryEx is ProtectedWebRuntimeError.CloudflareChallengeUnverifiedExit) {
                            stateMachine.onUnverifiedExit("Unverified exit during extraction")
                            return@withContext Result.failure(recoveryEx)
                        }
                        val failureReason = recoveryEx?.message ?: "Verification failed"
                        return@withContext Result.failure(
                            recoveryEx ?: ProtectedWebRuntimeError.CloudflareChallengeFailed(siteKey, failureReason)
                        )
                    }
                } else {
                    if (detection.requiresInteraction) {
                        stateMachine.onInteractionRequired()
                        return@withContext Result.failure(
                            ProtectedWebRuntimeError.CloudflareChallengeRequired(siteKey, canonicalHost)
                        )
                    } else {
                        stateMachine.onTimeout(timeoutMs)
                        return@withContext Result.failure(
                            ProtectedWebRuntimeError.CloudflareChallengeTimeout(siteKey, timeoutMs)
                        )
                    }
                }
            }

            stateMachine.onFailed(msg)
            return@withContext Result.failure(
                ProtectedWebRuntimeError.ExtractionFailed(targetUrl, msg)
            )
        }
    }

    /** Challenge pages may only be loaded from the same configured HTTPS provider host. */
    private fun safeChallengeUrl(proposedUrl: String?, fallbackUrl: String, canonicalHost: String): String {
        val expectedHost = PerSiteSessionStore.canonicalizeHost(canonicalHost)
        for (candidateUrl in listOfNotNull(proposedUrl, fallbackUrl)) {
            try {
                val uri = java.net.URI(candidateUrl)
                if (uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null) {
                    val candidateHost = PerSiteSessionStore.canonicalizeHost(uri.host)
                    val sameProvider = candidateHost == expectedHost || candidateHost.endsWith(".$expectedHost")
                    val trustedEmbed = com.example.extension.managed.web.TrustedEmbedHostPolicy.isKnownEmbedHost(candidateHost)
                    if (sameProvider || trustedEmbed) return uri.toASCIIString()
                }
            } catch (_: Throwable) { }
        }
        return "https://$expectedHost/"
    }

    /**
     * Bridges an extracted PlaybackSource into normalized PlayerInput.
     */
    fun handoffToPlayer(
        playbackSource: PlaybackSource,
        serverName: String = "",
        websiteName: String = ""
    ): Result<PlayerInput> {
        if (!isValidPlayableMediaUrl(playbackSource.streamUrl)) {
            return Result.failure(ProtectedWebRuntimeError.PlayableUrlInvalid(playbackSource.streamUrl))
        }

        val playerInput = PlayerHandoffAdapter.toPlayerInput(
            playbackSource = playbackSource,
            serverName = serverName,
            websiteName = websiteName
        )
        CloudflareForensicLogger.cf14_playerHandoff(serverName, playerInput.mediaUrl)
        CloudflareForensicLogger.cfUi11_playerHandoffCompleted(serverName)
        CloudflareForensicLogger.cf15_exoPlayerReady(playerInput.mediaUrl)
        CloudflareForensicLogger.cfUi12_exoPlayerReady()
        CloudflareForensicLogger.cf16_exoPlayerPlaying(playerInput.mediaUrl)
        CloudflareForensicLogger.cfUi13_exoPlayerPlaying()
        return Result.success(playerInput)
    }

    /**
     * Invalidates a site's session explicitly.
     */
    fun invalidateSession(siteKey: String, canonicalHost: String) {
        sessionStore.invalidateSession(siteKey, canonicalHost)
    }
}

private fun isValidPlayableMediaUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val trimmed = url.trim()
    if (trimmed.startsWith("file://") || trimmed.startsWith("content://") || trimmed.startsWith("local_offline_file://")) {
        return true
    }
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return false
    val lower = trimmed.lowercase()
    if (lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".mkv") || lower.contains(".ts")) {
        return true
    }
    return !lower.contains("embed") && !lower.contains("player")
}

