package com.example.extension.managed.scraper

import android.util.Log
import com.example.extension.managed.contract.BaseSiteScraper
import com.example.extension.managed.runtime.web.ProtectedSiteHttpClient
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.*
import com.example.extension.managed.runtime.CredentialProjector
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.runtime.web.PerSiteSessionStore
import com.example.extension.managed.runtime.web.CloudflareForensicLogger
import com.example.extension.managed.web.ControlledWebViewEngine
import com.example.extension.managed.web.MediaStreamDetector
import com.example.extension.managed.web.WebExtractionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.net.URI
import java.io.IOException

/**
 * Bundled Site Scraper implementation for AnimeBlkom (https://animeblkom.net).
 * Follows the forensic specifications of PHASE NEW-BLK-01:
 * - Search endpoint: /search?query=<encodedQuery>
 * - Details: /anime/{slug}
 * - Episode list: .episodes-links -> li.episode-link a[href] -> /watch/{slug}/{episode}
 * - Watch page & Server Discovery: dynamic server extraction from [data-src], iframes, and container links
 * - Decoupled: Zero reflection, zero dynamic loading, zero Firebase/Firestore, zero UI/Player/Downloader dependencies.
 */
class AnimeBlkomScraper : BaseSiteScraper {

    override val scraperKey: String = "animeblkom"
    override val implementationVersion: Int = 1

    override val supportedCapabilities: Set<ScraperCapability> = setOf(
        ScraperCapability.SEARCH,
        ScraperCapability.DETAILS,
        ScraperCapability.EPISODES,
        ScraperCapability.SERVER_DISCOVERY,
        ScraperCapability.VIDEO_EXTRACTION
    )

    override val supportedContentTypes: Set<ContentType> = setOf(ContentType.ANIME)

    private val userAgent = MediaStreamDetector.STANDARD_USER_AGENT

    private data class FetchedPage(val document: org.jsoup.nodes.Document, val finalUrl: String)

    private class CloudflareEncounterException(
        val challengeUrl: String,
        statusCode: Int,
        detail: String
    ) : IOException("Cloudflare challenge on HTTP $statusCode: $detail")

    private fun safeUrlSummary(rawUrl: String): String {
        return try {
            val uri = URI(rawUrl)
            "${uri.scheme}://${uri.host.orEmpty()}/[path-redacted]"
        } catch (_: Exception) { "[invalid-url-redacted]" }
    }

    private fun isSameSiteHttpsUrl(candidateUrl: String, baseUrl: String): Boolean {
        return try {
            val candidate = URI(candidateUrl)
            val base = URI(if (baseUrl.contains("://")) baseUrl else "https://$baseUrl")
            candidate.scheme.equals("https", ignoreCase = true) &&
                !candidate.host.isNullOrBlank() && candidate.userInfo == null &&
                PerSiteSessionStore.canonicalizeHost(candidate.host) == PerSiteSessionStore.canonicalizeHost(base.host.orEmpty())
        } catch (_: Exception) { false }
    }

