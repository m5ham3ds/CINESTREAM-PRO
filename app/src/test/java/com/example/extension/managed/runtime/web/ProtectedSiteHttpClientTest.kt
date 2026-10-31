package com.example.extension.managed.runtime.web

import okhttp3.Headers
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the shared Cloudflare/session HTTP policy used by every bundled scraper. */
class ProtectedSiteHttpClientTest {

    @Test
    fun detectsCommonCloudflarePageSignatures() {
        val titleChallenge = Jsoup.parse(
            "<html><head><title>Just a moment...</title></head><body>Checking your browser</body></html>",
            "https://anime4up.example/"
        )
        val turnstileChallenge = Jsoup.parse(
            "<html><body><form id='challenge-form'><input name='cf-turnstile-response'></form></body></html>",
            "https://witanime.example/search"
        )

        assertTrue(ProtectedSiteHttpClient.isCloudflareDocument(titleChallenge))
        assertTrue(ProtectedSiteHttpClient.isCloudflareDocument(turnstileChallenge))
    }

    @Test
    fun doesNotTreatOrdinaryContentAsChallenge() {
        val normal = Jsoup.parse(
            "<html><head><title>الحلقات الجديدة</title></head><body>أحدث الحلقات</body></html>",
            "https://egydead.example/"
        )
        assertFalse(ProtectedSiteHttpClient.isCloudflareDocument(normal))
    }

    @Test
    fun providerUrlPolicyAllowsOnlyHttpsProviderHostAndSubdomains() {
        assertTrue(ProtectedSiteHttpClient.isProviderHttpsUrl("https://anime4up.example/search?q=test", "https://anime4up.example/"))
        assertTrue(ProtectedSiteHttpClient.isProviderHttpsUrl("https://www.anime4up.example/search", "https://anime4up.example/"))
        assertTrue(ProtectedSiteHttpClient.isProviderHttpsUrl("https://anime4up.example/search", "https://www.anime4up.example/"))
        assertFalse(ProtectedSiteHttpClient.isProviderHttpsUrl("http://anime4up.example/search", "https://anime4up.example/"))
        assertFalse(ProtectedSiteHttpClient.isProviderHttpsUrl("https://anime4up.example.attacker.invalid/search", "https://anime4up.example/"))
        assertFalse(ProtectedSiteHttpClient.isProviderHttpsUrl("https://other.invalid/", "https://anime4up.example/"))
        assertFalse(ProtectedSiteHttpClient.isProviderHttpsUrl("https://user:password@anime4up.example/", "https://anime4up.example/"))
    }

    @Test
    fun plain403And503AreNotCloudflareProofByThemselves() {
        assertFalse(ProtectedSiteHttpClient.isCloudflareException(
            HttpStatusException("HTTP error fetching URL", 403, "https://anime4up.example/")
        ))
        assertFalse(ProtectedSiteHttpClient.isCloudflareException(
            HttpStatusException("HTTP error fetching URL", 503, "https://anime4up.example/")
        ))
        assertFalse(ProtectedSiteHttpClient.isCloudflareException(
            HttpStatusException("HTTP error fetching URL", 404, "https://anime4up.example/")
        ))
        assertFalse(ProtectedSiteHttpClient.isCloudflareException(
            IllegalStateException("Cookie cf_clearance is absent; cf-ray=example-only")
        ))
    }

    @Test
    fun cfMitigatedHeaderIsAConfirmedChallengeSignal() {
        val response = response(
            status = 403,
            headers = Headers.Builder().add("cf-mitigated", "challenge").add("Server", "cloudflare").build(),
            body = "Access denied",
            url = "https://anime4up.example/search"
        )
        assertTrue(ProtectedSiteHttpClient.isCloudflareResponse(response))
    }

    @Test
    fun cloudflareServerWithoutChallengeMarkerIsNotEnough() {
        val response = response(
            status = 503,
            headers = Headers.Builder().add("Server", "cloudflare").build(),
            body = "Service temporarily unavailable",
            url = "https://anime4up.example/search"
        )
        assertFalse(ProtectedSiteHttpClient.isCloudflareResponse(response))
    }

    @Test
    fun challengeDomCanConfirmAChallengeWithoutSpecialHeaders() {
        val response = response(
            status = 403,
            headers = Headers.Builder().build(),
            body = "<html><head><title>Just a moment...</title></head><body><form id='challenge-form'></form></body></html>",
            url = "https://anime4up.example/search"
        )
        assertTrue(ProtectedSiteHttpClient.isCloudflareResponse(response))
    }

    private fun response(status: Int, headers: Headers, body: String, url: String) =
        ProtectedHttpResponse(url, status, body, headers)
}
