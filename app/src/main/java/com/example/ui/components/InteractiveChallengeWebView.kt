package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.*
import com.example.extension.managed.runtime.web.InteractiveChallengeController
import com.example.extension.managed.runtime.web.ChallengeStateMachine
import com.example.extension.managed.runtime.web.PerSiteSessionStore
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.extension.managed.web.MediaStreamDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shared interactive Cloudflare WebView lifecycle. The single Dialog host is mounted by MainActivity,
 * not inside player/detail surfaces, so every managed-scraper operation can request the same UI.
 * Verification remains on the official provider page; the app only detects completion and retries the
 * original operation. WebView navigation, cookies, and cleanup are scoped and explicitly managed.
 */
object InteractiveChallengeWebViewHelper {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun removePolling(runnable: Runnable) {
        mainHandler.removeCallbacks(runnable)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun configureWebView(
        webView: WebView,
        canonicalHost: String,
        controller: InteractiveChallengeController,
        attemptId: Long = controller.stateFlow.value.attemptId,
        challengeKey: String = controller.stateFlow.value.challengeKey,
        previousClearanceFingerprint: String? = controller.stateFlow.value.previousClearanceFingerprint,
        onCompletionDetected: (Map<String, String>, String) -> Unit
    ): Runnable {
        val cookieManager = try { CookieManager.getInstance() } catch (_: Throwable) { null }
        cookieManager?.setAcceptCookie(true)
        cookieManager?.setAcceptThirdPartyCookies(webView, true)

        val defaultUa = try {
            WebSettings.getDefaultUserAgent(webView.context)
        } catch (_: Throwable) {
            null
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            if (!defaultUa.isNullOrBlank()) {
                userAgentString = defaultUa
            }
            useWideViewPort = false
            loadWithOverviewMode = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = false

        // Ensure user interactions (taps, drags, Turnstile clicks) reach the WebView
        // and pin state to VERIFYING as soon as user touches Turnstile checkbox.
        webView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_UP -> {
                    // Phase 05Q.3-B: Pin state to VERIFYING as soon as user begins interaction
                    if (controller.stateFlow.value.phase == ChallengeStateMachine.Phase.WAITING_FOR_USER) {
                        controller.onUserVerificationStarted()
                    }
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }

        var isCompleted = false

        fun triggerSuccessIfVerified() {
            if (isCompleted || controller.isAttemptCancelled(attemptId, challengeKey)) return
            val currentUrl = webView.url ?: ""
            checkVerificationCompletion(
                webView = webView,
                currentUrl = currentUrl,
                canonicalHost = canonicalHost,
                controller = controller,
                attemptId = attemptId,
                challengeKey = challengeKey,
                previousClearanceFingerprint = previousClearanceFingerprint
            ) { passed ->
                if (passed && !isCompleted && !controller.isAttemptCancelled(attemptId, challengeKey)) {
                    isCompleted = true
                    // Capture only cookies the browser would send to the current final URL.
                    // Do not merge apex/www/subdomain cookies: CookieManager's domain/path
                    // semantics must remain authoritative for the retry request.
                    val sessionStore = PerSiteSessionStore.getInstance(webView.context)
                    val cookies = sessionStore.captureCookiesForUrl(currentUrl)
                    onCompletionDetected(cookies, webView.settings.userAgentString.orEmpty())
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (controller.isAttemptCancelled(attemptId, challengeKey)) return
                // Internal progress does NOT reset verification or create new challenge
                if (newProgress >= 50 && view != null) {
                    scrollTurnstileIntoView(view, challengeKey, controller)
                }
                if (newProgress >= 70) {
                    triggerSuccessIfVerified()
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                if (controller.isAttemptCancelled(attemptId, challengeKey)) return
                triggerSuccessIfVerified()
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url ?: return true
                val scheme = target.scheme?.lowercase().orEmpty()
                if (scheme != "https") return true
                val initialChallengeHost = runCatching {
                    Uri.parse(controller.stateFlow.value.challengeUrl).host.orEmpty()
                }.getOrDefault("").ifBlank { canonicalHost }
                val allowed = com.example.extension.managed.web.TrustedEmbedHostPolicy.isNavigationAllowed(
                    targetUri = target,
                    expectedOriginHost = canonicalHost,
                    initialTargetHost = initialChallengeHost
                )
                return !allowed
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                // Never bypass TLS errors during human verification.
                handler?.cancel()
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (controller.isAttemptCancelled(attemptId, challengeKey)) return
                // Cloudflare internal navigation / form submission must not reset state to CHALLENGE_DETECTED
                if (url != null) {
                    controller.onSiteLoaded(url)
                }
                triggerSuccessIfVerified()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (controller.isAttemptCancelled(attemptId, challengeKey)) return
                if (view != null) {
                    scrollTurnstileIntoView(view, challengeKey, controller)
                }
                triggerSuccessIfVerified()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (controller.isAttemptCancelled(attemptId, challengeKey)) return
                triggerSuccessIfVerified()
            }
        }

        // Active Periodic Monitor: Polls every 500ms while user interacts with the challenge
        // to scroll Turnstile into view and detect genuine Turnstile / cf_clearance completion.
        val pollingRunnable = object : Runnable {
            override fun run() {
                if (!isCompleted && !controller.isAttemptCancelled(attemptId, challengeKey)) {
                    scrollTurnstileIntoView(webView, challengeKey, controller)
                    triggerSuccessIfVerified()
                    mainHandler.postDelayed(this, 500L)
                }
            }
        }
        mainHandler.postDelayed(pollingRunnable, 600L)

        return pollingRunnable
    }

    /**
     * Phase 05Q.5-B: Intelligent Visual Positioning Engine entry point.
     * Delegates to TurnstilePositioningEngine to position the Turnstile verification box
     * without blind scrolling, without smooth scroll, and without repeating executions.
     */
    fun scrollTurnstileIntoView(
        webView: WebView,
        challengeKey: String = "",
        controller: InteractiveChallengeController = InteractiveChallengeController.getInstance()
    ) {
        val key = challengeKey.ifBlank { controller.stateFlow.value.challengeKey }
        TurnstilePositioningEngine.attemptPositioning(webView, key, controller)
    }

    /**
     * Phase 05Q.3-B: Multi-Signal Verification Completion Detection.
     * Combines DOM state, page title, challenge URL disappearance, CookieManager (cf_clearance),
     * and non-challenge navigation checks.
     * HARD RULE: Presence of cf_clearance alone is NOT sufficient if the page still displays a Challenge!
     */
    fun checkVerificationCompletion(
        webView: WebView,
        currentUrl: String,
        canonicalHost: String,
        controller: InteractiveChallengeController = InteractiveChallengeController.getInstance(),
        attemptId: Long = controller.stateFlow.value.attemptId,
        challengeKey: String = controller.stateFlow.value.challengeKey,
        previousClearanceFingerprint: String? = controller.stateFlow.value.previousClearanceFingerprint,
        callback: (Boolean) -> Unit
    ) {
        if (controller.isAttemptCancelled(attemptId, challengeKey)) {
            callback(false)
            return
        }

        // Hard Guard: Blank or about:blank is never verified!
        if (currentUrl.isBlank() || currentUrl == "about:blank" || currentUrl.startsWith("data:")) {
            callback(false)
            return
        }

        val uri = try { Uri.parse(currentUrl) } catch (_: Exception) { null }
        if (uri == null || !uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) {
            callback(false)
            return
        }
        val cleanHost = PerSiteSessionStore.canonicalizeHost(canonicalHost)
        val urlHost = PerSiteSessionStore.canonicalizeHost(uri?.host.orEmpty())
        val initialChallengeHost = PerSiteSessionStore.canonicalizeHost(
            runCatching { Uri.parse(controller.stateFlow.value.challengeUrl).host.orEmpty() }.getOrDefault("")
        )
        val providerPage = urlHost == cleanHost || urlHost.endsWith(".$cleanHost")
        val initialChallengePage = initialChallengeHost.isNotBlank() && urlHost == initialChallengeHost
        val trustedChallengePage = com.example.extension.managed.web.TrustedEmbedHostPolicy.isKnownEmbedHost(urlHost)
        if (urlHost.isBlank() || !(providerPage || initialChallengePage || trustedChallengePage)) {
            callback(false)
            return
        }

        val path = uri?.path?.lowercase() ?: ""

        // Signal 1: URL is not Cloudflare challenge platform
        val isChallengeUrl = path.contains("/cdn-cgi/challenge-platform/") ||
                currentUrl.contains("challenges.cloudflare.com")

        if (isChallengeUrl) {
            callback(false)
            return
        }

        // Signal 2: Title check
        val title = webView.title?.lowercase() ?: ""
        val isChallengeTitle = title.contains("just a moment") ||
                title.contains("attention required") ||
                title.contains("security check") ||
                title.contains("ddos-guard") ||
                title.contains("please verify")

        if (isChallengeTitle) {
            callback(false)
            return
        }

        // Signal 3: Cookies check from CookieManager
        val sessionStore = PerSiteSessionStore.getInstance(webView.context)
        val cookies = sessionStore.captureCookiesForUrl(currentUrl)
        val clearanceValue = cookies.entries.firstOrNull { it.key.equals("cf_clearance", ignoreCase = true) }?.value
        val hasCfClearance = !clearanceValue.isNullOrBlank()
        val clearanceFingerprint = PerSiteSessionStore.cookieValueFingerprint(clearanceValue)
        val isNewClearance = hasCfClearance &&
            (previousClearanceFingerprint == null || clearanceFingerprint != previousClearanceFingerprint)

        // Signal 4: Detailed DOM inspection
        val jsCheck = """
            (function() {
                try {
                    var title = (document.title || '').toLowerCase();
                    var isTitleChallenge = title.indexOf('just a moment') !== -1 ||
                                           title.indexOf('attention required') !== -1 ||
                                           title.indexOf('security check') !== -1 ||
                                           title.indexOf('ddos-guard') !== -1 ||
                                           title.indexOf('please verify') !== -1;
                    if (isTitleChallenge) {
                        return JSON.stringify({ isChallenge: true, reason: 'CHALLENGE_TITLE' });
                    }

                    var challengeForm = document.querySelector('#challenge-form, #challenge-stage, #turnstile-wrapper, .cf-turnstile, #challenge-running, #cf-wrapper');
                    var challengeIframe = document.querySelector('iframe[src*="challenges.cloudflare.com"], iframe[src*="turnstile"]');
                    var turnstileToken = document.querySelector('[name="cf-turnstile-response"]');
                    var hasToken = !!(turnstileToken && turnstileToken.value && turnstileToken.value.trim().length > 10);
                    var successElem = document.querySelector('#challenge-success, .cf-turnstile-success');
                    
                    function isVisible(el) {
                        if (!el) return false;
                        var r = el.getBoundingClientRect();
                        var st = window.getComputedStyle(el);
                        return r.width > 1 && r.height > 1 && st.display !== 'none' &&
                               st.visibility !== 'hidden' && Number(st.opacity || 1) > 0 &&
                               r.bottom > 0 && r.right > 0 && r.top < (window.innerHeight || 0) &&
                               r.left < (window.innerWidth || 0);
                    }
                    // Hidden/stale Cloudflare nodes often remain after a successful transition.
                    // Only visible challenge UI should block completion.
                    var hasActiveChallengeElements = isVisible(challengeForm) || isVisible(challengeIframe);
                    var body = document.body;
                    var bodyTextLength = (body && body.innerText) ? body.innerText.trim().length : 0;
                    var htmlLength = (body && body.innerHTML) ? body.innerHTML.length : 0;

                    return JSON.stringify({
                        hasActiveChallengeElements: hasActiveChallengeElements,
                        successElem: !!successElem,
                        hasToken: hasToken,
                        bodyTextLength: bodyTextLength,
                        htmlLength: htmlLength
                    });
                } catch(e) {
                    return JSON.stringify({ error: e.toString() });
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(jsCheck) { result ->
            if (controller.isAttemptCancelled(attemptId, challengeKey)) {
                callback(false)
                return@evaluateJavascript
            }
            if (result.isNullOrBlank() || result == "null") {
                callback(false)
                return@evaluateJavascript
            }

            try {
                val cleanJson = if (result.startsWith("\"") && result.endsWith("\"")) {
                    org.json.JSONObject(org.json.JSONTokener(result).nextValue() as String)
                } else {
                    org.json.JSONObject(result)
                }

                val hasActiveChallengeElements = cleanJson.optBoolean("hasActiveChallengeElements", false)
                val successElem = cleanJson.optBoolean("successElem", false)
                val hasToken = cleanJson.optBoolean("hasToken", false)
                val bodyTextLength = cleanJson.optInt("bodyTextLength", 0)
                val htmlLength = cleanJson.optInt("htmlLength", 0)

                // If user solved Turnstile locally (token generated or success element visible),
                // ensure the controller is marked as VERIFYING
                if (hasToken || successElem) {
                    controller.onUserVerificationStarted()
                }

                // HARD RULE (Section 9):
                // "لا تعتبر وجود cf_clearance وحده كافيًا إذا كانت الصفحة لا تزال تعرض Challenge."
                // Some Cloudflare pages leave a hidden challenge iframe in the DOM after
                // successful navigation. Treat it as stale only when the browser has moved to a
                // clean URL/title and a real cf_clearance cookie is present for this host.
                val staleChallengeFrameAfterClearance = hasActiveChallengeElements &&
                        !successElem && hasCfClearance && !isChallengeTitle && !isChallengeUrl
                if (hasActiveChallengeElements && !successElem && !staleChallengeFrameAfterClearance) {
                    callback(false)
                    return@evaluateJavascript
                }

                // Phase 26.6: Do not require arbitrary body/HTML length thresholds. A real
                // clearance cookie on a clean, non-challenge page is sufficient even if stale
                // challenge nodes remain in the DOM. Embedded Turnstile may complete without
                // cf_clearance; accept its response only after the challenge UI is gone and the
                // browser has left the challenge URL/title. A tap alone is never success.
                val isDomClean = !isChallengeTitle && !isChallengeUrl &&
                        (bodyTextLength > 0 || htmlLength > 0)
                val clearanceVerified = isNewClearance && isDomClean
                val embeddedTurnstileVerified = (hasToken || successElem) &&
                        !hasActiveChallengeElements && isDomClean
                callback(clearanceVerified || embeddedTurnstileVerified)
            } catch (_: Exception) {
                callback(false)
            }
        }
    }
}

/**
 * App-wide owner of the single interactive Cloudflare WebView.
 * It is mounted once from MainActivity so challenges raised by search, details,
 * episode listing, server discovery, and playback all have the same visible surface.
 */
@Composable
fun InteractiveChallengeDialogHost() {
    val controller = remember { InteractiveChallengeController.getInstance() }
    val state by controller.stateFlow.collectAsState()
    if (!state.isVisible && state.phase != ChallengeStateMachine.Phase.CHALLENGE_TIMEOUT) return

    InteractiveChallengeOverlay(
        state = state,
        onCancel = {
            if (state.verificationCompleted) {
                controller.onUnverifiedExit("DIALOG_DISMISSED_AFTER_VERIFICATION")
            } else {
                controller.cancelCurrentChallenge()
            }
        },
        onRetry = { controller.retryChallenge() }
    )
}

/**
 * Full-page verification dialog. Unlike the former player-contained overlay, this is a
 * modal independent of the player bounds and is shared across all app entry points.
 * The WebView itself remains the official challenge page; we do not emulate or bypass it.
 */
@Composable
fun InteractiveChallengeOverlay(
    state: InteractiveChallengeController.UiState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.isVisible && state.phase != ChallengeStateMachine.Phase.CHALLENGE_TIMEOUT) return

    val controller = remember { InteractiveChallengeController.getInstance() }
    val context = LocalContext.current
    var pollingRunnable by remember(state.challengeKey, state.attemptId) { mutableStateOf<Runnable?>(null) }
    val activeWebView = remember(state.challengeKey, state.attemptId) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            val runnable = InteractiveChallengeWebViewHelper.configureWebView(
                webView = this,
                canonicalHost = state.canonicalHost,
                controller = controller,
                attemptId = state.attemptId,
                challengeKey = state.challengeKey,
                previousClearanceFingerprint = state.previousClearanceFingerprint,
                onCompletionDetected = { cookies, userAgent ->
                    controller.onVerificationCompleted(state.attemptId, state.challengeKey, cookies, userAgent)
                }
            )
            pollingRunnable = runnable
            setTag(com.example.R.id.tag_url, state.challengeKey)
            loadUrl(state.challengeUrl)
        }
    }

    DisposableEffect(activeWebView, state.challengeKey, state.attemptId) {
        val cleanupToken = controller.registerWebViewCleanup {
            try {
                activeWebView.stopLoading()
                pollingRunnable?.let(InteractiveChallengeWebViewHelper::removePolling)
                pollingRunnable = null
            } catch (_: Throwable) { }
        }
        onDispose {
            controller.unregisterWebViewCleanup(cleanupToken)
            try {
                activeWebView.stopLoading()
                activeWebView.loadUrl("about:blank")
                pollingRunnable?.let(InteractiveChallengeWebViewHelper::removePolling)
                pollingRunnable = null
                activeWebView.destroy()
            } catch (_: Throwable) { }
        }
    }

    val isTimedOut = state.phase == ChallengeStateMachine.Phase.CHALLENGE_TIMEOUT
    val isRetrying = state.verificationCompleted && state.phase == ChallengeStateMachine.Phase.EXTRACTION_RETRY

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onCancel,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth(0.965f)
                .fillMaxHeight(0.91f)
                .widthIn(max = 720.dp)
                .testTag("cloudflare_verification_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 14.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(13.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(10.dp).size(24.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = androidx.compose.ui.res.stringResource(com.example.R.string.challenge_dialog_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = state.canonicalHost.ifBlank { "Cloudflare" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (isTimedOut) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = when {
                                isTimedOut -> androidx.compose.ui.res.stringResource(com.example.R.string.challenge_dialog_timed_out)
                                isRetrying -> androidx.compose.ui.res.stringResource(com.example.R.string.challenge_dialog_retrying)
                                else -> androidx.compose.ui.res.stringResource(com.example.R.string.challenge_dialog_waiting)
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isTimedOut) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1
                        )
                    }
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag("challenge_dialog_close")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = androidx.compose.ui.res.stringResource(com.example.R.string.challenge_dialog_cancel),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(30.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = androidx.compose.ui.res.stringResource(com.example.R.string.challenge_dialog_scroll_title),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = androidx.compose.ui.res.stringResource(com.example.R.string.challenge_dialog_scroll_message),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                // Do not expose internal English exception/state-machine messages directly in the UI.
                // All visible dialog guidance must follow the app's selected locale.
                val localizedStatus = when {
                    isTimedOut -> androidx.compose.ui.res.stringResource(com.example.R.string.challenge_dialog_timeout_message)
                    isRetrying && !state.errorMessage.isNullOrBlank() -> androidx.compose.ui.res.stringResource(com.example.R.string.challenge_dialog_retry_failed)
                    state.phase == ChallengeStateMachine.Phase.CHALLENGE_FAILED -> androidx.compose.ui.res.stringResource(com.example.R.string.challenge_dialog_verification_failed)
                    else -> ""
                }
                if (localizedStatus.isNotBlank()) {
                    Text(
                        text = localizedStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isTimedOut) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().testTag("challenge_dialog_status")
                    )
                }

                // The web content receives almost all available dialog height, allowing normal
                // page scrolling and interaction rather than clipping the verification widget.
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .testTag("challenge_dialog_webview_container"),
                    color = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    AndroidView(
                        factory = {
                            (activeWebView.parent as? ViewGroup)?.removeView(activeWebView)
                            activeWebView
                        },
                        update = { webView ->
                            // Deliberately do not call loadUrl during recomposition.
                            if (webView.getTag(com.example.R.id.tag_url) != state.challengeKey) {
                                webView.setTag(com.example.R.id.tag_url, state.challengeKey)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f).heightIn(min = 46.dp).testTag("challenge_dialog_cancel_button")
                    ) {
                        Text(androidx.compose.ui.res.stringResource(com.example.R.string.challenge_dialog_cancel))
                    }
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.weight(1.25f).heightIn(min = 46.dp).testTag("challenge_dialog_retry_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            androidx.compose.ui.res.stringResource(
                                if (isTimedOut || state.phase == ChallengeStateMachine.Phase.EXTRACTION_RETRY)
                                    com.example.R.string.challenge_dialog_reload
                                else com.example.R.string.challenge_dialog_retry
                            )
                        )
                    }
                }
            }
        }
    }
}