    /**
     * Performs ordinary same-provider HTTPS requests using the session obtained by the user in
     * the interactive WebView. Redirects are manually bounded and revalidated so a provider page
     * cannot move a persisted cookie header to another host or downgrade to HTTP.
     */
    private fun fetchDocumentWithSession(
        extension: ManagedExtension,
        targetUrl: String,
        referer: String
    ): FetchedPage {
        val baseUrl = extension.baseUrl.trimEnd('/')
        var currentUrl = targetUrl.trim()
        var currentReferer = referer
        repeat(6) { redirectIndex ->
            if (!isSameSiteHttpsUrl(currentUrl, baseUrl)) {
                throw IOException("Blocked non-HTTPS or cross-site AnimeBlkom navigation")
            }
            // Shared session-aware transport is used by all bundled adapters, not only this site.
            val connection = ProtectedSiteHttpClient.connect(currentUrl, scraperKey, baseUrl, userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .referrer(currentReferer.takeIf { isSameSiteHttpsUrl(it, baseUrl) } ?: "$baseUrl/")
                .timeout(10_000)
                .ignoreHttpErrors(true)
                .followRedirects(false)
            val response = ProtectedSiteHttpClient.executeResponse(
                connection = connection,
                siteKey = scraperKey,
                providerBaseUrl = baseUrl,
                allowHttpErrors = true
            )
            val actualUrl = response.url
            val status = response.statusCode

            if (status in 300..399) {
                val location = response.header("Location")?.takeIf { it.isNotBlank() }
                    ?: throw IOException("Provider redirect omitted Location header")
                val nextUrl = try { URI(actualUrl).resolve(location).toString() } catch (_: Exception) {
                    throw IOException("Provider returned an invalid redirect URL")
                }
                if (!isSameSiteHttpsUrl(nextUrl, baseUrl)) {
                    throw IOException("Blocked non-HTTPS redirect or redirect outside configured provider host")
                }
                currentReferer = actualUrl
                currentUrl = nextUrl
                if (redirectIndex == 5) throw IOException("Too many provider redirects")
                return@repeat
            }

            val document = response.parse()
            // The shared transport throws CloudflareChallenge only for confirmed challenge signals;
            // a generic 403/503 remains an ordinary HTTP failure rather than opening the dialog.
            if (isCloudflareDocument(document)) {
                throw ExtensionError.CloudflareChallenge(
                    detail = "Confirmed Cloudflare challenge on AnimeBlkom",
                    challengeUrl = actualUrl
                )
            }
            if (status >= 400) throw IOException("AnimeBlkom HTTP $status")
            return FetchedPage(document, actualUrl)
        }
        throw IOException("Unable to fetch provider page after bounded redirects")
    }

    private fun cloudflareFailure(extension: ManagedExtension, stage: String, error: Throwable, fallbackUrl: String): ExtensionError.CloudflareChallenge {
        val exactUrl = (error as? CloudflareEncounterException)?.challengeUrl
            ?.takeIf { isSameSiteHttpsUrl(it, extension.baseUrl) }
            ?: fallbackUrl.takeIf { isSameSiteHttpsUrl(it, extension.baseUrl) }
        return ExtensionError.CloudflareChallenge(
            detail = "Cloudflare challenge encountered on AnimeBlkom $stage",
            challengeUrl = exactUrl
        )
    }

    private fun logDiag(msg: String) {
        val safe = CloudflareForensicLogger.safeDiagnosticText(msg)
        try {
            Log.i("ANIMEBLKOM_DIAG", safe)
        } catch (_: Throwable) {
            println("DIAG: [ANIMEBLKOM] $safe")
        }
    }

    private fun isCloudflareDocument(doc: org.jsoup.nodes.Document): Boolean =
        ProtectedSiteHttpClient.isCloudflareDocument(doc)

    private fun isCloudflareException(e: Throwable): Boolean =
        e is CloudflareEncounterException || ProtectedSiteHttpClient.isCloudflareException(e)

    internal fun resolveUrl(baseUrl: String, pathOrUrl: String): String {
        val clean = pathOrUrl.trim()
        if (clean.isBlank()) return ""
        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            return if (clean.startsWith("http://")) clean.replaceFirst("http://", "https://") else clean
        }
        if (clean.startsWith("//")) {
            return "https:$clean"
        }
        val base = baseUrl.trimEnd('/')
        return if (clean.startsWith("/")) "$base$clean" else "$base/$clean"
    }

    internal fun safeBase64Decode(str: String): String? {
        val clean = str.trim()
        if (clean.isBlank()) return null
        return try {
            val bytes = java.util.Base64.getDecoder().decode(clean)
            String(bytes, Charsets.UTF_8).trim()
        } catch (_: Throwable) {
            try {
                val bytes = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
                String(bytes, Charsets.UTF_8).trim()
            } catch (_: Throwable) {
                null
            }
        }
    }

    internal fun resolveWatchUrl(rawUrl: String, baseUrl: String): String? {
        val clean = rawUrl.trim()
        if (clean.isBlank()) return null

        // Security check: reject insecure or dangerous schemes
        val lower = clean.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("file:") || lower.startsWith("content:") || lower.startsWith("data:")) {
            return null
        }

        // 1. Direct or protocol-relative URL
        if (clean.startsWith("https://") || clean.startsWith("http://") || clean.startsWith("//")) {
            return resolveUrl(baseUrl, clean)
        }

        // 2. Relative path starting with /
        if (clean.startsWith("/")) {
            return resolveUrl(baseUrl, clean)
        }

        // 3. Potential Base64 encoded payload
        val decoded = safeBase64Decode(clean)
        if (decoded != null && (decoded.startsWith("http://") || decoded.startsWith("https://") || decoded.startsWith("//") || decoded.startsWith("/"))) {
            return resolveWatchUrl(decoded, baseUrl)
        }

