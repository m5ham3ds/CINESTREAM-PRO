package com.example.extension.managed.scraper

import com.example.extension.managed.contract.BaseSiteScraper
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.*
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.web.MediaStreamDetector
import com.example.extension.managed.web.WebExtractionEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/** CineStream-native port of the AniYomi Asia2TV Korean/Asian drama source. */
class Asia2TvScraper : BaseSiteScraper {
    override val scraperKey = "asia2tv"
    override val implementationVersion = 1
    override val supportedCapabilities = setOf(ScraperCapability.SEARCH, ScraperCapability.DETAILS,
        ScraperCapability.EPISODES, ScraperCapability.SERVER_DISCOVERY, ScraperCapability.VIDEO_EXTRACTION)
    override val supportedContentTypes = setOf(ContentType.SERIES)

    private fun cardUrl(ext: ManagedExtension, raw: String) = PortedScraperSupport.resolve(ext.baseUrl, raw)

    override suspend fun search(extension: ManagedExtension, request: SearchRequest): Result<SearchResult> = withContext(Dispatchers.IO) {
        val base = extension.baseUrl.trimEnd('/')
        val query = request.query.trim()
        val page = request.page.coerceAtLeast(1)
        val url = if (query.isBlank()) "$base/category/asian-drama/page/$page/" else "$base/page/$page/?s=${URLEncoder.encode(query, "UTF-8")}"
        try {
            val doc = PortedScraperSupport.document(extension, scraperKey, url)
            val cards = doc.select("div.postmovie-photo a[title], .postmovie-photo a[href], .movie-item a[title], article a[title]")
            val items = cards.mapNotNull { a ->
                val link = cardUrl(extension, a.absUrl("href").ifBlank { a.attr("href") })
                val title = a.attr("title").trim().ifBlank { a.text().trim() }
                if (link.isBlank() || title.isBlank()) return@mapNotNull null
                val img = a.selectFirst("img") ?: a.parent()?.selectFirst("img")
                val poster = img?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") } }?.takeIf { it.startsWith("https://") }
                SearchMediaItem(PortedScraperSupport.mediaId(link), title, poster, link, ContentType.SERIES)
            }.distinctBy { it.url }
            Result.success(SearchResult(items, page, doc.select("div.nav-links a.next, a[rel=next], .pagination a.next").isNotEmpty()))
        } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
            Result.failure(PortedScraperSupport.failure("Asia2TV", "search", url, e))
        }
    }

    override suspend fun getDetails(extension: ManagedExtension, url: String): Result<MediaDetailsResult> = withContext(Dispatchers.IO) {
        try {
            val doc = PortedScraperSupport.document(extension, scraperKey, url)
            val title = doc.selectFirst("h1 span.title")?.text()?.trim().orEmpty().ifBlank { doc.selectFirst("h1")?.text()?.trim().orEmpty() }
            if (title.isBlank()) return@withContext Result.failure(ExtensionError.MediaNotFound(url))
            val img = doc.selectFirst("div.single-thumb-bg > img, .single-thumb-bg img, .poster img")
            val poster = img?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") } }?.takeIf { it.startsWith("https://") }
            val description = doc.selectFirst("div.getcontent p, .getcontent, .movie-description")?.text()?.trim()
            val genres = doc.select("div.box-tags a, li:contains(البلد) a, .genres a")
                .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
            Result.success(MediaDetailsResult(
                id = PortedScraperSupport.mediaId(url), title = title, description = description, posterUrl = poster,
                contentType = ContentType.SERIES, url = url, genres = genres,
                metadata = mapOf("source" to scraperKey, "category" to "asian-drama")
            ))
        } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
            Result.failure(PortedScraperSupport.failure("Asia2TV", "details", url, e))
        }
    }

    override suspend fun getEpisodes(extension: ManagedExtension, seriesUrl: String, season: Int): Result<List<EpisodeItem>> = withContext(Dispatchers.IO) {
        try {
            val doc = PortedScraperSupport.document(extension, scraperKey, seriesUrl)
            val episodes = doc.select("div.loop-episode a[href], .loop-episode a[href]").mapIndexedNotNull { index, a ->
                val link = cardUrl(extension, a.absUrl("href").ifBlank { a.attr("href") })
                if (link.isBlank()) return@mapIndexedNotNull null
                val title = a.text().trim().ifBlank { a.attr("title").trim() }.ifBlank {
                    val tail = link.trimEnd('/').substringAfterLast('-')
                    if (tail.isNotBlank()) "الحلقة $tail" else "الحلقة ${index + 1}"
                }
                val ep = PortedScraperSupport.episodeNumber(title, link, index + 1)
                EpisodeItem(PortedScraperSupport.mediaId(link), title, ep, season, link)
            }.distinctBy { it.url }.reversed()
            if (episodes.isEmpty()) Result.failure(ExtensionError.MediaNotFound("episodes:$seriesUrl")) else Result.success(episodes)
        } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
            Result.failure(PortedScraperSupport.failure("Asia2TV", "episodes", seriesUrl, e))
        }
    }

    override suspend fun discoverServers(extension: ManagedExtension, session: ExtractionSession, request: ServerDiscoveryRequest, webEngine: WebExtractionEngine?): Result<ServerDiscoveryResult> = withContext(Dispatchers.IO) {
        try {
            val episodeDoc = PortedScraperSupport.document(extension, scraperKey, request.targetUrl)
            val currentLink = episodeDoc.selectFirst("div.loop-episode a.current[href], .loop-episode a.current[href]")
                ?.let { cardUrl(extension, it.absUrl("href").ifBlank { it.attr("href") }) }
            val page = if (!currentLink.isNullOrBlank() && currentLink != request.targetUrl) PortedScraperSupport.document(extension, scraperKey, currentLink) else episodeDoc
            val servers = page.select("ul.server-list-menu li[data-server], .server-list-menu li[data-server]").mapNotNull { node ->
                val raw = node.attr("data-server").trim()
                val link = cardUrl(extension, raw)
                if (link.isBlank() || !PortedScraperSupport.isSafeProviderOrEmbed(link, extension.baseUrl)) return@mapNotNull null
                val label = node.text().trim().ifBlank { node.attr("title").trim() }.ifBlank { "سيرفر ${node.elementSiblingIndex() + 1}" }
                val direct = MediaStreamDetector.isMediaUrl(link)
                ServerItem("asia2tv:${link.hashCode()}", label, link, direct, sourceUrl = request.targetUrl,
                    serverType = if (direct) ServerType.DIRECT else ServerType.EMBED, requiresWebView = !direct,
                    metadata = mapOf("scraperKey" to scraperKey))
            }.distinctBy { it.link }
            if (servers.isEmpty()) Result.failure(ExtensionError.MediaNotFound("servers:${request.targetUrl}"))
            else Result.success(ServerDiscoveryResult(servers, currentLink ?: request.targetUrl))
        } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
            Result.failure(PortedScraperSupport.failure("Asia2TV", "server discovery", request.targetUrl, e))
        }
    }

    override suspend fun extractStream(extension: ManagedExtension, session: ExtractionSession, request: ExtractionRequest, webEngine: WebExtractionEngine?): Result<ExtractionResult> =
        PortedScraperSupport.extract(extension, session, request, webEngine, scraperKey, "Asia2TV")
}
