package com.example.extension.managed.runtime.web

import java.io.File
import android.webkit.CookieManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PerSiteSessionStoreRecoveryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun compatibilityCookieHeaderReadsOnlyTheExactNativeCookieManagerUrl() {
        val host = "scoped-${System.nanoTime()}.example.test"
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        manager.setCookie("https://$host/", "cf_clearance=clearance-test; Path=/; Secure")
        manager.setCookie("https://$host/", "sessionid=session-test; Path=/; Secure")
        manager.setCookie("https://www.$host/", "cf_clearance=www-clearance; Path=/; Secure")
        manager.flush()
        val store = PerSiteSessionStore(temporaryFolder.newFolder("sessions"))
        store.saveSession(activeSession(host = host))

        val apexHeader = store.getCookieHeaderFor("animeblkom", "https://$host/search")
        val wwwHeader = store.getCookieHeaderFor("animeblkom", "https://www.$host/search")
        assertTrue(apexHeader.orEmpty().contains("cf_clearance=clearance-test"))
        assertTrue(apexHeader.orEmpty().contains("sessionid=session-test"))
        assertTrue(wwwHeader.orEmpty().contains("cf_clearance=www-clearance"))
        assertNull(store.getCookieHeaderFor("animeblkom", "http://$host/search"))
        assertNull(store.getCookieHeaderFor("animeblkom", "https://sub.$host/search"))
        assertNull(store.getCookieHeaderFor("animeblkom", "https://unrelated.example/search"))
    }

    @Test
    fun expiredOrInvalidSessionIsNeverUsedToBuildCookieHeader() {
        val store = PerSiteSessionStore(temporaryFolder.newFolder("sessions"))
        store.saveSession(activeSession(expiresAt = System.currentTimeMillis() - 1L))

        assertNull(store.getCookieHeaderFor("animeblkom", "https://animeblkom.net/search"))
        assertEquals(ProtectedSessionState.EXPIRED, store.getSession("animeblkom", "animeblkom.net")?.state)
    }

    @Test
    fun emptyActiveSessionCannotReplaceAStoredSession() {
        val store = PerSiteSessionStore(temporaryFolder.newFolder("sessions"))
        store.saveSession(activeSession())
        store.saveSession(
            ProtectedSiteSession(
                siteKey = "animeblkom",
                canonicalHost = "animeblkom.net",
                cookies = emptyMap(),
                state = ProtectedSessionState.ACTIVE
            )
        )

        val retained = store.getSession("animeblkom", "animeblkom.net")
        assertNotNull(retained)
        assertTrue(retained!!.sanitizedCookieNames().contains("cf_clearance"))
        assertTrue(retained.sanitizedCookieNames().contains("sessionid"))
        assertTrue(retained.cookies.isEmpty())
    }

    @Test
    fun invalidatingRejectedSessionExpiresOnlyCfClearanceForThatProvider() {
        val host = "recovery-${System.nanoTime()}.example.test"
        val manager = CookieManager.getInstance()
        val url = "https://$host/"
        manager.setAcceptCookie(true)
        manager.setCookie(url, "cf_clearance=stale-test-value; Path=/; Secure")
        manager.setCookie(url, "unrelated=keep-test-value; Path=/; Secure")
        manager.flush()
        assertTrue(manager.getCookie(url).orEmpty().contains("cf_clearance=stale-test-value"))

        val store = PerSiteSessionStore(temporaryFolder.newFolder("sessions"))
        store.saveSession(
            ProtectedSiteSession(
                siteKey = "animeblkom-test",
                canonicalHost = host,
                cookies = mapOf("cf_clearance" to "stale-test-value"),
                state = ProtectedSessionState.ACTIVE
            )
        )
        store.invalidateSession("animeblkom-test", host, "synthetic provider rejection")

        val remaining = manager.getCookie(url).orEmpty()
        assertFalse(remaining.contains("cf_clearance=stale-test-value"))
        assertTrue(remaining.contains("unrelated=keep-test-value"))
    }

    @Test
    fun persistedUserAgentRoundTripsAndIsScopedToProvider() {
        val dir = temporaryFolder.newFolder("sessions")
        val store = PerSiteSessionStore(dir)
        store.saveSession(activeSession(userAgent = "WebView-Test-UA"))

        val reloaded = PerSiteSessionStore(dir)
        assertEquals("WebView-Test-UA", reloaded.getUserAgentFor("animeblkom", "https://animeblkom.net/search"))
        assertNull(reloaded.getUserAgentFor("animeblkom", "https://other.example/search"))
    }

    @Test
    fun transientUserAgentCanBeRememberedWithoutPersistingEmptyAuthentication() {
        val store = PerSiteSessionStore(temporaryFolder.newFolder("sessions"))
        store.rememberUserAgent("animeblkom", "https://animeblkom.net/watch/1", "Browser-UA-Test")

        assertEquals("Browser-UA-Test", store.getUserAgentFor("animeblkom", "https://www.animeblkom.net/search"))
        assertNull(store.getSession("animeblkom", "animeblkom.net"))
    }

    @Test
    fun unsafeCookieNamesAndHeaderInjectionAreDiscarded() {
        val cleaned = ProtectedSiteSession(
            siteKey = "animeblkom",
            canonicalHost = "animeblkom.net",
            cookies = mapOf(
                "cf_clearance" to "safe-value",
                "bad name" to "ignored",
                "injected" to "value\r\nAuthorization: leaked",
                "empty" to " "
            )
        ).sanitizedCookies()

        assertEquals(mapOf("cf_clearance" to "safe-value"), cleaned)
        assertFalse(cleaned.values.any { it.contains('\n') || it.contains('\r') })
    }

    @Test
    fun legacyRawCookieValuesAreRemovedFromDiskAfterMigration() {
        val dir = temporaryFolder.newFolder("legacy-raw-cookie")
        val now = System.currentTimeMillis()
        val rawClearance = "legacy-secret-clearance-${System.nanoTime()}"
        val nonCanonicalLegacyFile = File(dir, "legacy-custom-name.session")
        nonCanonicalLegacyFile.writeText(
            listOf(
                "animeblkom",
                "animeblkom.net",
                "ACTIVE",
                now.toString(),
                now.toString(),
                (now + ProtectedSiteSession.DEFAULT_SESSION_TTL_MS).toString(),
                "cf_clearance=$rawClearance",
                "sessionid=legacy-secret-session"
            ).joinToString("\n") + "\n"
        )

        val store = PerSiteSessionStore(dir)
        assertTrue(store.getSession("animeblkom", "animeblkom.net")?.isValid() == true)
        val filesAfterMigration = dir.listFiles().orEmpty()
        assertFalse(filesAfterMigration.any { it.name == "legacy-custom-name.session" })
        assertTrue(filesAfterMigration.isNotEmpty())
        val persistedContent = filesAfterMigration.joinToString("\n") { it.readText() }
        assertTrue(persistedContent.contains("protected-session-v2"))
        assertTrue(persistedContent.contains("cf_clearance")) // cookie name only
        assertFalse(persistedContent.contains(rawClearance))
        assertFalse(persistedContent.contains("legacy-secret-session"))
    }

    @Test
    fun legacyZeroExpiryGetsFiniteExpiryOnLoad() {
        val dir = temporaryFolder.newFolder("legacy")
        val now = System.currentTimeMillis()
        File(dir, "legacy.session").writeText(
            listOf(
                "animeblkom",
                "animeblkom.net",
                "ACTIVE",
                now.toString(),
                now.toString(),
                "0",
                "cf_clearance=legacy-test"
            ).joinToString("\n") + "\n"
        )

        val store = PerSiteSessionStore(dir)
        val loaded = store.getSession("animeblkom", "animeblkom.net")
        assertNotNull(loaded)
        assertTrue(loaded!!.expiresAt > 0L)
        assertTrue(loaded.expiresAt <= now + ProtectedSiteSession.DEFAULT_SESSION_TTL_MS + 2_000L)
        assertTrue(loaded.isValid())
    }

    private fun activeSession(
        expiresAt: Long = System.currentTimeMillis() + ProtectedSiteSession.DEFAULT_SESSION_TTL_MS,
        userAgent: String = "",
        host: String = "www.animeblkom.net"
    ) = ProtectedSiteSession(
        siteKey = "animeblkom",
        canonicalHost = host,
        cookies = linkedMapOf("cf_clearance" to "clearance-test", "sessionid" to "session-test"),
        expiresAt = expiresAt,
        state = ProtectedSessionState.ACTIVE,
        userAgent = userAgent
    )
}