        // 4. Domain-like path (e.g. embed.server.com/...)
        if (clean.contains(".") && !clean.contains(" ") && clean.contains("/")) {
            return "https://$clean"
        }

        return null
    }

    // --- Search ---

    override suspend fun search(
        extension: ManagedExtension,
        request: SearchRequest
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        if (request.query.isBlank()) {
            return@withContext Result.success(SearchResult(items = emptyList(), page = request.page))
        }

        val baseUrl = extension.baseUrl.trimEnd('/')
        val encodedQuery = URLEncoder.encode(request.query.trim(), "UTF-8")
        val fallbackSearchUrl = if (request.page > 1) {
            "$baseUrl/search?query=$encodedQuery&page=${request.page}"
        } else {
            "$baseUrl/search?query=$encodedQuery"
        }

        var searchUrl = fallbackSearchUrl
        try {
            searchUrl = ConfiguredSearchUrl.build(extension, request.query, request.page, fallbackSearchUrl)
            val fetched = fetchDocumentWithSession(extension, searchUrl, "$baseUrl/")
            val doc = fetched.document
            val items = parseSearchDocument(doc, baseUrl)
            val hasNextPage = doc.select(".pagination .next, a[rel=next], .pagination li.active + li a").isNotEmpty()

            Result.success(
                SearchResult(
                    items = items,
                    page = request.page,
                    hasNextPage = hasNextPage
                )
            )
        } catch (e: Exception) {
            if (e is CloudflareEncounterException || isCloudflareException(e)) {
                logDiag("CLOUDFLARE_DETECTED on search at ${safeUrlSummary((e as? CloudflareEncounterException)?.challengeUrl ?: searchUrl)}")
                Result.failure(cloudflareFailure(extension, "search", e, searchUrl))
            } else {
                Result.failure(ExtensionError.ExtractionFailed("AnimeBlkom search failed: ${CloudflareForensicLogger.safeDiagnosticText(e.message?.take(160) ?: e.javaClass.simpleName)}", e))
            }
        }
    }

    internal fun parseSearchDocument(doc: org.jsoup.nodes.Document, baseUrl: String): List<SearchMediaItem> {
        val containerElements = doc.select(
            "div.contents div.poster > a, " + // Selector used by the supplied AniYomi extension.
            ".anime-card, " +
            ".search-results .item, " +
            ".content .item, " +
            "div.anime-item, " +
            "div.poster-item, " +
            "div.item"
        )

        val cardElements = if (containerElements.isNotEmpty()) {
            containerElements
        } else {
            doc.select("a[href*='/anime/'], a[href*='/movie/']")
        }

        val items = mutableListOf<SearchMediaItem>()
        for (el in cardElements) {
            val linkEl = if (el.tagName() == "a") el else el.selectFirst("a[href*='/movie/'], a[href*='/anime/'], a") ?: continue
            val href = linkEl.attr("href").trim()
            val isKnownContentRoute = listOf("/anime/", "/movie/", "/watch/", "/series/", "/animes/")
                .any { href.contains(it, ignoreCase = true) }
            if (href.isBlank() || !isKnownContentRoute) continue

            val fullUrl = resolveUrl(baseUrl, href)
            if (!fullUrl.startsWith("https://") && !fullUrl.startsWith("http://")) continue

            val rawTitle = el.selectFirst(".anime-title, .title, .name, .anime-name, h3, h4, h2, h1")?.text()?.trim()
                .orEmpty().ifBlank { linkEl.attr("title").trim() }
                .ifBlank { linkEl.selectFirst("img")?.attr("alt")?.removeSuffix(" poster")?.trim().orEmpty() }
                .ifBlank { linkEl.text().trim() }

            val title = rawTitle.substringBefore(" – ").trim()
            if (title.isBlank()) continue

            val poster = el.selectFirst(".poster img, img")?.let { img ->
                val candidates = listOf(
                    img.attr("data-src"),
                    img.attr("data-original"),
                    img.attr("data-lazy-src"),
                    img.attr("src")
                )
                candidates.firstOrNull { src ->
                    val s = src.trim()
                    s.isNotBlank() && !s.startsWith("data:image", ignoreCase = true) && !s.contains("placeholder", ignoreCase = true)
                }?.let { resolveUrl(baseUrl, it) }
            }

            val year = el.selectFirst(".year, .release-date, span.year")?.text()?.trim()
                ?.let { Regex("""\b(19\d\d|20\d\d)\b""").find(it)?.value }
                ?: Regex("""\b(19\d\d|20\d\d)\b""").find(title)?.value

            val rating = el.selectFirst(".rating, .score, .rate")?.text()?.trim()
                ?.let { Regex("""(\d+(?:\.\d+)?)""").find(it)?.groupValues?.get(1) }

            val typeBadge = el.selectFirst(".type, .badge, .category, .anime-type")?.text()?.trim() ?: ""
            val isMovie = fullUrl.contains("/movie/") ||
                    title.contains("فيلم", ignoreCase = true) ||
                    title.contains("Movie", ignoreCase = true) ||
                    typeBadge.contains("فيلم", ignoreCase = true) ||
                    typeBadge.contains("Movie", ignoreCase = true)

            val contentType = ContentType.ANIME

            val slug = fullUrl.trimEnd('/').substringAfterLast('/')
            val id = slug.ifBlank { "blkom-${items.size + 1}" }

            val extraMetadata = mutableMapOf<String, String>()
            rating?.let { extraMetadata["rating"] = it }
            if (typeBadge.isNotBlank()) extraMetadata["type"] = typeBadge

            items.add(
                SearchMediaItem(
                    id = id,
                    title = title,
                    posterUrl = poster,
                    url = fullUrl,
                    contentType = contentType,
                    year = year,
                    extraMetadata = extraMetadata
                )
            )
        }

        return items.distinctBy { it.url }
    }

    // --- Details ---

    override suspend fun getDetails(
        extension: ManagedExtension,
        url: String
    ): Result<MediaDetailsResult> = withContext(Dispatchers.IO) {
        val baseUrl = extension.baseUrl.trimEnd('/')
        val targetUrl = resolveUrl(baseUrl, url)

        try {
            val fetched = fetchDocumentWithSession(extension, targetUrl, "$baseUrl/")
            val details = parseDetailsDocument(fetched.document, fetched.finalUrl, baseUrl)
            Result.success(details)
        } catch (e: Exception) {
            if (e is CloudflareEncounterException || isCloudflareException(e)) {
                logDiag("CLOUDFLARE_DETECTED on details at ${safeUrlSummary((e as? CloudflareEncounterException)?.challengeUrl ?: targetUrl)}")
                Result.failure(cloudflareFailure(extension, "details", e, targetUrl))
            } else {
                Result.failure(ExtensionError.ExtractionFailed("AnimeBlkom getDetails failed: ${CloudflareForensicLogger.safeDiagnosticText(e.message?.take(160) ?: e.javaClass.simpleName)}", e))
            }
        }
    }

    internal fun parseDetailsDocument(
        doc: org.jsoup.nodes.Document,
        targetUrl: String,
        baseUrl: String
    ): MediaDetailsResult {
        // Title
        val title = doc.selectFirst("div.name span h1, h1.anime-title, h1.entry-title, .anime-name, h1")?.text()?.trim()
            ?: doc.title().substringBefore("-").trim()

        // Poster
        val poster = doc.selectFirst("div.poster img, .poster img, .anime-poster img, img.img-responsive, img.thumbnail, .content img, .single-poster img")?.let { img ->
            val candidates = listOf(
                img.attr("data-src"),
                img.attr("data-original"),
                img.attr("data-lazy-src"),
                img.attr("src")
            )
            candidates.firstOrNull { src ->
                val s = src.trim()
                s.isNotBlank() && !s.startsWith("data:image", ignoreCase = true) && !s.contains("placeholder", ignoreCase = true)
            }?.let { resolveUrl(baseUrl, it) }
        } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }?.let { resolveUrl(baseUrl, it) }

        // Description
        val description = doc.selectFirst(".story, .anime-story, .description, .anime-description, .overview, p.story")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content")?.trim()

        // Rating
        val ratingText = doc.selectFirst(".rating, .score, .rate, span[itemprop=ratingValue]")?.text()?.trim()
        val rating = ratingText?.let { Regex("""(\d+(?:\.\d+)?)""").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }

        // Year
        val year = doc.selectFirst(".year, .release-date, span:containsOwn(سنة), span:containsOwn(تاريخ)")?.let { el ->
            Regex("""\b(19\d\d|20\d\d)\b""").find(el.text())?.value ?: el.text().trim()
        } ?: Regex("""\b(19\d\d|20\d\d)\b""").find(doc.text())?.value

        // Status
        val status = doc.selectFirst("div.info-table div:contains(حالة الأنمي) span.info, .status, span:containsOwn(حالة), span:containsOwn(الحالة)")?.text()?.trim()

        // Genres
        val genres = doc.select("p.genres a, .genres a, .genre a, ul.genres li a, .categories a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        // Studio
        val studio = doc.selectFirst("div:contains(الاستديو) span > a, .studio, span:containsOwn(استديو), span:containsOwn(الاستوديو)")?.text()?.trim()

        // Country
        val country = doc.selectFirst(".country, span:containsOwn(البلد), span:containsOwn(دولة)")?.text()?.trim()

        // Episode count
        val episodeCount = doc.selectFirst(".episodes-count, span:containsOwn(عدد الحلقات)")?.text()?.trim()

        val slug = targetUrl.trimEnd('/').substringAfterLast('/')
        val id = slug.ifBlank { "blkom-details" }

        val isMovie = targetUrl.contains("/movie/") || title.contains("فيلم", ignoreCase = true) || title.contains("Movie", ignoreCase = true)
        val contentType = ContentType.ANIME

        // Episodes
        val parsedEpisodes = parseEpisodesDocument(doc, baseUrl, seasonNumber = 1)
        val episodes = if (isMovie && parsedEpisodes.isEmpty()) {
            listOf(
                EpisodeItem(
                    id = id,
                    title = title,
                    episodeNumber = 1,
                    seasonNumber = 1,
                    url = targetUrl
                )
            )
        } else {
            parsedEpisodes
        }

        val metadata = mutableMapOf<String, String>()
        status?.let { metadata["status"] = it }
        studio?.let { metadata["studio"] = it }
        country?.let { metadata["country"] = it }
        episodeCount?.let { metadata["episodeCount"] = it }

        return MediaDetailsResult(
            id = id,
            title = title.ifBlank { "بدون عنوان" },
            description = description,
            posterUrl = poster,
            contentType = contentType,
            episodes = episodes,
            seasons = listOf(1),
            url = targetUrl,
            year = year,
            genres = genres,
            rating = rating,
            metadata = metadata
        )
    }

    // --- Episodes ---

    override suspend fun getEpisodes(
        extension: ManagedExtension,
        seriesUrl: String,
        season: Int
    ): Result<List<EpisodeItem>> = withContext(Dispatchers.IO) {
        val baseUrl = extension.baseUrl.trimEnd('/')
        val targetUrl = resolveUrl(baseUrl, seriesUrl)

        try {
            val fetched = fetchDocumentWithSession(extension, targetUrl, "$baseUrl/")
            val parsedEpisodes = parseEpisodesDocument(fetched.document, baseUrl, seasonNumber = season)
            val episodes = if (parsedEpisodes.isNotEmpty()) parsedEpisodes else {
                val title = fetched.document.selectFirst("div.name.col-xs-12 span h1, div.name span h1, h1")?.text()?.trim().orEmpty()
                if (title.isBlank()) emptyList() else listOf(EpisodeItem(
                    id = "${targetUrl.trimEnd('/').substringAfterLast('/')}-episode-1",
                    title = title,
                    episodeNumber = 1,
                    seasonNumber = season,
                    url = targetUrl
                ))
            }
            Result.success(episodes)
        } catch (e: Exception) {
            if (e is CloudflareEncounterException || isCloudflareException(e)) {
                logDiag("CLOUDFLARE_DETECTED on episodes at ${safeUrlSummary((e as? CloudflareEncounterException)?.challengeUrl ?: targetUrl)}")
                Result.failure(cloudflareFailure(extension, "episodes", e, targetUrl))
            } else {
                Result.failure(ExtensionError.ExtractionFailed("AnimeBlkom getEpisodes failed: ${CloudflareForensicLogger.safeDiagnosticText(e.message?.take(160) ?: e.javaClass.simpleName)}", e))
            }
        }
    }

    internal fun parseEpisodesDocument(
        doc: org.jsoup.nodes.Document,
        baseUrl: String,
        seasonNumber: Int
    ): List<EpisodeItem> {
        val links = doc.select(
            ".episodes-links li.episode-link a[href], " +
            "ul.episodes-links li a[href], " +
            "li.episode-link a[href], " +
            ".episodes-links a[href], " +
            ".episodes-list a[href], " +
            "a[href*='/watch/'], " +
            "a[href*='/episode/']"
        )

        val episodes = mutableListOf<EpisodeItem>()
        for ((index, a) in links.withIndex()) {
            val href = a.attr("href").trim()
            if (href.isBlank() || href == "#" || (!href.contains("/watch/") && !href.contains("/episode/"))) continue

            val fullUrl = resolveUrl(baseUrl, href)
            if (!fullUrl.startsWith("https://") && !fullUrl.startsWith("http://")) continue

            val rawText = a.text().trim().ifBlank { a.attr("title").trim() }
            val epNumber = extractEpisodeNumber(rawText, fullUrl, index + 1)
            val title = rawText.ifBlank { "الحلقة $epNumber" }
            val id = fullUrl.trimEnd('/').substringAfterLast('/').ifBlank { "ep-$epNumber" }

            episodes.add(
                EpisodeItem(
                    id = id,
                    title = title,
                    episodeNumber = epNumber,
                    seasonNumber = seasonNumber,
                    url = fullUrl
                )
            )
        }

        return episodes.distinctBy { it.url }
    }

    internal fun extractEpisodeNumber(rawText: String, url: String, fallbackPosition: Int): Int {
        // 1. DOM text matching
        val textMatch = Regex("""(?:الحلقة|حلقة|Episode|Ep|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(rawText)
        if (textMatch != null) {
            val num = textMatch.groupValues[1].toIntOrNull()
            if (num != null) return num
        }

        val pureNumberMatch = Regex("""^(\d+)$""").find(rawText.trim())
        if (pureNumberMatch != null) {
            val num = pureNumberMatch.groupValues[1].toIntOrNull()
            if (num != null) return num
        }

        // 2. URL slug matching (/watch/{slug}/{episode})
        val urlWatchMatch = Regex("""/watch/[^/]+/(\d+)""").find(url)
        if (urlWatchMatch != null) {
            val num = urlWatchMatch.groupValues[1].toIntOrNull()
            if (num != null) return num
        }

        val urlTrailingMatch = Regex("""(?:episode|الحلقة|ep)[-_/]?(\d+)""", RegexOption.IGNORE_CASE).find(url)
            ?: Regex("""-(\d+)/?$""").find(url)
        if (urlTrailingMatch != null) {
            val num = urlTrailingMatch.groupValues[1].toIntOrNull()
            if (num != null) return num
        }

        // 3. Fallback position
        return fallbackPosition
    }

    // --- Server Discovery ---

    override suspend fun discoverServers(
        extension: ManagedExtension,
        session: ExtractionSession,
        request: ServerDiscoveryRequest,
        webEngine: WebExtractionEngine?
    ): Result<ServerDiscoveryResult> = withContext(Dispatchers.IO) {
        val baseUrl = extension.baseUrl.trimEnd('/')
        val targetUrl = request.targetUrl.trim()

        try {
            val fetched = fetchDocumentWithSession(extension, targetUrl, "$baseUrl/")
            val discovered = parseServersDocument(fetched.document, fetched.finalUrl, baseUrl)
            if (discovered.isEmpty()) {
                logDiag("NO_SERVERS_FOUND at ${safeUrlSummary(targetUrl)}")
                return@withContext Result.failure(ExtensionError.MediaNotFound(targetUrl))
            }

            logDiag("SERVERS_DISCOVERED: Found ${discovered.size} servers at ${safeUrlSummary(targetUrl)}")
            Result.success(ServerDiscoveryResult(servers = discovered, sourcePageUrl = targetUrl))
        } catch (e: Exception) {
            if (e is CloudflareEncounterException || isCloudflareException(e)) {
                logDiag("CLOUDFLARE_DETECTED on servers at ${safeUrlSummary((e as? CloudflareEncounterException)?.challengeUrl ?: targetUrl)}")
                Result.failure(cloudflareFailure(extension, "servers", e, targetUrl))
            } else {
                Result.failure(ExtensionError.ExtractionFailed("AnimeBlkom discoverServers failed: ${CloudflareForensicLogger.safeDiagnosticText(e.message?.take(160) ?: e.javaClass.simpleName)}", e))
            }
        }
    }

    internal fun parseServersDocument(
        doc: org.jsoup.nodes.Document,
        episodeUrl: String,
        baseUrl: String
    ): List<ServerItem> {
        val discovered = mutableListOf<ServerItem>()

        // 1. Scan for elements with data-src, data-embed, data-watch, or data-url attributes
        val dataSrcElements = doc.select(
            "ul.servers-list li[data-src], .servers-list li[data-src], li[data-src], a[data-src], button[data-src], .server-item[data-src], [data-src], " +
            "ul.servers-list li[data-embed], .servers-list li[data-embed], li[data-embed], a[data-embed], " +
            "ul.servers-list li[data-watch], .servers-list li[data-watch], li[data-watch], a[data-watch], " +
            "ul.servers-list li[data-url], .servers-list li[data-url], li[data-url], a[data-url]"
        )

        for (el in dataSrcElements) {
            val rawSrc = el.attr("data-src")
                .ifBlank { el.attr("data-embed") }
                .ifBlank { el.attr("data-watch") }
                .ifBlank { el.attr("data-url") }
                .trim()
            if (rawSrc.isBlank() || rawSrc == "#") continue

            val resolvedUrl = resolveWatchUrl(rawSrc, baseUrl) ?: continue
            val secureUrl = if (resolvedUrl.startsWith("http://")) resolvedUrl.replaceFirst("http://", "https://") else resolvedUrl

            val serverName = el.selectFirst(".server-name, span, p, a, b")?.text()?.trim()
                ?.ifBlank { null }
                ?: el.attr("data-server").ifBlank { null }
                ?: el.attr("data-name").ifBlank { null }
                ?: el.text().trim().ifBlank { null }
                ?: "سيرفر ${discovered.size + 1}"

            val qualityAttr = el.attr("data-quality").trim()
            val textToScan = "$serverName $qualityAttr ${el.text()}"
            val qualityMatch = Regex("""\b(4K|2160p|FHD|1080p|HD|720p|SD|480p|360p)\b""", RegexOption.IGNORE_CASE).find(textToScan)
            val quality = qualityAttr.ifBlank { qualityMatch?.value }

            val isDirect = secureUrl.contains(".mp4") || secureUrl.contains(".m3u8") || secureUrl.contains(".mkv")
            val serverType = if (isDirect) ServerType.DIRECT else ServerType.EMBED

            val metadata = mutableMapOf(
                "embedUrl" to secureUrl,
                "serverName" to serverName
            )
            quality?.let { metadata["quality"] = it }

            val deterministicId = "animeblkom:${discovered.size + 1}:${serverName.lowercase().replace(Regex("[^a-z0-9]"), "")}"

            discovered.add(
                ServerItem(
                    id = deterministicId,
                    name = if (!quality.isNullOrBlank() && !serverName.contains(quality, ignoreCase = true)) "$serverName ($quality)" else serverName,
                    link = secureUrl,
                    isDirectStream = isDirect,
                    sourceUrl = episodeUrl,
                    serverType = serverType,
                    requiresWebView = !isDirect,
                    metadata = metadata,
                    extraData = metadata
                )
            )
        }

        // 2. Scan for iframe elements (.embed-responsive, .video-container, or general iframe)
        val iframes = doc.select(".embed-responsive iframe, .video-container iframe, .player iframe, iframe[src]")
        for ((index, iframe) in iframes.withIndex()) {
            val src = iframe.attr("src").trim()
            if (src.isBlank() || src.contains("cloudflare")) continue

            val resolvedIframe = resolveWatchUrl(src, baseUrl) ?: continue
            val secureUrl = if (resolvedIframe.startsWith("http://")) resolvedIframe.replaceFirst("http://", "https://") else resolvedIframe

            val isDirect = secureUrl.contains(".mp4") || secureUrl.contains(".m3u8")
            val defaultName = if (discovered.isEmpty() && index == 0) "السيرفر الرئيسي" else "سيرفر مضمن ${index + 1}"

            val metadata = mapOf(
                "embedUrl" to secureUrl,
                "serverName" to defaultName
            )

            discovered.add(
                ServerItem(
                    id = "animeblkom:iframe:${index + 1}",
                    name = defaultName,
                    link = secureUrl,
                    isDirectStream = isDirect,
                    sourceUrl = episodeUrl,
                    serverType = if (isDirect) ServerType.DIRECT else ServerType.EMBED,
                    requiresWebView = !isDirect,
                    metadata = metadata,
                    extraData = metadata
                )
            )
        }

        // Deduplicate servers by unique target link
        return discovered.distinctBy { it.link }
    }

    // --- Stream Extraction & Generic Playback Resolution ---

    override suspend fun extractStream(
        extension: ManagedExtension,
        session: ExtractionSession,
        request: ExtractionRequest,
        webEngine: WebExtractionEngine?
    ): Result<ExtractionResult> = withContext(Dispatchers.IO) {
        val serverItem = request.serverItem
        val embedUrl = serverItem.link.trim()

        if (embedUrl.isBlank()) {
            return@withContext Result.failure(ExtensionError.InvalidConfiguration("Target stream link cannot be blank"))
        }

        // Security check
        val lower = embedUrl.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("file:") || lower.startsWith("content:") || lower.startsWith("data:")) {
            return@withContext Result.failure(ExtensionError.SecurityViolation("Insecure or unauthorized URL scheme"))
        }

        // Fast path: direct media stream
        if (MediaStreamDetector.isMediaUrl(embedUrl)) {
            val normalized = MediaStreamDetector.normalizeStreamUrl(embedUrl)
            if (MediaStreamDetector.validateMediaUrl(normalized)) {
                val headers = mapOf(
                    "Referer" to extension.baseUrl,
                    "User-Agent" to userAgent
                )
                val projected = CredentialProjector.projectHeaders(
                    sessionHeaders = headers,
                    sessionCookies = session.sessionCookies,
                    requiredHeaderKeys = setOf("referer", "user-agent")
                )
                val isHls = normalized.contains(".m3u8")
                val playbackSource = PlaybackSource(
                    streamUrl = normalized,
                    headers = projected,
                    mimeType = if (isHls) "application/x-mpegURL" else "video/mp4",
                    protocol = if (isHls) StreamProtocol.HLS else StreamProtocol.DIRECT_FILE
                )
                return@withContext Result.success(ExtractionResult(playbackSource = playbackSource))
            }
        }

        // Headless Controlled WebView extraction
        if (webEngine != null) {
            val script = ControlledWebViewEngine.getPublicEmbedMediaExtractionScript()
            val streamResult = webEngine.extractStreamUrl(
                targetUrl = embedUrl,
                targetServerId = serverItem.id,
                script = script,
                timeoutMs = request.timeoutMs,
                expectedOrigin = extension.baseUrl
            )

            if (streamResult.isSuccess) {
                val detected = streamResult.getOrThrow()
                val normalized = MediaStreamDetector.normalizeStreamUrl(detected)
                if (MediaStreamDetector.validateMediaUrl(normalized)) {
                    val headers = mapOf(
                        "Referer" to embedUrl,
                        "User-Agent" to userAgent
                    )
                    val projected = CredentialProjector.projectHeaders(
                        sessionHeaders = headers,
                        sessionCookies = session.sessionCookies,
                        requiredHeaderKeys = setOf("referer", "user-agent")
                    )
                    val isHls = normalized.contains(".m3u8")
                    val playbackSource = PlaybackSource(
                        streamUrl = normalized,
                        headers = projected,
                        mimeType = if (isHls) "application/x-mpegURL" else "video/mp4",
                        protocol = if (isHls) StreamProtocol.HLS else StreamProtocol.DIRECT_FILE
                    )
                    return@withContext Result.success(ExtractionResult(playbackSource = playbackSource))
                }
            } else {
                val err = streamResult.exceptionOrNull()
                if (err != null && isCloudflareException(err)) {
                    return@withContext Result.failure(ExtensionError.CloudflareChallenge(
                        detail = "Cloudflare challenge encountered on AnimeBlkom embed",
                        challengeUrl = embedUrl.takeIf { isSameSiteHttpsUrl(it, extension.baseUrl) }
                    ))
                }
            }
        }

        // Fallback for embed handoff when direct stream couldn't be intercepted
        val headers = mapOf("Referer" to extension.baseUrl, "User-Agent" to userAgent)
        val playbackSource = PlaybackSource(
            streamUrl = embedUrl,
            headers = headers,
            protocol = StreamProtocol.EMBED,
            mimeType = "text/html"
        )
        Result.success(ExtractionResult(playbackSource = playbackSource))
    }
}
