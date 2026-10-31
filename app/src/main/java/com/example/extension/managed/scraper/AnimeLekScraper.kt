package com.example.extension.managed.scraper

import com.example.extension.managed.contract.BaseSiteScraper
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.*
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.web.WebExtractionEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/** CineStream-native adapter for the AniYomi Arabic AnimeLek source. */
class AnimeLekScraper : BaseSiteScraper {
    override val scraperKey = "animelek"
    override val implementationVersion = 1
    override val supportedCapabilities = setOf(ScraperCapability.SEARCH, ScraperCapability.DETAILS,
        ScraperCapability.EPISODES, ScraperCapability.SERVER_DISCOVERY, ScraperCapability.VIDEO_EXTRACTION)
    override val supportedContentTypes = setOf(ContentType.ANIME)

    override suspend fun search(extension: ManagedExtension, request: SearchRequest): Result<SearchResult> = withContext(Dispatchers.IO) {
        val query = request.query.trim()
        val base = extension.baseUrl.trimEnd('/')
        val url = if (query.isBlank()) base else "$base/search/?s=${URLEncoder.encode(query, "UTF-8")}&page=${request.page.coerceAtLeast(1)}"
        try {
            val doc = PortedScraperSupport.document(extension, scraperKey, url)
            val cards = doc.select("div.anime-list-content div.anime-card-container, div.slider-episode-container div.episodes-card-container, div.episodes-list-content div.episodes-card-container")
            val items = cards.mapNotNull { card ->
                val a = card.selectFirst("h3 a[href], a[href][title]") ?: card.selectFirst("a[href]") ?: return@mapNotNull null
                val link = PortedScraperSupport.resolve(base, a.attr("href"))
                if (link.isBlank()) return@mapNotNull null
                val title = a.text().trim().ifBlank { a.attr("title").trim() }
                if (title.isBlank()) return@mapNotNull null
                val img = card.selectFirst("img")
                val poster = img?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") } }?.takeIf { it.startsWith("https://") }
                SearchMediaItem(PortedScraperSupport.mediaId(link), title, poster, link, ContentType.ANIME)
            }.distinctBy { it.url }
            Result.success(SearchResult(items, request.page, doc.select("li.page-item a[rel=next], a[rel=next], .pagination a.next").isNotEmpty()))
        } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
            Result.failure(PortedScraperSupport.failure("AnimeLek", "search", url, e))
        }
    }

    override suspend fun getDetails(extension: ManagedExtension, url: String): Result<MediaDetailsResult> = withContext(Dispatchers.IO) {
        try {
            val doc = PortedScraperSupport.document(extension, scraperKey, url)
            val infos = doc.selectFirst("div.anime-container-infos, .anime-container-infos")
            val data = doc.selectFirst("div.anime-container-data, .anime-container-data")
            val title = data?.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank { doc.selectFirst("h1")?.text()?.trim().orEmpty() }
            if (title.isBlank()) return@withContext Result.failure(ExtensionError.MediaNotFound(url))
            val poster = infos?.selectFirst("img")?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") } }?.takeIf { it.startsWith("https://") }
            val description = data?.selectFirst("p.anime-story")?.text()?.trim()
                ?: doc.selectFirst(".anime-story, .anime-description, .description")?.text()?.trim()
            val genres = (data?.select("ul li > a") ?: doc.select(".genre a, a[href*='genre']"))
                .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
            val statusText = infos?.selectFirst("div.full-list-info:contains(حالة الأنمي) a")?.text().orEmpty()
            val status = when {
                statusText.contains("مكتمل", true) -> "completed"
                statusText.contains("يعرض", true) || statusText.contains("مستمر", true) -> "ongoing"
                else -> "unknown"
            }
            Result.success(MediaDetailsResult(
                id = PortedScraperSupport.mediaId(url), title = title, description = description, posterUrl = poster,
                contentType = ContentType.ANIME, url = url, genres = genres,
                metadata = mapOf("source" to scraperKey, "status" to status)
            ))
        } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
            Result.failure(PortedScraperSupport.failure("AnimeLek", "details", url, e))
        }
    }

    override suspend fun getEpisodes(extension: ManagedExtension, seriesUrl: String, season: Int): Result<List<EpisodeItem>> = withContext(Dispatchers.IO) {
        try {
            val doc = PortedScraperSupport.document(extension, scraperKey, seriesUrl)
            val nodes = doc.select("div.ep-card-anime-title-detail h3 a[href], .ep-card-anime-title-detail a[href], a[href*='episode']")
            val episodes = nodes.mapIndexedNotNull { index, a ->
                val link = PortedScraperSupport.resolve(extension.baseUrl, a.attr("href"))
                if (link.isBlank()) return@mapIndexedNotNull null
                val title = a.text().trim().ifBlank { "الحلقة ${index + 1}" }
                val number = PortedScraperSupport.episodeNumber(title, link, index + 1)
                EpisodeItem(PortedScraperSupport.mediaId(link), title, number, season, link)
            }.distinctBy { it.url }.reversed()
            if (episodes.isEmpty()) Result.failure(ExtensionError.MediaNotFound("episodes:$seriesUrl")) else Result.success(episodes)
        } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
            Result.failure(PortedScraperSupport.failure("AnimeLek", "episodes", seriesUrl, e))
        }
    }

    override suspend fun discoverServers(extension: ManagedExtension, session: ExtractionSession, request: ServerDiscoveryRequest, webEngine: WebExtractionEngine?): Result<ServerDiscoveryResult> = withContext(Dispatchers.IO) {
        try {
            val doc = PortedScraperSupport.document(extension, scraperKey, request.targetUrl)
            val nodes = doc.select("ul#episode-servers li.watch a[data-ep-url], ul#episode-servers li.watch a[href], .episode-servers li a[data-ep-url]")
            val servers = nodes.mapNotNull { node ->
                val raw = node.attr("data-ep-url").ifBlank { node.attr("href") }
                val link = PortedScraperSupport.resolve(extension.baseUrl, raw)
                if (link.isBlank() || !PortedScraperSupport.isSafeProviderOrEmbed(link, extension.baseUrl)) return@mapNotNull null
                val name = node.text().trim().ifBlank { "سيرفر ${node.attr("data-server")}" }.ifBlank { "سيرفر" }
                val direct = com.example.extension.managed.web.MediaStreamDetector.isMediaUrl(link)
                ServerItem("animelek:${link.hashCode()}", name, link, direct, sourceUrl = request.targetUrl,
                    serverType = if (direct) ServerType.DIRECT else ServerType.EMBED, requiresWebView = !direct,
                    metadata = mapOf("scraperKey" to scraperKey))
            }.distinctBy { it.link }
            if (servers.isEmpty()) Result.failure(ExtensionError.MediaNotFound("servers:${request.targetUrl}"))
            else Result.success(ServerDiscoveryResult(servers, request.targetUrl))
        } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
            Result.failure(PortedScraperSupport.failure("AnimeLek", "server discovery", request.targetUrl, e))
        }
    }

    override suspend fun extractStream(extension: ManagedExtension, session: ExtractionSession, request: ExtractionRequest, webEngine: WebExtractionEngine?): Result<ExtractionResult> =
        PortedScraperSupport.extract(extension, session, request, webEngine, scraperKey, "AnimeLek")
}
