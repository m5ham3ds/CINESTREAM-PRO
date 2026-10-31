package com.example.extension.managed.scraper

import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ScraperCapability
import com.example.extension.managed.registry.BundledScraperCatalog
import com.example.extension.managed.registry.ScraperRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortedScraperRegistryTest {
    @Test
    fun newAniyomiSourcesAreRegisteredWithoutChangingExistingProviderKeys() {
        val registry = ScraperRegistry()
        val expected = setOf("egydead", "qfilm", "witanime", "anime4up", "animeblkom", "animelek", "arabanime", "asia2tv")
        assertEquals(expected, registry.allScrapers().map { it.scraperKey }.toSet())
        assertNotNull(registry.getScraper("animelek"))
        assertNotNull(registry.getScraper("arabanime"))
        assertNotNull(registry.getScraper("asia2tv"))
    }

    @Test
    fun newScrapersDeclareTheRequiredSiteCapabilities() {
        val registry = ScraperRegistry()
        for (key in listOf("animelek", "arabanime", "asia2tv")) {
            val scraper = requireNotNull(registry.getScraper(key))
            assertTrue(scraper.supportedCapabilities.containsAll(setOf(
                ScraperCapability.SEARCH, ScraperCapability.DETAILS, ScraperCapability.EPISODES,
                ScraperCapability.SERVER_DISCOVERY, ScraperCapability.VIDEO_EXTRACTION
            )))
        }
        assertEquals(setOf(ContentType.ANIME), requireNotNull(registry.getScraper("animelek")).supportedContentTypes)
        assertEquals(setOf(ContentType.ANIME), requireNotNull(registry.getScraper("arabanime")).supportedContentTypes)
        assertEquals(setOf(ContentType.SERIES), requireNotNull(registry.getScraper("asia2tv")).supportedContentTypes)
    }

    @Test
    fun sourceCatalogDescribesNewSourcesWithoutAutoActivatingOrWritingRemoteConfiguration() {
        val animeLek = requireNotNull(BundledScraperCatalog.find("animelek"))
        val arabAnime = requireNotNull(BundledScraperCatalog.find("arabanime"))
        val asia2Tv = requireNotNull(BundledScraperCatalog.find("asia2tv"))
        assertEquals("https://animelek.me", animeLek.baseUrlFromSourcePackage)
        assertEquals("https://www.arabanime.net", arabAnime.baseUrlFromSourcePackage)
        assertEquals("https://ww1.asia2tv.pw", asia2Tv.baseUrlFromSourcePackage)
        assertFalse(animeLek.autoActivate)
        assertFalse(arabAnime.autoActivate)
        assertFalse(asia2Tv.autoActivate)
    }

    @Test
    fun sourceProvidedVideoHostAliasesAreAllowlistedExplicitly() {
        assertTrue(com.example.extension.managed.web.TrustedEmbedHostPolicy.isKnownEmbedHost("yodbox.com"))
        assertTrue(com.example.extension.managed.web.TrustedEmbedHostPolicy.isKnownEmbedHost("youdbox.net"))
        assertTrue(com.example.extension.managed.web.TrustedEmbedHostPolicy.isKnownEmbedHost("vidbam.com"))
        assertTrue(com.example.extension.managed.web.TrustedEmbedHostPolicy.isKnownEmbedHost("vadbam.com"))
        assertTrue(com.example.extension.managed.web.TrustedEmbedHostPolicy.isKnownEmbedHost("vidbm.com"))
        assertFalse(com.example.extension.managed.web.TrustedEmbedHostPolicy.isKnownEmbedHost("random-unknown.example"))
    }

    @Test
    fun portedUrlResolverRejectsActiveContentSchemes() {
        assertEquals("https://animelek.me/search/?s=one", PortedScraperSupport.resolve("https://animelek.me", "/search/?s=one"))
        assertEquals("https://animelek.me/episode/1", PortedScraperSupport.resolve("https://animelek.me/", "http://animelek.me/episode/1"))
        assertEquals("", PortedScraperSupport.resolve("https://animelek.me", "javascript:alert(1)"))
        assertEquals("", PortedScraperSupport.resolve("https://animelek.me", "file:///etc/passwd"))
    }
}
