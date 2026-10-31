package com.example.extension.managed.runtime.web

import android.os.Handler
import android.os.Looper
import com.example.extension.managed.model.ContentType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Phase 05Q.1: Interactive Challenge Controller.
 *
 * Coordinates the natural interactive Cloudflare recovery flow when automated
 * background extraction triggers a verification challenge.
 *
 * Invariants:
 * 1. Single active challenge lock: Pauses scrapers and guarantees one active challenge per site/session.
 * 2. Original Context Preservation: Retains full playback/extraction context (mediaId, title, contentType,
 *    season, episode, extensionId, serverId, quality) to resume automatically without user re-trigger.
 * 3. Zero Firebase calls: Completely offline / client-side lifecycle and cookie store.
 * 4. Deterministic state transitions: IDLE -> CHALLENGE_DETECTED -> INTERACTIVE_RECOVERY_REQUIRED ->
 *    WEBVIEW_VISIBLE -> WAITING_FOR_USER -> VERIFYING -> SESSION_ESTABLISHED -> SESSION_PERSISTED ->
 *    WEBVIEW_HIDDEN -> EXTRACTION_RETRY -> PLAYBACK.
 */
class InteractiveChallengeController(
    private val sessionStoreProvider: () -> PerSiteSessionStore = { PerSiteSessionStore.getInstance() }
) {
    data class ExtractionContext(
        val mediaId: String = "",
        val title: String = "",
        val originalTitle: String? = null,
        val year: String = "",
        val contentType: ContentType = ContentType.MOVIE,
        val season: Int = 1,
        val episode: Int = 1,
        val extensionId: String = "",
        val scraperKey: String = "",
        val serverId: String? = null,
        val serverName: String? = null,
        val quality: String = "Auto",
        val targetUrl: String = "",
        val script: String = "",
        val expectedOrigin: String = ""
    )

    data class UiState(
        val phase: ChallengeStateMachine.Phase = ChallengeStateMachine.Phase.IDLE,
        val isVisible: Boolean = false,
        val siteKey: String = "",
        val canonicalHost: String = "",
        val challengeUrl: String = "",
        val challengeKey: String = "",
        val attemptId: Long = 0L,
        val hadExistingValidSession: Boolean = false,
        /** SHA-256 fingerprint only; raw rejected clearance is never retained. */
        val previousClearanceFingerprint: String? = null,
        /** True once the interactive page's completion detector accepted the verification. */
        val verificationCompleted: Boolean = false,
        val originalContext: ExtractionContext? = null,
        val errorMessage: String? = null,
        val remainingSeconds: Int = 60
    )

    private val _stateFlow = MutableStateFlow(UiState())
    val stateFlow: StateFlow<UiState> = _stateFlow.asStateFlow()

    private val challengeLock = Mutex()
    private var pendingDeferred: CompletableDeferred<Result<Map<String, String>>>? = null
    private var countdownJob: Job? = null
    private var stateMachine: ChallengeStateMachine? = null
    @Volatile private var activeChallengeKey: String? = null
    private var currentAttemptId: Long = 0L
    private val cancelledAttemptIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    private val cancelledChallengeKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var webViewCleanupCallback: (() -> Unit)? = null
    private var postVerificationRetryHandler: (suspend () -> Unit)? = null
    private var postVerificationExitHandler: (() -> Unit)? = null
    private var webViewCleanupToken: Long = 0L
    private var nextWebViewCleanupToken: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        @Volatile
        private var instance: InteractiveChallengeController? = null

        fun getInstance(): InteractiveChallengeController {
            return instance ?: synchronized(this) {
                instance ?: InteractiveChallengeController().also { instance = it }
            }
        }

        fun computeChallengeKey(
            siteKey: String,
            canonicalHost: String,
            challengeUrl: String,
            candidateId: String? = null
        ): String {
            val normUrl = challengeUrl.trim().removeSuffix("/").lowercase()
            val normHost = PerSiteSessionStore.canonicalizeHost(canonicalHost.ifBlank { challengeUrl })
            val normCandidate = (candidateId ?: siteKey).trim().lowercase()
            return "$normCandidate:$normHost:$normUrl"
        }
    }

    @Synchronized
    fun registerWebViewCleanup(cleanup: () -> Unit): Long {
        val token = ++nextWebViewCleanupToken
        webViewCleanupToken = token
        webViewCleanupCallback = cleanup
        return token
    }

    /** An older Compose instance must not unregister a newer WebView's cleanup callback. */
    @Synchronized
    fun unregisterWebViewCleanup(token: Long) {
        if (webViewCleanupToken == token) {
            webViewCleanupCallback = null
            webViewCleanupToken = 0L
        }
    }

    /** WebView APIs are main-thread confined even when the extraction runtime completes on IO. */
    private fun cleanupActiveWebView() {
        val callback = synchronized(this) {
            val current = webViewCleanupCallback
            webViewCleanupCallback = null
            webViewCleanupToken = 0L
            current
        } ?: return
        val cleanup = Runnable { try { callback.invoke() } catch (_: Throwable) { } }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run() else mainHandler.post(cleanup)
    }

    fun isAttemptCancelled(attemptId: Long = _stateFlow.value.attemptId, key: String? = null): Boolean {
        return cancelledAttemptIds.contains(attemptId) ||
                (key != null && cancelledChallengeKeys.contains(key)) ||
                _stateFlow.value.phase == ChallengeStateMachine.Phase.CANCEL_REQUESTED ||
                _stateFlow.value.phase == ChallengeStateMachine.Phase.CHALLENGE_CANCELLED ||
                _stateFlow.value.phase == ChallengeStateMachine.Phase.USER_CANCELLED ||
                _stateFlow.value.phase == ChallengeStateMachine.Phase.UNVERIFIED_EXIT ||
                _stateFlow.value.phase == ChallengeStateMachine.Phase.ATTEMPT_DISCARDED
    }

    private fun sanitizeChallengeUrl(rawUrl: String, canonicalHost: String): String {
        val candidate = try { java.net.URI(rawUrl.trim()) } catch (_: Exception) { null }
        val safeHost = PerSiteSessionStore.canonicalizeHost(canonicalHost)
        if (candidate != null && candidate.scheme.equals("https", ignoreCase = true) &&
            !candidate.host.isNullOrBlank() && candidate.userInfo == null) {
            val candidateHost = PerSiteSessionStore.canonicalizeHost(candidate.host)
            val sameProvider = candidateHost == safeHost || candidateHost.endsWith(".$safeHost")
            val trustedEmbed = com.example.extension.managed.web.TrustedEmbedHostPolicy.isKnownEmbedHost(candidateHost)
            if (sameProvider || trustedEmbed) return candidate.toASCIIString()
        }
        // Never load an HTTP downgrade or unrelated host into the verification WebView.
        return "https://$safeHost/"
    }

    /**
     * Initiates the interactive challenge recovery flow.
     * Pauses the scraper and displays the embedded WebView dialog inside the playback screen.
     */
    suspend fun launchInteractiveChallenge(
        siteKey: String,
        canonicalHost: String,
        challengeUrl: String,
        context: ExtractionContext,
        timeoutMs: Long = 60_000L
    ): Result<Map<String, String>> {
        val normalizedHost = PerSiteSessionStore.canonicalizeHost(canonicalHost)
        val safeChallengeUrl = sanitizeChallengeUrl(challengeUrl, normalizedHost)
        val challengeKey = computeChallengeKey(siteKey, normalizedHost, safeChallengeUrl, context.extensionId)

        // Phase 05Q.5-C: If candidate is already cancelled in current PlaybackAttempt, immediately abort!
        val isAlreadyCancelled = com.example.extension.managed.playback.PlaybackAttempt.current()?.let { attempt ->
            attempt.isCandidateCancelled(context.extensionId) || attempt.isCandidateCancelled(siteKey)
        } ?: false

        if (isAlreadyCancelled) {
            CloudflareForensicLogger.cfTrace("candidate_already_cancelled_skip", candidateId = context.extensionId, host = canonicalHost)
            return Result.failure(ProtectedWebRuntimeError.CloudflareChallengeCancelled(siteKey))
        }

        val currentUi = _stateFlow.value
        if (currentUi.isVisible && currentUi.phase == ChallengeStateMachine.Phase.EXTRACTION_RETRY &&
            currentUi.challengeKey != challengeKey) {
            CloudflareForensicLogger.cfTrace("challenge_request_deferred_while_verified_retry_surface_active", candidateId = context.extensionId, host = normalizedHost)
            return Result.failure(ProtectedWebRuntimeError.CloudflareChallengeFailed(
                siteKey,
                "A verified challenge surface is still active for another provider."
            ))
        }

        // Fast-path without lock: If identical challenge is already actively waiting or verifying, await it!
        val existingActiveDeferred = pendingDeferred
        if (activeChallengeKey == challengeKey && existingActiveDeferred != null && existingActiveDeferred.isActive) {
            val curPhase = _stateFlow.value.phase
            if (curPhase == ChallengeStateMachine.Phase.WAITING_FOR_USER ||
                curPhase == ChallengeStateMachine.Phase.VERIFYING ||
                curPhase == ChallengeStateMachine.Phase.WEBVIEW_VISIBLE) {
                return existingActiveDeferred.await()
            }
        }

        val deferredToAwait: CompletableDeferred<Result<Map<String, String>>> = challengeLock.withLock {
            // Re-check under lock before touching the live CookieManager. A second caller for the
            // same page must join the active attempt, not delete the clearance produced by it.
            cancelledChallengeKeys.remove(challengeKey)
            val curDeferred = pendingDeferred
            if (activeChallengeKey == challengeKey && curDeferred != null && curDeferred.isActive) {
                val curPhase = _stateFlow.value.phase
                if (curPhase == ChallengeStateMachine.Phase.WAITING_FOR_USER ||
                    curPhase == ChallengeStateMachine.Phase.VERIFYING ||
                    curPhase == ChallengeStateMachine.Phase.WEBVIEW_VISIBLE) {
                    return@withLock curDeferred
                }
            }

            val sessionStore = sessionStoreProvider()
            val sessionUnderLock = sessionStore.getSession(siteKey, normalizedHost)
            val hadExistingValidSession = sessionUnderLock?.isValid() == true
            // Capture a fingerprint BEFORE deletion and await CookieManager callbacks BEFORE the
            // WebView is shown. This ensures the detector cannot mistake the rejected old cookie
            // for the fresh verification result.
            val invalidationReason = if (hadExistingValidSession) {
                "provider rejected cached session"
            } else {
                "provider challenge observed; invalidate stale clearance"
            }
            val previousClearanceFingerprint = sessionStore.invalidateForChallenge(
                siteKey = siteKey,
                providerCanonicalHost = normalizedHost,
                challengeUrl = safeChallengeUrl,
                reason = invalidationReason
            )
            if (hadExistingValidSession) {
                CloudflareForensicLogger.cfTrace("cached_session_rejected_by_provider", candidateId = context.extensionId, host = normalizedHost)
            }

            val attemptId = ++currentAttemptId
            activeChallengeKey = challengeKey

            CloudflareForensicLogger.cfUi20_newAttemptStarted(siteKey, attemptId)
            CloudflareForensicLogger.cfTrace("new_playback_attempt", host = normalizedHost, candidateId = context.extensionId)
            CloudflareForensicLogger.cfTrace("challenge_recreated", host = normalizedHost)

            val sm = ChallengeStateMachine(siteKey, normalizedHost)
            stateMachine = sm
            sm.onInteractiveRecoveryRequired("Interactive Cloudflare verification challenge initiated")
            sm.onWebViewVisible(safeChallengeUrl)
            sm.onWaitingForUser()

            val deferred = CompletableDeferred<Result<Map<String, String>>>()
            pendingDeferred = deferred

            val initialSeconds = (timeoutMs / 1000).toInt().coerceAtLeast(10)
            _stateFlow.value = UiState(
                phase = ChallengeStateMachine.Phase.WAITING_FOR_USER,
                isVisible = true,
                siteKey = siteKey,
                canonicalHost = normalizedHost,
                challengeUrl = safeChallengeUrl,
                challengeKey = challengeKey,
                attemptId = attemptId,
                hadExistingValidSession = hadExistingValidSession,
                previousClearanceFingerprint = previousClearanceFingerprint,
                verificationCompleted = false,
                originalContext = context,
                remainingSeconds = initialSeconds
            )

            countdownJob?.cancel()
            countdownJob = scope.launch {
                var secs = initialSeconds
                while (secs > 0 && deferred.isActive) {
                    delay(1000)
                    secs--
                    _stateFlow.value = _stateFlow.value.copy(remainingSeconds = secs)
                }
                if (secs <= 0 && deferred.isActive) onTimeout(timeoutMs)
            }
            deferred
        }

        try {
            return deferredToAwait.await()
        } finally {
            countdownJob?.cancel()
            countdownJob = null
        }
    }

    fun onSiteLoaded(url: String) {
        CloudflareForensicLogger.cfUi03_siteLoaded(url)
    }

    fun onUserVerificationStarted() {
        val currentPhase = _stateFlow.value.phase
        if (currentPhase == ChallengeStateMachine.Phase.VERIFYING ||
            currentPhase == ChallengeStateMachine.Phase.SESSION_ESTABLISHED ||
            currentPhase == ChallengeStateMachine.Phase.SESSION_PERSISTED ||
            currentPhase == ChallengeStateMachine.Phase.CANCEL_REQUESTED ||
            currentPhase == ChallengeStateMachine.Phase.CHALLENGE_CANCELLED ||
            currentPhase == ChallengeStateMachine.Phase.USER_CANCELLED) {
            return
        }
        stateMachine?.onVerifying()
        _stateFlow.value = _stateFlow.value.copy(phase = ChallengeStateMachine.Phase.VERIFYING)
    }

    fun onVerificationCompleted(cookies: Map<String, String>) {
        onVerificationCompleted(_stateFlow.value.attemptId, _stateFlow.value.challengeKey, cookies, "")
    }

    fun onVerificationCompleted(attemptId: Long, challengeKey: String, cookies: Map<String, String>) {
        onVerificationCompleted(attemptId, challengeKey, cookies, "")
    }

    fun onVerificationCompleted(
        attemptId: Long,
        challengeKey: String,
        cookies: Map<String, String>,
        userAgent: String
    ) {
        val current = _stateFlow.value
        val host = current.canonicalHost
        val siteKey = current.siteKey
        val curPhase = current.phase

        // Phase 05Q.3-C & 05Q.3-D Hard Rule (Section 6, 7 & 12): CANCEL / UNVERIFIED EXIT MUST WIN OVER SUCCESS!
        // If user cancelled, or exited unverified, or callback is from an old attempt, drop late success and do NOT persist session!
        val completionAllowedPhase = curPhase == ChallengeStateMachine.Phase.WAITING_FOR_USER ||
            curPhase == ChallengeStateMachine.Phase.WEBVIEW_VISIBLE ||
            curPhase == ChallengeStateMachine.Phase.VERIFYING ||
            curPhase == ChallengeStateMachine.Phase.INTERACTIVE_RECOVERY_REQUIRED
        if (attemptId != current.attemptId ||
            challengeKey != current.challengeKey ||
            !completionAllowedPhase ||
            curPhase == ChallengeStateMachine.Phase.CANCEL_REQUESTED ||
            curPhase == ChallengeStateMachine.Phase.CHALLENGE_CANCELLED ||
            curPhase == ChallengeStateMachine.Phase.USER_CANCELLED ||
            curPhase == ChallengeStateMachine.Phase.UNVERIFIED_EXIT ||
            curPhase == ChallengeStateMachine.Phase.ATTEMPT_DISCARDED ||
            cancelledAttemptIds.contains(attemptId) ||
            cancelledChallengeKeys.contains(challengeKey)) {
            CloudflareForensicLogger.cfTrace("late_success_ignored_after_cancel", host = host, state = curPhase.name)
            return
        }

        countdownJob?.cancel()
        countdownJob = null
        activeChallengeKey = null

        stateMachine?.onSessionEstablished(host)
        val safeCookies = ProtectedSiteSession(
            siteKey = siteKey,
            canonicalHost = host,
            cookies = cookies
        ).sanitizedCookies()
        // Keep the browser's actual UA for the immediate same-provider retry even when the
        // WebView CookieManager API does not expose exportable cookies. This is metadata only;
        // no empty or unauthenticated session is persisted as ACTIVE.
        sessionStoreProvider().rememberUserAgent(siteKey, host, userAgent)

        // Persist only after the completion detector has confirmed success. Do not write an
        // empty/placeholder session: some embedded Turnstile flows keep the browser session in
        // CookieManager without exposing a parent-origin cookie map. That live WebView session
        // can still be used for the immediate retry, while empty data must not poison disk state.
        // The returned cookie map may belong to a trusted external embed host. Persist metadata
        // only from the exact configured provider origin; never attach embed cookies to provider
        // metadata. Actual outbound requests always query CookieManager for their own URL.
        val providerCookies = sessionStoreProvider().captureCookiesForUrl("https://$host/")
        val providerHasClearance = providerCookies.keys.any { it.equals("cf_clearance", ignoreCase = true) }
        if (providerCookies.isNotEmpty()) {
            val session = ProtectedSiteSession(
                siteKey = siteKey,
                canonicalHost = host,
                cookies = providerCookies,
                cookieNames = providerCookies.keys,
                state = ProtectedSessionState.ACTIVE,
                lastValidatedAt = System.currentTimeMillis(),
                userAgent = userAgent
            )
            sessionStoreProvider().saveSession(session)
        } else {
            CloudflareForensicLogger.cfTrace("verification_success_no_provider_cookies_keep_native_cookie_manager", host = host, state = curPhase.name)
        }

        if (providerCookies.isNotEmpty()) {
            stateMachine?.onSessionPersisted(host, providerHasClearance)
        }

        // Keep the user-verified WebView alive until the runtime confirms that the
        // post-verification extraction retry succeeded. Hiding it here destroyed the
        // only browser instance that had just completed the interactive challenge,
        // before the retry had even been tested.
        val targetUrl = current.originalContext?.targetUrl ?: current.challengeUrl
        stateMachine?.onExtractionRetry(targetUrl)
        _stateFlow.value = _stateFlow.value.copy(
            phase = ChallengeStateMachine.Phase.EXTRACTION_RETRY,
            isVisible = true,
            verificationCompleted = true,
            errorMessage = null
        )

        pendingDeferred?.complete(Result.success(safeCookies))
    }

    /**
     * Finalizes the interactive challenge only after the runtime's immediate retry
     * has a result. On failure, keep the already verified page visible so the user
     * can inspect/retry it instead of automatically opening another challenge page.
     */
    fun onExtractionRetryCompleted(success: Boolean, reason: String = "") {
        val current = _stateFlow.value
        if (!current.verificationCompleted ||
            (current.phase != ChallengeStateMachine.Phase.EXTRACTION_RETRY && current.phase != ChallengeStateMachine.Phase.VERIFYING)) return
        if (success) {
            stateMachine?.onWebViewHidden()
            cleanupActiveWebView()
            _stateFlow.value = current.copy(
                phase = ChallengeStateMachine.Phase.WEBVIEW_HIDDEN,
                isVisible = false,
                errorMessage = null
            )
            CloudflareForensicLogger.cfTrace("post_verification_retry_succeeded_webview_closed", host = current.canonicalHost)
        } else {
            // Keep the internal machine and the Compose-facing state aligned. Preserve
            // EXTRACTION_RETRY so a later ordered fallback can report success and dismiss
            // this same overlay.
            stateMachine?.onExtractionRetryFailed(reason.ifBlank { "Post-verification extraction failed" })
            _stateFlow.value = current.copy(
                phase = ChallengeStateMachine.Phase.EXTRACTION_RETRY,
                isVisible = true,
                errorMessage = reason.ifBlank { "اكتمل التحقق، لكن تعذر استكمال استخراج المصدر. يمكنك المحاولة يدويًا أو الإلغاء." }
            )
            CloudflareForensicLogger.cfTrace("post_verification_retry_failed_keep_current_webview", host = current.canonicalHost)
        }
    }

    /** Finalize recovery for all runtime operations, not playback alone. */
    fun onRecoveryRetryCompleted(success: Boolean, challengeStillPresent: Boolean, reason: String = "") {
        if (success) {
            clearPostVerificationRetryHandler()
            onExtractionRetryCompleted(success = true)
            return
        }
        val current = _stateFlow.value
        if (!current.verificationCompleted || current.phase != ChallengeStateMachine.Phase.EXTRACTION_RETRY) return
        if (challengeStillPresent) {
            onExtractionRetryCompleted(success = false, reason = reason)
            return
        }
        stateMachine?.onWebViewHidden()
        cleanupActiveWebView()
        _stateFlow.value = current.copy(
            phase = ChallengeStateMachine.Phase.WEBVIEW_HIDDEN,
            isVisible = false,
            errorMessage = reason.take(240).ifBlank { null }
        )
        clearPostVerificationRetryHandler()
        CloudflareForensicLogger.cfTrace("post_verification_retry_failed_without_challenge_webview_closed", host = current.canonicalHost)
    }

    /** Keeps the original suspended operation alive when a verified retry is challenged again. */
    @Synchronized
    fun setPostVerificationRetryHandler(onRetry: suspend () -> Unit, onExit: () -> Unit) {
        postVerificationRetryHandler = onRetry
        postVerificationExitHandler = onExit
    }

    @Synchronized
    fun clearPostVerificationRetryHandler() {
        postVerificationRetryHandler = null
        postVerificationExitHandler = null
    }

    private fun notifyPostVerificationExit() {
        val callback = synchronized(this) {
            val current = postVerificationExitHandler
            postVerificationRetryHandler = null
            postVerificationExitHandler = null
            current
        }
        try { callback?.invoke() } catch (_: Throwable) { }
    }

    fun onVerificationFailed(reason: String) {
        countdownJob?.cancel()
        countdownJob = null
        activeChallengeKey = null

        stateMachine?.onFailed(reason)
        _stateFlow.value = _stateFlow.value.copy(
            phase = ChallengeStateMachine.Phase.CHALLENGE_FAILED,
            errorMessage = reason
        )
        val siteKey = _stateFlow.value.siteKey
        pendingDeferred?.complete(
            Result.failure(ProtectedWebRuntimeError.CloudflareChallengeFailed(siteKey, reason))
        )
    }

    fun cancelCurrentChallenge() = cancelChallenge()

    fun cancelChallenge() {
        if (_stateFlow.value.phase == ChallengeStateMachine.Phase.SESSION_PERSISTED) {
            return
        }
        notifyPostVerificationExit()
        val attemptId = _stateFlow.value.attemptId
        val key = _stateFlow.value.challengeKey
        if (attemptId > 0L) {
            cancelledAttemptIds.add(attemptId)
        }
        if (key.isNotBlank()) {
            cancelledChallengeKeys.add(key)
        }
        activeChallengeKey?.let { cancelledChallengeKeys.add(it) }

        countdownJob?.cancel()
        countdownJob = null
        activeChallengeKey = null

        val siteKey = _stateFlow.value.siteKey
        val candidateId = _stateFlow.value.originalContext?.extensionId ?: siteKey

        // Phase 05Q.5-C: Record cancelled candidate in current PlaybackAttempt
        val currentAttempt = com.example.extension.managed.playback.PlaybackAttempt.current()
        currentAttempt?.cancelCandidate(candidateId)
        currentAttempt?.cancelCandidate(siteKey)
        _stateFlow.value.originalContext?.extensionId?.let { currentAttempt?.cancelCandidate(it) }

        // 1. Transition state machine: CANCEL_REQUESTED -> CHALLENGE_CANCELLED
        stateMachine?.onCancelRequested()
        stateMachine?.onChallengeCancelled("USER_CANCELLED")

        // 2. Perform WebView cleanup on its owning (main) thread.
        cleanupActiveWebView()

        // 3. Mark UiState as cancelled and hidden
        _stateFlow.value = _stateFlow.value.copy(
            phase = ChallengeStateMachine.Phase.CHALLENGE_CANCELLED,
            isVisible = false,
            errorMessage = "USER_CANCELLED"
        )

        CloudflareForensicLogger.cfUi16_recoveryCancelled(siteKey)
        CloudflareForensicLogger.cfTrace("challenge_cancelled", candidateId = candidateId, host = _stateFlow.value.canonicalHost)

        // 4. Complete pending deferred with failure so caller aborts candidate
        pendingDeferred?.complete(
            Result.failure(ProtectedWebRuntimeError.CloudflareChallengeCancelled(siteKey))
        )
        pendingDeferred = null
    }

    /**
     * Phase 05Q.3-D: Unverified Exit.
     * Called when user exits/closes screen, presses Back, or navigates away without completing verification.
     * Discards current challenge attempt, clears transient state, stops WebView and polling,
     * completes pendingDeferred with CloudflareChallengeUnverifiedExit, and does NOT save session.
     * Existing valid sessions in PerSiteSessionStore remain intact.
     */
    fun onUnverifiedExit(reason: String = "USER_EXIT") {
        notifyPostVerificationExit()
        val current = _stateFlow.value
        val curPhase = current.phase

        // Verification is already committed once the completion detector accepted it. A back
        // gesture while extraction retry is running may close the surface, but must not relabel
        // the verified session as an unverified exit or invalidate its locally persisted cookies.
        if (current.verificationCompleted || curPhase == ChallengeStateMachine.Phase.SESSION_PERSISTED) {
            countdownJob?.cancel()
            countdownJob = null
            cleanupActiveWebView()
            _stateFlow.value = current.copy(
                phase = ChallengeStateMachine.Phase.WEBVIEW_HIDDEN,
                isVisible = false,
                errorMessage = null
            )
            CloudflareForensicLogger.cfTrace("verified_challenge_surface_closed_during_retry", host = current.canonicalHost)
            return
        }

        val attemptId = current.attemptId
        val siteKey = current.siteKey
        val key = current.challengeKey

        if (attemptId > 0L) {
            cancelledAttemptIds.add(attemptId)
        }

        countdownJob?.cancel()
        countdownJob = null
        activeChallengeKey = null

        // 1. Transition state machine
        stateMachine?.onUnverifiedExit(reason)
        stateMachine?.onAttemptDiscarded(attemptId)

        // 2. Perform WebView cleanup on its owning (main) thread.
        cleanupActiveWebView()

        // 3. Mark UiState as unverified exit and hidden
        _stateFlow.value = _stateFlow.value.copy(
            phase = ChallengeStateMachine.Phase.UNVERIFIED_EXIT,
            isVisible = false,
            errorMessage = "UNVERIFIED_EXIT"
        )

        CloudflareForensicLogger.cfUi18_unverifiedExit(siteKey, reason)
        CloudflareForensicLogger.cfUi19_attemptDiscarded(siteKey, attemptId)
        CloudflareForensicLogger.cfTrace("user_exit", host = current.canonicalHost)
        CloudflareForensicLogger.cfTrace("unverified_exit", host = current.canonicalHost)
        CloudflareForensicLogger.cfTrace("challenge_attempt_discarded", host = current.canonicalHost)
        CloudflareForensicLogger.cfTrace("transient_state_cleared", host = current.canonicalHost)
        CloudflareForensicLogger.cfTrace("webview_cleaned", host = current.canonicalHost)

        // 4. Complete pending deferred with CloudflareChallengeUnverifiedExit
        pendingDeferred?.complete(
            Result.failure(ProtectedWebRuntimeError.CloudflareChallengeUnverifiedExit(siteKey))
        )
        pendingDeferred = null
    }

    fun onTimeout(timeoutMs: Long = 60_000L) {
        countdownJob?.cancel()
        countdownJob = null
        activeChallengeKey = null

        val siteKey = _stateFlow.value.siteKey
        stateMachine?.onTimeout(timeoutMs)
        _stateFlow.value = _stateFlow.value.copy(
            phase = ChallengeStateMachine.Phase.CHALLENGE_TIMEOUT,
            errorMessage = "TIMEOUT"
        )
        pendingDeferred?.complete(
            Result.failure(ProtectedWebRuntimeError.CloudflareChallengeTimeout(siteKey, timeoutMs))
        )
    }

    fun retryChallenge() {
        val current = _stateFlow.value
        val ctx = current.originalContext ?: return
        scope.launch {
            val result = launchInteractiveChallenge(
                siteKey = current.siteKey,
                canonicalHost = current.canonicalHost,
                challengeUrl = current.challengeUrl,
                context = ctx,
                timeoutMs = 180_000L
            )
            if (result.isSuccess && stateFlow.value.verificationCompleted) {
                val handler = synchronized(this@InteractiveChallengeController) { postVerificationRetryHandler }
                try { handler?.invoke() } catch (cancelled: CancellationException) { throw cancelled } catch (t: Throwable) {
                    onRecoveryRetryCompleted(false, false, t.message.orEmpty())
                }
            } else if (result.isFailure) {
                // A failed/expired verification attempt is retryable. Keep the suspended
                // original-operation handler installed; it is cleared only after that
                // operation succeeds/fails terminally or the user explicitly cancels/exits.
                CloudflareForensicLogger.cfTrace(
                    "verification_retry_failed_original_operation_retained",
                    host = stateFlow.value.canonicalHost,
                    state = stateFlow.value.phase.name
                )
            }
        }
    }

    fun reset() {
        clearPostVerificationRetryHandler()
        countdownJob?.cancel()
        countdownJob = null
        activeChallengeKey = null
        webViewCleanupCallback = null
        webViewCleanupToken = 0L
        pendingDeferred?.cancel()
        pendingDeferred = null
        stateMachine?.onIdle()
        _stateFlow.value = UiState()
    }
}
