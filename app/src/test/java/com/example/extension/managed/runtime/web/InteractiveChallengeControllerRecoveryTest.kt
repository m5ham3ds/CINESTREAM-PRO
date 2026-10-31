package com.example.extension.managed.runtime.web

import com.example.extension.managed.model.ContentType
import android.webkit.CookieManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InteractiveChallengeControllerRecoveryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun rejectedCachedClearanceDoesNotSuppressInteractiveChallenge() = runBlocking {
        val sessionDir = temporaryFolder.newFolder("sessions")
        val store = PerSiteSessionStore(sessionDir)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setCookie("https://animeblkom.net/search?query=sample", "cf_clearance=old-rejected-value; Path=/; Secure")
        cookieManager.setCookie("https://animeblkom.net/search?query=sample", "sessionid=keep-this-cookie; Path=/; Secure")
        cookieManager.flush()
        store.saveSession(
            ProtectedSiteSession(
                siteKey = "animeblkom",
                canonicalHost = "animeblkom.net",
                cookies = mapOf("cf_clearance" to "old-rejected-value"),
                state = ProtectedSessionState.ACTIVE
            )
        )
        val controller = InteractiveChallengeController { store }
        val pending = async(Dispatchers.Unconfined) {
            controller.launchInteractiveChallenge(
                siteKey = "animeblkom",
                canonicalHost = "animeblkom.net",
                challengeUrl = "https://animeblkom.net/search?query=sample",
                context = InteractiveChallengeController.ExtractionContext(
                    contentType = ContentType.ANIME,
                    extensionId = "animeblkom",
                    scraperKey = "animeblkom",
                    targetUrl = "https://animeblkom.net/search?query=sample"
                ),
                timeoutMs = 20_000L
            )
        }

        val ui = withTimeout(2_000L) { controller.stateFlow.first { it.isVisible } }
        assertTrue(ui.hadExistingValidSession)
        assertNotNull(ui.previousClearanceFingerprint)
        assertEquals(ChallengeStateMachine.Phase.WAITING_FOR_USER, ui.phase)
        assertEquals(ProtectedSessionState.INVALID, store.getSession("animeblkom", "animeblkom.net")?.state)
        assertFalse(cookieManager.getCookie("https://animeblkom.net/search?query=sample").orEmpty().contains("old-rejected-value"))
        assertTrue(cookieManager.getCookie("https://animeblkom.net/search?query=sample").orEmpty().contains("sessionid=keep-this-cookie"))

        cookieManager.setCookie("https://animeblkom.net/search?query=sample", "cf_clearance=new-clearance-value; Path=/; Secure")
        cookieManager.flush()
        controller.onVerificationCompleted(
            ui.attemptId,
            ui.challengeKey,
            mapOf("cf_clearance" to "new-clearance-value"),
            "Browser-UA-Test"
        )
        val result = pending.await()
        assertTrue(result.isSuccess)
        assertEquals(ChallengeStateMachine.Phase.EXTRACTION_RETRY, controller.stateFlow.value.phase)
        assertTrue(controller.stateFlow.value.isVisible)
        assertTrue(controller.stateFlow.value.verificationCompleted)
        assertEquals("Browser-UA-Test", store.getUserAgentFor("animeblkom", "https://animeblkom.net/search"))
        assertTrue(store.getSession("animeblkom", "animeblkom.net")?.sanitizedCookieNames()?.contains("cf_clearance") == true)
        val persistedText = sessionDir.listFiles().orEmpty().joinToString("\n") { it.readText() }
        assertFalse(persistedText.contains("new-clearance-value"))
        assertFalse(persistedText.contains("old-rejected-value"))
    }


    @Test
    fun verificationCallbackArrivingAfterTimeoutIsIgnored() = runBlocking {
        val store = PerSiteSessionStore(temporaryFolder.newFolder("sessions"))
        val controller = InteractiveChallengeController { store }
        val pending = async(Dispatchers.Unconfined) {
            controller.launchInteractiveChallenge(
                siteKey = "animeblkom",
                canonicalHost = "animeblkom.net",
                challengeUrl = "https://animeblkom.net/",
                context = InteractiveChallengeController.ExtractionContext(
                    contentType = ContentType.ANIME,
                    extensionId = "animeblkom",
                    scraperKey = "animeblkom",
                    targetUrl = "https://animeblkom.net/"
                ),
                timeoutMs = 20_000L
            )
        }
        val ui = withTimeout(2_000L) { controller.stateFlow.first { it.isVisible } }
        controller.onTimeout(20_000L)
        assertTrue(pending.await().isFailure)

        controller.onVerificationCompleted(ui.attemptId, ui.challengeKey, mapOf("cf_clearance" to "late-cookie"), "UA")
        assertEquals(ChallengeStateMachine.Phase.CHALLENGE_TIMEOUT, controller.stateFlow.value.phase)
        assertFalse(controller.stateFlow.value.verificationCompleted)
        assertEquals(null, store.getSession("animeblkom", "animeblkom.net"))
    }

    @Test
    fun retryFailureKeepsVerifiedUiVisibleAndLaterSuccessClosesIt() = runBlocking {
        val store = PerSiteSessionStore(temporaryFolder.newFolder("sessions"))
        val controller = InteractiveChallengeController { store }
        val pending = async(Dispatchers.Unconfined) {
            controller.launchInteractiveChallenge(
                siteKey = "animeblkom",
                canonicalHost = "animeblkom.net",
                challengeUrl = "https://animeblkom.net/",
                context = InteractiveChallengeController.ExtractionContext(
                    contentType = ContentType.ANIME,
                    extensionId = "animeblkom",
                    scraperKey = "animeblkom",
                    targetUrl = "https://animeblkom.net/"
                ),
                timeoutMs = 20_000L
            )
        }
        val ui = withTimeout(2_000L) { controller.stateFlow.first { it.isVisible } }
        controller.onVerificationCompleted(ui.attemptId, ui.challengeKey, mapOf("cf_clearance" to "verified"), "UA")
        assertTrue(pending.await().isSuccess)

        controller.onExtractionRetryCompleted(false, "synthetic retry failure")
        assertEquals(ChallengeStateMachine.Phase.EXTRACTION_RETRY, controller.stateFlow.value.phase)
        assertTrue(controller.stateFlow.value.isVisible)
        assertEquals("synthetic retry failure", controller.stateFlow.value.errorMessage)

        controller.onExtractionRetryCompleted(true)
        assertEquals(ChallengeStateMachine.Phase.WEBVIEW_HIDDEN, controller.stateFlow.value.phase)
        assertFalse(controller.stateFlow.value.isVisible)
    }
}
