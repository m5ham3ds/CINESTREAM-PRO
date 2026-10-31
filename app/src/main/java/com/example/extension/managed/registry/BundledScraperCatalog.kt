package com.example.extension.managed.registry

import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ScraperCapability

/**
 * Metadata for source adapters compiled into the APK.
 *
 * This catalog is deliberately descriptive only: it does not seed, enable, disable, rank, or write
 * any ManagedExtension record. The Admin-managed remote configuration remains authoritative, and
 * adding a scraper implementation must never resurrect a source deleted/disabled by an administrator.
 */
data class BundledScraperDescriptor(
    val scraperKey: String,
    val displayName: String,
    val baseUrlFromSourcePackage: String,
    val contentTypes: Set<ContentType>,
    val capabilities: Set<ScraperCapability>,
    val language: String = "ar",
    val autoActivate: Boolean = false
)

object BundledScraperCatalog {
    private val fullSiteCapabilities = setOf(
        ScraperCapability.SEARCH,
        ScraperCapability.DETAILS,
        ScraperCapability.EPISODES,
        ScraperCapability.SERVER_DISCOVERY,
        ScraperCapability.VIDEO_EXTRACTION
    )

    /**
     * Canonical URLs copied from the submitted source packages / current bundled config.
     * These are configuration seeds for an administrator to review, not a guarantee of live availability.
     */
    val descriptors: List<BundledScraperDescriptor> = listOf(
        BundledScraperDescriptor("qfilm", "QFilm", "https://a.qfilm.tv", setOf(ContentType.MOVIE, ContentType.ANIME), fullSiteCapabilities),
        BundledScraperDescriptor("egydead", "EgyDead", "https://tv10.egydead.live", setOf(ContentType.MOVIE, ContentType.SERIES, ContentType.ANIME), fullSiteCapabilities),
        BundledScraperDescriptor("witanime", "WitAnime", "https://witanime.site", setOf(ContentType.ANIME), fullSiteCapabilities),
        BundledScraperDescriptor("anime4up", "Anime4Up", "https://w1.anime4up.rest", setOf(ContentType.ANIME), fullSiteCapabilities),
        BundledScraperDescriptor("animeblkom", "Anime Blkom", "https://animeblkom.net", setOf(ContentType.ANIME), fullSiteCapabilities),
        BundledScraperDescriptor("animelek", "AnimeLek", "https://animelek.me", setOf(ContentType.ANIME), fullSiteCapabilities),
        BundledScraperDescriptor("arabanime", "ArabAnime", "https://www.arabanime.net", setOf(ContentType.ANIME), fullSiteCapabilities),
        BundledScraperDescriptor("asia2tv", "Asia2TV", "https://ww1.asia2tv.pw", setOf(ContentType.SERIES), fullSiteCapabilities)
    )

    fun find(scraperKey: String): BundledScraperDescriptor? =
        descriptors.firstOrNull { it.scraperKey.equals(scraperKey.trim(), ignoreCase = true) }
}
