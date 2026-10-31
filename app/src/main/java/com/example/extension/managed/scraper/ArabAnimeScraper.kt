package com.example.extension.managed.scraper

import android.util.Base64
import com.example.extension.managed.contract.BaseSiteScraper
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.*
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.runtime.web.ProtectedSiteHttpClient
import com.example.extension.managed.web.WebExtractionEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** CineStream-native port of the AniYomi ArabAnime API/Base64 source. */
class ArabAnimeScraper : BaseSiteScraper {
    override val scraperKey = "arabanime"
    override val implementationVersion = 1
    override val supportedCapabilities = setOf(ScraperCapability.SEARCH, ScraperCapability.DETAILS,
        ScraperCapability.EPISODES, ScraperCapability.SERVER_DISCOVERY, ScraperCapability.VIDEO_EXTRACTION)
    override val supportedContentTypes = setOf(ContentType.ANIME)

    private fun decode(raw: String): String = try { String(Base64.decode(raw.trim(), Base64.DEFAULT), Charsets.UTF_8).trim() } catch (_: Throwable) { "" }
    private fun obj(raw: String): JSONObject? = try { JSONObject(raw) } catch (_: Throwable) { null }
    private fun stringList(value: Any?): List<String> = when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }
        is String -> value.split(',', '،').map(String::trim).filter(String::isNotBlank)
        else -> emptyList()
    }
    private fun safeUrl(ext: ManagedExtension, raw: String): String = PortedScraperSupport.resolve(ext.baseUrl, raw)

    private fun decodeShows(body: String, ext: ManagedExtension, page: Int): SearchResult {
        val root = obj(body) ?: return SearchResult(emptyList(), page, false)
        val shows = root.optJSONArray("Shows") ?: root.optJSONArray("shows") ?: JSONArray()
        val items = (0 until shows.length()).mapNotNull { i ->
            val encoded = shows.optString(i)
            val item = obj(decode(encoded)) ?: obj(encoded) ?: return@mapNotNull null
            val link = safeUrl(ext, item.optString("info_src", item.optString("info-src")))
            val title = item.optString("anime_name").trim()
            if (link.isBlank() || title.isBlank()) return@mapNotNull null
            SearchMediaItem(PortedScraperSupport.mediaId(link), title,
                safeUrl(ext, item.optString("anime_cover_image_url")).takeIf(String::isNotBlank), link, ContentType.ANIME)
        }
        return SearchResult(items.distinctBy { it.url }, page,
            root.optInt("current_page", page) < root.optInt("last_page", page))
    }

    private fun requestText(extension: ManagedExtension, url: String, postQuery: String? = null): Pair<String, String> {
        val safe = safeUrl(extension, url)
        if (safe.isBlank() || !PortedScraperSupport.isSafeProviderOrEmbed(safe, extension.baseUrl)) throw ExtensionError.InvalidConfiguration("Unsafe ArabAnime URL")
        val connection = PortedScraperSupport.connection(extension, scraperKey, safe).ignoreContentType(true)
        if (postQuery != null) {
            connection.method(org.jsoup.Connection.Method.POST).data("searchq", postQuery)
        }
        val response = ProtectedSiteHttpClient.executeResponse(
            connection = connection,
            siteKey = scraperKey,
            providerBaseUrl = extension.baseUrl,
            allowHttpErrors = true
        )
        ProtectedSiteHttpClient.throwIfChallenge(response, scraperKey, extension.baseUrl, safe)
        val body = response.body
        val contentType = response.header("Content-Type").orEmpty()
        val status = response.statusCode
        if (status >= 400) throw java.io.IOException("ArabAnime HTTP $status")
        return body to contentType
    }

    override suspend fun search(extension: ManagedExtension, request: SearchRequest): Result<SearchResult> = withContext(Dispatchers.IO) {
        val query = request.query.trim()
        val base = extension.baseUrl.trimEnd('/')
        val url = if (query.isNotBlank()) "$base/searchq" else "$base/api?order=&type=&stat=&tags=&page=${request.page.coerceAtLeast(1)}"
        try {
            val (body, contentType) = requestText(extension, url, query.takeIf(String::isNotBlank))
            if (contentType.contains("json", true) || body.trimStart().startsWith("{")) {
                Result.success(decodeShows(body, extension, request.page))
            } else {
                val doc = org.jsoup.Jsoup.parse(body, url)
                if (ProtectedSiteHttpClient.isCloudflareDocument(doc)) return@withContext Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge on ArabAnime search", url))
                val items = doc.select("div.show").mapNotNull { item ->
                    val a = item.selectFirst("a[href]") ?: return@mapNotNull null
                    val link = safeUrl(extension, a.absUrl("href").ifBlank { a.attr("href") })
                    val title = item.selectFirst("h3")?.text()?.trim().orEmpty()
                    if (link.isBlank() || title.isBlank()) return@mapNotNull null
                    val image = item.selectFirst("img")?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") } }
                    SearchMediaItem(PortedScraperSupport.mediaId(link), title, image?.takeIf { it.startsWith("https://") }, link, ContentType.ANIME)
                }.distinctBy { it.url }
                Result.success(SearchResult(items, request.page, false))
            }
        } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
            Result.failure(PortedScraperSupport.failure("ArabAnime", "search", url, e))
        }
    }

    private fun decodeContainer(doc: org.jsoup.nodes.Document, selector: String, url: String): JSONObject {
        val text = doc.selectFirst(selector)?.text()?.trim().orEmpty()
        if (text.isBlank()) throw ExtensionError.MediaNotFound("ArabAnime payload missing: $selector")
        return obj(decode(text)) ?: throw ExtensionError.ExtractionFailed("ArabAnime payload was not valid Base64 JSON")
    }

    override suspend fun getDetails(extension: ManagedExtension, url: String): Result<MediaDetailsResult> = withContext(Dispatchers.IO) {
        try {
            val doc = PortedScraperSupport.document(extension, scraperKey, url)
            val root = decodeContainer(doc, "div#data", url)
            val show = root.optJSONArray("show")?.optJSONObject(0) ?: root.optJSONObject("show") ?: root
            val title = show.optString("anime_name").trim()
            if (title.isBlank()) return@withContext Result.failure(ExtensionError.MediaNotFound(url))
            val poster = safeUrl(extension, show.optString("anime_cover_image_url")).takeIf(String::isNotBlank)
            val status = show.optString("anime_status")
            val genres = stringList(show.opt("anime_genres"))
            Result.success(MediaDetailsResult(
                id = show.optString("anime_id").ifBlank { PortedScraperSupport.mediaId(url) }, title = title,
                description = show.optString("anime_description").takeIf(String::isNotBlank), posterUrl = poster,
                contentType = ContentType.ANIME, url = url, genres = genres,
                metadata = mapOf("source" to scraperKey, "status" to status, "slug" to show.optString("anime_slug"))
            ))
        } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
            Result.failure(PortedScraperSupport.failure("ArabAnime", "details", url, e))
        }
    }

    override suspend fun getEpisodes(extension: ManagedExtension, seriesUrl: String, season: Int): Result<List<EpisodeItem>> = withContext(Dispatchers.IO) {
        try {
            val doc = PortedScraperSupport.document(extension, scraperKey, seriesUrl)
            val root = decodeContainer(doc, "div#data", seriesUrl)
            val eps = root.optJSONArray("EPS") ?: root.optJSONArray("eps") ?: JSONArray()
            val episodes = (0 until eps.length()).mapNotNull { i ->
                val ep = eps.optJSONObject(i) ?: return@mapNotNull null
                val link = safeUrl(extension, ep.optString("info-src", ep.optString("info_src")))
                if (link.isBlank()) return@mapNotNull null
                val number = ep.optInt("episode_number", i + 1)
                val title = ep.optString("episode_name").ifBlank { "الحلقة $number" }
                EpisodeItem(PortedScraperSupport.mediaId(link), title, number, season, link)
            }.reversed()
            if (episodes.isEmpty()) Result.failure(ExtensionError.MediaNotFound("episodes:$seriesUrl")) else Result.success(episodes)
        } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
            Result.failure(PortedScraperSupport.failure("ArabAnime", "episodes", seriesUrl, e))
        }
    }

    override suspend fun discoverServers(extension: ManagedExtension, session: ExtractionSession, request: ServerDiscoveryRequest, webEngine: WebExtractionEngine?): Result<ServerDiscoveryResult> = withContext(Dispatchers.IO) {
        try {
            val episodeDoc = PortedScraperSupport.document(extension, scraperKey, request.targetUrl)
            val watchRoot = decodeContainer(episodeDoc, "div#datawatch", request.targetUrl)
            val epInfo = watchRoot.optJSONArray("ep_info")?.optJSONObject(0) ?: watchRoot
            val servers = epInfo.optJSONArray("stream_servers") ?: JSONArray()
            val serverAddress = if (servers.length() > 0) safeUrl(extension, decode(servers.optString(0))) else ""
            if (serverAddress.isBlank() || !PortedScraperSupport.isSafeProviderOrEmbed(serverAddress, extension.baseUrl)) {
                return@withContext Result.failure(ExtensionError.MediaNotFound("ArabAnime server address missing"))
            }
            val serverDoc = PortedScraperSupport.document(extension, scraperKey, serverAddress)
            val discovered = serverDoc.select("option[data-src]").mapNotNull { option ->
                val raw = decode(option.attr("data-src")).ifBlank { option.attr("data-src") }
                val link = safeUrl(extension, raw)
                if (link.isBlank() || !link.contains("/embed", true) || !PortedScraperSupport.isSafeProviderOrEmbed(link, extension.baseUrl)) return@mapNotNull null
                val name = option.text().trim().ifBlank { "سيرفر ${option.elementSiblingIndex() + 1}" }
                ServerItem("arabanime:${link.hashCode()}", name, link,
                    isDirectStream = com.example.extension.managed.web.MediaStreamDetector.isMediaUrl(link),
                    sourceUrl = request.targetUrl,
                    serverType = if (com.example.extension.managed.web.MediaStreamDetector.isMediaUrl(link)) ServerType.DIRECT else ServerType.EMBED,
                    requiresWebView = !com.example.extension.managed.web.MediaStreamDetector.isMediaUrl(link),
                    metadata = mapOf("serverEndpoint" to serverAddress, "scraperKey" to scraperKey))
            }.distinctBy { it.link }
            if (discovered.isEmpty()) Result.failure(ExtensionError.MediaNotFound("servers:${request.targetUrl}"))
            else Result.success(ServerDiscoveryResult(discovered, request.targetUrl))
        } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
            Result.failure(PortedScraperSupport.failure("ArabAnime", "server discovery", request.targetUrl, e))
        }
    }

    override suspend fun extractStream(extension: ManagedExtension, session: ExtractionSession, request: ExtractionRequest, webEngine: WebExtractionEngine?): Result<ExtractionResult> =
        PortedScraperSupport.extract(extension, session, request, webEngine, scraperKey, "ArabAnime")
}
