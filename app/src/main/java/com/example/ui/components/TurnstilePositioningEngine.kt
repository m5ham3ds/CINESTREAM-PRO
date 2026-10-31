package com.example.ui.components

import android.net.Uri
import android.os.SystemClock
import android.webkit.WebView
import com.example.extension.managed.runtime.web.InteractiveChallengeController
import java.util.WeakHashMap

/**
 * Positions a visible Cloudflare Turnstile widget for the user without interacting with it
 * or attempting to bypass verification. It first asks the page to scroll the actual widget
 * into view; if no widget is detectable yet, it applies a single small initial WebView scroll.
 */
internal object TurnstilePositioningEngine {
    /** Initial fallback, in CSS-independent WebView pixels; tune only after device testing. */
    const val fixedInitialScrollPx: Int = 320

    private const val minimumAttemptIntervalMs = 1_000L

    private val lastAttemptAt = WeakHashMap<WebView, MutableMap<String, Long>>()
    private val fallbackApplied = WeakHashMap<WebView, MutableSet<String>>()

    private val positionScript = """
        (function() {
          try {
            var selectors = [
              'iframe[src*="challenges.cloudflare.com"]',
              'iframe[src*="turnstile"]',
              '.cf-turnstile',
              'input[name="cf-turnstile-response"]',
              '[id*="turnstile"]'
            ];
            var candidates = [];
            selectors.forEach(function(selector) {
              try {
                document.querySelectorAll(selector).forEach(function(node) {
                  if (candidates.indexOf(node) < 0) candidates.push(node);
                });
              } catch (_) {}
            });
            function visible(node) {
              if (!node || !node.getBoundingClientRect) return false;
              var rect = node.getBoundingClientRect();
              var style = window.getComputedStyle(node);
              return rect.width > 0 && rect.height > 0 &&
                     style.display !== 'none' && style.visibility !== 'hidden' &&
                     Number(style.opacity || 1) > 0;
            }
            var target = candidates.find(visible);
            if (!target) return 'NO_TARGET';
            var ancestor = target.parentElement;
            while (ancestor && ancestor !== document.body && ancestor !== document.documentElement) {
              var css = window.getComputedStyle(ancestor);
              var scrollable = /(auto|scroll|overlay)/.test(css.overflowY) &&
                               ancestor.scrollHeight > ancestor.clientHeight + 8;
              if (scrollable) {
                var targetRect = target.getBoundingClientRect();
                var parentRect = ancestor.getBoundingClientRect();
                var before = ancestor.scrollTop;
                ancestor.scrollTop += targetRect.top - parentRect.top - (parentRect.height * 0.32);
                return ancestor.scrollTop !== before ? 'SCROLLED' : 'FOUND';
              }
              ancestor = ancestor.parentElement;
            }
            target.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'auto' });
            return 'SCROLLED';
          } catch (_) {
            return 'ERROR';
          }
        })();
    """.trimIndent()

    fun attemptPositioning(
        webView: WebView,
        challengeKey: String,
        controller: InteractiveChallengeController = InteractiveChallengeController.getInstance()
    ) {
        val state = controller.stateFlow.value
        val key = challengeKey.ifBlank {
            Uri.parse(webView.url.orEmpty()).host.orEmpty().ifBlank { "unknown-challenge" }
        }
        val attemptId = state.attemptId
        if (controller.isAttemptCancelled(attemptId, key)) return

        // The attempt ID avoids suppressing the initial scroll for a later retry on the same host.
        val throttleKey = "$attemptId|$key"
        val now = SystemClock.elapsedRealtime()
        val allowed = synchronized(lastAttemptAt) {
            val perWebView = lastAttemptAt.getOrPut(webView) { mutableMapOf() }
            val previous = perWebView[throttleKey] ?: Long.MIN_VALUE
            if (previous != Long.MIN_VALUE && now - previous < minimumAttemptIntervalMs) {
                false
            } else {
                perWebView[throttleKey] = now
                true
            }
        }
        if (!allowed) return

        webView.evaluateJavascript(positionScript) { rawResult ->
            if (controller.isAttemptCancelled(attemptId, key)) return@evaluateJavascript
            val result = rawResult.orEmpty().trim().trim('"')
            if (result == "SCROLLED" || result == "FOUND") return@evaluateJavascript

            val shouldFallback = synchronized(fallbackApplied) {
                val perWebView = fallbackApplied.getOrPut(webView) { mutableSetOf() }
                perWebView.add(throttleKey)
            }
            if (shouldFallback) {
                webView.post {
                    if (!controller.isAttemptCancelled(attemptId, key)) {
                        webView.scrollBy(0, fixedInitialScrollPx)
                    }
                }
            }
        }
    }
}
